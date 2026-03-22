package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationStatusService;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationSubmitBridge;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceContinuationMixin {
    @Shadow(remap = false)
    private IGrid grid;

    @Shadow(remap = false)
    private Set<CraftingCPUCluster> craftingCPUClusters;

    @Shadow(remap = false)
    @Nullable
    protected abstract CraftingCPUCluster findSuitableCraftingCPU(
            ICraftingPlan job,
            boolean prioritizePower,
            IActionSource src,
            MutableObject<UnsuitableCpus> unsuitableCpus
    );

    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$submitPartialJob(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        if (!job.simulation()
                || CraftingContinuationSubmitBridge.currentMode() != CraftingContinuationMode.IGNORE_MISSING) {
            return;
        }

        // Stock AE2 would otherwise stop here with CraftingSubmitResult.INCOMPLETE_PLAN.
        CraftingCPUCluster cpuCluster;
        if (target instanceof CraftingCPUCluster concreteCpu) {
            cpuCluster = concreteCpu;
        } else {
            MutableObject<UnsuitableCpus> unsuitableCpus = new MutableObject<>();
            cpuCluster = findSuitableCraftingCPU(job, prioritizePower, src, unsuitableCpus);
            if (cpuCluster == null) {
                UnsuitableCpus unsuitable = unsuitableCpus.getValue();
                cir.setReturnValue(unsuitable == null
                        ? CraftingSubmitResult.NO_CPU_FOUND
                        : CraftingSubmitResult.noSuitableCpu(unsuitable));
                return;
            }
        }

        cir.setReturnValue(CraftingContinuationPartialSubmit.submitPartialJob(
                this.grid,
                job,
                cpuCluster,
                prioritizePower,
                src
        ));
    }

    @Inject(method = "insertIntoCpus", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$reconcileWaitingInputsOnInsert(
            AEKey what,
            long amount,
            Actionable type,
            CallbackInfoReturnable<Long> cir
    ) {
        if (type != Actionable.MODULATE || what == null || amount <= 0L) {
            return;
        }

        CraftingContinuationStatusService.reconcileWaitingInputs(
                this.grid,
                this.craftingCPUClusters,
                what,
                false
        );
    }

    @Inject(method = "updateCPUClusters", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$reconcileWaitingInputsAfterCpuRebuild(CallbackInfo ci) {
        CraftingContinuationStatusService.reconcileWaitingInputs(
                this.grid,
                this.craftingCPUClusters,
                null,
                true
        );
    }

    @Inject(method = "onServerEndTick", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$reconcileWaitingInputsOnServerEndTick(CallbackInfo ci) {
        CraftingContinuationStatusService.reconcileWaitingInputsOnServerEndTick(
                this.grid,
                this.craftingCPUClusters
        );
    }
}
