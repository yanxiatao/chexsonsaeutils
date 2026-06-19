package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.config.EnhancedCraftingStatusFeatureGate;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingStatusService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingPlanSummary.class, remap = false)
public abstract class CraftingPlanSummaryEnhancedStatusMixin {

    @Inject(method = "fromJob", at = @At("RETURN"), cancellable = true, remap = false)
    private static void chexsonsaeutils$attachPatternTimes(
            IGrid grid,
            IActionSource actionSource,
            ICraftingPlan job,
            CallbackInfoReturnable<CraftingPlanSummary> cir
    ) {
        if (!EnhancedCraftingStatusFeatureGate.isEnabledAtStartup()) {
            return;
        }
        cir.setReturnValue(EnhancedCraftingStatusService.attachPatternTimes(cir.getReturnValue(), job));
    }
}
