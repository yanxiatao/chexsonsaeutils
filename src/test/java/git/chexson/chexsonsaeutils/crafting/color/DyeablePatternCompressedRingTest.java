package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DyeablePatternCompressedRingTest {

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

    private record TestPatternDetails(
            AEItemKey definition,
            int color,
            List<GenericStack> inputs,
            List<GenericStack> outputs
    ) implements IPatternDetails, IPatternDetailsColorAccessor {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return inputs.stream().map(TestInput::new).toArray(IInput[]::new);
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

    private record TestProvider(IPatternDetails pattern) implements appeng.api.networking.crafting.ICraftingProvider {

        @Override
        public java.util.List<IPatternDetails> getAvailablePatterns() {
            return List.of(pattern);
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

    private record TestInput(GenericStack stack) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[]{stack};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return stack.what().equals(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
