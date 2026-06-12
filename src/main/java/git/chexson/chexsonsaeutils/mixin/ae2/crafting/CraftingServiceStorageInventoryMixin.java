package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.AeExternalIngressContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "appeng.me.service.helpers.CraftingServiceStorage$1", remap = false)
public abstract class CraftingServiceStorageInventoryMixin {

    @Inject(method = "insert", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$markExternalIngress(
            AEKey what,
            long amount,
            Actionable mode,
            IActionSource source,
            CallbackInfoReturnable<Long> cir
    ) {
        AeExternalIngressContext.enter();
    }

    @Inject(method = "insert", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$clearExternalIngress(
            AEKey what,
            long amount,
            Actionable mode,
            IActionSource source,
            CallbackInfoReturnable<Long> cir
    ) {
        AeExternalIngressContext.exit();
    }
}
