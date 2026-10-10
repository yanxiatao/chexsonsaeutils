package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningProvenance;
import git.chexson.chexsonsaeutils.crafting.status.CraftingStatusEnhancer;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

@Mixin(value = CraftingPlanSummary.class, remap = false, priority = 1300)
public abstract class CraftingPlanSummaryEnhancedStatusMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @ModifyVariable(
            method = "fromJob",
            at = @At("HEAD"),
            argsOnly = true,
            remap = false
    )
    private static ICraftingPlan chexsonsaeutils$unwrapRecursivePlan(ICraftingPlan job) {
        if (job instanceof RecursivePlanContract recursivePlan) {
            return recursivePlan.chexsonsaeutils$getDelegate();
        }
        return job;
    }

    @Inject(method = "fromJob", at = @At("RETURN"), cancellable = false, remap = false)
    private static void chexsonsaeutils$attachPatternTimes(
            IGrid grid,
            IActionSource actionSource,
            ICraftingPlan job,
            CallbackInfoReturnable<CraftingPlanSummary> cir
    ) {
        CraftingPlanSummary summary = cir.getReturnValue();
        PlanningProvenance.Info provenance = PlanningProvenance.peek(job);
        if (provenance != null && summary instanceof EnhancedCraftingPlanSummary enhanced) {
            enhanced.chexsonsaeutils$setProvenance(provenance.millis(), provenance.solver());
        }
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(ChexsonsaeutilsCompatibilityConfig.ENHANCED_CRAFTING_STATUS_ENABLED, "enhancedCraftingStatusEnabled")) {
            return;
        }
        CraftingStatusEnhancer.attachPatternTimes(summary, job);
    }
}
