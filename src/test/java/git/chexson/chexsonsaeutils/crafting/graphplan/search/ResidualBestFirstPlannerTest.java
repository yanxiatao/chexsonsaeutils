package git.chexson.chexsonsaeutils.crafting.graphplan.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 独立残量搜索与 LinearPlanner 在首版受理域内的逐字段对拍。 */
final class ResidualBestFirstPlannerTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey MID = AEItemKey.of(Items.GUNPOWDER);
    private static final AEItemKey LEAF = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey OTHER = AEItemKey.of(Items.GOLD_INGOT);

    private static CraftGraph compile(FakeGrid grid) {
        return compile(grid, TOP);
    }

    private static CraftGraph compile(FakeGrid grid, AEKey target) {
        CraftGraph graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
        assertFalse(graph.hasDeclined(), () -> "compile declined: " + graph.declineReason());
        return graph;
    }

    private static SolveResult assertEquivalent(FakeGrid grid, long amount, CalculationStrategy strategy) {
        SolveResult expected = LinearPlanner.solve(compile(grid), strategy, amount);
        SolveResult actual = ResidualBestFirstPlanner.solve(compile(grid), strategy, amount);
        assertFalse(expected.hasDeclined(), () -> "linear declined: " + expected.declineReason());
        assertFalse(actual.hasDeclined(), () -> "search declined: " + actual.declineReason());
        assertEquals(expected.finalAmount(), actual.finalAmount(), "finalAmount");
        assertEquals(expected.simulation(), actual.simulation(), "simulation");
        assertLedger(expected.state(), actual.state());
        return actual;
    }

    private static void assertLedger(GraphPlanningState expected, GraphPlanningState actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(plain(expected.usedItems()), plain(actual.usedItems()), "usedItems");
        assertEquals(plain(expected.missingItems()), plain(actual.missingItems()), "missingItems");
        assertEquals(plain(expected.emittedItems()), plain(actual.emittedItems()), "emittedItems");
        assertEquals(crafts(expected), crafts(actual), "patternTimes");
        assertEquals(expected.bytes(), actual.bytes(), "bytes");
    }

    private static Map<AEKey, Long> plain(KeyCounter counter) {
        var result = new HashMap<AEKey, Long>();
        for (var entry : counter) {
            result.put(entry.getKey(), entry.getLongValue());
        }
        return result;
    }

    private static Map<String, Long> crafts(GraphPlanningState state) {
        var result = new HashMap<String, Long>();
        for (var entry : state.crafts().entrySet()) {
            result.merge(String.valueOf(entry.getKey().getDefinition()), entry.getValue(), Long::sum);
        }
        return result;
    }

    @Test
    void batchedSingleProducerDagMatchesLinear() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 10_000L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(MID, 2L)))
                .pattern(MID, FakePattern.of(MID, 3L, new FakeInput(LEAF, 4L)))
                .build();

        assertEquivalent(grid, 37L, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void sharedDependencyUsesOneStockLedger() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 10_000L))
                .pattern(TOP, FakePattern.of(TOP, 1L,
                        new FakeInput(MID, 2L), new FakeInput(OTHER, 1L)))
                .pattern(MID, FakePattern.of(MID, 1L, new FakeInput(LEAF, 3L)))
                .pattern(OTHER, FakePattern.of(OTHER, 1L, new FakeInput(LEAF, 5L)))
                .build();

        assertEquivalent(grid, 7L, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void fuzzyInputKeepsStableGreedyVariantOrder() {
        AEItemKey pristine = AEItemKey.of(Items.IRON_PICKAXE);
        var damagedStack = new ItemStack(Items.IRON_PICKAXE);
        damagedStack.setDamageValue(50);
        AEItemKey damaged = AEItemKey.of(damagedStack);
        var grid = FakeGrid.builder()
                .storage(Map.of(pristine, 10L, damaged, 5L))
                .pattern(TOP, FakePattern.of(TOP, 1L, FakeInput.anyVariant(pristine, 1L)))
                .build();

        SolveResult result = assertEquivalent(grid, 12L, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertEquals(5L, result.state().usedItems().get(damaged));
        assertEquals(7L, result.state().usedItems().get(pristine));
    }

    @Test
    void componentSpecificChainMatchesLinear() {
        AEItemKey version1 = customDataKey(1);
        AEItemKey version2 = customDataKey(2);
        AEItemKey version3 = customDataKey(3);
        FakePattern firstStage = FakePattern.of(version2, 1L,
                new FakeInput(version1, 1L), new FakeInput(LEAF, 1L));
        FakePattern secondStage = FakePattern.of(version3, 1L,
                new FakeInput(version2, 1L), new FakeInput(OTHER, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(version1, 4L, LEAF, 4L, OTHER, 4L))
                .pattern(version2, firstStage)
                .pattern(version3, secondStage)
                .build();
        CraftGraph graph = compile(grid, version3);

        SolveResult linear = LinearPlanner.solve(graph,
                CalculationStrategy.REPORT_MISSING_ITEMS, 4L);
        SolveResult search = ResidualBestFirstPlanner.solve(graph,
                CalculationStrategy.REPORT_MISSING_ITEMS, 4L);

        assertFalse(linear.hasDeclined(), () -> "linear declined: " + linear.declineReason());
        assertFalse(search.hasDeclined(), () -> "search declined: " + search.declineReason());
        assertFalse(linear.simulation());
        assertFalse(search.simulation());
        assertLedger(linear.state(), search.state());
        assertEquals(4L, search.state().crafts().get(firstStage));
        assertEquals(4L, search.state().crafts().get(secondStage));
        assertEquals(4L, search.state().usedItems().get(version1));
        assertEquals(0L, search.state().usedItems().get(version2));
    }

    private static AEItemKey customDataKey(int version) {
        ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", version);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return AEItemKey.of(stack);
    }

    @Test
    void boundedMultiProducerSearchSplitsAtMaximumFeasibleBatch() {
        FakePattern scarceRoute = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L));
        FakePattern fallbackRoute = FakePattern.of(TOP, 1L, new FakeInput(OTHER, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 2L, OTHER, 100L))
                .pattern(TOP, scarceRoute, fallbackRoute)
                .build();

        SolveResult result = assertEquivalent(grid, 6L, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertEquals(2L, result.state().crafts().get(scarceRoute));
        assertEquals(4L, result.state().crafts().get(fallbackRoute));
    }

    @Test
    void nestedRoutesKeepProviderOrderDespiteParentColor() {
        FakePattern parent = FakePattern.colored(2, AEItemKey.of(Items.CLOCK),
                List.of(new GenericStack(TOP, 1L)), new FakeInput(MID, 1L));
        FakePattern firstRegistered = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(MID, 1L)), new FakeInput(LEAF, 1L));
        FakePattern matchingParentColor = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(MID, 1L)), new FakeInput(OTHER, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 1L, OTHER, 1L))
                .pattern(TOP, parent)
                .pattern(MID, firstRegistered, matchingParentColor)
                .build();

        SolveResult result = ResidualBestFirstPlanner.solve(compile(grid),
                CalculationStrategy.REPORT_MISSING_ITEMS, 1L);
        assertFalse(result.hasDeclined(), () -> "search declined: " + result.declineReason());
        assertEquals(1L, result.state().crafts().get(firstRegistered));
        assertEquals(0L, result.state().crafts().getOrDefault(matchingParentColor, 0L));
    }

    @Test
    void missingSimulationMatchesLinear() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 5L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(MID, 1L)))
                .pattern(MID, FakePattern.of(MID, 1L, new FakeInput(LEAF, 4L)))
                .build();

        SolveResult result = assertEquivalent(grid, 9L, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(result.simulation());
    }

    @Test
    void craftLessFindsLargestExecutableAmount() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 10L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(LEAF, 2L)))
                .build();

        SolveResult result = assertEquivalent(grid, 32L, CalculationStrategy.CRAFT_LESS);
        assertEquals(5L, result.finalAmount());
        assertFalse(result.simulation());
    }

    @Test
    void compressibleMultiSeedRingMatchesLinearWithoutDelegation() {
        AEItemKey intermediate = AEItemKey.of(Items.DIAMOND);
        FakePattern starter = FakePattern.of(intermediate, 1L,
                new FakeInput(LEAF, 1L), new FakeInput(OTHER, 1L));
        FakePattern recycler = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(intermediate, 1L)},
                new GenericStack(LEAF, 2L), new GenericStack(OTHER, 2L),
                new GenericStack(MID, 1L));
        FakePattern consumer = FakePattern.of(TOP, 1L, new FakeInput(MID, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 1L, OTHER, 1L))
                .pattern(LEAF, recycler)
                .pattern(OTHER, recycler)
                .pattern(intermediate, starter)
                .pattern(MID, recycler)
                .pattern(TOP, consumer)
                .build();

        CraftGraph graph = compile(grid);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        assertFalse(graph.ringMacrosByEntry().getOrDefault(MID, List.of()).isEmpty());
        SolveResult result = assertEquivalent(grid, 8L, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertFalse(result.simulation());
        assertTrue(result.state().missingItems().isEmpty());
        assertEquals(1L, result.state().ringExtractions().getLong(LEAF));
        assertEquals(1L, result.state().ringExtractions().getLong(OTHER));
        assertInstanceOf(RecursivePlanContract.class,
                git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter.toPlan(result, graph));
    }

    @Test
    void nonUniformRingRatioMatchesLinearWithoutDelegation() {
        FakePattern producer = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(LEAF, 2L)}, new GenericStack(OTHER, 3L));
        FakePattern recycler = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(OTHER, 1L)},
                new GenericStack(LEAF, 1L), new GenericStack(MID, 1L));
        FakePattern consumer = FakePattern.of(TOP, 1L, new FakeInput(MID, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 2L))
                .pattern(LEAF, recycler)
                .pattern(OTHER, producer)
                .pattern(MID, recycler)
                .pattern(TOP, consumer)
                .build();

        CraftGraph graph = compile(grid);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        var macros = graph.ringMacrosByEntry().getOrDefault(MID, List.of());
        assertFalse(macros.isEmpty());
        assertEquals(1L, macros.getFirst().executionRatio().get(producer));
        assertEquals(2L, macros.getFirst().executionRatio().get(recycler));
        SolveResult result = assertEquivalent(grid, 8L, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertEquals(9L, result.state().crafts().get(producer));
        assertEquals(24L, result.state().crafts().get(recycler));
        assertEquals(8L, result.state().crafts().get(consumer));
    }

    @Test
    void nestedRingsMatchLinearWithoutDelegation() {
        AEItemKey innerSeed = AEItemKey.of(Items.REDSTONE);
        AEItemKey outerIntermediate = AEItemKey.of(Items.DIAMOND);
        FakePattern inner = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(innerSeed, 1L)},
                new GenericStack(innerSeed, 2L), new GenericStack(LEAF, 1L));
        FakePattern outerProducer = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(OTHER, 1L), new FakeInput(LEAF, 1L)},
                new GenericStack(outerIntermediate, 2L));
        FakePattern outerRecycler = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(outerIntermediate, 1L)},
                new GenericStack(OTHER, 1L), new GenericStack(MID, 1L));
        FakePattern consumer = FakePattern.of(TOP, 1L, new FakeInput(MID, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(innerSeed, 1L, OTHER, 1L, outerIntermediate, 1L))
                .pattern(innerSeed, inner)
                .pattern(LEAF, inner)
                .pattern(OTHER, outerRecycler)
                .pattern(outerIntermediate, outerProducer)
                .pattern(MID, outerRecycler)
                .pattern(TOP, consumer)
                .build();

        CraftGraph graph = compile(grid);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        assertFalse(graph.ringMacrosByEntry().getOrDefault(LEAF, List.of()).isEmpty());
        assertFalse(graph.ringMacrosByEntry().getOrDefault(MID, List.of()).isEmpty());
        SolveResult linear = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);
        SolveResult result = ResidualBestFirstPlanner.solve(
                graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);
        assertFalse(linear.hasDeclined(), () -> "linear declined: " + linear.declineReason());
        assertFalse(result.hasDeclined(), () -> "search declined: " + result.declineReason());
        assertFalse(result.simulation());
        assertTrue(result.state().missingItems().isEmpty());
        assertLedger(linear.state(), result.state());
        var expectedPlan = git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter
                .toPlan(linear, graph);
        var actualPlan = git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter
                .toPlan(result, graph);
        assertInstanceOf(RecursivePlanContract.class, actualPlan);
        git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.assertPlansEqual(expectedPlan, actualPlan);
    }

    @Test
    void consumedByproductFeedbackDeclinesInsteadOfInventingPlan() {
        AEItemKey byproduct = AEItemKey.of(Items.REDSTONE);
        FakePattern producer = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(LEAF, 1L)},
                new appeng.api.stacks.GenericStack(MID, 1L),
                new appeng.api.stacks.GenericStack(byproduct, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 10L))
                .pattern(TOP, FakePattern.of(TOP, 1L,
                        new FakeInput(MID, 1L), new FakeInput(byproduct, 1L)))
                .pattern(MID, producer)
                .build();

        SolveResult result = ResidualBestFirstPlanner.solve(compile(grid),
                CalculationStrategy.REPORT_MISSING_ITEMS, 3L);
        assertTrue(result.hasDeclined());
        assertTrue(result.declineReason().contains("byproduct feedback"));
    }
}
