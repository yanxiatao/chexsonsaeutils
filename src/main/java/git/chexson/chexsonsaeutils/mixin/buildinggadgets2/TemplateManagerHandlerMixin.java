package git.chexson.chexsonsaeutils.mixin.buildinggadgets2;

import appeng.core.definitions.AEItems;
import com.direwolf20.buildinggadgets2.common.containers.customhandler.TemplateManagerHandler;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TemplateManagerHandler.class, remap = false)
public abstract class TemplateManagerHandlerMixin {

    @Inject(
            method = "isItemValid(ILnet/minecraft/world/item/ItemStack;)Z",
            at = @At("RETURN"),
            cancellable = true,
            remap = false
    )
    private void chexsonsaeutils$allowAe2Patterns(
            int slot,
            ItemStack stack,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValue() || slot != 1 || stack == null || stack.isEmpty()) {
            return;
        }
        if (stack.is(AEItems.BLANK_PATTERN.asItem()) || stack.is(AEItems.PROCESSING_PATTERN.asItem())) {
            cir.setReturnValue(true);
        }
    }
}
