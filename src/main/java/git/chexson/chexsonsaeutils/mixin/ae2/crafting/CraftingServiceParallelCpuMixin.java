package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.ICraftingWatcherNode;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.InterestManager;
import appeng.me.helpers.StackWatcher;
import appeng.me.service.CraftingService;
import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.config.FormalMachineCraftingDispatchFeatureGate;
import git.chexson.chexsonsaeutils.config.FormalMachinePlanningAggregationFeatureGate;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineCraftingDispatchService;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCPU;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCpuCluster;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCpuGrid;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationSubmitBridge;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceParallelCpuMixin {

    @Shadow(remap = false)
    private IGrid grid;

    @Shadow(remap = false)
    private IEnergyService energyGrid;

    @Shadow(remap = false)
    private Set<AEKey> currentlyCrafting;

    @Shadow(remap = false)
    private InterestManager<StackWatcher<ICraftingWatcherNode>> interestManager;

    @Shadow(remap = false)
    private Set<CraftingCPUCluster> craftingCPUClusters;

    @Shadow(remap = false)
    private boolean updateList;

    @Shadow(remap = false)
    private long lastProcessedCraftingLogicChangeTick;

    @Shadow(remap = false)
    public abstract void addLink(CraftingLink link);

    @Unique
    private final Set<ParallelCraftingCpuCluster> chexsonsaeutils$parallelCpuClusters = new LinkedHashSet<>();

    @Unique
    private ParallelCraftingCpuGrid chexsonsaeutils$parallelCpuGrid;

    @Inject(method = "updateCPUClusters", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$refreshParallelCpuClusters(CallbackInfo ci) {
        chexsonsaeutils$rebuildParallelClusters();
    }

    @Inject(method = "addNode", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$markParallelCpuListDirtyOnAdd(
            IGridNode gridNode,
            CompoundTag savedData,
            CallbackInfo ci
    ) {
        if (gridNode != null && gridNode.getOwner() instanceof AE2ParallelCpuToolBlockEntity) {
            this.updateList = true;
        }
    }

    @Inject(method = "removeNode", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$markParallelCpuListDirtyOnRemove(IGridNode gridNode, CallbackInfo ci) {
        if (gridNode != null && gridNode.getOwner() instanceof AE2ParallelCpuToolBlockEntity) {
            this.updateList = true;
        }
    }

    @Inject(
            method = "onServerEndTick",
            at = @At(
                    value = "FIELD",
                    target = "Lappeng/me/service/CraftingService;lastProcessedCraftingLogicChangeTick:J",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0
            ),
            remap = false
    )
    private void chexsonsaeutils$tickParallelCpuLanes(CallbackInfo ci, @Local(name = "latestChange") long latestChange) {
        long latestParallelChange = chexsonsaeutils$getSyncedParallelCpuGrid()
                .tick(this.energyGrid, (CraftingService) (Object) this);
        if (latestParallelChange > latestChange) {
            this.lastProcessedCraftingLogicChangeTick = -1L;
        }
    }

    @Inject(
            method = "onServerEndTick",
            at = @At(
                    value = "FIELD",
                    target = "Lappeng/me/service/CraftingService;interests:Lcom/google/common/collect/Multimap;",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0
            ),
            remap = false
    )
    private void chexsonsaeutils$syncParallelCurrentlyCrafting(CallbackInfo ci) {
        chexsonsaeutils$syncParallelCurrentlyCrafting(chexsonsaeutils$getSyncedParallelCpuGrid());
    }

    @Inject(
            method = "getCpus",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableSet$Builder;build()Lcom/google/common/collect/ImmutableSet;"
            ),
            locals = LocalCapture.CAPTURE_FAILHARD,
            remap = false
    )
    private void chexsonsaeutils$appendParallelCpus(
            CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir,
            ImmutableSet.Builder<ICraftingCPU> cpus
    ) {
        chexsonsaeutils$syncParallelClusterView();
        int activeLaneCount = chexsonsaeutils$parallelActiveLaneCount();
        ParallelCraftingCpuConfig.Settings settings = ParallelCraftingCpuConfig.current();
        for (ParallelCraftingCpuCluster cluster : this.chexsonsaeutils$parallelCpuClusters) {
            cluster.appendVisibleCpus(
                    cpus,
                    cluster.canAdvertiseRemainingCapacityCpu()
                            && activeLaneCount < settings.maxInternalLanesPerGrid()
            );
        }
    }

    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$submitExplicitParallelCpuJob(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        if (!(target instanceof ParallelCraftingCPU)) {
            return;
        }

        if (job == null) {
            ICraftingSubmitResult result = CraftingSubmitResult.INCOMPLETE_PLAN;
            chexsonsaeutils$registerFormalMachineSubmitResult(job, requestingMachine, target, result);
            cir.setReturnValue(result);
            return;
        }
        if (job.simulation()) {
            ICraftingSubmitResult result = CraftingContinuationPartialSubmit.isPartialSubmitRequest(
                    job,
                    CraftingContinuationSubmitBridge.currentMode())
                    ? CraftingSubmitResult.noSuitableCpu(new UnsuitableCpus(0, 0, 0, 1))
                    : CraftingSubmitResult.INCOMPLETE_PLAN;
            chexsonsaeutils$registerFormalMachineSubmitResult(job, requestingMachine, target, result);
            cir.setReturnValue(result);
            return;
        }

        ICraftingSubmitResult result = chexsonsaeutils$getSyncedParallelCpuGrid()
                .submitJob(job, requestingMachine, target, prioritizePower, src);
        chexsonsaeutils$registerFormalMachineSubmitResult(job, requestingMachine, target, result);
        cir.setReturnValue(result == null ? CraftingSubmitResult.CPU_OFFLINE : result);
    }

    @Inject(
            method = "submitJob",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/me/service/CraftingService;"
                            + "findSuitableCraftingCPU(Lappeng/api/networking/crafting/ICraftingPlan;"
                            + "ZLappeng/api/networking/security/IActionSource;"
                            + "Lorg/apache/commons/lang3/mutable/MutableObject;)"
                            + "Lappeng/me/cluster/implementations/CraftingCPUCluster;"
            ),
            cancellable = true,
            remap = false
    )
    private void chexsonsaeutils$submitAutoSelectedParallelCpuJob(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        if (target != null) {
            return;
        }

        ICraftingSubmitResult result = chexsonsaeutils$getSyncedParallelCpuGrid()
                .submitJob(job, requestingMachine, null, prioritizePower, src);
        if (result != null) {
            chexsonsaeutils$registerFormalMachineSubmitResult(job, requestingMachine, null, result);
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "submitJob", at = @At("RETURN"), cancellable = true, remap = false)
    private void chexsonsaeutils$mergeParallelCpuAutoSelectionFailure(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        if (target != null || job == null || job.simulation()) {
            return;
        }
        ICraftingSubmitResult nativeResult = cir.getReturnValue();
        if (nativeResult == null || nativeResult.successful()) {
            return;
        }

        ICraftingSubmitResult parallelFailure = chexsonsaeutils$getSyncedParallelCpuGrid()
                .getAutoSelectionFailure(job, src);
        if (parallelFailure == null || parallelFailure.successful()) {
            return;
        }

        ICraftingSubmitResult merged = chexsonsaeutils$mergeSubmitFailures(nativeResult, parallelFailure);
        if (merged != nativeResult) {
            cir.setReturnValue(merged);
        }
    }

    @Inject(
            method = "insertIntoCpus",
            at = @At(value = "RETURN", shift = At.Shift.BY, by = -1),
            remap = false
    )
    private void chexsonsaeutils$insertIntoParallelCpus(
            AEKey what,
            long amount,
            Actionable type,
            CallbackInfoReturnable<Long> cir,
            @Local(ordinal = 1) LocalLongRef inserted
    ) {
        long original = Math.max(0L, inserted.get());
        long parallelInserted = chexsonsaeutils$getSyncedParallelCpuGrid().insertIntoCpus(
                what,
                amount,
                type,
                original
        );
        if (parallelInserted > 0L) {
            inserted.set(saturatedAdd(original, parallelInserted));
        }
    }

    @Inject(method = "getRequestedAmount", at = @At("RETURN"), cancellable = true, remap = false)
    private void chexsonsaeutils$getParallelRequestedAmount(AEKey what, CallbackInfoReturnable<Long> cir) {
        long requested = chexsonsaeutils$getSyncedParallelCpuGrid().getRequestedAmount(what);
        if (requested > 0L) {
            cir.setReturnValue(cir.getReturnValue() + requested);
        }
    }

    @Inject(method = "isRequesting", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$isParallelCpuRequesting(AEKey what, CallbackInfoReturnable<Boolean> cir) {
        if (chexsonsaeutils$getSyncedParallelCpuGrid().isRequesting(what)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isRequestingAny", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$isAnyParallelCpuRequesting(CallbackInfoReturnable<Boolean> cir) {
        if (chexsonsaeutils$getSyncedParallelCpuGrid().isRequestingAny()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "hasCpu", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$hasParallelCpu(ICraftingCPU cpu, CallbackInfoReturnable<Boolean> cir) {
        if (chexsonsaeutils$getSyncedParallelCpuGrid().hasCpu(cpu)) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    private void chexsonsaeutils$rebuildParallelClusters() {
        chexsonsaeutils$refreshParallelClusters(true);
    }

    @Unique
    private void chexsonsaeutils$syncParallelClusterView() {
        chexsonsaeutils$refreshParallelClusters(false);
    }

    @Unique
    private void chexsonsaeutils$refreshParallelClusters(boolean registerActiveLinks) {
        Set<ParallelCraftingCpuCluster> nextClusters = new LinkedHashSet<>();
        if (this.grid != null) {
            for (AE2ParallelCpuToolBlockEntity provider : chexsonsaeutils$collectParallelCpuProviders()) {
                if (provider == null) {
                    continue;
                }
                ParallelCraftingCpuCluster cluster = provider.getParallelCpuCluster();
                if (cluster == null) {
                    continue;
                }
                if (chexsonsaeutils$shouldIncludeCluster(provider, cluster)) {
                    nextClusters.add(cluster);
                }
            }
        }

        this.chexsonsaeutils$parallelCpuClusters.clear();
        this.chexsonsaeutils$parallelCpuClusters.addAll(nextClusters);
        chexsonsaeutils$getParallelCpuGrid().setClusters(this.chexsonsaeutils$parallelCpuClusters);

        if (!registerActiveLinks) {
            return;
        }
        Collection<ICraftingRequester> activeRequesters = chexsonsaeutils$collectCraftingRequesters();
        Collection<CraftingLink> activeLinks = new ArrayList<>();
        for (ParallelCraftingCpuCluster cluster : this.chexsonsaeutils$parallelCpuClusters) {
            cluster.restoreRequesterLinks(activeRequesters);
            cluster.appendActiveLaneLinks(activeLinks);
        }
        for (CraftingLink activeLink : activeLinks) {
            this.addLink(activeLink);
        }
    }

    @Unique
    private Set<AE2ParallelCpuToolBlockEntity> chexsonsaeutils$collectParallelCpuProviders() {
        Set<AE2ParallelCpuToolBlockEntity> providers = new LinkedHashSet<>();
        if (this.grid == null) {
            return providers;
        }
        providers.addAll(this.grid.getMachines(AE2ParallelCpuToolBlockEntity.class));
        for (IGridNode node : this.grid.getNodes()) {
            if (node != null && node.getOwner() instanceof AE2ParallelCpuToolBlockEntity provider) {
                providers.add(provider);
            }
        }
        return providers;
    }

    @Unique
    private Collection<ICraftingRequester> chexsonsaeutils$collectCraftingRequesters() {
        Collection<ICraftingRequester> requesters = new ArrayList<>();
        if (this.grid == null) {
            return requesters;
        }
        for (IGridNode node : this.grid.getNodes()) {
            ICraftingRequester requester = node == null ? null : node.getService(ICraftingRequester.class);
            if (requester != null) {
                requesters.add(requester);
            }
        }
        return requesters;
    }

    @Unique
    private boolean chexsonsaeutils$shouldIncludeCluster(
            AE2ParallelCpuToolBlockEntity provider,
            ParallelCraftingCpuCluster cluster
    ) {
        if (provider.getMainNode().getNode() == null) {
            return false;
        }
        if (provider.getMainNode().getNode().getGrid() != this.grid) {
            return false;
        }
        return provider.isParallelCpuProviderActive() || cluster.activeLaneCount() > 0;
    }

    @Unique
    private int chexsonsaeutils$parallelActiveLaneCount() {
        int activeLaneCount = 0;
        for (ParallelCraftingCpuCluster cluster : this.chexsonsaeutils$parallelCpuClusters) {
            int clusterLaneCount = cluster.activeLaneCount();
            if (activeLaneCount >= Integer.MAX_VALUE - clusterLaneCount) {
                return Integer.MAX_VALUE;
            }
            activeLaneCount += clusterLaneCount;
        }
        return activeLaneCount;
    }

    @Unique
    private static ICraftingSubmitResult chexsonsaeutils$mergeSubmitFailures(
            ICraftingSubmitResult nativeResult,
            ICraftingSubmitResult parallelResult
    ) {
        UnsuitableCpus nativeUnsuitable = chexsonsaeutils$unsuitableCpus(nativeResult);
        UnsuitableCpus parallelUnsuitable = chexsonsaeutils$unsuitableCpus(parallelResult);
        if (nativeUnsuitable == null && parallelUnsuitable == null) {
            return nativeResult;
        }
        if (nativeUnsuitable == null) {
            nativeUnsuitable = new UnsuitableCpus(0, 0, 0, 0);
        }
        if (parallelUnsuitable == null) {
            parallelUnsuitable = new UnsuitableCpus(0, 0, 0, 0);
        }
        return CraftingSubmitResult.noSuitableCpu(new UnsuitableCpus(
                saturatedAdd(nativeUnsuitable.offline(), parallelUnsuitable.offline()),
                saturatedAdd(nativeUnsuitable.busy(), parallelUnsuitable.busy()),
                saturatedAdd(nativeUnsuitable.tooSmall(), parallelUnsuitable.tooSmall()),
                saturatedAdd(nativeUnsuitable.excluded(), parallelUnsuitable.excluded())
        ));
    }

    @Unique
    private static UnsuitableCpus chexsonsaeutils$unsuitableCpus(ICraftingSubmitResult result) {
        if (result.errorCode() == CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND
                && result.errorDetail() instanceof UnsuitableCpus unsuitableCpus) {
            return unsuitableCpus;
        }
        if (result.errorCode() == CraftingSubmitErrorCode.NO_CPU_FOUND) {
            return null;
        }
        return null;
    }

    @Unique
    private static int saturatedAdd(int left, int right) {
        if (right > 0 && left >= Integer.MAX_VALUE - right) {
            return Integer.MAX_VALUE;
        }
        return left + right;
    }

    @Unique
    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    @Unique
    private void chexsonsaeutils$registerFormalMachineSubmitResult(
            @Nullable ICraftingPlan job,
            @Nullable ICraftingRequester requestingMachine,
            @Nullable ICraftingCPU target,
            @Nullable ICraftingSubmitResult result
    ) {
        if (!(FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup()
                || FormalMachineCraftingDispatchFeatureGate.isEnabledAtStartup())
                || result == null
                || !result.successful()) {
            return;
        }
        FormalMachineCraftingDispatchService.onSubmitJobTail(
                (CraftingService) (Object) this,
                job,
                requestingMachine,
                target,
                result
        );
    }

    @Unique
    private ParallelCraftingCpuGrid chexsonsaeutils$getParallelCpuGrid() {
        if (chexsonsaeutils$parallelCpuGrid == null) {
            chexsonsaeutils$parallelCpuGrid = new ParallelCraftingCpuGrid(this.grid);
            chexsonsaeutils$parallelCpuGrid.setClusters(this.chexsonsaeutils$parallelCpuClusters);
        }
        return chexsonsaeutils$parallelCpuGrid;
    }

    @Unique
    private ParallelCraftingCpuGrid chexsonsaeutils$getSyncedParallelCpuGrid() {
        chexsonsaeutils$syncParallelClusterView();
        return chexsonsaeutils$getParallelCpuGrid();
    }

    @Unique
    private void chexsonsaeutils$syncParallelCurrentlyCrafting(ParallelCraftingCpuGrid parallelCpuGrid) {
        parallelCpuGrid.appendCurrentlyCrafting(this.currentlyCrafting);
    }
}
