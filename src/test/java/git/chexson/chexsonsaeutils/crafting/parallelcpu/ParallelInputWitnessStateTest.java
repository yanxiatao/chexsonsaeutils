package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitness;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ParallelInputWitnessStateTest {

    private static final AEItemKey FUZZY_DEFINITION = AEItemKey.of(Items.PAPER);
    private static final AEItemKey EXACT_DEFINITION = AEItemKey.of(Items.MAP);
    private static final AEItemKey SEED = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey BULK = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey OUTPUT = AEItemKey.of(Items.CLOCK);

    @Test
    void exactWitnessPreparesCompleteLargeBatchAndTracksOutputCount() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 1024L),
                List.of(pattern(EXACT_DEFINITION, SEED, 1024L)),
                List.of(ownership(SEED, EXACT_DEFINITION, 1024L))
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 1024L, BULK, 0L);
        var details = FakePattern.colored(-1, EXACT_DEFINITION,
                List.of(new appeng.api.stacks.GenericStack(OUTPUT, 2L)),
                new FakeInput(SEED, 1L));
        var expectedOutputs = new appeng.api.stacks.KeyCounter();
        var prepared = state.prepare(
                details,
                inventory,
                1024L,
                expectedOutputs,
                new appeng.api.stacks.KeyCounter()
        );

        assertNotNull(prepared);
        assertEquals(1024L, prepared.holder()[0].get(SEED));
        assertEquals(2048L, expectedOutputs.get(OUTPUT));
        state.commit(prepared);
        assertEquals(0L, state.maxExecutions(details, inventory, 1024L));
    }

    @Test
    void ownershipPreventsFuzzyTaskFromTakingExactSeed() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(FUZZY_DEFINITION, 3L, EXACT_DEFINITION, 1L),
                List.of(
                        pattern(EXACT_DEFINITION, SEED, 1L),
                        pattern(FUZZY_DEFINITION, BULK, 3L)
                ),
                List.of(
                        ownership(SEED, EXACT_DEFINITION, 1L),
                        ownership(BULK, FUZZY_DEFINITION, 3L)
                )
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 1L, BULK, 3L);
        var fuzzy = FakePattern.colored(-1, FUZZY_DEFINITION,
                List.of(new appeng.api.stacks.GenericStack(OUTPUT, 1L)),
                FakeInput.multi(1L, SEED, BULK));
        var exact = FakePattern.colored(-1, EXACT_DEFINITION,
                List.of(new appeng.api.stacks.GenericStack(OUTPUT, 1L)),
                new FakeInput(SEED, 1L));

        assertEquals(3L, state.maxExecutions(fuzzy, inventory, 3L));
        var fuzzyPrepared = state.prepare(fuzzy, inventory, 3L,
                new appeng.api.stacks.KeyCounter(), new appeng.api.stacks.KeyCounter());
        assertNotNull(fuzzyPrepared);
        assertEquals(1L, inventory.extract(SEED, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(3L, fuzzyPrepared.holder()[0].get(BULK));
        CraftingCpuHelperAccess.reinject(inventory, fuzzyPrepared.holder());

        assertEquals(1L, state.maxExecutions(exact, inventory, 1L));
    }

    @Test
    void nbtRoundTripPreservesOwnershipCursor() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 1L, FUZZY_DEFINITION, 1L),
                List.of(
                        pattern(EXACT_DEFINITION, SEED, 1L),
                        pattern(FUZZY_DEFINITION, SEED, 1L)
                ),
                List.of(new PlanningInputWitness.ExactKeyOwnership(SEED, List.of(
                        owner(EXACT_DEFINITION, 1L),
                        owner(FUZZY_DEFINITION, 1L)
                )))
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 2L, BULK, 0L);
        var first = patternDetails(EXACT_DEFINITION, SEED);
        var later = patternDetails(FUZZY_DEFINITION, SEED);
        var prepared = state.prepare(first, inventory, 1L,
                new appeng.api.stacks.KeyCounter(), new appeng.api.stacks.KeyCounter());
        assertNotNull(prepared);
        state.commit(prepared);

        var restored = ParallelInputWitnessState.readFromNBT(
                state.writeToNBT(net.minecraft.core.RegistryAccess.EMPTY),
                net.minecraft.core.RegistryAccess.EMPTY
        );
        assertEquals(1L, restored.maxExecutions(later, inventory, 1L));
        assertEquals(0L, restored.maxExecutions(first, inventory, 1L));
    }

    @Test
    void rejectedPreparationDoesNotAdvanceOwnership() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 1L, FUZZY_DEFINITION, 1L),
                List.of(
                        pattern(EXACT_DEFINITION, SEED, 1L),
                        pattern(FUZZY_DEFINITION, SEED, 1L)
                ),
                List.of(new PlanningInputWitness.ExactKeyOwnership(SEED, List.of(
                        owner(EXACT_DEFINITION, 1L),
                        owner(FUZZY_DEFINITION, 1L)
                )))
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 2L, BULK, 0L);
        var first = patternDetails(EXACT_DEFINITION, SEED);
        var later = patternDetails(FUZZY_DEFINITION, SEED);

        var prepared = state.prepare(first, inventory, 1L,
                new appeng.api.stacks.KeyCounter(), new appeng.api.stacks.KeyCounter());
        assertNotNull(prepared);
        CraftingCpuHelperAccess.reinject(inventory, prepared.holder());
        assertEquals(1L, state.maxExecutions(later, inventory, 1L));
        assertEquals(1L, state.maxExecutions(first, inventory, 1L));
    }

    @Test
    void laterOwnerWaitsWhenOnlyEarlierSeedExists() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 1L, FUZZY_DEFINITION, 1L),
                List.of(
                        pattern(EXACT_DEFINITION, SEED, 1L),
                        pattern(FUZZY_DEFINITION, SEED, 1L)
                ),
                List.of(new PlanningInputWitness.ExactKeyOwnership(SEED, List.of(
                        owner(EXACT_DEFINITION, 1L),
                        owner(FUZZY_DEFINITION, 1L)
                )))
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 1L, BULK, 0L);

        assertEquals(0L, state.maxExecutions(
                patternDetails(FUZZY_DEFINITION, SEED), inventory, 1L));
        assertEquals(1L, state.maxExecutions(
                patternDetails(EXACT_DEFINITION, SEED), inventory, 1L));
    }

    @Test
    void witnessedExactTaskKeepsLargeBatch() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 4096L),
                List.of(pattern(EXACT_DEFINITION, SEED, 4096L)),
                List.of(ownership(SEED, EXACT_DEFINITION, 4096L))
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 4096L, BULK, 0L);

        assertEquals(1024L, state.maxExecutions(
                patternDetails(EXACT_DEFINITION, SEED), inventory, 1024L));
        assertEquals(new ParallelInputWitnessState.BatchLimit(1024L,
                        "material+ownership(slot=0, k=1024)"),
                state.explainBatchLimit(patternDetails(EXACT_DEFINITION, SEED), inventory, 1024L));
    }

    @Test
    void batchLimitExplainsOwnershipInsteadOfMisreportingMaterial() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 1L, FUZZY_DEFINITION, 8L),
                List.of(
                        pattern(EXACT_DEFINITION, SEED, 1L),
                        pattern(FUZZY_DEFINITION, SEED, 8L)
                ),
                List.of(new PlanningInputWitness.ExactKeyOwnership(SEED, List.of(
                        owner(EXACT_DEFINITION, 1L),
                        owner(FUZZY_DEFINITION, 8L)
                )))
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 1L, BULK, 0L);

        assertEquals(new ParallelInputWitnessState.BatchLimit(0L, "ownership(k=0)"),
                state.explainBatchLimit(patternDetails(FUZZY_DEFINITION, SEED), inventory, 8L));
    }

    @Test
    void interleavedOwnershipDoesNotFragmentReadyTaskBatch() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 4L, FUZZY_DEFINITION, 1L),
                List.of(
                        pattern(EXACT_DEFINITION, SEED, 4L),
                        pattern(FUZZY_DEFINITION, SEED, 1L)
                ),
                List.of(new PlanningInputWitness.ExactKeyOwnership(SEED, List.of(
                        owner(EXACT_DEFINITION, 1L),
                        owner(FUZZY_DEFINITION, 1L),
                        owner(EXACT_DEFINITION, 3L)
                )))
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 5L, BULK, 0L);
        var exact = patternDetails(EXACT_DEFINITION, SEED);

        assertEquals(4L, state.maxExecutions(exact, inventory, 4L));
        var prepared = state.prepare(exact, inventory, 4L,
                new appeng.api.stacks.KeyCounter(), new appeng.api.stacks.KeyCounter());
        assertNotNull(prepared);
        state.commit(prepared);
        assertEquals(1L, state.maxExecutions(
                patternDetails(FUZZY_DEFINITION, SEED), inventory, 1L));
    }

    @Test
    void largeEarlierOwnerReservesOneRunnableTemplateInsteadOfWholePlan() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 1_000_000L, FUZZY_DEFINITION, 256L),
                List.of(
                        pattern(EXACT_DEFINITION, SEED, 1_000_000L),
                        pattern(FUZZY_DEFINITION, SEED, 256L)
                ),
                List.of(new PlanningInputWitness.ExactKeyOwnership(SEED, List.of(
                        owner(EXACT_DEFINITION, 1_000_000L),
                        owner(FUZZY_DEFINITION, 256L)
                )))
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 257L, BULK, 0L);

        assertEquals(256L, state.maxExecutions(
                patternDetails(FUZZY_DEFINITION, SEED), inventory, 256L));
    }

    @Test
    void ownershipReservesOnlyWhenStockCannotCoverEarlierOwner() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 1L, FUZZY_DEFINITION, 1L),
                List.of(
                        pattern(EXACT_DEFINITION, SEED, 1L),
                        pattern(FUZZY_DEFINITION, SEED, 1L)
                ),
                List.of(new PlanningInputWitness.ExactKeyOwnership(SEED, List.of(
                        owner(EXACT_DEFINITION, 1L),
                        owner(FUZZY_DEFINITION, 1L)
                )))
        );
        var state = new ParallelInputWitnessState(witness);
        var inventory = inventory(SEED, 2L, BULK, 0L);
        var exact = patternDetails(EXACT_DEFINITION, SEED);
        var later = patternDetails(FUZZY_DEFINITION, SEED);

        assertEquals(1L, state.maxExecutions(later, inventory, 1L));
        var prepared = state.prepare(exact, inventory, 1L,
                new appeng.api.stacks.KeyCounter(), new appeng.api.stacks.KeyCounter());
        assertNotNull(prepared);
        state.commit(prepared);
        assertEquals(1L, state.maxExecutions(later, inventory, 1L));
    }

    @Test
    void dispatchLimitKeepsHomogeneousRunBatchSize() {
        var witness = new PlanningInputWitness(
                true,
                Map.of(EXACT_DEFINITION, 1024L),
                List.of(pattern(EXACT_DEFINITION, SEED, 1024L)),
                List.of(ownership(SEED, EXACT_DEFINITION, 1024L))
        );
        var state = new ParallelInputWitnessState(witness);
        var full = inventory(SEED, 1024L, BULK, 0L);
        var partial = inventory(SEED, 100L, BULK, 0L);
        var details = patternDetails(EXACT_DEFINITION, SEED);

        assertEquals(1024L, state.dispatchLimit(details, full, 1024L));
        assertEquals(100L, state.dispatchLimit(details, partial, 1024L));
    }

    private static PlanningInputWitness.PatternInputRuns pattern(
            AEItemKey definition, AEItemKey key, long units) {
        return new PlanningInputWitness.PatternInputRuns(definition, 1,
                List.of(new PlanningInputWitness.InputRun(0, key, 1L, units)));
    }

    private static PlanningInputWitness.ExactKeyOwnership ownership(
            AEItemKey key, AEItemKey definition, long units) {
        return new PlanningInputWitness.ExactKeyOwnership(key, List.of(owner(definition, units)));
    }

    private static PlanningInputWitness.OwnershipRun owner(AEItemKey definition, long units) {
        return new PlanningInputWitness.OwnershipRun(definition, 0, 1L, units);
    }

    private static FakePattern patternDetails(AEItemKey definition, AEItemKey key) {
        return FakePattern.colored(-1, definition,
                List.of(new appeng.api.stacks.GenericStack(OUTPUT, 1L)),
                new FakeInput(key, 1L));
    }

    private static ListCraftingInventory inventory(AEItemKey first, long firstAmount,
            AEItemKey second, long secondAmount) {
        var inventory = new ListCraftingInventory(ignored -> {
        });
        if (firstAmount > 0L) {
            inventory.insert(first, firstAmount, Actionable.MODULATE);
        }
        if (secondAmount > 0L) {
            inventory.insert(second, secondAmount, Actionable.MODULATE);
        }
        return inventory;
    }

    private static final class CraftingCpuHelperAccess {
        private CraftingCpuHelperAccess() {
        }

        static void reinject(ListCraftingInventory inventory,
                appeng.api.stacks.KeyCounter[] holder) {
            appeng.crafting.execution.CraftingCpuHelper.reinjectPatternInputs(inventory, holder);
        }
    }
}
