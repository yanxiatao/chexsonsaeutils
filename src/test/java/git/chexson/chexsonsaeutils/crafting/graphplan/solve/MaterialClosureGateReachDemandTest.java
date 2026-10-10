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
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务 #212 —— 模糊槽"每格顺位真伸手的那一枚 × 每发件数 × 该任务发数"进规划闭合的需求宇宙。
 *
 * <p>实机 lane {@code 6928385d}（订单 {@code ae2:creative_storage_cell x 1e11}）的
 * {@code mekanism:enriched_redstone} 那一格：守恒判据 {@code for (var key : demand.keys())}
 * 只遍历 demand 的键，而模糊槽那一族的每发需求从没进 demand ⇒ 需求 {@code 3.5e20}、存量与
 * 已排产能加起来差 {@code 3e20}，计划照样闭、照样下单，跑到一半整链饿死（全场零条
 * shortage/DECLINE/closure violated）。
 *
 * <h3>四臂（手算 oracle 全部独立于被测乘式）</h3>
 * <pre>
 *   公共夹具（忠实图，>long 形态）：
 *     消费样板 C：TARGET×1/发；模糊槽 multiplier=1e9，候选 [DUST@1, SIBLING@1]（都在宇宙内）
 *     请求量     ：TARGET × 1e11 发 ⇒ 这一格每发真伸手 DUST 1 枚 × 1e9 倍率 × 1e11 发
 *                 = 需求 100000000000000000000 枚（1e20，越出 long ⇒ 触发记账那一档）
 *     DUST 网络真值   5e19 枚（无限盘溢出键：long 视图长成长天花板，truth 纠回 50000000000000000000）
 *     SIBLING 网络真值 6e19 枚（同族外的备选候选，够排水跨候选回落付满整槽）
 *     生产样板 P（排产臂才有）：FUEL×1 → DUST×1e6/发；FUEL 存量 1e14（装得进 long）
 * </pre>
 *
 * <ul>
 * <li><b>排产臂</b>：图上 DUST 有生产者。HEAD 红在"DUST 不进 demand ⇒ 计划照闭、
 * 零排产增量"；改后 demand 记上那一格 ⇒ 守恒判据在同一趟里逼出排产腿（payerOwnsSlot
 * 不许跨族改道）⇒ DUST 生产者发数必须等于 ceil(缺口 5e19 / 每发 1e6) = 50000000000000 的宽域真值。</li>
 * <li><b>报缺臂</b>：同图撤掉生产者 ⇒ 这一格不许静默闭账：残缺标记照打、缺口账逐键点名 DUST 与
 * 宽域缺口 50000000000000000000（越出 long，被夹成饱和哨兵就报不出这个数），没伸手的备选不许被立洞。</li>
 * <li><b>正向臂一</b>：可行订单照旧闭合、数字逐字不变——(a) 伸手族有实记供给（DUST 真值
 * 1e20 全部提走）；(b) 伸手键是真无限发射（emittable，发射腿盖住需求）。</li>
 * <li><b>正向臂二（守 121/123/126）</b>："首位族无实记、只有封套"的宽域形状：封套那一笔
 * 1.2e19 枚照样进下发的表与封套本，但不算排水实记（执行侧 hole= 的 need 逐键剔掉它）——整表照发；
 * 把封套塞成需求或塞回实记的变异必须只红这条臂。</li>
 * </ul>
 */
class MaterialClosureGateReachDemandTest {

    /** 伸手那一枚（实机 {@code mekanism:enriched_redstone} 的形状）。 */
    private static final AEItemKey DUST = AEItemKey.of(Items.GLOWSTONE_DUST);
    /** 同槽靠后的备选候选（实机 essence 一族的形状：宽域存量、盖得住回落那一截）。 */
    private static final AEItemKey SIBLING = AEItemKey.of(Items.NETHER_STAR);
    private static final AEItemKey TARGET = AEItemKey.of(Items.CLOCK);
    /** 生产样板的原料（装得进 long 的常规库存）。 */
    private static final AEItemKey FUEL = AEItemKey.of(Items.COAL);

