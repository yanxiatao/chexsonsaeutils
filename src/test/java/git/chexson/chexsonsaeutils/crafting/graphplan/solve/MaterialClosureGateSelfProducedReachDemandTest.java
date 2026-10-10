package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.solve.Ae2TreePlanReference;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务 #214 —— 模糊槽伸手那一枚的记账，bigint 化必须做在<b>自产侧</b>，不是只做在原料侧。
 *
 * <p>#212 留下的触发尺 {@code chargeableReachFamily} 读的两条腿（网络诚实存量、这份计划已入账的
 * 供给腿）<b>全是来路</b>，"图上有自产路线"这件事不在触发条件里；再叠上 {@code !shot.isFast()}
 * 那一档窄值门 ⇒ 只要一格需求塞得进 {@code long}、且这一枚键网络现量为 0，它就不进 {@code demand}
 * ——哪怕图上明摆着有一个生产者。demand 不记 ⇒ 没人要求排产 ⇒ 生产者永远排不上，这一档自锁。
 * 实机 {@code 01:46} 那一跑的 {@code mekanism:enriched_redstone x 3763234236668680780}
 * （{@code 池内=0}、{@code 网络sim=0}、{@code src=NONE}，全场零条 {@code closure violated}/
 * {@code shortage}，照旧 {@code ok keys=}）正是这个形状。
 *
 * <p>本刀把尺改成一处（{@link MaterialClosureGate#chargesReachDemand} 走 {@code collectFuzzySlots}）：
 * <b>图上有生产路线 ⇒ 宽窄一律记</b>；图上没有生产路线时仍只在越出 {@code long} 且有存量来路时记，
 * 那一档守的是 121/123/126/196 的封套契约（零来路的许可上界不许当需求＝要求首选独自盖住整槽，
 * 与台账 #46/#146 的跨候选回落口径相对撞；把这一档也摘掉的实测红件 24 条，逐字判决与清单存
 * {@code .scratch/red-214-widthtier-noguard.log}）。"够不够"仍然只有 {@code MaterialClosureGate}
 * 一处、报缺仍然只有 {@code shortageSink.put} 一个写入点，不新建第二本账。
 *
 * <h3>六臂（手算 oracle 全部独立于被测乘式）</h3>
 * <pre>
 *   自产型公共夹具（<b>窄值</b>，逐字照实机那一格）：
 *     消费样板 C：TARGET×1/发；模糊槽 multiplier=20，候选 [DUST@1, SIBLING@1]（都在宇宙内）
 *     请求量     ：TARGET × 188161711833434039 发
 *                 ⇒ 这一格每发真伸手 DUST 1 枚 × 20 倍率 × 188161711833434039 发
 *                 = 需求 3763234236668680780 枚（3.76e18，<b>塞得进 long</b> ⇒ 旧尺不记的那一档）
 *     DUST 网络真值 0（本刀主角：两条旧腿都为零）
 *     生产样板 P：FUEL×1 → DUST×7/发；FUEL 存量按臂取 6e17 / 0
 *     手算排产   ：ceil(3763234236668680780 / 7) = 537604890952668683 发
 *                 （537604890952668682 × 7 = 3763234236668680774 差 6 枚 ⇒ 必进一发）
 * </pre>
 *
 * <ul>
 * <li><b>自产型红臂（排产，HEAD 必红）</b>：一格需求塞得进 {@code long}、网络现量 0、图上有生产路线
 * ⇒ 改后那个生产者被排上的发数必须等于上面那个具体十位数。</li>
 * <li><b>自产型报缺臂（HEAD 必红）</b>：同形状的账（生产者还没被排上、网络 0）必须经全仓唯一写入点
 * 逐字点名键名与缺口 {@code 3763234236668680780}，判决里 {@code producers=1} 要能指出图上有路线。</li>
 * <li><b>不误拦臂</b>：伸手那一枚自己就盖得住整格需求的窄值订单照旧闭合，发数/提取腿/配给表份额
 * 逐字不变（守历史条目 36）。</li>
 * <li><b>封套契约臂（守 121/123/126/196）</b>：<b>窄值</b>、三条来路全为零（网络 0、计划 0、图上没有
 * 生产路线）而整槽由备选付满的一格 ⇒ 只该有封套：进下发的表与封套本，不进 demand，也不算排水实记。
 * 这一臂就是"记账不许做成要求首选独自盖住整槽"的牙。</li>
 * <li><b>多格共吃臂</b>：两格同伸手那一枚、存量只够一格 ⇒ demand 逐格复加、存量只并一次物理量，
 * 缺口如实落在真实差额（40−60 ⇒ 40）上，不许报成两格需求之和。</li>
 * <li><b>窄路字节水位臂</b>：不涉模糊槽的普通订单，journal mark 钉绝对值，字节水位逐字等于
 * <b>AE2 原生树在同一张图上的读数</b>（离线参照 {@code Ae2TreePlanReference}）。</li>
 * </ul>
 */
class MaterialClosureGateSelfProducedReachDemandTest {

    /** 伸手那一枚（实机 {@code mekanism:enriched_redstone} 的形状）。 */
    private static final AEItemKey DUST = AEItemKey.of(Items.GLOWSTONE_DUST);
    /** 同槽靠后的备选候选（排水跨候选回落那一枚）。 */
    private static final AEItemKey SIBLING = AEItemKey.of(Items.NETHER_STAR);
    private static final AEItemKey TARGET = AEItemKey.of(Items.CLOCK);
    /** 生产样板的原料。 */
    private static final AEItemKey FUEL = AEItemKey.of(Items.COAL);
    /** 封套契约臂那三枚。 */
    private static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEItemKey SNOW = AEItemKey.of(Items.SNOWBALL);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

    /** 消费样板发数：这一格要发 188161711833434039 发（实机那一格的发数形状）。 */
    private static final long REQUEST = 188_161_711_833_434_039L;
    private static final long SLOT_MULTIPLIER = 20L;
    /** 手算需求：20 × 1 × 188161711833434039 = 3763234236668680780（装得进 long ⇒ 旧尺不记的窄值）。 */
    private static final BigInteger REQUIRED = new BigInteger("3763234236668680780");
    /** 生产样板每发产出。 */
    private static final long PRODUCER_OUTPUT_PER_FIRING = 7L;
    /** 手算排产：ceil(3763234236668680780 / 7) = 537604890952668683 发。 */
    private static final BigInteger EXPECTED_PRODUCER_FIRINGS = new BigInteger("537604890952668683");
    /** FUEL 存量：够排产那一臂（≥ 537604890952668683 枚）。 */
    private static final long FUEL_ENOUGH = 600_000_000_000_000_000L;
    /** 饱和哨兵：不许在任何判决/缺口读数里冒充量。 */
    private static final String SENTINEL = "9223372036854775807";

    private static final class RawInput implements IPatternDetails.IInput {
        private final GenericStack[] candidates;
        private final long multiplier;

        private RawInput(long multiplier, GenericStack... candidates) {
            this.candidates = candidates;
            this.multiplier = multiplier;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return true;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private static final class Details implements IPatternDetails {
        private final AEItemKey definition;
        private final RawInput input;
        private final List<GenericStack> outputs;

        private Details(Item definitionItem, long multiplier, GenericStack... candidates) {
            this(definitionItem, new RawInput(multiplier, candidates),
                    List.of(new GenericStack(TARGET, 1L)));
        }

        private Details(Item definitionItem, RawInput input, List<GenericStack> outputs) {
            this.definition = AEItemKey.of(new ItemStack(definitionItem));
            this.input = input;
            this.outputs = outputs;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] { input };
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    /** 消费样板：TARGET×1/发，模糊槽 [DUST@1, SIBLING@1]、倍率 20。 */
    private static Details consumerDetails(Item definitionItem) {
        return new Details(definitionItem, SLOT_MULTIPLIER,
                new GenericStack(DUST, 1L), new GenericStack(SIBLING, 1L));
    }

    /** 消费样板（<b>定键</b>形态，窄路逐字臂用）：同一个槽但没有候选表 ⇒ 不进模糊槽宇宙。 */
    private static Details fixedConsumerDetails() {
        return new Details(Items.REPEATER, SLOT_MULTIPLIER, new GenericStack(DUST, 1L));
    }

    /** 生产样板：FUEL×1 → DUST×7/发。 */
    private static Details producerDetails() {
        return new Details(Items.COMPARATOR,
                new RawInput(1L, new GenericStack(FUEL, 1L)),
                List.of(new GenericStack(DUST, PRODUCER_OUTPUT_PER_FIRING)));
    }

    /** 消费样板的图侧条目：模糊槽候选顺位 [DUST, SIBLING]，两枚都在宇宙内。 */
    private static GraphPattern consumerPattern(Details consumer, boolean fuzzy) {
        var candidates = fuzzy
                ? List.of(new GraphInput.Candidate(DUST, 1L), new GraphInput.Candidate(SIBLING, 1L))
                : null;
        Set<AEKey> variants = fuzzy ? Set.of(DUST, SIBLING) : null;
        return new GraphPattern(consumer, TARGET, 1L,
                List.of(new GenericStack(TARGET, 1L)),
                List.of(new GraphInput(DUST, 1L, SLOT_MULTIPLIER, GraphInput.Mode.NORMAL, null,
                        candidates, variants, false)));
    }

    /** 生产样板的图侧条目：定键槽 FUEL×1，产出 DUST×7/发。 */
    private static GraphPattern producerPattern(Details producer) {
        return new GraphPattern(producer, DUST, PRODUCER_OUTPUT_PER_FIRING,
                List.of(new GenericStack(DUST, PRODUCER_OUTPUT_PER_FIRING)),
                List.of(new GraphInput(FUEL, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
    }

    /**
     * 自产型忠实图：{@code dustStock} 是伸手那一枚的网络现量（本刀主角取 {@code 0}——两条旧腿都为零），
     * {@code siblingStock} 是同槽靠后那枚备选的现量（取够数时"整槽本来能靠回落付满"），
     * {@code withProducer} 决定图上到底有没有这一枚的生产路线，{@code fuelStock} 决定那条路线
     * 真跑得动跑不动。
     */
    private static CraftGraph dustGraph(long dustStock, long siblingStock, boolean withProducer,
            long fuelStock, Details consumer, Details producer) {
        var consumerPattern = consumerPattern(consumer, true);
        var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
        var bySource = new java.util.IdentityHashMap<IPatternDetails, GraphPattern>();
        var order = new java.util.ArrayList<IPatternDetails>();
        producers.put(TARGET, List.of(consumerPattern));
        bySource.put(consumer, consumerPattern);
        order.add(consumer);
        if (withProducer) {
            var producerPattern = producerPattern(producer);
            producers.put(DUST, List.of(producerPattern));
            bySource.put(producer, producerPattern);
            order.add(producer);
        }
        return new CraftGraph(TARGET, Map.copyOf(producers), Map.copyOf(bySource),
                List.copyOf(order), Map.of(), Map.of(), Map.of(),
                Map.of(DUST, dustStock, SIBLING, siblingStock, FUEL, fuelStock),
                Set.of(), Map.of(), false, null, order.size(), 1);
    }

    /** 求解链就是生产那一趟的入口（{@code GraphPlanningSession#solveLedger} 直调 {@code LinearPlanner}）。 */
    private static SolveResult solveLedger(CraftGraph graph, long request) {
        return LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, request, false, null);
    }

    /**
     * 自产型红臂（HEAD 必红）：一格需求 {@code 3763234236668680780} 塞得进 {@code long}、
     * DUST 网络现量 0、图上却有它的生产路线（同槽备选 SIBLING 现量 4e18 够回落付满整槽）。
     *
     * <p>HEAD 的形态：{@code !shot.isFast()} 把窄值挡在门外 ⇒ 这一格既不进 demand、也没人要求排产
     * ⇒ 生产者发数 {@code 0}、整格白吃备选、照旧闭账（实机 {@code src=NONE} 却 {@code ok keys=} 那一格）。
     * 改后 demand 记上 3763234236668680780 枚 ⇒ 守恒判据在同一趟里逼出排产腿 ⇒ DUST 生产者发数必须
     * 等于 ceil(需求 / 每发 7 枚)。
     */
    @Test
    void narrowProducibleReachDemandSchedulesTheProducer() {
        var consumer = consumerDetails(Items.REPEATER);
        var producer = producerDetails();
        var graph = dustGraph(0L, 4_000_000_000_000_000_000L, true, FUEL_ENOUGH, consumer, producer);
        var result = solveLedger(graph, REQUEST);
        assertFalse(result.hasDeclined(),
                () -> "有生产路线的一族必须走排产出口把计划配平：" + result.declineReason());
        assertEquals(BigInteger.valueOf(REQUEST),
                result.state().craftsWide().getAmount(consumer).exactValue(),
                "消费样板发数逐字不变：188161711833434039");
        var firings = result.state().craftsWide().getAmount(producer);
        assertEquals(EXPECTED_PRODUCER_FIRINGS, firings.exactValue(),
                () -> "DUST 生产者发数必须钉在 ceil(需求 3763234236668680780 / 每发 7)"
                        + " = 537604890952668683 的具体十位数上： 实得=" + firings
                        + "（HEAD 的形态：窄值被 !shot.isFast() 挡在门外 ⇒ 这一格根本不记账 ⇒ 零排产增量）");
        assertEquals(BigInteger.ZERO, result.state().used().getAmount(DUST).exactValue(),
                "自产腿盖住整格 ⇒ 网络上那一枚一枚都不必提，提取腿逐字为零");
        assertEquals(EXPECTED_PRODUCER_FIRINGS, result.state().used().getAmount(FUEL).exactValue(),
                "生产样板的原料腿逐字等于排产发数 × 每发 1 枚");
        var task = MaterialClosureGate.buildMaterialPlan(result.state(), graph, REQUEST)
                .assigned().get(consumer);
        assertNotNull(task, "配给表照发");
        assertEquals(REQUIRED, task.getExact(DUST), "伸手键那一格的份额逐字钉在整格需求上");
        assertEquals(BigInteger.ZERO, task.getExact(SIBLING),
                "归属从备选挪回伸手那一枚（键宇宙＝执行侧真伸手的键宇宙）");
        var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        assertNull(MaterialClosureGate.validate(result.state(), graph, REQUEST, shortage),
                () -> "自产腿盖住之后闭合照旧、缺口口袋空：" + shortage);
        assertTrue(shortage.isEmpty(), () -> "一个键都不许报缺：" + shortage);
    }

    /**
     * 自产型报缺臂（HEAD 必红）：同一形状的账，图上明明有这一枚的生产路线、却还没被排上
     * （{@code 池内=0}、{@code src=NONE} 的实机形态）。
     *
     * <p>出路只剩既有的 {@code shortageSink}（全仓唯一写入点）：必须逐字点名 DUST 与缺口
     * {@code 3763234236668680780} 枚，判决里 {@code producers=1} 要能指出"有路线却没排产"，
     * 也不许拿饱和哨兵当量。HEAD 在这一格连判决都没有——那一声不吭就是本刀要关的洞。
     */
    @Test
    void narrowProducibleReachDemandWithoutScheduledProducerNamesTheKeyInShortage() {
        var consumer = consumerDetails(Items.REPEATER);
        var graph = dustGraph(0L, 0L, true, FUEL_ENOUGH, consumer, producerDetails());
        var state = new GraphPlanningState(Map.of(DUST, 0L, SIBLING, 0L, FUEL, FUEL_ENOUGH), Map.of());
        state.addCrafting(consumer, REQUEST);
        var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        var violation = MaterialClosureGate.validate(state, graph, REQUEST, shortage);
        assertNotNull(violation, () -> "窄值且网络现量 0 的一格不许再静默闭账");
        var gap = shortage.get(DUST);
        assertNotNull(gap, () -> "缺口账必须点名 DUST：" + shortage.keySet());
        assertEquals(REQUIRED, gap.exactValue(), () -> "缺口 = 需求 3763234236668680780 − 来路 0：" + gap);
        assertTrue(violation.contains(DUST.toString()), () -> "判决必须逐字点名键名：" + violation);
        assertTrue(violation.contains("demand=3763234236668680780"), () -> violation);
        assertTrue(violation.contains("producers=1"),
                () -> "判决要能指出这一枚在图上有生产路线（有路线却没排产那一型）：" + violation);
        assertFalse(violation.contains(SENTINEL), () -> "饱和哨兵不许冒充缺口数：" + violation);
        assertFalse(shortage.containsKey(SIBLING), () -> "没伸手的备选不许被立洞：" + shortage);
    }

    /**
     * 不误拦臂（守历史条目 36）：伸手那一枚自己就盖得住整格需求的<b>窄值</b>订单照旧闭合。
     *
     * <p>手算：DUST 现量 3763234236668680780 枚 = 整格需求 ⇒ 提取腿付满、生产者一发都不必排、
     * 缺口口袋空、配给表份额逐字不变。记账一旦做成"要求首选独自盖住整槽之外的更严假设"，
     * 这一臂立刻红（发数或提取腿多出那一截）。
     */
    @Test
    void feasibleNarrowOrderWithHonestReachStockStillClosesUnchanged() {
        var consumer = consumerDetails(Items.REPEATER);
        var producer = producerDetails();
        var graph = dustGraph(REQUIRED.longValueExact(), 0L, true, FUEL_ENOUGH, consumer, producer);
        var result = solveLedger(graph, REQUEST);
        assertFalse(result.hasDeclined(),
                () -> "伸手族自己就盖得住整格需求，这一单可行，不许被记账拦下：" + result.declineReason());
        var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        assertNull(MaterialClosureGate.validate(result.state(), graph, REQUEST, shortage),
                () -> "闭合照旧、缺口口袋空：" + shortage);
        assertTrue(shortage.isEmpty(), () -> "一个键都不许报缺：" + shortage);
        assertEquals(REQUIRED, result.state().used().getAmount(DUST).exactValue(),
                "DUST 提取那一腿逐字不变：3763234236668680780 枚");
        assertEquals(BigInteger.valueOf(REQUEST),
                result.state().craftsWide().getAmount(consumer).exactValue(),
                "消费样板发数逐字不变：188161711833434039");
        assertEquals(BigInteger.ZERO, result.state().craftsWide().getAmount(producer).exactValue(),
                "存量已经盖住整格 ⇒ 生产者一发都不许多排，记账不许变成额外产能");
        var task = MaterialClosureGate.buildMaterialPlan(result.state(), graph, REQUEST)
                .assigned().get(consumer);
        assertNotNull(task, "配给表照发");
        assertEquals(REQUIRED, task.getExact(DUST), "排水实记份额逐字不变");
        assertEquals(BigInteger.ZERO, task.getExact(SIBLING), "备选那一枚一枚都没被吃：逐字不变");
    }

    /**
     * 封套契约臂（守 121/123/126/196）：<b>窄值</b>、三条来路全为零（网络 0、计划 0、图上没有生产
     * 路线）而整槽由备选付满的一格 ⇒ 只该有封套。
     *
     * <p>手算表（独立于被测乘式）：
     * <pre>
     *   槽倍率 3   每模板量 2 枚   发数 5
     *   整槽需求 = 3 × 5 = 15 模板 = 30 枚（30 装得进 long ⇒ 正是"宽窄一律"会碰到的那一档）
     *   排水实付（备选 SNOW）= 15 模板 × 每模板 2 枚 = 30 枚（SNOW 现场 40 枚，付满）
     *   封套（首位 GLASS）  = 每发 2 枚 × 倍率 3 × 5 发 = 30 枚（许可上界）
     *   排水实记（剔掉封套那一本后）= SNOW 30 ≤ 供给 40 ⇒ 表照发
     * </pre>
     * 这一臂是"记账不许做成要求首选独自盖住整槽"的牙：把零来路的封套塞成需求，这里立刻红——
     * 拦下来就是 {@code 121/123/126} 定过的白卷病，也正是台账 #46/#146 的跨候选回落口径。
     */
    @Test
    void unsourcedNarrowReachFamilyStillShipsEnvelopeInsteadOfDemand() {
        var source = new Details(Items.CLOCK, new RawInput(3L,
                new GenericStack(GLASS, 2L), new GenericStack(SNOW, 2L)),
                List.of(new GenericStack(IRON, 1L)));
        var pattern = new GraphPattern(source, IRON, 1L,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GLASS, 2L, 3L, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(SNOW, 2L),
                                new GraphInput.Candidate(GLASS, 2L)),
                        Set.of(GLASS, SNOW), false)));
        var graph = new CraftGraph(IRON,
                Map.of(IRON, List.of(pattern)),
                Map.of(source, pattern),
                List.of(source),
                Map.of(), Map.of(), Map.of(),
                Map.of(GLASS, 0L, SNOW, 40L),
                Set.of(), Map.of(), false, null, 1, 1);
        var state = new GraphPlanningState(Map.of(GLASS, 0L, SNOW, 40L), Map.of());
        state.addCrafting(source, 5L);
        state.extract(SNOW, 40L, false);

        assertNull(MaterialClosureGate.validate(state, graph, 5L, null),
                "三条来路全为零的封套形状不许被记成需求——拦下来就是 121/123/126 的白卷病");
        var bill = MaterialClosureGate.buildMaterialPlan(state, graph, 5L);
        var shipped = bill.assigned().get(source);
        assertNotNull(shipped, () -> "封套不进需求 ⇒ 表必须照发：" + bill.assigned());
        assertEquals(30L, shipped.getLong(GLASS), "封套那一笔照样进下发的表（许可上界，配额夹读它）");
        assertEquals(30L, shipped.getLong(SNOW), "排水实记逐字不变");
        assertNotNull(bill.envelopes().get(source), "封套本照记");
        assertEquals(30L, bill.envelopes().get(source).getLong(GLASS), "同一笔值另记封套本");
    }

    /**
     * 多格共吃臂：两格同伸手 DUST（每格 20 倍率 × 1 枚 × 5 发 = 100 枚，窄值），图上这一枚有生产
     * 路线却没被排上，网络存量只有 60 枚。
     *
     * <p>手算：demand = 100 + 100 = 200，supply 只并<b>一次</b>物理量 60 ⇒ 缺口 140。
     * 存量若被逐格并两次就是 200−120 ⇒ 假闭合；一格都不并就报成 200 ⇒ 毛账报缺（历史条目 36）。
     * 两个错向在这一臂上都红，读数是 140 才说明"demand 逐格复加、supply 只并一次"。
     */
    @Test
    void sharedStockAcrossTwoNarrowSlotsReportsRealGapNotSumOfDemand() {
        var first = consumerDetails(Items.REPEATER);
        var second = consumerDetails(Items.COMPASS);
        var producer = producerDetails();
        var firstPattern = consumerPattern(first, true);
        var secondPattern = consumerPattern(second, true);
        var producerPattern = producerPattern(producer);
        var graph = new CraftGraph(TARGET,
                Map.of(TARGET, List.of(firstPattern, secondPattern), DUST, List.of(producerPattern)),
                Map.of(first, firstPattern, second, secondPattern, producer, producerPattern),
                List.of(first, second, producer),
                Map.of(), Map.of(), Map.of(),
                Map.of(DUST, 60L, SIBLING, 0L, FUEL, 0L),
                Set.of(), Map.of(), false, null, 3, 1);
        var state = new GraphPlanningState(Map.of(DUST, 60L, SIBLING, 0L, FUEL, 0L), Map.of());
        state.addCrafting(first, 5L);
        state.addCrafting(second, 5L);
        var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        var violation = MaterialClosureGate.validate(state, graph, 10L, shortage);
        assertNotNull(violation, "两格共吃一份存量、图上路线又没排上 ⇒ 不许假闭合");
        var gap = shortage.get(DUST);
        assertNotNull(gap, () -> "缺口账必须点名 DUST：" + shortage.keySet());
        assertEquals(new BigInteger("140"), gap.exactValue(),
                () -> "缺口 = 两格需求 200 − 只并一次的存量 60 = 140 枚：" + shortage);
        assertTrue(violation.contains("demand=200"), () -> violation);
        assertFalse(shortage.containsKey(SIBLING), () -> "没伸手的备选不许被立洞：" + shortage);
    }

    /**
     * 窄路逐字臂：不涉模糊槽的普通订单（<b>定键</b>槽，DUST 现量 0 ⇒ 产能全靠排产）。
     *
     * <p>字节水位的锚点是<b>同一张图上的 AE2 原生树</b>，不是我方求解器：先在离线参照
     * （{@code Ae2TreePlanReference}，AE2 自己的 {@code CraftingTreeNode} + 沙盒）上量一次，
     * 再把 {@code bytes} 钉成等于它那一份，并同时钉住它那一侧的字面数值
     * （{@code bytes=2.771431E7}、{@code exactBytes=23857143}）。
     * 换引擎后要是与原生树不再相等，那就是真丢功能，不是"重基线"能盖过去的。
     *
     * <p>{@code journal} 是我方 undo 日志的计次（原生树没有这一格），留作绝对水位钉住
     * "一次分配都不许多"；它跟 AE2 无关，不许被当成字节水位那一格的对照片。
     * 消费者发数 {@code 1000000}、生产者发数 {@code 2857143}（DUST 需求 {@code 1e6 发 × 20 倍率
     * = 2e7 枚 ÷ 每发 7 枚 向上取整）、原料腿 {@code usedFuel=2857143}、{@code usedDust=0}
     * 与摘档前逐字相同。
     */
    @Test
    void plainOrderWithoutFuzzySlotKeepsJournalAndByteWatermark() {
        var consumer = fixedConsumerDetails();
        var producer = producerDetails();
        var consumerPattern = consumerPattern(consumer, false);
        var producerPattern = producerPattern(producer);
        var graph = new CraftGraph(TARGET,
                Map.of(TARGET, List.of(consumerPattern), DUST, List.of(producerPattern)),
                Map.of(consumer, consumerPattern, producer, producerPattern),
                List.of(consumer, producer),
                Map.of(), Map.of(), Map.of(),
                Map.of(DUST, 0L, SIBLING, 600_000_000_000_000L, FUEL, 100_000_000_000_000L),
                Set.of(), Map.of(), false, null, 2, 1);
        var longRequest = 1_000_000L;
        var result = solveLedger(graph, longRequest);
        assertFalse(result.hasDeclined(), () -> "定键订单照旧闭合：" + result.declineReason());
        // 锚点：同一张图（同样两张样板、同样的库存读数、同样的 1e6 发请求）上的 AE2 原生树。
        var referenceGrid = FakeGrid.builder()
                .storage(Map.of(DUST, 0L, SIBLING, 600_000_000_000_000L, FUEL, 100_000_000_000_000L))
                .pattern(TARGET, consumer)
                .pattern(DUST, producer)
                .build();
        var treePlan = new Ae2TreePlanReference(referenceGrid, referenceGrid.requester,
                new GenericStack(TARGET, longRequest), CalculationStrategy.REPORT_MISSING_ITEMS).run();
        assertFalse(treePlan.simulation(), "AE2 树那一侧这一单配得平，水位才有意义");
        assertEquals(2.771431E7, treePlan.bytes(), 0.0,
                "AE2 原生树自己那一侧的字节水位（AE2 改了口径这条就红）");
        assertEquals(treePlan.bytes(), result.state().bytes(), 0.0,
                "字节水位必须逐字等于 AE2 树：换了求解引擎也不许改这份对外读数");
        assertEquals(new BigInteger("23857143"), result.state().byteAccount().exactValue().exactValue(),
                "精确字节读数逐字不变（与上面那份 double 同源的那一本整数账）");
        assertEquals(20, result.state().journal(), "journal mark 逐字不变（一次分配都不许多）");
        assertEquals(BigInteger.valueOf(longRequest),
                result.state().craftsWide().getAmount(consumer).exactValue(), "消费者发数逐字不变：1e6");
        assertEquals(new BigInteger("2857143"),
                result.state().craftsWide().getAmount(producer).exactValue(),
                "生产者发数逐字不变：ceil(2e7 枚 / 每发 7 枚) = 2857143 发");
        assertEquals(new BigInteger("2857143"),
                result.state().used().getAmount(FUEL).exactValue(),
                "原料腿逐字不变：2857143 发 × 每发 1 枚 FUEL");
        assertEquals(BigInteger.ZERO,
                result.state().used().getAmount(DUST).exactValue(),
                "DUST 那一腿逐字不变：定键槽的 2e7 枚全由自产补齐，网络现量 0 一枚也没提");
        var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        assertNull(MaterialClosureGate.validate(result.state(), graph, longRequest, shortage),
                () -> "闭合照旧、缺口口袋空：" + shortage);
        assertTrue(shortage.isEmpty(), () -> "一个键都不许报缺：" + shortage);
    }
}
