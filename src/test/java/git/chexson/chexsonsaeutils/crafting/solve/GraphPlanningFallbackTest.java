package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import git.chexson.chexsonsaeutils.crafting.solve.Ae2TreePlanReference;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.assertPlansEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图规划与 AE2 原生树的等价对拍：图能表达的请求必须与原生树交出同一份计划。
 *
 * <p>图这一腿走仍在生产的有序规划入口（GRAPH 打头），不再另起一台零调用的求解壳。
 *
 * <p>回落链（图 DECLINE → 原生树）本身不在这里测：原生树要 {@code TickHandler}
 * 逐 tick 泵，离线用例没有服务端可泵，只有真机走得到。链的形状由
 * {@code OrderedPlanningSelectorTest} 按默认序钉住。
 *
 * <p>回落腿不管 bigint（台账 #155/T12）：{@link #nativeTreePlanCarriesNoWideLegs()} 断的就是
 * 原生树交出的那一份<b>不带任何宽域件</b>——宽域全长在自有图引擎那一侧，原生腿只交 AE2 自己的
 * {@code long} 读数。
 */
final class GraphPlanningFallbackTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);

    /** 图这一腿走仍在生产的有序规划入口（GRAPH 打头，见 {@link OrderedPlanningReplay}）。 */
    private static ICraftingPlan runGraph(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
        return OrderedPlanningReplay.create(
                grid, grid.requester, new GenericStack(output, amount), strategy, PlanningAlgorithmId.GRAPH).run();
    }

    private static ICraftingPlan runTree(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
        return new Ae2TreePlanReference(
                grid, grid.requester, new GenericStack(output, amount), strategy).run();
    }

    @Test
    void graphExpressibleRequestMatchesNativeTree() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L))
                .pattern(IRON, ironFromGold)
                .build();

        assertPlansEqual(runTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS),
                runGraph(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS));
    }

    @Test
    void multipleRoutesMatchNativeTree() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakePattern ironFromDiamond = FakePattern.crafting(IRON, 1, Map.of(DIAMOND, 3L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L, DIAMOND, 30L))
                .pattern(IRON, ironFromGold, ironFromDiamond)
                .build();

        ICraftingPlan expected = runTree(grid, IRON, 15, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(expected);
        assertTrue(expected.multiplePaths());
        // Multi-route graphs are solved directly since phase 4; the result
        // must still equal the optimized tree's answer.
        assertPlansEqual(expected, runGraph(grid, IRON, 15, CalculationStrategy.REPORT_MISSING_ITEMS));
    }

    @Test
    void craftLessBisectionMatchesNativeTree() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan expected = runTree(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
        assertNotNull(expected);
        assertFalse(expected.simulation());
        ICraftingPlan graphPlan = runGraph(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
        assertPlansEqual(expected, graphPlan);
        assertEquals(5L, graphPlan.finalOutput().amount());
    }

    @Test
    void missingItemsSimulationMatchesNativeTree() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 5L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan expected = runTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(expected);
        assertTrue(expected.simulation());
        assertPlansEqual(expected, runGraph(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS));
    }

    /**
     * 回落腿不管 bigint（台账 #155/T12）：AE2 原生树真跑一趟交出的那一份，就是 AE2 自己的
     * {@code CraftingPlan}，三条宽域读口（出入账／份数账／无限源键集）一律解析为空。
     *
     * <p>挑的这一格是宽域账<b>最可能被顺手补上去</b>的那一型：整单做不出来、原生树交回
     * {@code simulation=true} 的缺料计划——我方自有图引擎在这种场合确实会换成带宽域缺料账的
     * 回执（#83），原生腿要是也长出这一手，回落就变成"给 AE2 的结果再宽域化"。
     *
     * <p>一行只判一件事：类、出入账、份数账、无限源键各断一条，红字点名是哪一条断的。
     */
    @Test
    void nativeTreePlanCarriesNoWideLegs() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 5L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan plan = runTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(plan.simulation(), "这一格原生树交的是残缺计划：宽域账最可能被补上去的那一型");

        assertEquals(CraftingPlan.class, plan.getClass(),
                "原生腿只能交 AE2 自己的 CraftingPlan：套上我方载体就等于给原生树造宽域账");
        assertTrue(WideAmountPlanCarrier.resolveWideLedgers(plan).isEmpty(),
                "原生计划不得带宽域出入账 " + WideAmountPlanCarrier.resolveWideLedgers(plan));
        assertTrue(WideAmountPlanCarrier.resolveWideCrafts(plan).isEmpty(),
                "原生计划不得带宽域份数账 " + WideAmountPlanCarrier.resolveWideCrafts(plan));
        assertTrue(WideAmountPlanCarrier.resolveUnboundedKeys(plan).isEmpty(),
                "原生计划不得带无限源键集 " + WideAmountPlanCarrier.resolveUnboundedKeys(plan));
    }
}
