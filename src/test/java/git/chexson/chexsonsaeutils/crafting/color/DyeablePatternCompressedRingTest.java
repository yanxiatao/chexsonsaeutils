package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
