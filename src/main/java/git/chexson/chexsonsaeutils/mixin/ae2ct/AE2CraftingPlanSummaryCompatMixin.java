package git.chexson.chexsonsaeutils.mixin.ae2ct;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = CraftingPlanSummary.class, priority = 1100, remap = false)
public abstract class AE2CraftingPlanSummaryCompatMixin {

    @ModifyVariable(
            method = "fromJob",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false
    )
    private static ICraftingPlan chexsonsaeutils$convertAggregatedPlanBeforeAe2ct(ICraftingPlan job) {
        // Let AggregatedCraftingPlan pass through unchanged
        // ae2ct should handle it via ICraftingPlan interface
        return job;
    }
}
