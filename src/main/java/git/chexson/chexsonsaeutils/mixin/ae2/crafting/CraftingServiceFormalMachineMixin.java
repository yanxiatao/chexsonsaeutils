package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.networking.energy.IEnergyService;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.config.FormalMachineCraftingDispatchFeatureGate;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineCraftingDispatchService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceFormalMachineMixin {

    @Shadow(remap = false)
    private IGrid grid;

    @Shadow(remap = false)
    private Set<CraftingCPUCluster> craftingCPUClusters;

    @Shadow(remap = false)
    private IEnergyService energyGrid;

    @Inject(method = "onServerEndTick", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$dispatchFormalMachineFastPath(CallbackInfo ci) {
        if (!FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup()) {
            return;
        }
        FormalMachineCraftingDispatchService.dispatchOnServerEndTick(
                this.grid,
                this.craftingCPUClusters,
                (CraftingService) (Object) this,
                this.energyGrid
        );
    }

    @Inject(method = "insertIntoCpus", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$wakeFormalMachineDispatchOnInsert(
            AEKey what,
            long amount,
            Actionable type,
            CallbackInfoReturnable<Long> cir
    ) {
        if (!FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup()) {
            return;
        }
        FormalMachineCraftingDispatchService.onInsertIntoCpus(
                this.grid,
                this.craftingCPUClusters,
                what,
                amount,
                type
        );
    }

    @Inject(method = "updateCPUClusters", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$refreshFormalMachineDispatchCpuContext(CallbackInfo ci) {
        if (!FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup()) {
            return;
        }
        FormalMachineCraftingDispatchService.onUpdateCpuClusters(this.grid, this.craftingCPUClusters);
    }

    @Inject(method = "submitJob", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$initFormalMachineDispatchContextHead(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        if (FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup()) {
            FormalMachineCraftingDispatchService.onSubmitJobHead();
        }
    }

    @Inject(method = "submitJob", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$initFormalMachineDispatchContextTail(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        if (FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup()) {
            FormalMachineCraftingDispatchService.onSubmitJobTail(
                    (CraftingService) (Object) this,
                    job,
                    requestingMachine,
                    target,
                    cir.getReturnValue()
            );
        }
    }
}
