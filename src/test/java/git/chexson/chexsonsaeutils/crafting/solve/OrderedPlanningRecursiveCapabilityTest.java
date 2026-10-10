package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.RetentionApplier;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OrderedPlanningRecursiveCapabilityTest {
    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey A = AEItemKey.of(Items.STICK);
    private static final AEItemKey B = AEItemKey.of(Items.COAL);
    private static final AEItemKey LEAF = AEItemKey.of(Items.REDSTONE);

    @Test
    void everyConfiguredAlgorithmUsesSharedRecursiveKernel() {
        var first = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L)),
                new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
        var second = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 2L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B, 1L, LEAF, 32L))
                .pattern(A, second)
                .pattern(B, first)
                .pattern(TOP, consumer)
                .build();

        assertEveryAlgorithmPlans(grid, consumer, first, second);
    }

    @Test
    void retentionOnlyReseedsCatalystsExtractedFromNetwork() {
        var first = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L)),
                new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
        var second = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 2L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B, 1L, LEAF, 32L))
                .pattern(A, second)
                .pattern(B, first)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(B, 1L);
        stock.add(LEAF, 32L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 8L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        var graph = kernel.session().graph();
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);

        assertFalse(result.hasDeclined());
        assertEquals(0L, result.state().usedAmount(A), "A must be supplied by the ring, not network stock");
        assertTrue(result.state().usedAmount(B) > 0L, "B is the actual network seed");
        assertTrue(result.state().ringExtractions().getLong(B) > 0L);
        assertEquals(0L, result.state().ringExtractions().getLong(A));
        assertNull(RetentionApplier.apply(result.state(), graph, result.finalAmount()));
        assertEquals(0L, result.state().ringExtractions().getLong(A));
        RecursivePlanContract contract = assertInstanceOf(RecursivePlanContract.class,
                Ae2PlanConverter.toPlan(result, graph));
        assertTrue(contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(B) > 0L);
        assertEquals(0L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(A));
        assertTrue(contract.chexsonsaeutils$dyeableRecursiveInternalItems().get(B) > 0L);
    }

    @Test
    void retentionPreservesCompleteMultiSeedStartupSet() {
        var intermediate = AEItemKey.of(Items.IRON_INGOT);
        var output = AEItemKey.of(Items.DIAMOND);
        var starter = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(intermediate, 1L)),
                new FakeInput(A, 1L), new FakeInput(B, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 2L), new GenericStack(B, 2L),
                        new GenericStack(output, 1L)),
                new FakeInput(intermediate, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(output, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, B, 1L))
                .pattern(A, recycler)
                .pattern(B, recycler)
                .pattern(intermediate, starter)
                .pattern(output, recycler)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(A, 1L);
        stock.add(B, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 8L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        var graph = kernel.session().graph();
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);

        assertFalse(result.hasDeclined());
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        assertEquals(1L, result.state().ringExtractions().getLong(A));
        assertEquals(1L, result.state().ringExtractions().getLong(B));
        assertEquals(0L, result.state().ringExtractions().getLong(intermediate));
        assertNull(RetentionApplier.apply(result.state(), graph, result.finalAmount()));
        RecursivePlanContract directContract = assertInstanceOf(RecursivePlanContract.class,
                Ae2PlanConverter.toPlan(result, graph));
        assertEquals(1L, directContract.chexsonsaeutils$dyeableRecursiveInitialItems().get(A));
        assertEquals(1L, directContract.chexsonsaeutils$dyeableRecursiveInitialItems().get(B));
        assertEquals(0L, directContract.chexsonsaeutils$dyeableRecursiveInitialItems().get(intermediate));

        forEachAlgorithmPlan(grid, plan -> {
            RecursivePlanContract contract = assertInstanceOf(RecursivePlanContract.class, plan);
            assertEquals(1L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(A));
            assertEquals(1L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(B));
            assertEquals(0L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(intermediate));
            assertEquals(8L, plan.patternTimes().get(consumer));
            assertTrue(plan.patternTimes().getOrDefault(starter, 0L) >= 8L);
            assertTrue(plan.patternTimes().getOrDefault(recycler, 0L) >= 8L);
        });
    }

    @Test
    void everyConfiguredAlgorithmHandlesCrossPatternCatalyst() {
        var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L)),
                new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(B, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, LEAF, 32L))
                .pattern(A, recycler)
                .pattern(B, producer)
                .pattern(TOP, consumer)
                .build();

        assertEveryAlgorithmPlans(grid, consumer, producer, recycler);
    }

    @Test
    void everyConfiguredAlgorithmPreservesContractForFiniteUnsupportedCycle() {
        var forward = FakePattern.of(A, 1L, new FakeInput(B, 1L));
        var backEdge = FakePattern.of(B, 1L, new FakeInput(A, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B, 1L))
                .pattern(A, forward)
                .pattern(B, backEdge)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(B, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 1L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        assertEquals(CraftGraph.CycleKind.UNSUPPORTED_CYCLE, kernel.session().graph().cycleKind());
        forEachAlgorithmPlan(grid, 1L, plan -> {
            assertEquals(1L, plan.finalOutput().amount());
            assertEquals(1L, plan.patternTimes().get(consumer));
            assertEquals(1L, plan.patternTimes().get(forward));
            assertEquals(0L, plan.patternTimes().getOrDefault(backEdge, 0L));
            assertEquals(1L, plan.usedItems().get(B));
        });
    }

    @Test
    void recursiveKernelRetriesWithoutExpiredOptimizationBudget() throws InterruptedException {
        var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L)),
                new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(B, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, LEAF, 32L))
                .pattern(A, recycler)
                .pattern(B, producer)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(A, 1L);
        stock.add(LEAF, 32L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 8L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 1L, stock);

        var graph = kernel.session().graph();
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        assertFalse(graph.ringMacrosByEntry().getOrDefault(B, List.of()).isEmpty(),
                "recursive ring macro must be compiled for its entry key");
        Thread.sleep(20L);
        ICraftingPlan plan = kernel.solve();

        assertNotNull(plan, "expired optimization budget must not make recursive planning decline");
        assertFalse(plan.simulation(), () -> "fallback plan remained simulated: missing="
                + FakePlanGrid.toPlain(plan.missingItems()) + ", patterns=" + plan.patternTimes());
        assertInstanceOf(RecursivePlanContract.class, plan);
    }

    @Test
    void externallyDetectedRecursivePathRetriesWhenGraphClassificationIsNone() throws InterruptedException {
        var producer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 1L))
                .pattern(TOP, producer)
                .build();
        var stock = new KeyCounter();
        stock.add(LEAF, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 1L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 1L, stock);

        assertEquals(CraftGraph.CycleKind.NONE, kernel.session().graph().cycleKind());
        Thread.sleep(20L);
        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, "external recursive detection must force an exact retry");
        assertFalse(plan.simulation());
        assertTrue(plan.missingItems().isEmpty());
        assertEquals(1L, plan.patternTimes().get(producer));
    }

    @Test
    void everyConfiguredAlgorithmHandlesNonUniformRingRatio() {
        var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 3L)), new FakeInput(A, 2L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L), new GenericStack(LEAF, 1L)),
                new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 2L))
                .pattern(A, recycler)
                .pattern(B, producer)
                .pattern(LEAF, recycler)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(A, 2L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 8L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, kernel.session().graph().cycleKind());
        var leafMacros = kernel.session().graph().ringMacrosByEntry().getOrDefault(LEAF, List.of());
        assertFalse(leafMacros.isEmpty());
        assertEquals(1L, leafMacros.getFirst().executionRatio().get(producer));
        assertEquals(2L, leafMacros.getFirst().executionRatio().get(recycler));
        ICraftingPlan plan = kernel.solve();

        assertNotNull(plan, "non-uniform recursive ratio must have an internal exact plan");
        assertFalse(plan.simulation(), () -> "non-uniform ring remained simulated: missing="
                + FakePlanGrid.toPlain(plan.missingItems()) + ", patterns=" + plan.patternTimes());
        assertTrue(plan.missingItems().isEmpty());
        assertInstanceOf(RecursivePlanContract.class, plan);
        assertEquals(8L, plan.patternTimes().get(consumer));
        assertEquals(9L, plan.patternTimes().get(producer));
        assertEquals(24L, plan.patternTimes().get(recycler));

        assertEveryAlgorithmPlans(grid, consumer, producer, recycler);
    }

    @Test
    void everyConfiguredAlgorithmHandlesNestedRings() {
        var inner = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(A, 2L), new GenericStack(LEAF, 1L)),
                new FakeInput(A, 1L));
        var outerSeed = AEItemKey.of(Items.IRON_INGOT);
        var outerIntermediate = AEItemKey.of(Items.GOLD_INGOT);
        var outerOutput = AEItemKey.of(Items.DIAMOND);
        var outerProducer = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(outerIntermediate, 2L)),
                new FakeInput(outerSeed, 1L), new FakeInput(LEAF, 1L));
        var outerRecycler = FakePattern.colored(3, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(outerSeed, 1L), new GenericStack(outerOutput, 1L)),
                new FakeInput(outerIntermediate, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(outerOutput, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, outerSeed, 1L, outerIntermediate, 1L))
                .pattern(A, inner)
                .pattern(LEAF, inner)
                .pattern(outerSeed, outerRecycler)
                .pattern(outerIntermediate, outerProducer)
                .pattern(outerOutput, outerRecycler)
                .pattern(TOP, consumer)
                .build();

        assertEveryAlgorithmPlansNestedRings(
                grid, consumer, inner, outerProducer, outerRecycler);
    }

    @Test
    void everyConfiguredAlgorithmHandlesMultiOutputRing() {
        var firstOutput = AEItemKey.of(Items.IRON_INGOT);
        var secondOutput = AEItemKey.of(Items.GOLD_INGOT);
        var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L), new GenericStack(firstOutput, 1L),
                        new GenericStack(secondOutput, 1L)),
                new FakeInput(A, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L,
                new FakeInput(firstOutput, 1L), new FakeInput(secondOutput, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, B, 1L))
                .pattern(A, recycler)
                .pattern(B, producer)
                .pattern(firstOutput, producer)
                .pattern(secondOutput, producer)
                .pattern(TOP, consumer)
                .build();

        assertEveryAlgorithmPlansMultiOutputRing(grid, consumer, producer, recycler);
    }

    private static void assertEveryAlgorithmPlansNestedRings(
            FakeGrid grid,
            FakePattern consumer,
            FakePattern inner,
            FakePattern outerProducer,
            FakePattern outerRecycler
    ) {
        forEachAlgorithmPlan(grid, plan -> {
            assertEquals(8L, plan.patternTimes().get(consumer));
            assertTrue(plan.patternTimes().getOrDefault(inner, 0L) >= 8L);
            assertTrue(plan.patternTimes().getOrDefault(outerProducer, 0L) >= 8L);
            assertTrue(plan.patternTimes().getOrDefault(outerRecycler, 0L) >= 8L);
        });
    }

    private static void assertEveryAlgorithmPlansMultiOutputRing(
            FakeGrid grid,
            FakePattern consumer,
            FakePattern producer,
            FakePattern recycler
    ) {
        forEachAlgorithmPlan(grid, plan -> {
            assertEquals(8L, plan.patternTimes().get(consumer));
            assertTrue(plan.patternTimes().getOrDefault(producer, 0L) >= 8L);
            assertTrue(plan.patternTimes().getOrDefault(recycler, 0L) >= 4L);
        });
    }

    private static void forEachAlgorithmPlan(
            FakeGrid grid,
            java.util.function.Consumer<ICraftingPlan> assertion
    ) {
        forEachAlgorithmPlan(grid, 8L, assertion);
    }

    private static void forEachAlgorithmPlan(
            FakeGrid grid,
            long amount,
            java.util.function.Consumer<ICraftingPlan> assertion
    ) {
        var service = PlanningAlgorithmOrderService.global();
        List<PlanningAlgorithmId> originalOrder = service.snapshot().order();
        try {
            for (PlanningAlgorithmId algorithm : PlanningAlgorithmId.values()) {
                moveFirst(algorithm);
                ICraftingPlan plan = new OrderedPlanningCalculation(
                        null, grid, grid.requester, new GenericStack(TOP, amount),
                        CalculationStrategy.REPORT_MISSING_ITEMS).run();
                assertFalse(plan.simulation(), algorithm + " must calculate the recursive rings");
                assertTrue(plan.missingItems().isEmpty(), algorithm + " must not report a false ring shortfall");
                assertInstanceOf(RecursivePlanContract.class, plan,
                        algorithm + " must preserve recursive metadata");
                assertion.accept(plan);
            }
        } finally {
            service.reload(originalOrder);
        }
    }

    private static void assertEveryAlgorithmPlans(
            FakeGrid grid,
            FakePattern consumer,
            FakePattern first,
            FakePattern second
    ) {
        var service = PlanningAlgorithmOrderService.global();
        List<PlanningAlgorithmId> originalOrder = service.snapshot().order();
        try {
            for (PlanningAlgorithmId algorithm : PlanningAlgorithmId.values()) {
                moveFirst(algorithm);
                ICraftingPlan plan = new OrderedPlanningCalculation(
                        null, grid, grid.requester, new GenericStack(TOP, 8L),
                        CalculationStrategy.REPORT_MISSING_ITEMS).run();
                assertFalse(plan.simulation(), algorithm + " must calculate the recursive ring");
                assertTrue(plan.missingItems().isEmpty(), algorithm + " must not report a false ring shortfall");
                assertInstanceOf(RecursivePlanContract.class, plan, algorithm + " must preserve recursive metadata");
                assertEquals(8L, plan.patternTimes().get(consumer));
                assertTrue(plan.patternTimes().getOrDefault(first, 0L) > 0L);
                assertTrue(plan.patternTimes().getOrDefault(second, 0L) > 0L);
            }
        } finally {
            service.reload(originalOrder);
        }
    }

    private static void moveFirst(PlanningAlgorithmId first) {
        var service = PlanningAlgorithmOrderService.global();
        var order = new ArrayList<>(List.of(PlanningAlgorithmId.values()));
        order.remove(first);
        order.addFirst(first);
        service.reload(order);
    }
}
