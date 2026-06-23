package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingStatus;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingBlockedTracker;
import git.chexson.chexsonsaeutils.crafting.status.CraftingStatusEnhancer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingStatus.class, remap = false)
public abstract class CraftingStatusEnhancedStatusMixin {

    @Inject(method = "create", at = @At("RETURN"), cancellable = true, remap = false)
    private static void chexsonsaeutils$attachBlockedAmounts(
            IncrementalUpdateHelper changes,
            CraftingCpuLogic logic,
            CallbackInfoReturnable<CraftingStatus> cir
    ) {
        if (!FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED, "enhancedCraftingStatusEnabled")) {
            return;
        }
        cir.setReturnValue(CraftingStatusEnhancer.attachBlockedAmounts(
                cir.getReturnValue(),
                EnhancedCraftingBlockedTracker.from(logic),
                changes
        ));
    }
}
