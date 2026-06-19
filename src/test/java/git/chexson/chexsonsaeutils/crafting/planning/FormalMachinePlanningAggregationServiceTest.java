package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.blockentity.crafting.TaskCompletionRoute;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursivePlan;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternSelectedInputsPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FormalMachinePlanningAggregationServiceTest {

    @Test
    void aggregationFutureDoesNotCacheDelegatePlan() throws Exception {
        MutablePlanFuture delegate = new MutablePlanFuture();
        ICraftingPlan firstPlan = plan(Items.DIAMOND);
        ICraftingPlan secondPlan = plan(Items.EMERALD);
        Future<ICraftingPlan> wrapped = FormalMachinePlanningAggregationService.wrapNativeFuture(
                null,
                null,
                AEItemKey.of(Items.DIAMOND),
                1L,
                delegate
        );

        delegate.setPlan(firstPlan);
        assertTrue(wrapped.isDone());
        assertSame(firstPlan, wrapped.get());

        delegate.setPlan(secondPlan);
        assertSame(secondPlan, wrapped.get());
    }

    @Test
    void aggregationInputsPreferDyeableSelectedInputs() {
        TestPatternDetails pattern = new TestPatternDetails(
                AEItemKey.of(Items.COMPARATOR),
                AEItemKey.of(Items.DIAMOND),
                Map.of(AEItemKey.of(Items.GOLD_INGOT), 1L)
        );
        ICraftingPlan nativePlan = new TestSelectedInputsPlan(
                new CraftingPlan(
                        new GenericStack(AEItemKey.of(Items.DIAMOND), 1L),
                        1L,
                        false,
                        false,
                        new KeyCounter(),
                        new KeyCounter(),
                        new KeyCounter(),
                        Map.of(pattern, 1L)
                ),
                Map.of(
                        pattern.getDefinition(),
                        Map.of((AEKey) AEItemKey.of(Items.IRON_INGOT), 1L)
                )
        );
        Map<AEKey, Long> inputs = FormalMachinePlanningAggregationService.describeAggregationInputs(
                nativePlan,
                pattern
        );

        assertEquals(1L, inputs.get(AEItemKey.of(Items.IRON_INGOT)));
    }

    @Test
    void aggregatedBoundaryInputsKeepSingleStartupSeedForSelfAmplifyingPattern() {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        AEItemKey dust = AEItemKey.of(Items.REDSTONE);
        List<FormalMachineAggregationStep> steps = List.of(
                new FormalMachineAggregationStep(
                        ItemStack.EMPTY,
                        3L,
                        List.of(
                                new GenericStack(seed, 3L),
                                new GenericStack(dust, 3L)
                        ),
                        new GenericStack(seed, 6L),
                        List.of(),
                        TaskCompletionRoute.AE_STORAGE
                )
        );

        Map<AEKey, Long> boundaryInputs = FormalMachinePlanningAggregationService.describeAggregatedBoundaryInputs(
                steps
        );

        assertNotNull(boundaryInputs);
        assertEquals(1L, boundaryInputs.get(seed));
        assertEquals(3L, boundaryInputs.get(dust));
    }

    @Test
    void aggregatedBoundaryInputsDoNotRetainConfiguredSeedMultiplierForSelfAmplifyingPattern() {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        AEItemKey dust = AEItemKey.of(Items.REDSTONE);
        List<FormalMachineAggregationStep> steps = List.of(
                new FormalMachineAggregationStep(
                        ItemStack.EMPTY,
                        3L,
                        List.of(
                                new GenericStack(seed, 3L),
                                new GenericStack(dust, 3L)
                        ),
                        new GenericStack(seed, 6L),
                        List.of(),
                        TaskCompletionRoute.AE_STORAGE
                )
        );

        Map<AEKey, Long> boundaryInputs = FormalMachinePlanningAggregationService.describeAggregatedBoundaryInputs(
                steps
        );

        assertNotNull(boundaryInputs);
        assertEquals(1L, boundaryInputs.get(seed));
        assertEquals(3L, boundaryInputs.get(dust));
    }

    @Test
    void recursiveStartupSeedRestoredIntoBoundaryOutputs() throws Exception {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        AEItemKey dust = AEItemKey.of(Items.REDSTONE);
        Map<AEKey, Long> boundaryOutputs = new LinkedHashMap<>(Map.of(
                seed, 3L,
                dust, 3L
        ));
        KeyCounter recursiveInitialItems = new KeyCounter();
        recursiveInitialItems.add(seed, 1L);

        FormalMachinePlanningAggregationService.restoreRecursiveInitialBoundaryOutputs(
                boundaryOutputs,
                recursiveInitialItems
        );

        assertEquals(4L, boundaryOutputs.get(seed));
        assertEquals(3L, boundaryOutputs.get(dust));
    }

    @Test
    void bootstrapSeedMissingIsNotRemovedFromExternalMissingInputs() {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        AEItemKey dust = AEItemKey.of(Items.REDSTONE);
        KeyCounter missingItems = new KeyCounter();
        missingItems.add(seed, 1L);
        missingItems.add(dust, 3L);
        KeyCounter recursiveInitialItems = new KeyCounter();
        recursiveInitialItems.add(seed, 1L);

        Map<AEKey, Long> missing = FormalMachinePlanningAggregationService.extractExternalMissingInputs(
                missingItems,
                Map.of(seed, 6L, dust, 3L),
                recursiveInitialItems
        );

        assertEquals(1L, missing.get(seed));
        assertNull(missing.get(dust));
    }

    @Test
    void dependencySegmentsMergeSelfAmplifyingChainIntoSingleSegment() {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        AEItemKey finalProduct = AEItemKey.of(Items.DIAMOND);

        List<Set<AEKey>> segments = FormalMachinePlanningAggregationService.splitPerPatternFormalAggregationSegments(
                Map.of(
                        seed, List.of(seed, AEItemKey.of(Items.REDSTONE)),
                        finalProduct, List.of(seed)
                )
        );

        assertEquals(1, segments.size());
        assertEquals(Set.of(seed, finalProduct), segments.getFirst());
    }

    @Test
    void topoSortIgnoresSelfLoopInputOnSameOutput() {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        AEItemKey finalProduct = AEItemKey.of(Items.DIAMOND);

        List<AEKey> ordered = FormalMachinePlanningAggregationService.topoSortDependencyOutputs(
                Map.of(
                        seed, List.of(seed, AEItemKey.of(Items.REDSTONE)),
                        finalProduct, List.of(seed)
                )
        );

        assertNotNull(ordered);
        assertIterableEquals(List.of(seed, finalProduct), ordered);
    }

    @Test
    void rewrittenUsedItemsDoNotDoubleCountRecursiveStartupSeed() {
        AEItemKey seed = AEItemKey.of(Items.PAPER);
        AEItemKey dust = AEItemKey.of(Items.REDSTONE);
        TestPatternDetails aggregatedPattern = new TestPatternDetails(
                AEItemKey.of(Items.COMPARATOR),
                seed,
                Map.of(
                        seed, 1L,
                        dust, 3L
                )
        );
        KeyCounter recursiveInitialItems = new KeyCounter();
        recursiveInitialItems.add(seed, 1L);
        KeyCounter empty = new KeyCounter();

        KeyCounter usedItems = FormalMachinePlanningAggregationService.computeRewrittenUsedItems(
                Map.of(aggregatedPattern, 1L),
                empty,
                new TestSelectedInputsPlan(
                        new CraftingPlan(
                                new GenericStack(seed, 6L),
                                1L,
                                false,
                                false,
                                empty,
                                empty,
                                empty,
                                Map.of()
                        ),
                        Map.of(),
                        recursiveInitialItems
                )
        );

        assertEquals(1L, usedItems.get(seed));
        assertEquals(3L, usedItems.get(dust));
    }

    private static ICraftingPlan plan(Item item) {
        return new CraftingPlan(
                new GenericStack(AEItemKey.of(item), 1L),
                1L,
                false,
                false,
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter(),
                Map.of()
        );
    }

    private static final class MutablePlanFuture implements Future<ICraftingPlan> {
        private ICraftingPlan plan;
        private boolean cancelled;

        private void setPlan(ICraftingPlan plan) {
            this.plan = plan;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public ICraftingPlan get() {
            return plan;
        }

        @Override
        public ICraftingPlan get(long timeout, TimeUnit unit) throws TimeoutException {
            if (plan == null) {
                throw new TimeoutException("No plan prepared");
            }
            return plan;
        }
    }

    private record TestPatternDetails(
            AEItemKey definition,
            AEItemKey output,
            Map<AEKey, Long> deterministicFallbackInputs
    ) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return deterministicFallbackInputs.entrySet().stream()
                    .map(entry -> new TestInput(new GenericStack(entry.getKey(), entry.getValue())))
                    .toArray(IInput[]::new);
        }

        @Override
        public java.util.List<GenericStack> getOutputs() {
            return java.util.List.of(new GenericStack(output, 1L));
        }
    }

    private record TestInput(GenericStack input) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] { input };
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return this.input.what().equals(input);
        }
    }

    private record TestSelectedInputsPlan(
            CraftingPlan delegate,
            Map<AEItemKey, Map<AEKey, Long>> selectedInputs,
            KeyCounter recursiveInitialItems
    ) implements ICraftingPlan, DyeablePatternRecursivePlan, DyeablePatternSelectedInputsPlan {
        private TestSelectedInputsPlan(
                CraftingPlan delegate,
                Map<AEItemKey, Map<AEKey, Long>> selectedInputs
        ) {
            this(delegate, selectedInputs, new KeyCounter());
        }

        @Override
        public GenericStack finalOutput() {
            return delegate.finalOutput();
        }

        @Override
        public long bytes() {
            return delegate.bytes();
        }

        @Override
        public boolean simulation() {
            return delegate.simulation();
        }

        @Override
        public boolean multiplePaths() {
            return delegate.multiplePaths();
        }

        @Override
        public KeyCounter usedItems() {
            return delegate.usedItems();
        }

        @Override
        public KeyCounter emittedItems() {
            return delegate.emittedItems();
        }

        @Override
        public KeyCounter missingItems() {
            return delegate.missingItems();
        }

        @Override
        public Map<IPatternDetails, Long> patternTimes() {
            return delegate.patternTimes();
        }

        @Override
        public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
            return true;
        }

        @Override
        public KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
            KeyCounter copy = new KeyCounter();
            copy.addAll(recursiveInitialItems);
            return copy;
        }

        @Override
        public Map<AEItemKey, Map<AEKey, Long>> chexsonsaeutils$dyeableSelectedPatternInputs() {
            return selectedInputs;
        }
    }
}
