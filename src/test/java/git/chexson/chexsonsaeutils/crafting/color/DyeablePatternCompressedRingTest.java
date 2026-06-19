package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeService;
import appeng.api.networking.IGridVisitor;
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
import java.util.UUID;
import net.minecraft.CrashReportCategory;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void retainedCatalystCandidatesIgnorePlanOutputsAndMissingItems() {
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        AEKey extractedSeed = AEItemKey.of(Items.GLOWSTONE_DUST);
        AEKey zeroExtraction = AEItemKey.of(Items.LAPIS_LAZULI);
        AEKey finalProduct = AEItemKey.of(Items.DIAMOND);
        AEKey emitted = AEItemKey.of(Items.EMERALD);
        AEKey missing = AEItemKey.of(Items.IRON_INGOT);
        AEKey patternInput = AEItemKey.of(Items.COPPER_INGOT);
        AEKey patternOutput = AEItemKey.of(Items.GOLD_INGOT);
        AEKey patternOnlyInput = AEItemKey.of(Items.QUARTZ);
        IPatternDetails downstreamPattern = pattern(
                List.of(stack(patternOutput, 1L)),
                input(stack(patternInput, 1L)),
                input(stack(seed, 1L)),
                input(stack(patternOnlyInput, 1L))
        );
        KeyCounter usedItems = new KeyCounter();
        usedItems.add(seed, 1L);
        KeyCounter emittedItems = new KeyCounter();
        emittedItems.add(emitted, 1L);
        KeyCounter missingItems = new KeyCounter();
        missingItems.add(missing, 1L);
        KeyCounter recursiveExtractions = new KeyCounter();
        recursiveExtractions.add(extractedSeed, 1L);
        recursiveExtractions.add(zeroExtraction, 0L);
        CraftingPlan plan = new CraftingPlan(
                stack(finalProduct, 1L),
                8L,
                false,
                false,
                usedItems,
                emittedItems,
                missingItems,
                Map.of(downstreamPattern, 1L)
        );

        Set<AEKey> candidates = DyeablePatternCraftingCalculation.collectRetainedCatalystCandidates(
                plan,
                recursiveExtractions
        );

        assertEquals(Set.of(seed, patternInput, patternOnlyInput, extractedSeed), candidates);
    }

    @Test
    void balancedRingIntermediateIsNotInitialCatalyst() {
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        AEKey dust = AEItemKey.of(Items.GLOWSTONE_DUST);
        AEKey intermediate = AEItemKey.of(Items.IRON_INGOT);
        IPatternDetails upstream = pattern(
                input(stack(seed, 1L)),
                input(stack(dust, 1L)),
                List.of(stack(intermediate, 1L))
        );
        IPatternDetails producer = coloredPattern(
                0xFF336699,
                input(stack(intermediate, 1L)),
                List.of(stack(seed, 2L))
        );

        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(upstream, producer));

        assertNotNull(ring);
        assertEquals(1L, ring.catalysts().get(seed));
        assertEquals(0L, ring.catalysts().get(intermediate));
        assertEquals(1L, ring.netOutputs().get(seed));
        assertEquals(1L, ring.netInputs().get(dust));
    }

    @Test
    void samePatternReturnedInputRemainsInitialCatalyst() {
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        AEKey dust = AEItemKey.of(Items.GLOWSTONE_DUST);
        AEKey output = AEItemKey.of(Items.DIAMOND);
        IPatternDetails pattern = pattern(
                input(stack(seed, 1L)),
                input(stack(dust, 1L)),
                List.of(stack(seed, 1L), stack(output, 1L))
        );

        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(pattern));

        assertNotNull(ring);
        assertEquals(1L, ring.catalysts().get(seed));
        assertEquals(0L, ring.netOutputs().get(seed));
        assertEquals(1L, ring.netOutputs().get(output));
        assertEquals(1L, ring.netInputs().get(dust));
    }

    @Test
    void samePatternReturnedFluidInputKeepsGenericStackAmountAsInitialCatalyst() {
        AEKey seed = AEFluidKey.of(Fluids.WATER);
        AEKey dust = AEItemKey.of(Items.GLOWSTONE_DUST);
        AEKey output = AEItemKey.of(Items.DIAMOND);
        IPatternDetails pattern = pattern(
                input(stack(seed, 1_000L)),
                input(stack(dust, 1L)),
                List.of(stack(seed, 1_000L), stack(output, 1L))
        );

        DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(List.of(pattern));

        assertNotNull(ring);
        assertEquals(1_000L, ring.catalysts().get(seed));
        assertEquals(0L, ring.netOutputs().get(seed));
        assertEquals(1L, ring.netOutputs().get(output));
        assertEquals(1L, ring.netInputs().get(dust));
    }

    @Test
    void ringUpstreamConsumerFeedingPendingProducerIsNotDeferred() {
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        AEKey dust = AEItemKey.of(Items.GLOWSTONE_DUST);
        AEKey intermediate = AEItemKey.of(Items.IRON_INGOT);
        AEKey finalProduct = AEItemKey.of(Items.DIAMOND);
        IPatternDetails upstream = pattern(
                input(stack(seed, 1L)),
                input(stack(dust, 1L)),
                List.of(stack(intermediate, 1L))
        );
        IPatternDetails producer = coloredPattern(
                0xFF336699,
                input(stack(intermediate, 1L)),
                List.of(stack(seed, 2L))
        );
        IPatternDetails downstream = coloredPattern(
                -1,
                input(stack(seed, 1L)),
                List.of(stack(finalProduct, 1L))
        );
        KeyCounter internalItems = new KeyCounter();
        internalItems.add(seed, 1L);
        appeng.crafting.inv.ListCraftingInventory inventory = new appeng.crafting.inv.ListCraftingInventory(
                ignored -> {
                }
        );
        inventory.insert(seed, 1L, Actionable.MODULATE);

        assertFalse(DyeablePatternRecursiveTaskOrdering.shouldDeferConsumer(
                upstream,
                internalItems,
                inventory,
                Map.of(upstream, new TestTaskProgress(1L), producer, new TestTaskProgress(1L))
        ));
        assertTrue(DyeablePatternRecursiveTaskOrdering.shouldDeferConsumer(
                downstream,
                internalItems,
                inventory,
                Map.of(downstream, new TestTaskProgress(1L), producer, new TestTaskProgress(1L))
        ));
    }

    @Test
    void retainingRingOnlyMatchesDyedSelfRecursiveCatalysts() {
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        AEKey downstreamParticipant = AEItemKey.of(Items.GLOWSTONE_DUST);
        AEKey finalProduct = AEItemKey.of(Items.DIAMOND);
        IPatternDetails recursiveSeed = coloredPattern(
                0xFF336699,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        IPatternDetails colorlessDownstream = coloredPattern(
                -1,
                input(stack(downstreamParticipant, 1L)),
                List.of(stack(finalProduct, 1L))
        );
        IPatternDetails dyedNonRecursiveDownstream = coloredPattern(
                0xFF336699,
                input(stack(downstreamParticipant, 1L)),
                List.of(stack(finalProduct, 1L))
        );
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();
        providers.addProvider(new TestProvider(
                recursiveSeed,
                colorlessDownstream,
                dyedNonRecursiveDownstream
        ));

        DyeablePatternCompressedRing seedRing = providers.getRetainingRing(seed);
        assertNotNull(seedRing);
        assertEquals(1L, seedRing.catalysts().get(seed));
        assertNull(providers.getRetainingRing(downstreamParticipant));
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

    @Test
    void providerRemovalUsesSnapshotIndexedAtMountTime() {
        int color = 0xFF336699;
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        IPatternDetails initialPattern = identityColoredPattern(
                color,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        IPatternDetails refreshedPattern = identityColoredPattern(
                color,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        MutableTestProvider provider = new MutableTestProvider(initialPattern);
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();

        providers.addProvider(provider);
        provider.setPatterns(refreshedPattern);
        providers.removeProvider(provider);

        assertNull(providers.getOrCalculateCompressedRing(color, seed));

        providers.addProvider(provider);
        DyeablePatternCompressedRing ring = providers.getOrCalculateCompressedRing(color, seed);

        assertNotNull(ring);
        assertEquals(1, ring.executionRatio().size());
        assertTrue(ring.executionRatio().containsKey(refreshedPattern));
    }

    @Test
    void nodeProviderRemovalUsesSnapshotIndexedAtMountTime() {
        int color = 0xFF336699;
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        IPatternDetails initialPattern = identityColoredPattern(
                color,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        IPatternDetails refreshedPattern = identityColoredPattern(
                color,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        MutableTestProvider provider = new MutableTestProvider(initialPattern);
        TestGridNode node = new TestGridNode(provider);
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();

        providers.addProvider(node);
        provider.setPatterns(refreshedPattern);
        providers.removeProvider(node);

        assertNull(providers.getOrCalculateCompressedRing(color, seed));

        providers.addProvider(node);
        DyeablePatternCompressedRing ring = providers.getOrCalculateCompressedRing(color, seed);

        assertNotNull(ring);
        assertEquals(1, ring.executionRatio().size());
        assertTrue(ring.executionRatio().containsKey(refreshedPattern));
    }

    @Test
    void staleGlobalProviderPatternsRefreshBeforeRecursiveRingLookup() {
        int color = 0xFF336699;
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        IPatternDetails recursiveSeed = identityColoredPattern(
                color,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        MutableTestProvider provider = new MutableTestProvider();
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();

        providers.addProvider(provider);
        provider.setPatterns(recursiveSeed);

        DyeablePatternCompressedRing ring = providers.getOrCalculateCompressedRing(color, seed);

        assertNotNull(ring);
        assertTrue(ring.executionRatio().containsKey(recursiveSeed));
        assertTrue(providers.getCraftingFor(seed).contains(recursiveSeed));
    }

    @Test
    void staleNodeProviderPatternsRefreshBeforeRecursiveRingLookup() {
        int color = 0xFF336699;
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        IPatternDetails recursiveSeed = identityColoredPattern(
                color,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        MutableTestProvider provider = new MutableTestProvider();
        TestGridNode node = new TestGridNode(provider);
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();

        providers.addProvider(node);
        provider.setPatterns(recursiveSeed);

        DyeablePatternCompressedRing ring = providers.getOrCalculateCompressedRing(color, seed);

        assertNotNull(ring);
        assertTrue(ring.executionRatio().containsKey(recursiveSeed));
        assertTrue(providers.getCraftingFor(seed).contains(recursiveSeed));
    }

    @Test
    void movedProviderPatternRefreshesRecursiveRingAndExecutionMedium() {
        int color = 0xFF336699;
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        IPatternDetails recursiveSeed = identityColoredPattern(
                color,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        MutableTestProvider sourceProvider = new MutableTestProvider(recursiveSeed);
        MutableTestProvider targetProvider = new MutableTestProvider();
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();

        providers.addProvider(sourceProvider);
        providers.addProvider(targetProvider);
        sourceProvider.setPatterns();
        targetProvider.setPatterns(recursiveSeed);

        DyeablePatternCompressedRing ring = providers.getOrCalculateCompressedRing(color, seed);

        assertNotNull(ring);
        assertTrue(ring.executionRatio().containsKey(recursiveSeed));
        boolean sourceStillRegistered = false;
        boolean targetRegistered = false;
        for (ICraftingProvider medium : providers.getMediums(recursiveSeed)) {
            sourceStillRegistered |= medium == sourceProvider;
            targetRegistered |= medium == targetProvider;
        }
        assertFalse(sourceStillRegistered);
        assertTrue(targetRegistered);
    }

    @Test
    void recoloredEqualDefinitionPatternRefreshesRecursiveRingColor() {
        int blue = 0xFF336699;
        int red = 0xFF993333;
        AEKey seed = AEItemKey.of(Items.REDSTONE);
        IPatternDetails bluePattern = definitionEqualsColoredPattern(
                blue,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        IPatternDetails redPattern = definitionEqualsColoredPattern(
                red,
                input(stack(seed, 1L)),
                List.of(stack(seed, 2L))
        );
        MutableTestProvider provider = new MutableTestProvider(bluePattern);
        DyeablePatternCraftingProviders providers = new DyeablePatternCraftingProviders();

        providers.addProvider(provider);
        provider.setPatterns(redPattern);

        assertNull(providers.getOrCalculateCompressedRing(blue, seed));
        DyeablePatternCompressedRing redRing = providers.getOrCalculateCompressedRing(red, seed);

        assertNotNull(redRing);
        assertSame(redPattern, redRing.executionRatio().keySet().iterator().next());
    }

    private static IPatternDetails pattern(
            IPatternDetails.IInput firstInput,
            IPatternDetails.IInput secondInput,
            List<GenericStack> outputs
    ) {
        return new TestPattern(new IPatternDetails.IInput[] { firstInput, secondInput }, outputs);
    }

    private static IPatternDetails pattern(
            List<GenericStack> outputs,
            IPatternDetails.IInput... inputs
    ) {
        return new TestPattern(inputs, outputs);
    }

    private static IPatternDetails coloredPattern(
            int color,
            IPatternDetails.IInput input,
            List<GenericStack> outputs
    ) {
        return new ColoredTestPattern(new IPatternDetails.IInput[] { input }, outputs, color);
    }

    private static IPatternDetails identityColoredPattern(
            int color,
            IPatternDetails.IInput input,
            List<GenericStack> outputs
    ) {
        return new IdentityColoredTestPattern(new IPatternDetails.IInput[] { input }, outputs, color);
    }

    private static IPatternDetails definitionEqualsColoredPattern(
            int color,
            IPatternDetails.IInput input,
            List<GenericStack> outputs
    ) {
        return new DefinitionEqualsColoredTestPattern(new IPatternDetails.IInput[] { input }, outputs, color);
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

    private record ColoredTestPattern(
            IPatternDetails.IInput[] inputs,
            List<GenericStack> outputs,
            int color
    ) implements IPatternDetails, IPatternDetailsColorAccessor {

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

        @Override
        public int chexsonsaeutils$getColor() {
            return color;
        }
    }

    private static final class IdentityColoredTestPattern implements IPatternDetails, IPatternDetailsColorAccessor {
        private final IPatternDetails.IInput[] inputs;
        private final List<GenericStack> outputs;
        private final int color;

        private IdentityColoredTestPattern(
                IPatternDetails.IInput[] inputs,
                List<GenericStack> outputs,
                int color
        ) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.color = color;
        }

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

        @Override
        public int chexsonsaeutils$getColor() {
            return color;
        }
    }

    private static final class DefinitionEqualsColoredTestPattern
            implements IPatternDetails, IPatternDetailsColorAccessor {
        private final IPatternDetails.IInput[] inputs;
        private final List<GenericStack> outputs;
        private final int color;

        private DefinitionEqualsColoredTestPattern(
                IPatternDetails.IInput[] inputs,
                List<GenericStack> outputs,
                int color
        ) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.color = color;
        }

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

        @Override
        public int chexsonsaeutils$getColor() {
            return color;
        }

        @Override
        public boolean equals(Object other) {
            return other != null
                    && other.getClass() == getClass()
                    && ((DefinitionEqualsColoredTestPattern) other).getDefinition().equals(getDefinition());
        }

        @Override
        public int hashCode() {
            return getDefinition().hashCode();
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

    private static final class MutableTestProvider implements ICraftingProvider {
        private List<IPatternDetails> patterns;

        private MutableTestProvider(IPatternDetails... patterns) {
            setPatterns(patterns);
        }

        private void setPatterns(IPatternDetails... patterns) {
            this.patterns = List.of(patterns);
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return patterns;
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

    private record TestGridNode(ICraftingProvider provider) implements IGridNode {
        @Override
        public <T extends IGridNodeService> T getService(Class<T> serviceClass) {
            if (serviceClass == ICraftingProvider.class) {
                return serviceClass.cast(provider);
            }
            return null;
        }

        @Override
        public Object getOwner() {
            return this;
        }

        @Override
        public void beginVisit(IGridVisitor visitor) {
        }

        @Override
        public IGrid getGrid() {
            return null;
        }

        @Override
        public ServerLevel getLevel() {
            return null;
        }

        @Override
        public Set<Direction> getConnectedSides() {
            return Set.of();
        }

        @Override
        public Map<Direction, IGridConnection> getInWorldConnections() {
            return Map.of();
        }

        @Override
        public List<IGridConnection> getConnections() {
            return List.of();
        }

        @Override
        public boolean hasGridBooted() {
            return true;
        }

        @Override
        public boolean isPowered() {
            return true;
        }

        @Override
        public boolean meetsChannelRequirements() {
            return true;
        }

        @Override
        public boolean hasFlag(GridFlags flag) {
            return false;
        }

        @Override
        public int getOwningPlayerId() {
            return 0;
        }

        @Override
        public @Nullable UUID getOwningPlayerProfileId() {
            return null;
        }

        @Override
        public double getIdlePowerUsage() {
            return 0D;
        }

        @Override
        public @Nullable AEItemKey getVisualRepresentation() {
            return null;
        }

        @Override
        public appeng.api.util.AEColor getGridColor() {
            return appeng.api.util.AEColor.TRANSPARENT;
        }

        @Override
        public void fillCrashReportCategory(CrashReportCategory category) {
        }

        @Override
        public int getMaxChannels() {
            return 0;
        }

        @Override
        public int getUsedChannels() {
            return 0;
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
