package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.menu.me.crafting.CraftingStatus;
import git.chexson.chexsonsaeutils.config.EnhancedCraftingStatusFeatureGate;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingStatusService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCPUScreen.class, remap = false)
public abstract class CraftingCPUScreenEnhancedStatusMixin {

    @Shadow(remap = false)
    private CraftingStatus status;

    @Inject(method = "postUpdate", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$copyBlockedAmountsAfterIncrementalMerge(
            CraftingStatus update,
            CallbackInfo ci
    ) {
        if (!EnhancedCraftingStatusFeatureGate.isEnabledAtStartup()) {
            return;
        }
        EnhancedCraftingStatusService.copyBlockedAmountsBySerial(this.status, update);
    }
}