    /** 请求 1e11 发 × 槽倍率 1e9 × 每发 1 枚 = 这一格伸手键的需求 1e20 枚。 */
    private static final long REQUEST = 100_000_000_000L;
    private static final long SLOT_MULTIPLIER = 1_000_000_000L;
    private static final BigInteger REQUIRED = new BigInteger("100000000000000000000");
    /** DUST 网络真值 5e19 枚。 */
    private static final BigInteger DUST_TRUTH = new BigInteger("50000000000000000000");
    /** SIBLING 网络真值 6e19 枚。 */
    private static final BigInteger SIBLING_TRUTH = new BigInteger("60000000000000000000");
    /** 手算缺口：1e20 − 5e19 = 5e19。 */
    private static final BigInteger SHORTAGE = new BigInteger("50000000000000000000");
    /** 手算排产：ceil(5e19 / 1e6) = 5e13 发。 */
    private static final BigInteger EXPECTED_PRODUCER_FIRINGS = new BigInteger("50000000000000");
    private static final long PRODUCER_OUTPUT_PER_FIRING = 1_000_000L;

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

        private Details(net.minecraft.world.item.Item definitionItem, long multiplier,
                GenericStack... candidates) {
            this(definitionItem, new RawInput(multiplier, candidates),
                    List.of(new GenericStack(TARGET, 1L)));
        }

