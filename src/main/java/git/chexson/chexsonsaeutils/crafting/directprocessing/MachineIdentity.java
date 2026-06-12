package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public record MachineIdentity(
        ResourceLocation machineItemId,
        @Nullable ResourceLocation blockId,
        @Nullable ResourceLocation blockEntityTypeId,
        String namespace,
        @Nullable String capabilitySummary,
        @Nullable String configProfileId
) {

    @Nullable
    public static MachineIdentity fromBindingStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) {
            return null;
        }
        ResourceLocation blockId = null;
        if (stack.getItem() instanceof BlockItem blockItem) {
            blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
        }
        return new MachineIdentity(itemId, blockId, null, itemId.getNamespace(), null, null);
    }
}
