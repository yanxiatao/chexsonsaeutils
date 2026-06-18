package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternSlotReplacementRule;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleValidation;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotTagService;
import git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwareProcessingPattern;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DyeablePatternCompressedRingTest {

    @Test
    void dyeableCalculationHelpersResolveRequestedColorAndPreparedRing() {
        GenericStack redOutput = new GenericStack(
                AEItemKey.of(dyedItem(Items.PAPER, 0xFFFF0000)),
                1L
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(
                new TestPatternDetails(
                        AEItemKey.of(Items.PAPER),
                        0xFFFF0000,
                        List.of(new GenericStack(AEItemKey.of(Items.REDSTONE), 1L)),
                        List.of(new GenericStack(AEItemKey.of(Items.COMPARATOR), 1L))
                )
        ));

        assertEquals(0xFFFF0000, DyeablePatternCraftingCalculation.resolveRequestedColor(redOutput));
        assertNotNull(DyeablePatternCraftingCalculation.resolvePreparedCompressedRing(redOutput, providers));
    }

    @Test
    void rootPlanningUsesRequestedOutputColor() {
        GenericStack redOutput = new GenericStack(
                AEItemKey.of(dyedItem(Items.PAPER, 0xFFFF0000)),
                1L
        );

        int preferredColor = DyeablePatternCraftingPlanner.resolvePreferredColor(null, true, redOutput);

        assertEquals(0xFFFF0000, preferredColor);
    }

    @Test
    void childPlanningKeepsParentPatternColor() {
        TestPatternDetails blueParent = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFF0000FF,
                List.of(new GenericStack(AEItemKey.of(Items.REDSTONE), 1L)),
                List.of(new GenericStack(AEItemKey.of(Items.COMPARATOR), 1L))
        );
        GenericStack redOutput = new GenericStack(
                AEItemKey.of(dyedItem(Items.MAP, 0xFFFF0000)),
                1L
        );

        int preferredColor = DyeablePatternCraftingPlanner.resolvePreferredColor(blueParent, false, redOutput);

        assertEquals(0xFF0000FF, preferredColor);
    }

    @Test
    void calculatesNetInputsOutputsAndEntryPointsForSameColorRing() {
        AEKey quartz = AEItemKey.of(Items.QUARTZ);
        AEKey redstone = AEItemKey.of(Items.REDSTONE);
        AEKey comparator = AEItemKey.of(Items.COMPARATOR);
        TestPatternDetails first = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFFFF0000,
                List.of(new GenericStack(redstone, 1L), new GenericStack(quartz, 1L)),
                List.of(new GenericStack(comparator, 1L))
        );
        TestPatternDetails second = new TestPatternDetails(
                AEItemKey.of(Items.MAP),
                0xFFFF0000,
                List.of(new GenericStack(comparator, 1L)),
                List.of(new GenericStack(redstone, 1L))
        );

        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(first, second));

        assertNotNull(ring);
        assertEquals(1L, ring.netInputs().get(quartz));
        assertEquals(0L, ring.netInputs().get(redstone));
        assertEquals(0L, ring.netOutputs().get(redstone));
        assertEquals(0L, ring.netOutputs().get(comparator));
        assertTrue(ring.entryPoints().isEmpty());
        assertEquals(1L, ring.catalysts().get(redstone));
        assertEquals(1L, ring.catalysts().get(comparator));
        assertTrue(ring.calculable());
    }

    @Test
    void exposesNetOutputsAsEntryPointsWhenRingProducesExternalOutput() {
        AEKey iron = AEItemKey.of(Items.IRON_INGOT);
        AEKey piston = AEItemKey.of(Items.PISTON);
        TestPatternDetails pattern = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFF336699,
                List.of(new GenericStack(iron, 1L)),
                List.of(new GenericStack(piston, 1L), new GenericStack(iron, 1L))
        );

        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(pattern));

        assertNotNull(ring);
        assertEquals(1L, ring.netOutputs().get(piston));
        assertTrue(ring.entryPoints().contains(piston));
        assertTrue(ring.calculable());
    }

    @Test
    void marksBalancedClosedLoopAsNotCalculableYet() {
        AEKey redstone = AEItemKey.of(Items.REDSTONE);
        AEKey comparator = AEItemKey.of(Items.COMPARATOR);
        TestPatternDetails first = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFF336699,
                List.of(new GenericStack(redstone, 1L)),
                List.of(new GenericStack(comparator, 1L))
        );
        TestPatternDetails second = new TestPatternDetails(
                AEItemKey.of(Items.MAP),
                0xFF336699,
                List.of(new GenericStack(comparator, 1L)),
                List.of(new GenericStack(redstone, 1L))
        );

        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(first, second));

        assertNotNull(ring);
        assertFalse(ring.calculable());
        assertFalse(DyeablePatternCraftingPlanner.isCompressedRingCalculable(ring));
    }

    @Test
    void providerCacheReturnsSameRingForSameColorGroup() {
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        TestPatternDetails pattern = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFFFF0000,
                List.of(new GenericStack(AEItemKey.of(Items.REDSTONE), 1L)),
                List.of(new GenericStack(AEItemKey.of(Items.COMPARATOR), 1L))
        );
        providers.addProvider(new TestProvider(pattern));

        DyeablePatternCompressedRing first = providers.getOrCalculateCompressedRing(0xFFFF0000);
        DyeablePatternCompressedRing second = providers.getOrCalculateCompressedRing(0xFFFF0000);

        assertNotNull(first);
        assertEquals(first, second);
    }

    @Test
    void plannerUsesCachedRingCalculableFlagInsteadOfRingPresence() {
        AEKey redstone = AEItemKey.of(Items.REDSTONE);
        AEKey comparator = AEItemKey.of(Items.COMPARATOR);
        TestPatternDetails first = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFF336699,
                List.of(new GenericStack(redstone, 1L)),
                List.of(new GenericStack(comparator, 1L))
        );
        TestPatternDetails second = new TestPatternDetails(
                AEItemKey.of(Items.MAP),
                0xFF336699,
                List.of(new GenericStack(comparator, 1L)),
                List.of(new GenericStack(redstone, 1L))
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(first, second));

        DyeablePatternCompressedRing cachedRing = providers.getOrCalculateCompressedRing(0xFF336699);

        assertNotNull(cachedRing);
        assertFalse(DyeablePatternCraftingPlanner.isCompressedRingCalculable(cachedRing));
    }

    @Test
    void plannerFallbackStillOrdersSameColorFirstWhenRingIsNotCalculable() {
        AEKey redstone = AEItemKey.of(Items.REDSTONE);
        AEKey comparator = AEItemKey.of(Items.COMPARATOR);
        TestPatternDetails redFirst = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFFFF0000,
                List.of(new GenericStack(redstone, 1L)),
                List.of(new GenericStack(comparator, 1L))
        );
        TestPatternDetails redSecond = new TestPatternDetails(
                AEItemKey.of(Items.MAP),
                0xFFFF0000,
                List.of(new GenericStack(comparator, 1L)),
                List.of(new GenericStack(redstone, 1L))
        );
        TestPatternDetails blue = new TestPatternDetails(
                AEItemKey.of(Items.COMPASS),
                0xFF0000FF,
                List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)),
                List.of(new GenericStack(AEItemKey.of(Items.CLOCK), 1L))
        );

        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(redFirst, redSecond));
        List<IPatternDetails> ordered = DyeablePatternCraftingPlanner.prioritizeSameColorFallback(
                List.of(blue, redFirst),
                0xFFFF0000
        );

        assertNotNull(ring);
        assertFalse(DyeablePatternCraftingPlanner.isCompressedRingCalculable(ring));
        assertEquals(redFirst, ordered.getFirst());
        assertEquals(blue, ordered.getLast());
    }

    @Test
    void allowsRingReplacementCandidateNeedsMatchingEntryPoint() {
        AEKey iron = AEItemKey.of(Items.IRON_INGOT);
        AEKey piston = AEItemKey.of(Items.PISTON);
        TestPatternDetails pattern = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFF336699,
                List.of(new GenericStack(iron, 1L)),
                List.of(new GenericStack(piston, 1L), new GenericStack(iron, 1L))
        );
        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(pattern));

        assertNotNull(ring);
        assertTrue(DyeablePatternCraftingPlanner.allowsRingReplacementCandidate(ring, 0xFF336699, piston));
        assertFalse(DyeablePatternCraftingPlanner.allowsRingReplacementCandidate(ring, 0xFF336699, iron));
        assertFalse(DyeablePatternCraftingPlanner.allowsRingReplacementCandidate(ring, -1, piston));
    }

    @Test
    void ringReplacementPlanningAllowsSmithingTemplateSubstitutes() {
        AEKey iron = AEItemKey.of(Items.IRON_INGOT);
        AEKey copper = AEItemKey.of(Items.COPPER_INGOT);
        AEKey piston = AEItemKey.of(Items.PISTON);
        TestPatternDetails pattern = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFF336699,
                List.of(new TestInput(new GenericStack(iron, 1L), new GenericStack(copper, 1L))),
                List.of(new GenericStack(piston, 1L))
        );
        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(pattern));

        assertNotNull(ring);
        assertTrue(DyeablePatternCraftingPlanner.allowsRingReplacementCandidate(ring, 0xFF336699, piston));
        assertTrue(DyeablePatternCraftingPlanner.canPlanRingReplacementWithoutSwallowingReplacement(ring));
    }

    @Test
    void sameColorPrioritizationUsesRingForReplacementAwareSmithingLikeInputs() {
        AEKey iron = AEItemKey.of(Items.IRON_INGOT);
        AEKey copper = AEItemKey.of(Items.COPPER_INGOT);
        AEKey piston = AEItemKey.of(Items.PISTON);
        TestPatternDetails replacementAware = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFF336699,
                List.of(new TestInput(new GenericStack(iron, 1L), new GenericStack(copper, 1L))),
                List.of(new GenericStack(piston, 1L))
        );
        TestPatternDetails otherColor = new TestPatternDetails(
                AEItemKey.of(Items.COMPASS),
                0xFFFF0000,
                List.of(new GenericStack(AEItemKey.of(Items.REDSTONE), 1L)),
                List.of(new GenericStack(AEItemKey.of(Items.CLOCK), 1L))
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(replacementAware, otherColor));

        DyeablePatternCompressedRing ring = providers.getOrCalculateCompressedRing(0xFF336699);
        List<IPatternDetails> ordered = providers.getCraftingForByColor(piston, 0xFF336699);

        assertNotNull(ring);
        assertTrue(DyeablePatternCraftingPlanner.canPlanRingReplacementWithoutSwallowingReplacement(ring));
        assertEquals(replacementAware, ordered.getFirst());
    }

    @Test
    void compressedRingUsesNonPrimaryReplacementCandidateWhenCandidateIsRingOutput() {
        AEItemKey primary = AEItemKey.of(Items.PAPER);
        AEItemKey seed = AEItemKey.of(Items.MAP);
        AEItemKey diamond = AEItemKey.of(Items.DIAMOND);
        AEItemKey netherBricks = AEItemKey.of(Items.NETHER_BRICKS);
        ReplacementAwareProcessingPattern pattern = replacementAwareRecursivePattern(
                primary,
                seed,
                List.of(
                        new GenericStack(primary, 1L),
                        new GenericStack(diamond, 7L),
                        new GenericStack(netherBricks, 1L)
                ),
                List.of(new GenericStack(seed, 2L))
        );

        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(pattern));

        assertNotNull(ring);
        assertEquals(1L, ring.catalysts().get(seed));
        assertEquals(7L, ring.netInputs().get(diamond));
        assertEquals(1L, ring.netInputs().get(netherBricks));
        assertEquals(1L, ring.netOutputs().get(seed));
        assertTrue(ring.entryPoints().contains(seed));
    }

    private record TestPatternDetails(
            AEItemKey definition,
            int color,
            List<?> inputs,
            List<GenericStack> outputs
    ) implements IPatternDetails, IPatternDetailsColorAccessor {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return inputs.stream()
                    .map(input -> input instanceof IInput testInput
                            ? testInput
                            : new TestInput((GenericStack) input))
                    .toArray(IInput[]::new);
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }

        @Override
        public int chexsonsaeutils$getColor() {
            return color;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    private record TestProvider(IPatternDetails... patterns) implements appeng.api.networking.crafting.ICraftingProvider {

        @Override
        public java.util.List<IPatternDetails> getAvailablePatterns() {
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
        public java.util.Set<AEKey> getEmitableItems() {
            return Set.of();
        }

        @Override
        public int getPatternPriority() {
            return 0;
        }
    }

    private record TestInput(GenericStack... stacks) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return stacks;
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            for (GenericStack stack : stacks) {
                if (stack.what().equals(input)) {
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

    private static net.minecraft.world.item.ItemStack dyedItem(net.minecraft.world.level.ItemLike item, int color) {
        net.minecraft.world.item.ItemStack stack = item.asItem().getDefaultInstance();
        stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                new net.minecraft.world.item.component.DyedItemColor(color & 0x00FFFFFF, true));
        return stack;
    }

    private static ReplacementAwareProcessingPattern replacementAwareRecursivePattern(
            AEItemKey primary,
            AEItemKey seed,
            List<GenericStack> inputs,
            List<GenericStack> outputs
    ) {
        net.minecraft.world.item.ItemStack definitionStack =
                PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
        definitionStack.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                new net.minecraft.world.item.component.DyedItemColor(0x336699, true));
        AEItemKey definition = AEItemKey.of(definitionStack);
        ResourceLocation primaryId = primary.getId();
        ResourceLocation seedId = seed.getId();
        ResourceLocation tag = ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "recursive_test");
        ProcessingSlotTagService tagService = new ProcessingSlotTagService(
                ignored -> List.of(tag),
                ignored -> List.of(primaryId, seedId),
                itemId -> primaryId.equals(itemId) || seedId.equals(itemId) ? Set.of(tag) : Set.of()
        );
        return new ReplacementAwareProcessingPattern(
                definition,
                List.of(new ProcessingPatternSlotReplacementRule(
                        0,
                        primaryId,
                        Set.of(),
                        Set.of(seedId)
                )),
                tagService,
                new ProcessingSlotRuleValidation(tagService)
        );
    }
}
