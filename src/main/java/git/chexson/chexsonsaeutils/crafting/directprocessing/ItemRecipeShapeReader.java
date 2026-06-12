package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

final class ItemRecipeShapeReader {

    @Nullable
    GenericStack toGenericStack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return null;
        }
        return new GenericStack(key, Math.max(1, stack.getCount()));
    }
}
