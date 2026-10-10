package git.chexson.chexsonsaeutils.crafting.plannercompare;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.graphplan.CapturedPlanFixture;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.recursive.SelectedInputsPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PatternTimesExecutionValidatorTest {
    private static final Path CAPTURE = Path.of(
            "src", "test", "resources", "plan-captures",
            "chexsonsaeutils-plan-capture-recursive-ring.json");

    private static CapturedPlanFixture.Loaded fixture;
    private static CraftGraph graph;
    private static RecursiveCraftingPlan reference;
    private static IPatternDetails consumer;
    private static IPatternDetails producer;
    private static IPatternDetails recycler;

    @BeforeAll
    static void loadFixture() throws IOException {
        fixture = CapturedPlanFixture.load(CAPTURE);
        graph = GraphCompiler.compile(
                fixture.craftingService(), fixture.stock(), fixture.target(), null);
        var result = LinearPlanner.solve(
                graph, CalculationStrategy.REPORT_MISSING_ITEMS, fixture.amount());
        reference = (RecursiveCraftingPlan) Ae2PlanConverter.toPlan(result, graph);
        consumer = graph.patternRegistrationOrder().get(0);
        producer = graph.patternRegistrationOrder().get(1);
        recycler = graph.patternRegistrationOrder().get(2);
    }

    @Test
    void acceptsEveryExecutableRecursiveVector() {
        assertValid(plan(8L, 8L, 8L, reference.usedItems(), initialSeeds()));
        assertValid(plan(8L, 8L, 15L, reference.usedItems(), initialSeeds()));
        assertValid(plan(8L, 15L, 15L, reference.usedItems(), initialSeeds()));
    }

    @Test
    void rejectsInsufficientRecyclerFirings() {
        var validation = PatternTimesExecutionValidator.validate(
                fixture, plan(8L, 8L, 7L, reference.usedItems(), initialSeeds()), graph);
        assertFalse(validation.valid());
        assertEquals("patternTimes cannot be scheduled", validation.reason());
    }

    @Test
    void rejectsMissingRecursiveSeed() {
        AEKey removed = initialSeeds().iterator().next().getKey();
        KeyCounter seeds = copyCounterExcept(initialSeeds(), removed);
        KeyCounter used = copyCounterExcept(reference.usedItems(), removed);

        var validation = PatternTimesExecutionValidator.validate(
                fixture, plan(8L, 8L, 8L, used, seeds), graph);
        assertFalse(validation.valid());
        assertEquals("patternTimes cannot be scheduled", validation.reason());
    }

    @Test
    void rejectsRecursiveInitialItemsOutsideUsedItems() {
        AEKey removed = initialSeeds().iterator().next().getKey();
        KeyCounter used = copyCounterExcept(reference.usedItems(), removed);

        var validation = PatternTimesExecutionValidator.validate(
                fixture, plan(8L, 8L, 8L, used, initialSeeds()), graph);
        assertFalse(validation.valid());
        assertEquals("recursive initial item was not sourced", validation.reason());
    }

    @Test
    void rejectsEmittedItemWithoutEmitter() {
        KeyCounter emitted = new KeyCounter();
        emitted.add(fixture.target(), 1L);

        var validation = PatternTimesExecutionValidator.validate(
                fixture, plan(8L, 8L, 8L, reference.usedItems(), initialSeeds(), emitted), graph);
        assertFalse(validation.valid());
        assertEquals("emitted item is not emittable", validation.reason());
    }

    private static void assertValid(RecursiveCraftingPlan plan) {
        var validation = PatternTimesExecutionValidator.validate(fixture, plan, graph);
        assertTrue(validation.valid(), validation::reason);
        assertTrue(validation.producedTarget().longValueExact() >= fixture.amount());
    }

    private static RecursiveCraftingPlan plan(
            long consumerTimes,
            long producerTimes,
            long recyclerTimes,
            KeyCounter used,
            KeyCounter initial
    ) {
        return plan(consumerTimes, producerTimes, recyclerTimes, used, initial, new KeyCounter());
    }

    private static RecursiveCraftingPlan plan(
            long consumerTimes,
            long producerTimes,
            long recyclerTimes,
            KeyCounter used,
            KeyCounter initial,
            KeyCounter emitted
    ) {
        Map<IPatternDetails, Long> patternTimes = new IdentityHashMap<>();
        patternTimes.put(consumer, consumerTimes);
        patternTimes.put(producer, producerTimes);
        patternTimes.put(recycler, recyclerTimes);
        CraftingPlan delegate = new CraftingPlan(
                reference.finalOutput(), reference.bytes(), false, reference.multiplePaths(),
                copyCounter(used), copyCounter(emitted), new KeyCounter(), patternTimes);
        Map<AEItemKey, Map<AEKey, Long>> selected = reference instanceof SelectedInputsPlan selectedPlan
                ? selectedPlan.chexsonsaeutils$dyeableSelectedPatternInputs()
                : Map.of();
        return new RecursiveCraftingPlan(
                delegate, copyCounter(initial),
                ((RecursivePlanContract) reference).chexsonsaeutils$dyeableRecursiveInternalItems(),
                reference.finalOutput().amount(), selected);
    }

    private static KeyCounter initialSeeds() {
        return ((RecursivePlanContract) reference).chexsonsaeutils$dyeableRecursiveInitialItems();
    }

    private static KeyCounter copyCounterExcept(KeyCounter source, AEKey excluded) {
        KeyCounter copy = new KeyCounter();
        for (var entry : source) {
            if (!excluded.equals(entry.getKey())) {
                copy.add(entry.getKey(), entry.getLongValue());
            }
        }
        return copy;
    }

    private static KeyCounter copyCounter(KeyCounter source) {
        KeyCounter copy = new KeyCounter();
        copy.addAll(source);
        return copy;
    }
}
