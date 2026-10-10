package git.chexson.chexsonsaeutils.crafting.solve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanPostProcessor;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.PlanningAttemptResult;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;

/**
 * 终点算法（NATIVE）也没辙那条路的验收：守恒闸门逐键报出的缺口，必须变成一份能进
 * AE2 合成确认界面的「材料不足」计划（{@code simulation=true} + 逐键缺料），而不是聊天栏里
 * 一行 {@code IllegalStateException: Terminal planning algorithm declined: NATIVE}。
 *
 * <p>三条判据一条都不能少：缺口<b>该多大报多大</b>（&gt;{@code long} 的那一截走宽域账，
 * 确认屏按真值显示）、需求<b>一分不砍</b>（{@code finalOutput} 就是那份账自己的份数）、
 * 判决<b>不放行</b>（闸门照旧 DECLINE，只有"真缺料"这一类才有回执；预算耗尽、图 overflow、
 * 编译失败、模糊槽排水一律交回 {@code null}，既有回落链一字不变）。
 */
final class TerminalShortagePlanTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    // 递归环夹具用的三枚键 + 目标件（与 OrderedPlanningRecursiveCapabilityTest 同一形状）
    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey A = AEItemKey.of(Items.STICK);
    private static final AEItemKey B = AEItemKey.of(Items.COAL);
    private static final AEItemKey LEAF = AEItemKey.of(Items.REDSTONE);

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    /**
     * 每发 3e12 枚金 × {@value #FIRINGS} 发 = 9e18 一枚笔，两笔合计 1.8e19 越过 long。
     *
     * <p>单笔刻意留在 long 域内：闸门的需求乘算自 2026-09-23 起按<b>真值</b>算，不再把越界的
     * 单项钉成天花板；本用例锁的是"缺料账读宽域"，不该顺带依赖饱和值。
     */
    private static final long FIRINGS = 3_000_000L;
    /** 该键的总需求：两个站点各 9e18 枚，合计 1.8e19 枚。 */
    private static final BigInteger DEMAND = BigInteger.valueOf(9_000_000_000_000_000_000L).shiftLeft(1);

    /** 闸门判缺料时逐键缺口要记<b>全</b>、记<b>精确</b>：判决文本只点名第一个键，缺口账不能只抄它。 */
    @Test
    void closureGateCollectsEveryShortKeyBeyondLong() {
        var first = hugeGoldPattern();
        var second = hugeGoldPattern();
        var state = goldLedger(first, second, 1_000L);

        var shortage = new LinkedHashMap<AEKey, PlanAmount>();
        String violation = MaterialClosureGate.validate(
                state, twoPatternGraph(first, second), 6_000_000L, shortage);

        assertNotNull(violation, () -> "宽域缺料盖不住需求就是算料错误，不能放行" + shortage);
        assertEquals(PlanAmount.of(DEMAND.subtract(BigInteger.valueOf(2_000L))), shortage.get(GOLD),
                "缺口 = 需求 1.8446744e19 − 已记缺料 2000：必须按宽域真值记，不许被 long 截成一枚天花板");
    }

    /** 账配平时那只口袋一个字都不该写：报缺料不是"顺手记一笔"。 */
    @Test
    void balancedLedgerCollectsNoShortage() {
        var first = hugeGoldPattern();
        var second = hugeGoldPattern();
        var state = goldLedger(first, second, Long.MAX_VALUE);

        var shortage = new LinkedHashMap<AEKey, PlanAmount>();
        String violation = MaterialClosureGate.validate(
                state, twoPatternGraph(first, second), 6_000_000L, shortage);

        assertNull(violation, () -> "两笔天花板缺料已盖住需求，闸门不该拒：" + violation);
        assertTrue(shortage.isEmpty(), () -> "闭合的账不该留下缺口条目：" + shortage);
    }

    /**
     * 用户定的新出口：闸门判决 + 那份被拒的账 → {@code simulation=true} 的残缺计划。
     * AE2 的 {@code KeyCounter} 只能装 {@code long}，交回的那份读数是向上饱和的哨兵；
     * 真值随 {@link WideAmountPlanCarrier} 的缺料账出门，确认屏按真值显示。
     */
    @Test
    void terminalShortagePlanReportsTheExactGapInsteadOfThrowing() {
        var first = hugeGoldPattern();
        var second = hugeGoldPattern();
        var state = goldLedger(first, second, 1_000L);
        var graph = twoPatternGraph(first, second);
        var shortage = new LinkedHashMap<AEKey, PlanAmount>();
        String violation = MaterialClosureGate.validate(state, graph, 6_000_000L, shortage);
        assertNotNull(violation);

        ICraftingPlan plan = GraphPlanPostProcessor.shortagePlan(
                PlanningAttemptResult.declinedWithLedger(
                        SolveResult.declinedShortage(violation, shortage, state, 6_000_000L)), graph);

        assertNotNull(plan, "终点算法也没辙时要交回一份材料不足回执，而不是一行异常");
        assertTrue(plan.simulation(),
                "缺料计划必须带 simulation 标记，否则执行侧会照单吞掉一份没有来源的料");
        assertEquals(IRON, plan.finalOutput().what());
        assertEquals(6_000_000L, plan.finalOutput().amount(), "需求一分不许砍");
        assertEquals(Long.MAX_VALUE, plan.missingItems().get(GOLD),
                "AE2 那份 long 读数按向上饱和报缺料（今日口径）");
        assertEquals(shortage.get(GOLD).exactValue(), WideAmountPlanCarrier.resolveWideLedgers(plan)
                        .entries(WideAmountKind.MISSING).get(GOLD),
                "确认屏要看得见的真值：闸门逐键算出多少缺口就报多少，>long 那一截不许被截成天花板");
        assertTrue(plan.patternTimes().isEmpty(),
                "判决是「这份订单做不出来」：回执只交缺料事实，不摆一份跑不通的样板表充数");
    }

    /**
     * 实机 2026-09-22 22:36 交不出回执的确切形状：上一刀把缺口记进<b>那份被判不可行的账本</b>再转换，
     * 而不可行的账本从超支库存出账、出口值带负数，守卫一挡就什么都没有了。
     * 现在回执只依赖闸门逐键缺口——账本坏成什么样（甚至压根没有账本）都不许影响它。
     */
    @Test
    void shortageReportDoesNotDependOnTheRejectedLedger() {
        var graph = twoPatternGraph(hugeGoldPattern(), hugeGoldPattern());
        var shortage = new LinkedHashMap<AEKey, PlanAmount>();
        shortage.put(GOLD, PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1)));

        var plan = GraphPlanPostProcessor.shortagePlan(PlanningAttemptResult.declinedWithLedger(
                new SolveResult(null, 6_000_000L, true, "material closure violated: gold", shortage)), graph);

        assertNotNull(plan, "被拒的账本不可用，不是把缺料事实一起吞掉的正当理由");
        assertTrue(plan.simulation());
        assertEquals(PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1))
                        .exactValue(),
                WideAmountPlanCarrier.resolveWideLedgers(plan)
                        .entries(WideAmountKind.MISSING).get(GOLD),
                ">long 的那一截仍要按真值报出去");
    }

    /** 只认「真缺料」这一类判决：预算耗尽 / 图 overflow / 编译失败一律照旧抛原异常。 */
    @Test
    void everyOtherDeclineKeepsThrowingPathUntouched() {
        var first = hugeGoldPattern();
        var second = hugeGoldPattern();
        var graph = twoPatternGraph(first, second);

        assertNull(GraphPlanPostProcessor.shortagePlan(
                PlanningAttemptResult.declined("graph planning budget exhausted during SOLVE"), graph));
        assertNull(GraphPlanPostProcessor.shortagePlan(
                PlanningAttemptResult.declined("graph overflow"), graph));
        assertNull(GraphPlanPostProcessor.shortagePlan(null, graph));
        // 判决不带缺口账（模糊槽排水这类）：即使手里有账本也不许替它编一个缺口
        assertNull(GraphPlanPostProcessor.shortagePlan(PlanningAttemptResult.declinedWithLedger(
                SolveResult.declined("material closure violated on fuzzy slot: unfundedUnits=1")), graph));
    }

    /**
     * 回落链的最后一站照旧会抛：终点算法拿到的是<b>没有缺口账</b>的判决时，新出口交回
     * {@code null}，{@link OrderedPlanningSelector} 仍按
     * {@code Terminal planning algorithm declined: NATIVE} 抛出去——只有真缺料那一条路径改了行为。
     *
     * <p>用"非正请求量"当判决来源只为一个目的：离线拿到一条<b>确定</b>不是守恒闸门判出的判决
     * （预算耗尽这类要看机器快慢，不能当判据）。真实订单量走的是另一条路：有缺口账就报缺料。
     */
    @Test
    void terminalDeclineWithoutShortageStillThrows() {
        var grid = ringGrid();

        var calculation = new OrderedPlanningCalculation(
                null, grid, grid.requester, new GenericStack(TOP, 0L),
                CalculationStrategy.REPORT_MISSING_ITEMS,
                List.of(PlanningAlgorithmId.NATIVE), false, 5_000L);

        var failure = assertThrows(IllegalStateException.class, calculation::run);
        assertEquals("Terminal planning algorithm declined: NATIVE", failure.getMessage());
    }

    /**
     * 递归核这一环的判据：还没解过就没有判决，更没有缺口账 —— {@code terminalShortagePlan()}
     * 交回 {@code null}，调用方照旧按"终点算法也没辙"抛原异常。
     */
    @Test
    void kernelWithoutAnyDeclineHandsBackNothing() {
        var grid = ringGrid();
        var stock = new appeng.api.stacks.KeyCounter();
        stock.add(B, 1L);
        stock.add(LEAF, 32L);
        var kernel = new RecursivePlanningKernel(null, grid, grid.requester,
                new GenericStack(TOP, 8L), CalculationStrategy.REPORT_MISSING_ITEMS, 5_000L, stock);

        assertNull(kernel.terminalShortagePlan(), "没解过就没有判决：不许凭空造一份缺料回执");
    }

    // ---- fixtures ----

    /** 一条真递归环（A↔B）+ 一个吃 A 的目标件：有环，终点算法才走我方递归核。 */
    private static FakePlanGrid.FakeGrid ringGrid() {
        var first = FakePlanGrid.FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L)),
                new FakePlanGrid.FakeInput(A, 1L), new FakePlanGrid.FakeInput(LEAF, 1L));
        var second = FakePlanGrid.FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 2L)), new FakePlanGrid.FakeInput(B, 1L));
        var consumer = FakePlanGrid.FakePattern.of(TOP, 1L, new FakePlanGrid.FakeInput(A, 1L));
        return FakePlanGrid.FakeGrid.builder()
                .storage(Map.of(B, 1L, LEAF, 32L))
                .pattern(A, second)
                .pattern(B, first)
                .pattern(TOP, consumer)
                .build();
    }

    /** 两发各记 {@code missingPerSite} 枚缺料的那本账：该键需求 {@code 2×Long.MAX_VALUE} 枚金。 */
    private static GraphPlanningState goldLedger(GraphPattern first, GraphPattern second,
            long missingPerSite) {
        var state = new GraphPlanningState(Map.of(GOLD, 100L), Map.of());
        state.addCrafting(first.source(), FIRINGS);
        state.addCrafting(second.source(), FIRINGS);
        state.addMissing(GOLD, missingPerSite);
        state.addMissing(GOLD, missingPerSite);
        return state;
    }

    private static GraphPattern hugeGoldPattern() {
        return new GraphPattern(new FakeDetails(), IRON, 1,
                List.of(new GenericStack(IRON, 1L)),
                List.of(new GraphInput(GOLD, 3_000_000_000_000L, 1L,
                        GraphInput.Mode.NORMAL, null, null, null, false)));
    }

    private static CraftGraph twoPatternGraph(GraphPattern first, GraphPattern second) {
        return new CraftGraph(IRON,
                Map.of(IRON, List.of(first, second)),
                Map.of(first.source(), first, second.source(), second),
                List.of(first.source(), second.source()),
                Map.of(), Map.of(), Map.of(),
                Map.of(GOLD, 100L),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
    }

    /** 轻量样板替身：闸门与转换器只拿 definition 当身份。 */
    private static final class FakeDetails implements IPatternDetails {
        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of();
        }

        @Override
        public AEItemKey getDefinition() {
            return AEItemKey.of(new ItemStack(Items.PAPER));
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
