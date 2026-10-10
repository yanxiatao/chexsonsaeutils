package git.chexson.chexsonsaeutils.crafting.graphplan;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.Ae2TreePlanReference;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 首槽终端剪枝的行为验证：产量、路线、余料、缺料及字节数保持既有契约。 */
class TerminalBatchPruningTest {
    private static final AEItemKey TARGET = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey SCARCE = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey ABUNDANT = AEItemKey.of(Items.DIAMOND);

    @Test
    void scarceFirstRouteKeepsNativePlanIncludingRoundingAndBytes() {
        for (long stock : new long[] {0L, 1L, 7L, 42L}) {
            var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                    List.of(new GenericStack(TARGET, 3L)), FakeInput.stack(SCARCE, 2L)).withPriority(10);
            var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                    List.of(new GenericStack(TARGET, 3L)), new FakeInput(ABUNDANT, 1L));
            var grid = FakeGrid.builder().storage(Map.of(SCARCE, stock, ABUNDANT, 100L))
                    .pattern(TARGET, preferred, alternative).build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
            assertEquals(2, graph.producersByOutput().get(TARGET).size());
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 97L);
            assertFalse(result.hasDeclined(), result::declineReason);
            var actual = Ae2PlanConverter.toPlan(result, graph);
            var expected = new Ae2TreePlanReference(grid, grid.requester,
                    new GenericStack(TARGET, 97L), CalculationStrategy.REPORT_MISSING_ITEMS).run();
            FakePlanGrid.assertPlansEqual(expected, actual);
        }
    }

    @Test
    void byproductRestoresAFormerlyUnavailableRouteInTheSameAttempt() {
        var intermediate = AEItemKey.of(Items.REDSTONE);
        var top = AEItemKey.of(Items.EMERALD);
        var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(TARGET, 1L)), new FakeInput(SCARCE, 1L)).withPriority(10);
        var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(TARGET, 1L)), new FakeInput(ABUNDANT, 1L));
        var byproduct = FakePattern.colored(0, AEItemKey.of(Items.MAP),
                List.of(new GenericStack(intermediate, 1L), new GenericStack(SCARCE, 1L)),
                new FakeInput(ABUNDANT, 1L));
        var parent = FakePattern.of(top, 1L,
                new FakeInput(TARGET, 1L), new FakeInput(intermediate, 1L), new FakeInput(TARGET, 1L));
        var grid = FakeGrid.builder().storage(Map.of(ABUNDANT, 2L))
                .pattern(top, parent).pattern(intermediate, byproduct)
                .pattern(TARGET, preferred, alternative).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), top, null);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1L);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        assertEquals(1L, (long) result.state().crafts().get(preferred));
        assertEquals(1L, (long) result.state().crafts().get(alternative));
        assertEquals(2L, result.state().usedItems().get(ABUNDANT));
        assertEquals(0L, result.state().usedItems().get(SCARCE));
    }

    @Test
    void missingSimulationStillReportsPreferredRouteInputs() {
        var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(TARGET, 1L)), new FakeInput(SCARCE, 1L)).withPriority(10);
        var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(TARGET, 1L)), new FakeInput(ABUNDANT, 1L));
        var grid = FakeGrid.builder().pattern(TARGET, preferred, alternative).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 17L);
        assertFalse(result.hasDeclined(), result::declineReason);
        var actual = Ae2PlanConverter.toPlan(result, graph);
        var expected = new Ae2TreePlanReference(grid, grid.requester,
                new GenericStack(TARGET, 17L), CalculationStrategy.REPORT_MISSING_ITEMS).run();
        FakePlanGrid.assertPlansEqual(expected, actual);
        assertEquals(17L, actual.missingItems().get(SCARCE));
    }
}
