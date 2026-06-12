package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineCraftingTimingService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCPUClusterFormalMachineStatusMixin {

    @Shadow(remap = false)
    public CraftingCpuLogic craftingLogic;

    @Inject(method = "getJobStatus", at = @At("RETURN"), cancellable = true)
    private void chexsonsaeutils$correctFormalMachineJobStatus(
            CallbackInfoReturnable<CraftingJobStatus> cir
    ) {
        cir.setReturnValue(FormalMachineCraftingTimingService.correctJobStatus(craftingLogic, cir.getReturnValue()));
    }
}
