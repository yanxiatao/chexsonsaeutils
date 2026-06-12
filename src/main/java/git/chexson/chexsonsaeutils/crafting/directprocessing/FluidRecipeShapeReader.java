package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

final class FluidRecipeShapeReader {

    @Nullable
    GenericStack toGenericStack(@Nullable FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        AEFluidKey key = AEFluidKey.of(stack);
        if (key == null) {
            return null;
        }
        return new GenericStack(key, Math.max(1, stack.getAmount()));
    }
}
