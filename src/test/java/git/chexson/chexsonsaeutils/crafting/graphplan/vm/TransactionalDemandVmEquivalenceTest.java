package git.chexson.chexsonsaeutils.crafting.graphplan.vm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.CapturedPlanFixture;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LinearPlanner 是首版事务 VM 的逐字段 oracle。 */
final class TransactionalDemandVmEquivalenceTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey MID = AEItemKey.of(Items.GUNPOWDER);
    private static final AEItemKey LEAF = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey OTHER = AEItemKey.of(Items.GOLD_INGOT);

    @Test
    void dagMatchesLinear() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 10_000L))
                .pattern(TOP, FakePattern.of(TOP, 1L,
                        new FakeInput(MID, 2L), new FakeInput(OTHER, 1L)))
                .pattern(MID, FakePattern.of(MID, 3L, new FakeInput(LEAF, 4L)))
                .pattern(OTHER, FakePattern.of(OTHER, 1L, new FakeInput(LEAF, 5L)))
                .build();
        assertMatches(grid, TOP, 7L, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void fuzzyCandidatesMatchLinear() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 4L, OTHER, 20L))
                .pattern(TOP, FakePattern.of(TOP, 1L, FakeInput.multi(1L, LEAF, OTHER)))
                .build();
        assertMatches(grid, TOP, 10L, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void orderedProducerFallbackMatchesLinear() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 2L, OTHER, 100L))
                .pattern(TOP,
                        FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L)),
                        FakePattern.of(TOP, 1L, new FakeInput(OTHER, 1L)))
                .build();
        assertMatches(grid, TOP, 6L, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void nestedRoutesKeepProviderOrderDespiteParentColor() {
        FakePattern parent = FakePattern.colored(2, AEItemKey.of(Items.CLOCK),
                java.util.List.of(new GenericStack(TOP, 1L)), new FakeInput(MID, 1L));
        FakePattern firstRegistered = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                java.util.List.of(new GenericStack(MID, 1L)), new FakeInput(LEAF, 1L));
        FakePattern matchingParentColor = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                java.util.List.of(new GenericStack(MID, 1L)), new FakeInput(OTHER, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 1L, OTHER, 1L))
                .pattern(TOP, parent)
                .pattern(MID, firstRegistered, matchingParentColor)
                .build();
        CraftGraph graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
        assertFalse(graph.hasDeclined(), () -> "compile declined: " + graph.declineReason());

        var vm = TransactionalDemandVm.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1L);

        assertFalse(vm.hasDeclined(), () -> "VM declined: " + vm.declineReason());
        assertEquals(1L, vm.state().crafts().get(firstRegistered));
        assertEquals(0L, vm.state().crafts().getOrDefault(matchingParentColor, 0L));
    }

    @Test
    void missingSimulationMatchesLinear() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 5L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(MID, 1L)))
                .pattern(MID, FakePattern.of(MID, 1L, new FakeInput(LEAF, 4L)))
                .build();
        assertMatches(grid, TOP, 9L, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void craftLessMatchesLinear() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 10L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(LEAF, 2L)))
                .build();
        assertMatches(grid, TOP, 32L, CalculationStrategy.CRAFT_LESS);
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

        CraftGraph graph = GraphCompiler.compile(
                grid.crafting, grid.storage.getCachedInventory(), TOP, null);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        assertFalse(graph.ringMacrosByEntry().getOrDefault(MID, java.util.List.of()).isEmpty());
        var vm = TransactionalDemandVm.compile(graph).solve(
                CalculationStrategy.REPORT_MISSING_ITEMS, 8L);
        assertFalse(vm.hasDeclined(), () -> "VM declined: " + vm.declineReason());
        assertFalse(vm.simulation());
        assertTrue(vm.state().missingItems().isEmpty());
        assertEquals(1L, vm.state().ringExtractions().getLong(LEAF));
        assertEquals(1L, vm.state().ringExtractions().getLong(OTHER));
        assertInstanceOf(RecursivePlanContract.class, Ae2PlanConverter.toPlan(vm, graph));
        assertMatches(graph, 8L, CalculationStrategy.REPORT_MISSING_ITEMS);
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

        CraftGraph graph = GraphCompiler.compile(
                grid.crafting, grid.storage.getCachedInventory(), TOP, null);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        var macros = graph.ringMacrosByEntry().getOrDefault(MID, java.util.List.of());
        assertFalse(macros.isEmpty());
        assertEquals(1L, macros.getFirst().executionRatio().get(producer));
        assertEquals(2L, macros.getFirst().executionRatio().get(recycler));
        var vm = TransactionalDemandVm.compile(graph).solve(
                CalculationStrategy.REPORT_MISSING_ITEMS, 8L);
        assertFalse(vm.hasDeclined(), () -> "VM declined: " + vm.declineReason());
        assertEquals(9L, vm.state().crafts().get(producer));
        assertEquals(24L, vm.state().crafts().get(recycler));
        assertEquals(8L, vm.state().crafts().get(consumer));
        assertMatches(graph, 8L, CalculationStrategy.REPORT_MISSING_ITEMS);
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

        CraftGraph graph = GraphCompiler.compile(
                grid.crafting, grid.storage.getCachedInventory(), TOP, null);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        assertFalse(graph.ringMacrosByEntry().getOrDefault(LEAF, java.util.List.of()).isEmpty());
        assertFalse(graph.ringMacrosByEntry().getOrDefault(MID, java.util.List.of()).isEmpty());
        var vm = TransactionalDemandVm.compile(graph).solve(
                CalculationStrategy.REPORT_MISSING_ITEMS, 8L);
        assertFalse(vm.hasDeclined(), () -> "VM declined: " + vm.declineReason());
        assertFalse(vm.simulation());
        assertTrue(vm.state().missingItems().isEmpty());
        assertInstanceOf(RecursivePlanContract.class, Ae2PlanConverter.toPlan(vm, graph));
        assertMatches(graph, 8L, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void capturedJsonGraphRunsAndMatchesLinear() throws IOException {
        assertCapturedPlanMatches("chexsonsaeutils-plan-capture-1788631871843.json");
    }

    @Test
    void capturedRecursiveRingMatchesLinear() throws IOException {
        assertCapturedPlanMatches("chexsonsaeutils-plan-capture-recursive-ring.json");
    }

    private static void assertCapturedPlanMatches(String name) throws IOException {
        var loaded = CapturedPlanFixture.load(Path.of(
                "src", "test", "resources", "plan-captures", name));
        CraftGraph graph = GraphCompiler.compile(
                loaded.craftingService(), loaded.stock(), loaded.target(), null);
        assertFalse(graph.hasDeclined(), () -> "capture compile declined: " + graph.declineReason());
        assertMatches(graph, loaded.amount(), CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    private static void assertMatches(FakeGrid grid, AEKey target, long amount, CalculationStrategy strategy) {
        CraftGraph graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
        assertFalse(graph.hasDeclined(), () -> "compile declined: " + graph.declineReason());
        assertMatches(graph, amount, strategy);
    }

    private static void assertMatches(CraftGraph graph, long amount, CalculationStrategy strategy) {
        var linear = LinearPlanner.solve(graph, strategy, amount);
        var vm = TransactionalDemandVm.compile(graph).solve(strategy, amount);
        assertFalse(linear.hasDeclined(), () -> "linear declined: " + linear.declineReason());
        assertFalse(vm.hasDeclined(), () -> "VM declined: " + vm.declineReason());
        var linearPlan = Ae2PlanConverter.toPlan(linear, graph);
        var vmPlan = Ae2PlanConverter.toPlan(vm, graph);
        FakePlanGrid.assertPlansEqual(linearPlan, vmPlan);
    }
}
