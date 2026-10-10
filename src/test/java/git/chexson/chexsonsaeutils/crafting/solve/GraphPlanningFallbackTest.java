package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.assertPlansEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the level-1 graph calculation shell and its fallback chain:
 * graph-expressible requests produce the same plan as the optimized tree, and
 * graph declines (multiple routes, fuzzy, recursive) fall through to the
 * optimized tree and still produce the tree's plan.
 */
final class GraphPlanningFallbackTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);

    private static ICraftingPlan runGraph(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
        var calc = new GraphPlanningCalculation(
                null, grid, grid.requester, new GenericStack(output, amount), strategy, 0L);
        return calc.run();
    }

    private static ICraftingPlan runFast(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
        var calc = new FastCraftingCalculation(
                null, grid, grid.requester, new GenericStack(output, amount), strategy, 0L);
        return calc.run();
    }

    @Test
    void graphExpressibleRequestMatchesOptimizedTree() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L))
                .pattern(IRON, ironFromGold)
                .build();

        assertPlansEqual(runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS),
                runGraph(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS));
    }

    @Test
    void multipleRoutesMatchOptimizedTree() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakePattern ironFromDiamond = FakePattern.crafting(IRON, 1, Map.of(DIAMOND, 3L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L, DIAMOND, 30L))
                .pattern(IRON, ironFromGold, ironFromDiamond)
                .build();

        ICraftingPlan expected = runFast(grid, IRON, 15, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(expected);
        assertTrue(expected.multiplePaths());
        // Multi-route graphs are solved directly since phase 4; the result
        // must still equal the optimized tree's answer.
        assertPlansEqual(expected, runGraph(grid, IRON, 15, CalculationStrategy.REPORT_MISSING_ITEMS));
    }

    @Test
    void craftLessBisectionMatchesOptimizedTree() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan expected = runFast(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
        assertNotNull(expected);
        assertFalse(expected.simulation());
        ICraftingPlan graphPlan = runGraph(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
        assertPlansEqual(expected, graphPlan);
        assertEquals(5L, graphPlan.finalOutput().amount());
    }

    @Test
    void missingItemsSimulationMatchesOptimizedTree() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 5L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan expected = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(expected);
        assertTrue(expected.simulation());
        assertPlansEqual(expected, runGraph(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS));
    }
}
