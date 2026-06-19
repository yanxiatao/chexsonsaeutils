package git.chexson.chexsonsaeutils.crafting.submit;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.core.AELog;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursivePlan;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCPU;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationStatusService;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingBranch;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingDetail;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ElapsedTimeTrackerAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CraftingContinuationPartialSubmit {
    private CraftingContinuationPartialSubmit() {
    }

    public static boolean shouldInterceptPartialSubmit(
            ICraftingPlan plan,
            @Nullable ICraftingCPU target,
            CraftingContinuationMode mode
    ) {
        return isPartialSubmitRequest(plan, mode) && supportsPartialSubmitTarget(target);
    }

    public static boolean isPartialSubmitRequest(ICraftingPlan plan, CraftingContinuationMode mode) {
        return plan != null
                && plan.simulation()
                && mode == CraftingContinuationMode.IGNORE_MISSING;
    }

    public static boolean supportsPartialSubmitTarget(@Nullable ICraftingCPU target) {
        return !(target instanceof ParallelCraftingCPU);
    }

    public static ICraftingSubmitResult submitPartialJob(
            IGrid grid,
            ICraftingPlan plan,
            @Nullable ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src
    ) {
        if (!(target instanceof CraftingCPUCluster cpuCluster)) {
            return CraftingSubmitResult.NO_CPU_FOUND;
        }

        CraftingCpuLogic logic = cpuCluster.craftingLogic;
        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) logic;
        if (logic.hasJob()) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (!cpuCluster.isActive()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (cpuCluster.getAvailableStorage() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }
        if (!logicAccessor.getInventory().list.isEmpty()) {
            AELog.warn("Crafting CPU inventory is not empty yet a continuation job was submitted.");
        }

        ICraftingSubmitResult submitResult = logic.trySubmitJob(
                grid,
                createNativeSubmissionPlan(plan),
                src,
                null
        );
        if (!submitResult.successful()) {
            return submitResult;
        }
        ExecutingCraftingJob job = logicAccessor.getJob();
        if (job == null) {
            AELog.error("AE2 accepted a continuation job without creating an executing job.");
            return CraftingSubmitResult.CPU_BUSY;
        }

        KeyCounter missingInitialItems = extractAvailableInitialItems(plan, grid, logicAccessor.getInventory(), src);
        seedInitialWaitingFor(job, missingInitialItems);
        cpuCluster.markDirty();
        recordWaitingDetail(cpuCluster, plan, job, missingInitialItems);

        return submitResult;
    }

    public static KeyCounter extractAvailableInitialItems(
            ICraftingPlan plan,
            IGrid grid,
            ListCraftingInventory cpuInventory,
            IActionSource src
    ) {
        KeyCounter requiredInitialItems = new KeyCounter();
        requiredInitialItems.addAll(plan.usedItems());
        requiredInitialItems.addAll(plan.missingItems());
        KeyCounter missingInitialItems = new KeyCounter();
        var storage = grid.getStorageService().getInventory();

        for (var entry : requiredInitialItems) {
            AEKey what = entry.getKey();
            long toExtract = entry.getLongValue();
            long extracted = storage.extract(what, toExtract, Actionable.MODULATE, src);
            cpuInventory.insert(what, extracted, Actionable.MODULATE);
            if (extracted < toExtract) {
                missingInitialItems.add(what, toExtract - extracted);
            }
        }

        return missingInitialItems;
    }

    static void seedInitialWaitingFor(ExecutingCraftingJob job, KeyCounter missingInitialItems) {
        if (missingInitialItems == null || missingInitialItems.isEmpty()) {
            return;
        }

        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        for (var entry : missingInitialItems) {
            accessor.getWaitingFor().insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            addTrackedWaitingTime(accessor, entry.getLongValue(), entry.getKey());
        }
    }

    static void recordWaitingDetail(
            CraftingCPUCluster cpuCluster,
            ICraftingPlan plan,
            ExecutingCraftingJob job,
            KeyCounter missingInitialItems
    ) {
        if (!(cpuCluster.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        UUID craftId = ((ExecutingCraftingJobAccessor) job).getLink().getCraftingID();
        if (missingInitialItems == null || missingInitialItems.isEmpty()) {
            CraftingContinuationStatusService.get(serverLevel).clearCompletedJob(craftId);
            return;
        }

        List<CraftingContinuationWaitingBranch> waitingBranches = new ArrayList<>();
        int order = 0;
        for (var entry : missingInitialItems) {
            waitingBranches.add(new CraftingContinuationWaitingBranch(
                    entry.getKey().getDisplayName().getString(),
                    order++,
                    Map.of(CraftingContinuationStatusService.encodeKeyForSync(entry.getKey()), entry.getLongValue())
            ));
        }

        List<String> runningBranchLabels = new ArrayList<>();
        for (var entry : plan.patternTimes().entrySet()) {
            for (var output : entry.getKey().getOutputs()) {
                runningBranchLabels.add(output.what().getDisplayName().getString());
                break;
            }
        }

        CraftingContinuationStatusService.get(serverLevel).trackJob(new CraftingContinuationWaitingDetail(
                craftId,
                CraftingContinuationStatusService.encodeKeyForSync(plan.finalOutput().what()),
                plan.finalOutput().amount(),
                waitingBranches,
                runningBranchLabels
        ));
    }

    private static void addTrackedWaitingTime(ExecutingCraftingJobAccessor accessor, long amount, AEKey key) {
        ((ElapsedTimeTrackerAccessor) accessor.getTimeTracker()).invokeAddMaxItems(amount, key.getType());
    }

    public static ICraftingPlan createNativeSubmissionPlan(ICraftingPlan plan) {
        return new NativeJobSubmissionPlan(plan);
    }

    private record NativeJobSubmissionPlan(ICraftingPlan delegate) implements ICraftingPlan, DyeablePatternRecursivePlan {
        private NativeJobSubmissionPlan {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public appeng.api.stacks.GenericStack finalOutput() {
            return delegate.finalOutput();
        }

        @Override
        public long bytes() {
            return delegate.bytes();
        }

        @Override
        public boolean simulation() {
            return false;
        }

        @Override
        public boolean multiplePaths() {
            return delegate.multiplePaths();
        }

        @Override
        public KeyCounter usedItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter emittedItems() {
            return delegate.emittedItems();
        }

        @Override
        public KeyCounter missingItems() {
            return new KeyCounter();
        }

        @Override
        public Map<appeng.api.crafting.IPatternDetails, Long> patternTimes() {
            return delegate.patternTimes();
        }

        @Override
        public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
            return delegate instanceof DyeablePatternRecursivePlan recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning();
        }

        @Override
        public KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
            if (delegate instanceof DyeablePatternRecursivePlan recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
                return recursivePlan.chexsonsaeutils$dyeableRecursiveInitialItems();
            }
            return new KeyCounter();
        }

        @Override
        public KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems() {
            if (delegate instanceof DyeablePatternRecursivePlan recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
                return recursivePlan.chexsonsaeutils$dyeableRecursiveInternalItems();
            }
            return new KeyCounter();
        }

        @Override
        public long chexsonsaeutils$dyeableRecursiveFinalOutputAmount() {
            if (delegate instanceof DyeablePatternRecursivePlan recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
                return recursivePlan.chexsonsaeutils$dyeableRecursiveFinalOutputAmount();
            }
            return -1L;
        }
    }
}
