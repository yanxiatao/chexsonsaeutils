package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 放大出来的<b>单站点</b> &gt; long 用量必须真排得下去（#67 的最后一公里、#77）。
 *
 * <p>请求本身仍是 {@code long}（AE2 口径），但用料 = 每发量 × 发数，放大 10 倍之后
 * 一格就要 1e19 枚——比网络那份饱和读数（{@code 9.22e18}）还多。
 * 真值侧网络里确实有 1.5e19 枚（无限盘的 BigInteger 已经挂到图上），所以这张图配得平。
 *
 * <p>卡它的是站点漏斗：需求以 {@code long} 参数进 {@code request(...)}，
 * {@code WideMath.wideMul} 一饱和就把 1e19 截成 9.22e18， planner 于是按"最多这么多"排产，
 * 尾段静默少产。补法是把&gt;long 的需求按 {@code min(Long.MAX_VALUE)} 分轮喂给同一条
 * long 路——每轮内部（抽料、排产、回滚、字节账）口径一字不变。
 */
class WidePerSiteDemandTest {

    /** 独立度量，不以耗时作门禁；同一夹具同时核对份数、用量与分配量。 */
    @Test
    @org.junit.jupiter.api.Tag("footprint")
    void planningPerformanceReadout() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(bean instanceof com.sun.management.ThreadMXBean,
                "度量需要 JVM 的线程分配量接口");
        var allocation = (com.sun.management.ThreadMXBean) bean;
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (long requested : new long[] {1_000_000L, REQUEST_4E18}) {
            var graph = ironFromGoldGraph(true, 100L);
            for (int warmup = 0; warmup < 100; warmup++) {
                LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, requested);
            }
            long allocated = allocation.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            SolveResult result = null;
            int samples = 200;
            for (int sample = 0; sample < samples; sample++) {
                result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, requested);
                assertFalse(result.hasDeclined());
            }
            long elapsed = System.nanoTime() - start;
            long bytes = allocation.getThreadAllocatedBytes(thread) - allocated;
            assertEquals(BigInteger.valueOf(requested).multiply(BigInteger.valueOf(100L)),
                    result.state().used().getExact(SAND));
            System.out.printf("规划度量 request=%d ns/op=%d allocated-B/op=%d journal=%d%n",
                    requested, elapsed / samples, bytes / samples, result.state().journal());
        }
        var amount = PlanAmount.of(BigInteger.TEN.pow(22));
        for (int warmup = 0; warmup < 30; warmup++) {
            measureWideLedger(amount);
        }
        long allocated = allocation.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        int samples = 50;
        int entries = 0;
        for (int sample = 0; sample < samples; sample++) {
            entries = measureWideLedger(amount);
        }
        long elapsed = System.nanoTime() - start;
        long bytes = allocation.getThreadAllocatedBytes(thread) - allocated;
        System.out.printf("宽账度量 amount=1e22 ns/op=%d allocated-B/op=%d journal=%d%n",
                elapsed / samples, bytes / samples, entries);
    }

    private static int measureWideLedger(PlanAmount amount) {
        var state = new GraphPlanningState(Map.of(), Map.of());
        int mark = state.journal();
        state.insert(GOLD, amount);
        assertEquals(amount, state.extractByIdWide(GOLD, state.idOf(GOLD), amount));
        state.emitItemsWide(GOLD, amount);
        state.addMissingWide(GOLD, amount);
        int entries = state.journal() - mark;
        state.undoTo(mark);
        assertTrue(state.emitted().isEmpty());
        assertTrue(state.missing().isEmpty());
        return entries;
    }

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey SAND = AEItemKey.of(Items.SAND);

    @Test
    void enormousTerminalDemandReportsExactMissingWithoutChunking() {
        var graph = ironFromGoldGraph(false, Long.MAX_VALUE);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, Long.MAX_VALUE);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).pow(2), result.state().missing().getExact(GOLD));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE),
                result.state().craftsWide().getAmount(graph.producersByOutput().get(IRON).get(0).source())
                        .exactValue());
    }

    @Test
    void enormousTerminalDemandConsumesWholeTemplatesAndPreservesRemainder() {
        var producer = new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GOLD, Long.MAX_VALUE, 1L, GraphInput.Mode.NORMAL,
                        null, null, null, false)));
        var demand = BigInteger.valueOf(Long.MAX_VALUE).pow(2);
        var graph = new CraftGraph(IRON, Map.of(IRON, List.of(producer)),
                Map.of(producer.source(), producer), List.of(producer.source()),
                Map.of(), Map.of(), Map.of(), Map.of(GOLD, Long.MAX_VALUE),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(
                Map.of(GOLD, PlanAmount.of(demand.add(BigInteger.valueOf(17L))))));
        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, Long.MAX_VALUE);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertTrue(result.state().missing().isEmpty());
        assertEquals(demand, result.state().used().getExact(GOLD));
        assertEquals(17L, result.state().extract(GOLD, 100L, true));
    }

    /** 每发 1 枚铁吃 10 枚金 ⇒ 1e18 枚铁的请求放大成 1e19 枚金。 */
    private static final long REQUEST_1E18 = 1_000_000_000_000_000_000L;
    private static final long PER_CRAFT_GOLD = 10L;
    private static final BigInteger NEED_1E19 = new BigInteger("10000000000000000000");
    private static final BigInteger TRUTH_1_5E19 = new BigInteger("15000000000000000000");

    @Test
    void beyondLongSingleSiteDemandDrawsInRoundsInsteadOfSaturating() {
        var producer = new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GOLD, PER_CRAFT_GOLD, 1L, GraphInput.Mode.NORMAL, null,
                        null, null, false)));
        var graph = new CraftGraph(IRON,
                Map.of(IRON, List.of(producer)),
                Map.of(producer.source(), producer),
                List.of(producer.source()),
                Map.of(), Map.of(), Map.of(),
                Map.<AEKey, Long>of(GOLD, Long.MAX_VALUE),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(
                Map.of(GOLD, PlanAmount.of(TRUTH_1_5E19))));

        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_1E18);

        assertFalse(result.hasDeclined(), () -> "真值 1.5e19 枚金盖得住 1e19 枚的需求，不该拒: "
                + result.declineReason());
        var state = result.state();
        assertTrue(state.missingItems().isEmpty(),
                () -> "池子里真有 1.5e19 枚金，不许报缺料: " + state.missingItems());
        assertEquals(REQUEST_1E18, (long) state.crafts().get(producer.source()),
                "份数必须等于请求 1e18 发；少一分就是被饱和读数截掉");
        assertEquals(NEED_1E19, state.used().getExact(GOLD),
                "用量 1e19 枚金本身就 >long：这本账必须记下真值");
    }

    /**
     * 排够臂（#211）：单站点<b>件数</b>需求 {@code 4e20}（&gt;long）时，那一格的生产者必须被排到
     * 宽域真值 {@code 4e20} 发，不许顶在 {@code Long.MAX_VALUE} 就出表。
     *
     * <p>形状：请求 {@code 4e18} 枚铁（合法 {@code long}），每张铁样板一发吃 {@code 100} 枚金
     * ⇒ 金这一格的件数需求 = {@code 100 × 4e18 = 4e20}。金的生产者每发产 {@code 1} 枚金。
     *
     * <p>HEAD 的断点不在"分轮口"（{@code request(Site, PlanAmount)}），而在<b>轮内</b>：
     * 金的<b>单位数</b> {@code 4e18} 装得进 {@code long}，于是 {@code fire} 直接把 long 版需求口
     * 叫起来，轮内第一句 {@code totalRequested = WideMath.wideMul(requestedAmount, site.amount)}
     * 把 {@code 4e18 × 100 = 4e20} 饱和成 {@code Long.MAX_VALUE} ⇒ 金的生产者只被排
     * {@code 9.22e18} 发，剩下那一截 {@code 4e20 − 9.22e18} 无声消失（连缺料都不报，
     * 是守恒闸门把它拦成 DECLINE 的，不是求解侧发现了洞）。
     *
     * <p>改后的口径：分轮的轮长由"轮内乘算不夹真值"决定（{@code 轮长 × 每件数 ≤ long}），
     * 于是每一轮的 {@code totalRequested} 都是精确值，逐轮累加进份数账 ⇒ 总发数 {@code 4e20}。
     * 断言钉<b>具体十位数</b>，不写"非饱和"这种读不出量的判据。
     */
    @Test
    void beyondLongSiteItemDemandSchedulesTheProducerToTheWideTrueFirings() {
        var graph = ironFromGoldGraph(true, 100L);

        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_4E18);

        assertFalse(result.hasDeclined(), () -> "5e20 枚沙盖得住 4e20 枚金的需求，不该拒: "
                + result.declineReason());
        var state = result.state();
        assertTrue(state.missingItems().isEmpty(),
                () -> "料链配得平，不许报任何缺料: " + state.missing());
        var goldPattern = graph.producersByOutput().get(GOLD).get(0).source();
        var ironPattern = graph.producersByOutput().get(IRON).get(0).source();
        assertEquals(GOLD_FIRINGS_4E20, state.craftsWide().getAmount(goldPattern).exactValue(),
                "金的生产者必须排满 4e20 发（每发产 1 枚金）：顶在 Long.MAX 就是尾段静默少产");
        assertEquals(BigInteger.valueOf(REQUEST_4E18),
                state.craftsWide().getAmount(ironPattern).exactValue(),
                "铁样板的发数就是请求的 4e18 发");
        assertEquals(GOLD_FIRINGS_4E20, state.used().getExact(SAND),
                "沙那一格的用量是 4e20 枚（每发 1 枚 × 4e20 发），这本账必须记下宽域真值");
    }

    /**
     * 报缺臂（#211）：同一张图把金的生产者撤掉 ⇒ 必须<b>当场报出</b>缺的是哪一枚键、缺多少枚，
     * 缺口数必须是宽域真值 {@code 4e20}，不许是被夹过的 {@code 9223372036854775807}，
     * 也不许 {@code CRAFT_LESS} 静默截半。
     *
     * <p>走的是<b>既有</b>那条延后判缺腿：轮内 {@code totalRequested} 精确 ⇒ 模拟那一腿逐轮把
     * 缺口累进 {@code state.missing()} 的宽账 ⇒ 守恒闸门"supply＋missing ≥ demand"在同一把
     * 宽域尺下配平（不再凭空造出一格假 hole），缺料表按真值出门。
     *
     * <p>HEAD 红在金那一格只被喂<b>一轮</b>：{@code totalRequested} 饱和成 {@code Long.MAX_VALUE}，
     * 缺料表记下 {@code 9.22e18} 枚——少报 {@code 3.9e20} 枚，同时闸门按 {@code 4e20} 的需求
     * 判出 {@code 3.9e20} 的 hole，把整条规划拒掉（玩家一个键名都看不到）。
     */
    @Test
    void producerlessBeyondLongSiteDemandNamesTheKeyAndTheWideGap() {
        var graph = ironFromGoldGraph(false, 100L);

        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_4E18);

        assertFalse(result.hasDeclined(), () -> "缺口该如实进缺料表，不该把整条规划判死: "
                + result.declineReason());
        var state = result.state();
        // keys() 交回的是 Iterable 视图，不是按元素相等的 Set——先落成 Set 再比，
        // 否则断言消息里只有视图对象的身份哈希，读不出缺的是哪一枚键。
        var missingKeys = new java.util.LinkedHashSet<AEKey>();
        state.missing().keys().forEach(missingKeys::add);
        assertEquals(java.util.List.of(GOLD), new java.util.ArrayList<>(missingKeys),
                "缺料表必须逐字点名那一枚键，不许捎上别的键");
        BigInteger gap = state.missing().getExact(GOLD);
        assertEquals(GOLD_FIRINGS_4E20, gap,
                "缺口必须是宽域真值 4e20 枚：需求 100×4e18 一枚都不许少");
        assertTrue(gap.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                () -> "缺口" + gap + " 没跨过 long 域，说明这一格还是被夹着报的（饱和读数不是量）");
    }

    /**
     * 窄路退回臂（#211）：件数需求塞得进 {@code long} 的那一支，调用序列必须与今天
     * <b>逐字相同</b>——一次分配都不许多。
     *
     * <p>判据用现成的 journal 账：每一轮需求都要往回滚日志追加"提料 / 份数 / 产出"条目，
     * 多切一轮就多一批条目。所以 {@code state.journal()}（当前日志水位）就是"多分配了几次"
     * 的直接读数。两个水位数字都是在 {@code 437a6c8} 上<b>实测</b>钉下来的，不是推出来的。
     *
     * <p>两条夹具一起才有意义：
     * <ul>
     * <li>{@code 1e6} 那一跑是小窄值，钉"窄路今天怎么走就怎么走"；</li>
     * <li>{@code 2e18 单位 × 每单位 4 枚 = 8e18 枚}那一跑把窄值<b>顶到天花板边上</b>
     * （{@code 8e18 < Long.MAX 9.22e18}）——轮长算子一旦被无谓地收紧（比如把
     * {@code roundUnits} 再除以二），这一跑就会多切出一轮、水位立刻变红。
     * 只留第一条的话，本臂对"轮长过紧"这类回归是不敏感的。</li>
     * </ul>
     */
    @Test
    void narrowSiteDemandStillTakesExactlyOneRound() {
        var graph = ironFromGoldGraph(true, 100L);
        long requested = 1_000_000L;

        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, requested);

        assertFalse(result.hasDeclined(), () -> "窄值请求不该被本轮改动挡下: " + result.declineReason());
        var state = result.state();
        var goldPattern = graph.producersByOutput().get(GOLD).get(0).source();
        assertEquals(BigInteger.valueOf(requested * 100L),
                state.craftsWide().getAmount(goldPattern).exactValue(),
                "窄值形状不变：每发 100 金 ⇒ 1e6 发铁要 1e8 发金");
        assertEquals(NARROW_JOURNAL_WATER_MARK, state.journal(),
                "窄值那一支的调用序列必须一字不变：journal 水位涨了就是多分了轮");
    }

    /**
     * 窄路退回臂的第二跑：件数需求 {@code 8e18} 枚，离 {@code long} 天花板只差
     * {@code 1.22e18} 枚——仍然"塞得进 long"，必须一次分配搞定。
     *
     * <p>这一跑是本轮长算式的<b>敏感性</b>来源：{@code WideMath.roundUnits(4)} 交出
     * {@code 2.305e18} 个单位数，请求恰好 {@code 2e18} 个单位数压在它下面，所以走的还是
     * 今天那一条单轮 {@code long} 路。把轮长再收紧一分（任何"顺手保守一点"的除法）都会
     * 立刻在这里多切出一轮。
     */
    @Test
    void boundaryNarrowSiteDemandStillTakesOneRound() {
        var graph = ironFromGoldGraph(true, 4L);

        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, REQUEST_2E18);

        assertFalse(result.hasDeclined(), () -> "8e18 枚金塞得进 long，不该被拆轮挡下: "
                + result.declineReason());
        var state = result.state();
        var goldPattern = graph.producersByOutput().get(GOLD).get(0).source();
        assertEquals(GOLD_ITEMS_8E18, state.craftsWide().getAmount(goldPattern).exactValue(),
                "每单位 4 枚 × 2e18 单位 = 8e18 枚金，一发都不许多也不许少");
        assertEquals(GOLD_ITEMS_8E18, state.used().getExact(SAND),
                "沙的用量同样还是 8e18 枚（每发金吃 1 枚沙）");
        assertEquals(BOUNDARY_NARROW_JOURNAL_WATER_MARK, state.journal(),
                "顶到天花板边上的窄值也必须一次分配搞定：水位涨了就是轮长被算紧了");
    }

    /**
     * 铁 ← 金 ← 沙 这条三点链。每发铁吃 {@code goldPerCraft} 枚金，
     * 每发金产 1 枚金并吃 1 枚沙。
     *
     * @param withGoldProducer 金的生产者在不在图上（撤掉即"图上再无来源"那一型）
     * @param goldPerCraft     每发铁吃几枚金——它决定这一格的<b>件数</b>需求会不会越出 long
     */
    private static CraftGraph ironFromGoldGraph(boolean withGoldProducer, long goldPerCraft) {
        var ironProducer = new GraphPattern(new FakeDetails(Items.CLOCK), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GOLD, goldPerCraft, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
        var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
        producers.put(IRON, List.of(ironProducer));
        var patternsBySource = new java.util.HashMap<IPatternDetails, GraphPattern>();
        var registrationOrder = new java.util.ArrayList<IPatternDetails>();
        patternsBySource.put(ironProducer.source(), ironProducer);
        registrationOrder.add(ironProducer.source());
        if (withGoldProducer) {
            var goldProducer = new GraphPattern(new FakeDetails(Items.COMPASS), GOLD, 1,
                    List.of(new GenericStack(GOLD, 1L)),
                    List.of(new GraphInput(SAND, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
            producers.put(GOLD, List.of(goldProducer));
            patternsBySource.put(goldProducer.source(), goldProducer);
            registrationOrder.add(goldProducer.source());
        }
        var graph = new CraftGraph(IRON, producers, patternsBySource, registrationOrder,
                Map.of(), Map.of(), Map.of(),
                Map.<AEKey, Long>of(SAND, Long.MAX_VALUE),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
        // 沙的真值 5e20 枚：够排满 4e20 枚金，不许因为读数饱和就把它当"夹得住"。
        graph.attachSupplyTruth(PlanningSupplyTruth.of(
                Map.of(SAND, PlanAmount.of(SAND_TRUTH_5E20))));
        return graph;
    }

    /** 请求 {@code 4e18} 枚铁：本身是合法 {@code long}，放大 100 倍后金那一格要 {@code 4e20} 枚。 */
    private static final long REQUEST_4E18 = 4_000_000_000_000_000_000L;
    /** 请求 {@code 2e18} 枚铁：每单位 4 枚金 ⇒ 件数 {@code 8e18} 枚，仍塞得进 {@code long}。 */
    private static final long REQUEST_2E18 = 2_000_000_000_000_000_000L;
    private static final BigInteger GOLD_FIRINGS_4E20 = new BigInteger("400000000000000000000");
    private static final BigInteger GOLD_ITEMS_8E18 = new BigInteger("8000000000000000000");
    private static final BigInteger SAND_TRUTH_5E20 = new BigInteger("500000000000000000000");
    /** 窄值那一条在 {@code 437a6c8} 上实测的 journal 水位（取证见本类注释）：多一轮就涨一截。 */
    private static final int NARROW_JOURNAL_WATER_MARK = 20;
    /** 顶到天花板边上的窄值那一条的实测水位（20，与小窄值同＝一次分配搞定）：轮长一算紧就多切一轮。 */
    private static final int BOUNDARY_NARROW_JOURNAL_WATER_MARK = 20;

    /** 轻量样板替身：definition 只需要唯一身份。 */
    private static final class FakeDetails implements IPatternDetails {
        private final AEItemKey definition;

        private FakeDetails() {
            this(Items.CLOCK);
        }

        private FakeDetails(Item definitionItem) {
            this.definition = AEItemKey.of(new ItemStack(definitionItem));
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public java.util.List<GenericStack> getOutputs() {
            return List.of();
        }

        @Override
        public AEItemKey getDefinition() {
            return this.definition;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
