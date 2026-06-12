package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.config.FormalMachinePlanningAggregationFeatureGate;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachinePlanningAggregationService;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Future;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServicePlanningAggregationMixin {

    @Shadow(remap = false)
    private IGrid grid;

    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$aggregateLargeFormalMachinePlanning(
            Level level,
            ICraftingSimulationRequester simRequester,
            AEKey what,
            long amount,
            CalculationStrategy strategy,
            CallbackInfoReturnable<Future<ICraftingPlan>> cir
    ) {
        if (!FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup()) {
            return;
        }
        Future<ICraftingPlan> aggregated = FormalMachinePlanningAggregationService.tryBeginCraftingCalculation(
                (CraftingService) (Object) this,
                this.grid,
                level,
                simRequester,
                what,
                amount,
                strategy
        );
        if (aggregated != null) {
            cir.setReturnValue(aggregated);
        }
    }
}
