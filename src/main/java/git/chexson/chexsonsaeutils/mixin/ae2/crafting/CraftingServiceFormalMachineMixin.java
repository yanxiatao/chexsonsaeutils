package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.config.FormalMachineCraftingDispatchFeatureGate;
import git.chexson.chexsonsaeutils.config.FormalMachinePlanningAggregationFeatureGate;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineCraftingDispatchService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceFormalMachineMixin {

    @Inject(method = "submitJob", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$registerFormalMachineSubmitContext(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        if (!chexsonsaeutils$shouldTrackFormalMachineSubmitContext()) {
            return;
        }
        FormalMachineCraftingDispatchService.onSubmitJobTail(
                (CraftingService) (Object) this,
                job,
                requestingMachine,
                target,
                cir.getReturnValue()
        );
    }

    private static boolean chexsonsaeutils$shouldTrackFormalMachineSubmitContext() {
        return FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup()
                || FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup();
    }
}
