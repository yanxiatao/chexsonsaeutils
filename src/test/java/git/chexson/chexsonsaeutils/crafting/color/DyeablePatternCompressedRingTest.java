package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DyeablePatternCompressedRingTest {

    @Test
    void processingPatternRecursiveInputCanBeFluidKey() {
        AEKey water = AEFluidKey.of(Fluids.WATER);
        AEKey lava = AEFluidKey.of(Fluids.LAVA);
        AEKey dust = AEItemKey.of(Items.REDSTONE);

        IPatternDetails waterAmplifier = pattern(
                input(stack(lava, 1L), stack(water, 1L)),
                input(stack(dust, 1L)),
                List.of(stack(water, 4L))
        );

        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(
                List.of(waterAmplifier)
        );

        assertNotNull(ring);
        assertEquals(3L, ring.netOutputs().get(water));
        assertEquals(1L, ring.catalysts().get(water));
        assertTrue(ring.entryPoints().contains(water));
        assertEquals(1L, ring.netInputs().get(dust));
    }

    @Test
    void recursivePlanKeepsRequestedFinalOutputVisible() {
        AEKey output = AEItemKey.of(Items.DIAMOND);
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        IPatternDetails producer = pattern(
                input(stack(seed, 1L)),
                input(stack(AEItemKey.of(Items.GLOWSTONE_DUST), 1L)),
                List.of(stack(output, 2L))
        );
        KeyCounter usedItems = new KeyCounter();
        usedItems.add(output, 3L);
        usedItems.add(seed, 1L);
        KeyCounter ringExtractions = new KeyCounter();
        ringExtractions.add(output, 3L);
        CraftingPlan basePlan = new CraftingPlan(
                stack(output, 4L),
                8L,
                false,
                false,
                usedItems,
                new KeyCounter(),
                new KeyCounter(),
                Map.of(producer, 2L)
        );

        ICraftingPlan recursivePlan = DyeablePatternCraftingCalculation.createRecursivePlanForRingExtractions(
                basePlan,
                ringExtractions
        );

        assertEquals(4L, recursivePlan.finalOutput().amount());
        assertTrue(recursivePlan instanceof DyeablePatternRecursivePlan);
        assertEquals(
                4L,
                ((DyeablePatternRecursivePlan) recursivePlan).chexsonsaeutils$dyeableRecursiveFinalOutputAmount()
        );
        assertEquals(3L, recursivePlan.usedItems().get(output));
        assertEquals(1L, recursivePlan.usedItems().get(seed));
    }

    @Test
    void processRingReplacementOnlyStartsForDyedFallbackEntryPoint() {
        int parentColor = 0xFF00AA00;
        int processColor = 0xFFAA0000;
        AEKey output = AEItemKey.of(Items.DIAMOND);
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        IPatternDetails recursiveProducer = pattern(
                input(stack(seed, 1L)),
                input(stack(AEItemKey.of(Items.GLOWSTONE_DUST), 1L)),
                List.of(stack(output, 2L))
        );
        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(
                List.of(recursiveProducer)
        );

        assertTrue(DyeablePatternCraftingPlanner.shouldTryProcessRingReplacement(
                parentColor,
                processColor,
                ring,
                output,
                false
        ));
        assertFalse(DyeablePatternCraftingPlanner.shouldTryProcessRingReplacement(
                parentColor,
                -1,
                ring,
                output,
                false
        ));
        assertFalse(DyeablePatternCraftingPlanner.shouldTryProcessRingReplacement(
                parentColor,
                processColor,
                ring,
                seed,
                false
        ));
        assertFalse(DyeablePatternCraftingPlanner.shouldTryProcessRingReplacement(
                parentColor,
                processColor,
                ring,
                output,
                true
        ));
    }

    @Test
    void recursiveCompletionRequiresPositivePendingTaskProgress() {
        AEKey output = AEItemKey.of(Items.DIAMOND);
        IPatternDetails producer = pattern(
                input(stack(AEItemKey.of(Items.REDSTONE), 1L)),
                input(stack(AEItemKey.of(Items.GLOWSTONE_DUST), 1L)),
                List.of(stack(output, 1L))
        );

        assertTrue(DyeablePatternRecursiveTaskOrdering.hasPendingTasks(Map.of(
                producer,
                new TestTaskProgress(1L)
        )));
        assertFalse(DyeablePatternRecursiveTaskOrdering.hasPendingTasks(Map.of(
                producer,
                new TestTaskProgress(0L)
        )));
    }

    @Test
    void sameColorUnrelatedRecursivePatternsStayInDifferentRings() {
        AEKey seedA = AEItemKey.of(Items.REDSTONE);
        AEKey seedB = AEItemKey.of(Items.GLOWSTONE_DUST);
        AEKey sharedInput = AEItemKey.of(Items.COPPER_INGOT);
        IPatternDetails ringA = pattern(
                input(stack(sharedInput, 1L)),
                input(stack(seedA, 1L)),
                List.of(stack(seedA, 2L))
        );
        IPatternDetails ringB = pattern(
                input(stack(sharedInput, 1L)),
                input(stack(seedB, 1L)),
                List.of(stack(seedB, 2L))
        );

        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(ringA, ringB));

        DyeablePatternCompressedRing ringForA = providers.getOrCalculateCompressedRing(-1, seedA);
        DyeablePatternCompressedRing ringForB = providers.getOrCalculateCompressedRing(-1, seedB);

        assertNotNull(ringForA);
        assertNotNull(ringForB);
        assertTrue(ringForA.entryPoints().contains(seedA));
        assertFalse(ringForA.entryPoints().contains(seedB));
        assertTrue(ringForB.entryPoints().contains(seedB));
        assertFalse(ringForB.entryPoints().contains(seedA));
    }

    private static IPatternDetails pattern(
            IPatternDetails.IInput firstInput,
            IPatternDetails.IInput secondInput,
            List<GenericStack> outputs
    ) {
        return new TestPattern(new IPatternDetails.IInput[] { firstInput, secondInput }, outputs);
    }

    private static IPatternDetails.IInput input(GenericStack... possibleInputs) {
        return new TestInput(possibleInputs);
    }

    private static GenericStack stack(AEKey key, long amount) {
        return new GenericStack(key, amount);
    }

    private record TestPattern(
            IPatternDetails.IInput[] inputs,
            List<GenericStack> outputs
    ) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return AEItemKey.of(Items.PAPER);
        }

        @Override
        public IInput[] getInputs() {
            return inputs.clone();
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }
    }

    private record TestInput(GenericStack[] possibleInputs) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs.clone();
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            for (GenericStack possibleInput : possibleInputs) {
                if (input.matches(possibleInput)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private record TestProvider(IPatternDetails... patterns) implements ICraftingProvider {

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of(patterns);
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, appeng.api.stacks.KeyCounter[] inputHolder) {
            return false;
        }

        @Override
        public boolean isBusy() {
            return false;
        }

        @Override
        public Set<AEKey> getEmitableItems() {
            return Set.of();
        }

        @Override
        public int getPatternPriority() {
            return 0;
        }
    }

    private record TestTaskProgress(long value)
            implements DyeablePatternRecursiveTaskOrdering.ParallelTaskProgressView {

        @Override
        public long chexsonsaeutils$dyeableRecursiveTaskProgressValue() {
            return value;
        }
    }
}
