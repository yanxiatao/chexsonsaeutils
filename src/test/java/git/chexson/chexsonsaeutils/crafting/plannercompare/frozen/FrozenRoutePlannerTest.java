package git.chexson.chexsonsaeutils.crafting.plannercompare.frozen;

import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitnessPlans;
import git.chexson.chexsonsaeutils.crafting.speedplan.FrozenRoutePlanner;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrozenRoutePlannerTest {
    private static final AEKey TOP = AEItemKey.of(Items.IRON_INGOT);
    private static final AEKey A = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEKey B = AEItemKey.of(Items.DIAMOND);
    private static final AEKey LEAF = AEItemKey.of(Items.REDSTONE);
    private static final AEKey RAW = AEItemKey.of(Items.COAL);

    @Test
    void freezesFirstProducerForProviderEpoch() {
        var first = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
        var second = FakePattern.of(TOP, 1L, new FakeInput(B, 1L));
        var grid = FakeGrid.builder().storage(Map.of(B, 1L)).pattern(TOP, first, second).build();
        var planner = new FrozenRoutePlanner();

        var compiled = planner.compile(grid.crafting, TOP, null);
        var solved = planner.solve(compiled, grid.storage.getCachedInventory(), 1L);

        assertEquals(1L, solved.plan().patternTimes().get(first));
        assertNull(solved.plan().patternTimes().get(second));
        assertEquals(1L, solved.plan().missingItems().get(A));
        assertEquals(0L, solved.plan().usedItems().get(B));
        assertTrue(solved.plan().multiplePaths());
        assertTrue(compiled.stats().providerEpoch() > 0L);
    }

    @Test
    void mergesSharedDependencyBeforeItsInstruction() {
        var top = FakePattern.of(TOP, 1L, new FakeInput(A, 1L), new FakeInput(B, 1L));
        var fromA = FakePattern.of(A, 1L, new FakeInput(LEAF, 2L));
        var fromB = FakePattern.of(B, 1L, new FakeInput(LEAF, 3L));
        var grid = FakeGrid.builder().storage(Map.of(LEAF, 4L))
                .pattern(TOP, top).pattern(A, fromA).pattern(B, fromB).build();

        var solved = new FrozenRoutePlanner().solve(
                new FrozenRoutePlanner().compile(grid.crafting, TOP, null),
                grid.storage.getCachedInventory(), 1L);

        assertEquals(4L, solved.plan().usedItems().get(LEAF));
        assertEquals(1L, solved.plan().missingItems().get(LEAF));
        assertEquals(1L, solved.plan().patternTimes().get(fromA));
        assertEquals(1L, solved.plan().patternTimes().get(fromB));
    }

    @Test
    void exactCandidatesOnlyConsumeStockAndDeficitUsesCanonicalKey() {
        var top = FakePattern.of(TOP, 1L, FakeInput.multi(3L, A, B));
        var canonical = FakePattern.of(A, 1L, new FakeInput(RAW, 1L));
        var alternative = FakePattern.of(B, 1L, new FakeInput(LEAF, 1L));
        var grid = FakeGrid.builder().storage(Map.of(B, 2L, RAW, 1L))
                .pattern(TOP, top).pattern(A, canonical).pattern(B, alternative).build();
        var planner = new FrozenRoutePlanner();

        var solved = planner.solve(planner.compile(grid.crafting, TOP, null),
                grid.storage.getCachedInventory(), 1L);

        assertEquals(2L, solved.plan().usedItems().get(B));
        assertEquals(1L, solved.plan().usedItems().get(RAW));
        assertEquals(1L, solved.plan().patternTimes().get(canonical));
        assertNull(solved.plan().patternTimes().get(alternative));
        assertTrue(solved.plan().missingItems().isEmpty());
    }

    @Test
    void emitTerminatesWithoutProducer() {
        var top = FakePattern.of(TOP, 1L, new FakeInput(A, 4L));
        var grid = FakeGrid.builder().pattern(TOP, top).emittable(A).build();
        var planner = new FrozenRoutePlanner();

        var solved = planner.solve(planner.compile(grid.crafting, TOP, null),
                grid.storage.getCachedInventory(), 2L);

        assertEquals(8L, solved.plan().emittedItems().get(A));
        assertTrue(solved.plan().missingItems().isEmpty());
        assertFalse(solved.plan().simulation());
    }

    @Test
    void secondaryOutputDoesNotFeedBack() {
        var producer = FakePattern.withOutputs(
                new FakeInput[] { new FakeInput(A, 1L) },
                new GenericStack(TOP, 1L), new GenericStack(A, 1L));
        var grid = FakeGrid.builder().pattern(TOP, producer).build();
        var planner = new FrozenRoutePlanner();

        var solved = planner.solve(planner.compile(grid.crafting, TOP, null),
                grid.storage.getCachedInventory(), 1L);

        assertEquals(1L, solved.plan().missingItems().get(A));
    }

    @Test
    void cycleTerminatesAtSccBoundary() {
        var top = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
        var back = FakePattern.of(A, 1L, new FakeInput(TOP, 1L));
        var grid = FakeGrid.builder().pattern(TOP, top).pattern(A, back).build();
        var planner = new FrozenRoutePlanner();
        var program = planner.compile(grid.crafting, TOP, null);

        var solved = planner.solve(program, grid.storage.getCachedInventory(), 3L);

        assertEquals(3L, solved.plan().patternTimes().get(top));
        assertEquals(3L, solved.plan().missingItems().get(A));
        assertEquals(1, program.stats().sccs());
        assertTrue(solved.stats().operations() < 20L);
    }

    @Test
    void checkedLongRejectsOverflow() {
        var top = FakePattern.of(TOP, 1L, new FakeInput(A, Long.MAX_VALUE));
        var grid = FakeGrid.builder().pattern(TOP, top).build();
        var planner = new FrozenRoutePlanner();
        var program = planner.compile(grid.crafting, TOP, null);

        assertThrows(ArithmeticException.class,
                () -> planner.solve(program, grid.storage.getCachedInventory(), 2L));
    }

    @Test
    void exactOnlyPlanDoesNotCarryWitness() {
        var top = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
        var grid = FakeGrid.builder().storage(Map.of(A, 1L)).pattern(TOP, top).build();
        var planner = new FrozenRoutePlanner();

        var solved = planner.solve(planner.compile(grid.crafting, TOP, null),
                grid.storage.getCachedInventory(), 1L);

        assertNull(PlanningInputWitnessPlans.find(solved.plan()));
    }

    @Test
    void fuzzyPlanCarriesInputWitness() {
        var top = FakePattern.of(TOP, 1L, FakeInput.multi(3L, A, B));
        var grid = FakeGrid.builder().storage(Map.of(A, 1L, B, 2L)).pattern(TOP, top).build();
        var planner = new FrozenRoutePlanner();

        var solved = planner.solve(planner.compile(grid.crafting, TOP, null),
                grid.storage.getCachedInventory(), 1L);
        var witness = PlanningInputWitnessPlans.find(solved.plan());

        assertNotNull(witness);
        assertTrue(witness.complete());
        assertEquals(1L, witness.expectedCrafts().get(top.getDefinition()));
        assertEquals(2, witness.patternInputs().getFirst().runs().size());
        assertEquals(2, witness.ownership().size());
    }

    @Test
    void compileAndSolveExposeAllStatistics() {
        var top = FakePattern.of(TOP, 1L, FakeInput.multi(1L, A, B));
        var grid = FakeGrid.builder().pattern(TOP, top).build();
        var planner = new FrozenRoutePlanner();
        var compiled = planner.compile(grid.crafting, TOP, null);
        var solved = planner.solve(compiled, grid.storage.getCachedInventory(), 1L);

        assertEquals(3, compiled.stats().keys());
        assertEquals(1, compiled.stats().edges());
        assertEquals(2, compiled.stats().candidates());
        assertEquals(1, compiled.stats().routes());
        assertEquals(3, compiled.stats().sccs());
        assertEquals(1, solved.stats().missingKinds());
        assertEquals(1L, solved.stats().tasks());
    }
}