        private Details(net.minecraft.world.item.Item definitionItem, RawInput input,
                List<GenericStack> outputs) {
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

    /** 消费样板：TARGET×1/发，模糊槽 [DUST@1, SIBLING@1]、倍率 1e9。 */
    private static Details consumerDetails() {
        return new Details(Items.REPEATER, SLOT_MULTIPLIER,
                new GenericStack(DUST, 1L), new GenericStack(SIBLING, 1L));
    }

    /** 生产样板：FUEL×1 → DUST×1e6/发。 */
    private static Details producerDetails() {
        return new Details(Items.COMPARATOR,
                new RawInput(1L, new GenericStack(FUEL, 1L)),
                List.of(new GenericStack(DUST, PRODUCER_OUTPUT_PER_FIRING)));
    }

    /** 忠实图：DUST 与 SIBLING 的 long 库存视图都长成大天花板（无限盘溢出键的 AE2 口径事实），
     * 真值由 {@link PlanningSupplyTruth} 纠回。{@code withProducer} 决定 DUST 在图上有没有生产者。 */
    private static CraftGraph graph(boolean withProducer, Map<AEKey, PlanAmount> truth,
            Details consumer, Details producer) {
        var consumerPattern = new GraphPattern(consumer, TARGET, 1L,
                List.of(new GenericStack(TARGET, 1L)),
                List.of(new GraphInput(DUST, 1L, SLOT_MULTIPLIER, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(DUST, 1L),
                                new GraphInput.Candidate(SIBLING, 1L)),
                        Set.of(DUST, SIBLING), false)));
        var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
        var bySource = new java.util.IdentityHashMap<IPatternDetails, GraphPattern>();
        var order = new java.util.ArrayList<IPatternDetails>();
        producers.put(TARGET, List.of(consumerPattern));
        bySource.put(consumer, consumerPattern);
        order.add(consumer);
        if (withProducer) {
            var producerPattern = new GraphPattern(producer, DUST, PRODUCER_OUTPUT_PER_FIRING,
                    List.of(new GenericStack(DUST, PRODUCER_OUTPUT_PER_FIRING)),
                    List.of(new GraphInput(FUEL, 1L, 1L, GraphInput.Mode.NORMAL, null, null, null, false)));
            producers.put(DUST, List.of(producerPattern));
            bySource.put(producer, producerPattern);
            order.add(producer);
        }
        var graph = new CraftGraph(TARGET, Map.copyOf(producers), Map.copyOf(bySource),
                List.copyOf(order), Map.of(), Map.of(), Map.of(),
                Map.of(DUST, Long.MAX_VALUE, SIBLING, Long.MAX_VALUE, FUEL, 100_000_000_000_000L),
                Set.of(), Map.of(), false, null, withProducer ? 2 : 1, 1);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(truth));
        return graph;
    }

    private static Map<AEKey, PlanAmount> laneTruth() {
        return Map.of(DUST, PlanAmount.of(DUST_TRUTH), SIBLING, PlanAmount.of(SIBLING_TRUTH));
    }

    /**
     * 排产臂（HEAD 必红）：图上 DUST 有生产者。求解链就是生产那一趟的入口
     * （{@code GraphPlanningSession#solveLedger} 直调 {@code LinearPlanner}）。
     */
    @Test
    void beyondLongReachDemandOnProducibleFamilySchedulesTheProducer() {
        var consumer = consumerDetails();
        var producer = producerDetails();
        var graph = graph(true, laneTruth(), consumer, producer);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
        assertFalse(result.hasDeclined(),
                () -> "有生产路线的一族必须走排产出口把计划配平：" + result.declineReason());
        assertEquals(new BigInteger("100000000000"),
                result.state().craftsWide().getAmount(consumer).exactValue(),
                "消费样板发数逐字不变：1e11");
        var firings = result.state().craftsWide().getAmount(producer);
        assertEquals(EXPECTED_PRODUCER_FIRINGS, firings.exactValue(),
                () -> "DUST 生产者发数必须钉在 ceil(缺口 5e19 / 每发 1e6) = 5e13 的宽域真值上："
                        + " 实得=" + firings + "（HEAD 的形状是零排产增量）");
    }

    /**
     * 报缺臂：同图撤掉 DUST 的生产者 ⇒ 这一格不许静默闭账，缺口必须逐键点名 DUST、
     * 并且是宽域真值 {@code 5e19}（不是饱和哨兵）。
     *
     * <p><b>载体随引擎换、数字没换</b>：摘档前这一格在 {@code GraphPlanningSession#solveLedger} 里
     * 先由独立残量搜索判一个带账 DECLINE，但那一份判决当场被丢弃——{@code solveLedger} 接着
     * 落 {@code LinearPlanner}，交回的本来就是这份"带 5e19 缺料的残缺计划"。所以生产出口对
     * 玩家的那一句（终产物照出、缺口如实挂账）一字未变；本条从"钉中间判决"改成"钉玩家看得见
     * 的那一本账"。
     *
     * <p>闸门自己的逐键缺口账本与判决文本（{@code demand=}／{@code short=}／键名点名）不在这里丢：
     * 那是 {@code MaterialClosureGate#validate} 的直接判据，由
     * {@code MaterialClosureGateSelfProducedReachDemandTest
     * .narrowProducibleReachDemandWithoutScheduledProducerNamesTheKeyInShortage} 逐字钉着。
     */
    @Test
    void beyondLongReachDemandWithoutProducerIsReportedPerKeyWithWideGap() {
        var graph = graph(false, laneTruth(), consumerDetails(), null);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
        assertFalse(result.hasDeclined(), () -> "不许把这一格整条拒掉后静默放行：" + result.declineReason());
        assertTrue(result.simulation(), "伸手族盖不住需求的一格必须带残缺标记，不许照闭");
        var gap = result.state().missing().getAmount(DUST);
        assertEquals(SHORTAGE, gap.exactValue(),
                () -> "缺口账必须逐键点名 DUST 且是宽域真值 1e20 − 5e19 = 5e19 枚：" + gap
                        + "（这一格越出 long，被夹成饱和哨兵就报不出这个数）");
        assertEquals(DUST_TRUTH, result.state().used().getAmount(DUST).exactValue(),
                "网络上那 5e19 枚照腿提走，缺的只是图上没有生产者的一截");
        assertEquals(BigInteger.ZERO, result.state().missing().getAmount(SIBLING).exactValue(),
                "没伸手的备选不许被立洞");
    }

    /** 报缺臂第二景：计划自己没动伸手那一枚的库存（实机形态：提取腿为 0）⇒ 缺口仍按"需求−存量"记。 */
    @Test
    void beyondLongReachShortageSubtractsUntouchedNetworkStock() {
        var consumer = consumerDetails();
        var pattern = new GraphPattern(consumer, TARGET, 1L,
                List.of(new GenericStack(TARGET, 1L)),
                List.of(new GraphInput(DUST, 1L, SLOT_MULTIPLIER, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(DUST, 1L),
                                new GraphInput.Candidate(SIBLING, 1L)),
                        Set.of(DUST, SIBLING), false)));
        var graph = new CraftGraph(TARGET,
                Map.of(TARGET, List.of(pattern)),
                Map.of(consumer, pattern),
                List.of(consumer),
                Map.of(), Map.of(), Map.of(),
                Map.of(DUST, Long.MAX_VALUE, SIBLING, Long.MAX_VALUE),
                Set.of(), Map.of(), false, null, 1, 1);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(
                DUST, PlanAmount.of(DUST_TRUTH),
                SIBLING, PlanAmount.of(SIBLING_TRUTH))));
        // 这份计划只从备选那一枚提走 40 枚，DUST 的 5e19 库存一枚没动——排水付不满整槽，
        // 但守恒判据先红：缺口必须是 需求 1e20 − 存量 5e19，不许把没动的存量当零报成 1e20。
        var state = new GraphPlanningState(
                Map.of(DUST, Long.MAX_VALUE, SIBLING, 40L), Map.of());
        state.addCrafting(consumer, REQUEST);
        state.extract(SIBLING, 40L, false);
        var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        var violation = MaterialClosureGate.validate(state, graph, REQUEST, shortage);
        assertNotNull(violation, "伸手族盖不住需求的一格不许静默闭账");
        assertEquals(SHORTAGE, shortage.get(DUST).exactValue(),
                () -> "宽域缺口 = 1e20 − 5e19（没动的存量也要如实进缺地）：" + shortage);
        assertTrue(violation.contains("demand=100000000000000000000"), () -> violation);
        assertTrue(violation.contains("short=50000000000000000000"), () -> violation);
        assertFalse(violation.contains("9223372036854775807"),
                () -> "判决与缺口都不许拿饱和哨兵当量：" + violation);
    }

    /** 正向臂一(a)：可行订单照旧闭合——伸手族自己就盖得住整格需求（全额提走那一型）。 */
    @Test
    void feasibleOrderWithBilledReachSupplyStillClosesUnchanged() {
        var graph = graph(false, Map.of(
                DUST, PlanAmount.of(REQUIRED),
                SIBLING, PlanAmount.of(SIBLING_TRUTH)), consumerDetails(), null);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
        assertFalse(result.hasDeclined(),
                () -> "伸手族自己就盖得住整格需求，这一单可行，不许被记账拦下：" + result.declineReason());
        var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        assertNull(MaterialClosureGate.validate(result.state(), graph, REQUEST, shortage),
                "闭合照旧、缺口口袋空");
        assertTrue(shortage.isEmpty(), () -> "一个键都不许报缺：" + shortage);
        assertEquals(REQUIRED, result.state().used().getAmount(DUST).exactValue(),
                "DUST 全额提走那一截逐字不变");
    }

    /** 正向臂一(b)：伸手键是真无限发射源（emitted 腿盖住需求）⇒ 照旧闭合。 */
    @Test
    void feasibleOrderWithEmittableReachKeyStillClosesUnchanged() {
        var consumer = new Details(Items.REPEATER, SLOT_MULTIPLIER, new GenericStack(DUST, 1L));
        var consumerPattern = new GraphPattern(consumer, TARGET, 1L,
                List.of(new GenericStack(TARGET, 1L)),
                List.of(new GraphInput(DUST, 1L, SLOT_MULTIPLIER, GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(DUST, 1L)),
                        Set.of(DUST), false)));
        // DUST 网络无存量，但它是发射键：求解侧把不足那一腿记进 emitted（真无限源一型）。
        var graph = new CraftGraph(TARGET,
                Map.of(TARGET, List.of(consumerPattern)),
                Map.of(consumer, consumerPattern),
                List.of(consumer),
                Map.of(), Map.of(), Map.of(),
                Map.of(DUST, 0L),
                Set.of(DUST), Map.of(), false, null, 1, 1);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
        assertFalse(result.hasDeclined(),
                () -> "无限发射的一格不许被报缺拦下：" + result.declineReason());
        var shortage = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        assertNull(MaterialClosureGate.validate(result.state(), graph, REQUEST, shortage),
                () -> "闭合照旧：" + shortage);
        assertTrue(shortage.isEmpty(), () -> "发射腿盖得住的需求不许立洞：" + shortage);
        assertEquals(REQUIRED, result.state().emitted().getAmount(DUST).exactValue(),
                "发射那一腿逐字不变：1e20 枚");
    }

    /**
     * 正向臂二（守 121/123/126）：首位族无实记、只有封套的宽域形状。
     *
     * <p>手算（同 {@code ParallelEnvelopeDemandHoleTest} 那张表，只是把键名换到本类）：
     * 倍率 3 × 宇宙内那枚每发 1e18 × 4 发 = 封套 1.2e19 枚（12000000000000000000，越出 long）；
     * 备选整槽付满 12 枚、计划供给 40 枚；伸手那一枚族在网络与计划账上都没有来路 ⇒
     * <b>不记需求</b>（记了就是把许可上界当需求、把整张表顶成白卷）：表照发、实记里
     * 不含那一格（封套另记一本，减法在 {@code envelopes} 上做）。把零来路的封套塞成需求或塞回
     * 实记的变异只能红这一条臂。
     */
    @Test
    void envelopeOnlyWideFamilyShipsTableWithEnvelopeExcludedFromCoverNumerator() {
        var reach = AEItemKey.of(Items.ENDER_EYE);
        var paid = AEItemKey.of(Items.SNOWBALL);
        var out = AEItemKey.of(Items.IRON_INGOT);
        var source = new Details(Items.CLOCK, new RawInput(3L,
                new GenericStack(reach, 1_000_000_000_000_000_000L),
                new GenericStack(paid, 1L)),
                List.of(new GenericStack(out, 1L)));
        var pattern = new GraphPattern(source, out, 1L,
                List.of(new GenericStack(out, 1L)),
                List.of(new GraphInput(reach, 1_000_000_000_000_000_000L, 3L,
                        GraphInput.Mode.NORMAL, null,
                        List.of(new GraphInput.Candidate(paid, 1L),
                                new GraphInput.Candidate(reach, 1_000_000_000_000_000_000L)),
                        Set.of(reach, paid), false)));
        var graph = new CraftGraph(out,
                Map.of(out, List.of(pattern)),
                Map.of(source, pattern),
                List.of(source),
                Map.of(), Map.of(), Map.of(),
                Map.of(reach, 0L, paid, 40L),
                Set.of(), Map.of(), false, null, 1, 1);
        var state = new GraphPlanningState(Map.of(reach, 0L, paid, 40L), Map.of());
        state.addCrafting(source, 4L);
        state.extract(paid, 40L, false);

        assertNull(MaterialClosureGate.validate(state, graph, 4L, null),
                "首位族没有来路的封套形状不许被记成需求（拦下来就是 121/123/126 的白卷病）");
        var bill = MaterialClosureGate.buildMaterialPlan(state, graph, 4L);
        var shipped = bill.assigned().get(source);
        assertNotNull(shipped, () -> "封套不进需求 ⇒ 表必须照发：" + bill.assigned());
        assertEquals(new BigInteger("12000000000000000000"), shipped.getExact(reach),
                "封套那一笔照样进下发的表（许可上界，执行侧配额夹要读它）");
        assertEquals(12L, shipped.getLong(paid), "备选排水实记逐字不变");
        assertNotNull(bill.envelopes().get(source), "封套本照记");
        assertEquals(new BigInteger("12000000000000000000"),
                bill.envelopes().get(source).getExact(reach), "同一笔值另记封套本");
    }
}
