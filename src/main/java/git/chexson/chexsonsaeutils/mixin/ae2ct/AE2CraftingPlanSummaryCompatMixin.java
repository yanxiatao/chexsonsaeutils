package git.chexson.chexsonsaeutils.mixin.ae2ct;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.crafting.planning.AggregatedCraftingPlan;
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
        if (job instanceof AggregatedCraftingPlan aggregated) {
            return new CraftingPlan(
                    aggregated.finalOutput(),
                    aggregated.bytes(),
                    aggregated.simulation(),
                    aggregated.multiplePaths(),
                    aggregated.usedItems(),
                    aggregated.emittedItems(),
                    aggregated.missingItems(),
                    aggregated.patternTimes()
            );
        }
        return job;
    }
}
