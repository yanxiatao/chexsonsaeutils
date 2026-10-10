package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingSubmitResult;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ParallelCraftingCpuGrid {

    private final IGrid grid;
    private final Set<ParallelCraftingCpuCluster> clusters = new LinkedHashSet<>();
    private final ParallelCpuWaitingIndex waitingIndex = new ParallelCpuWaitingIndex();
    private long currentTick = Long.MIN_VALUE;
    private int submissionsThisTick;
    private int nextTickClusterStartIndex;

    public ParallelCraftingCpuGrid(IGrid grid) {
        this.grid = grid;
    }

    public void setClusters(Collection<ParallelCraftingCpuCluster> nextClusters) {
        Set<ParallelCraftingCpuCluster> clusterSnapshot = new LinkedHashSet<>();
        if (nextClusters != null) {
            clusterSnapshot.addAll(nextClusters);
        }

        Set<ParallelCraftingCpuCluster> previousClusters = new LinkedHashSet<>(clusters);
        if (previousClusters.equals(clusterSnapshot)) {
            return;
        }

        for (ParallelCraftingCpuCluster cluster : previousClusters) {
            if (!clusterSnapshot.contains(cluster)) {
                cluster.detachGridContext();
                removeClusterLanes(cluster);
            }
        }

        clusters.clear();
        clusters.addAll(clusterSnapshot);
        for (ParallelCraftingCpuCluster cluster : clusters) {
            cluster.attachGridContext(this);
            if (!previousClusters.contains(cluster)) {
                refreshClusterLanes(cluster);
            }
        }
        if (clusters.isEmpty()) {
            nextTickClusterStartIndex = 0;
        } else {
            nextTickClusterStartIndex = Math.floorMod(nextTickClusterStartIndex, clusters.size());
        }
    }

    public ICraftingSubmitResult submitJob(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src
    ) {
        if (job == null || job.simulation()) {
            return null;
        }

        if (target instanceof ParallelCraftingCPU parallelCpu) {
            if (!hasCpu(parallelCpu)) {
                return CraftingSubmitResult.CPU_OFFLINE;
            }
            if (parallelCpu.isActiveVirtualCpu()) {
                return CraftingSubmitResult.CPU_BUSY;
            }
            return submitToCluster(parallelCpu.cluster(), job, requestingMachine, src);
        }

        if (target == null) {
            return submitToAutoSelectedCluster(job, requestingMachine, prioritizePower, src);
        }

        return null;
    }

    public ICraftingSubmitResult submitPartialJob(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src
    ) {
        if (job == null || !job.simulation()) {
            return null;
        }

        if (target instanceof ParallelCraftingCPU parallelCpu) {
            if (!hasCpu(parallelCpu)) {
                return CraftingSubmitResult.CPU_OFFLINE;
            }
            if (parallelCpu.isActiveVirtualCpu()) {
                return CraftingSubmitResult.CPU_BUSY;
            }
            return submitPartialToCluster(parallelCpu.cluster(), job, requestingMachine, src);
        }

        if (target == null) {
            return submitPartialToAutoSelectedCluster(job, requestingMachine, prioritizePower, src);
        }

        return null;
    }

    public ICraftingSubmitResult getAutoSelectionFailure(ICraftingPlan job, IActionSource src) {
        AutoSelectionResult result = findAutoSelectionCluster(job, src, false);
        return result.hasAnyUnsuitable()
                ? CraftingSubmitResult.noSuitableCpu(result.unsuitableCpus())
                : null;
    }

    public long tick(IEnergyService energyGrid, appeng.me.service.CraftingService craftingService) {
        long nextTick = currentTick == Long.MIN_VALUE ? 0L : currentTick + 1L;
        currentTick = nextTick;
        submissionsThisTick = 0;

        long startedAt = System.nanoTime();
        ParallelCpuGridBudgetLedger budgetLedger = createBudgetLedger();
        budgetLedger.resetForTick(currentTick, startedAt);
        List<ParallelCraftingCpuCluster> clusterSnapshot = List.copyOf(clusters);
        if (clusterSnapshot.isEmpty()) {
            nextTickClusterStartIndex = 0;
            return 0L;
        }

        boolean hadActiveLane = false;
        boolean madeProgress = false;
        int startIndex = Math.floorMod(nextTickClusterStartIndex, clusterSnapshot.size());
        int visitedClusters = 0;
        for (int offset = 0; offset < clusterSnapshot.size(); offset++) {
            if (!budgetLedger.hasTimeBudget(System.nanoTime())) {
                break;
            }
            int clusterIndex = (startIndex + offset) % clusterSnapshot.size();
            ParallelCraftingCpuCluster cluster = clusterSnapshot.get(clusterIndex);
            visitedClusters = offset + 1;
            ParallelCraftingCpuCluster.TickResult tickResult = cluster.tick(
                    energyGrid,
                    craftingService,
                    currentTick,
                    budgetLedger
            );
            hadActiveLane |= tickResult.hadActiveLane();
            madeProgress |= tickResult.madeProgress();
            if (budgetLedger.isExhausted()) {
                break;
            }
        }
        nextTickClusterStartIndex = computeNextStartIndex(clusterSnapshot.size(), startIndex, visitedClusters);

        long latestChange = 0L;
        for (ParallelCraftingCpuCluster cluster : clusters) {
            latestChange = Math.max(latestChange, cluster.lastModifiedOnTick());
        }
        return latestChange;
    }

    public long insertIntoCpus(AEKey what, long amount, Actionable type, long alreadyInserted) {
        long remaining = amount - Math.max(0L, alreadyInserted);
        if (remaining <= 0L) {
            return 0L;
        }

        long inserted = waitingIndex.insertIntoLanesAndGetResult(what, remaining, type).physicalInserted();
        if (type == Actionable.MODULATE) {
            removeInactiveLanes();
        }
        return inserted;
    }

    public long getRequestedAmount(AEKey what) {
        return waitingIndex.getRequestedAmount(what);
    }

    public boolean isRequesting(AEKey what) {
        return getRequestedAmount(what) > 0L;
    }

    public boolean isRequestingAny() {
        return waitingIndex.isRequestingAny();
    }

    public void appendCurrentlyCrafting(Set<AEKey> target) {
        if (target == null) {
            return;
        }
        waitingIndex.appendRequestingKeys(target);
    }

    public Set<AEKey> consumeChangedRequestKeys() {
        return waitingIndex.consumeChangedPresenceKeys();
    }

    public boolean hasCpu(ICraftingCPU cpu) {
        if (!(cpu instanceof ParallelCraftingCPU parallelCpu)) {
            return false;
        }
        return clusters.contains(parallelCpu.cluster()) && parallelCpu.cluster().hasVisibleCpu(parallelCpu);
    }

    void refreshLane(ParallelCraftingLane lane) {
        waitingIndex.refreshLane(lane);
    }

    void removeLane(ParallelCraftingLane lane) {
        waitingIndex.removeLane(lane);
    }

    private void removeInactiveLanes() {
        for (ParallelCraftingLane lane : waitingIndex.consumeInactiveLanes()) {
            if (lane instanceof ParallelCraftingLaneState laneState) {
                laneState.cluster().removeLane(laneState);
            } else {
                removeLane(lane);
            }
        }
    }

    private ICraftingSubmitResult submitToCluster(
            ParallelCraftingCpuCluster cluster,
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            IActionSource src
    ) {
        var settings = ParallelCraftingCpuConfig.current();
        if (submissionsThisTick >= settings.maxSubmissionsPerTickPerGrid()) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (activeLaneCount() >= settings.maxInternalLanesPerGrid()) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        submissionsThisTick++;
        return cluster.submitJob(grid, job, requestingMachine, src, currentTick);
    }

    private ICraftingSubmitResult submitPartialToCluster(
            ParallelCraftingCpuCluster cluster,
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            IActionSource src
    ) {
        var settings = ParallelCraftingCpuConfig.current();
        if (submissionsThisTick >= settings.maxSubmissionsPerTickPerGrid()) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (activeLaneCount() >= settings.maxInternalLanesPerGrid()) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        submissionsThisTick++;
        return cluster.submitPartialJob(grid, job, requestingMachine, src, currentTick);
    }

    private ICraftingSubmitResult submitToAutoSelectedCluster(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            boolean prioritizePower,
            IActionSource src
    ) {
        AutoSelectionResult result = findAutoSelectionCluster(job, src, prioritizePower);
        if (result.selectedCluster() == null) {
            return result.hasAnyUnsuitable()
                    ? CraftingSubmitResult.noSuitableCpu(result.unsuitableCpus())
                    : null;
        }
        return submitToCluster(result.selectedCluster(), job, requestingMachine, src);
    }

    private ICraftingSubmitResult submitPartialToAutoSelectedCluster(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            boolean prioritizePower,
            IActionSource src
    ) {
        AutoSelectionResult result = findAutoSelectionCluster(job, src, prioritizePower);
        if (result.selectedCluster() == null) {
            return result.hasAnyUnsuitable()
                    ? CraftingSubmitResult.noSuitableCpu(result.unsuitableCpus())
                    : null;
        }
        return submitPartialToCluster(result.selectedCluster(), job, requestingMachine, src);
    }

    private AutoSelectionResult findAutoSelectionCluster(
            ICraftingPlan job,
            IActionSource src,
            boolean prioritizePower
    ) {
        var settings = ParallelCraftingCpuConfig.current();
        int offline = 0;
        int busy = 0;
        int tooSmall = 0;
        int excluded = 0;
        if (submissionsThisTick >= settings.maxSubmissionsPerTickPerGrid()
                || activeLaneCount() >= settings.maxInternalLanesPerGrid()) {
            busy = clusters.size();
            return new AutoSelectionResult(null, offline, busy, tooSmall, excluded);
        }

        Comparator<ParallelCraftingCpuCluster> comparator = autoSelectionComparator(src, prioritizePower);
        @Nullable ParallelCraftingCpuCluster selectedCluster = null;
        for (ParallelCraftingCpuCluster cluster : clusters) {
            if (!cluster.canProcessJobs()) {
                offline++;
                continue;
            }
            if (!cluster.hasSubmissionCapacity()) {
                busy++;
                continue;
            }
            if (!cluster.canBeAutoSelectedFor(src)) {
                excluded++;
                continue;
            }
            if (selectedCluster == null || comparator.compare(cluster, selectedCluster) < 0) {
                selectedCluster = cluster;
            }
        }

        return new AutoSelectionResult(selectedCluster, offline, busy, tooSmall, excluded);
    }

    private static Comparator<ParallelCraftingCpuCluster> autoSelectionComparator(
            IActionSource src,
            boolean prioritizePower
    ) {
        Comparator<ParallelCraftingCpuCluster> processorComparator = Comparator
                .comparingInt(ParallelCraftingCpuCluster::advertisedCoProcessors);
        if (prioritizePower) {
            processorComparator = processorComparator.reversed();
        }
        return Comparator
                .comparing((ParallelCraftingCpuCluster cluster) -> cluster.isPreferredFor(src))
                .reversed()
                .thenComparing(processorComparator)
                .thenComparingInt(ParallelCraftingCpuCluster::activeLaneCount);
    }

    private int activeLaneCount() {
        int count = 0;
        for (ParallelCraftingCpuCluster cluster : clusters) {
            count = saturatedAdd(count, cluster.activeLaneCount());
        }
        return count;
    }

    private void refreshClusterLanes(ParallelCraftingCpuCluster cluster) {
        Set<ParallelCraftingLane> lanes = new LinkedHashSet<>();
        cluster.appendActiveLanes(lanes);
        for (ParallelCraftingLane lane : lanes) {
            waitingIndex.refreshLane(lane);
        }
    }

    private void removeClusterLanes(ParallelCraftingCpuCluster cluster) {
        Set<ParallelCraftingLane> lanes = new LinkedHashSet<>();
        cluster.appendActiveLanes(lanes);
        for (ParallelCraftingLane lane : lanes) {
            waitingIndex.removeLane(lane);
        }
    }

    private static ParallelCpuGridBudgetLedger createBudgetLedger() {
        var settings = ParallelCraftingCpuConfig.current();
        return new ParallelCpuGridBudgetLedger(ParallelCpuGridBudgetLedger.Limits.fromSettings(settings));
    }

    private static int computeNextStartIndex(int size, int startIndex, int visitedCount) {
        if (size <= 0) {
            return 0;
        }
        if (visitedCount <= 0) {
            return Math.floorMod(startIndex, size);
        }
        return Math.floorMod(startIndex + visitedCount, size);
    }

    private static int saturatedAdd(int left, int right) {
        if (right > 0 && left >= Integer.MAX_VALUE - right) {
            return Integer.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private record AutoSelectionResult(
            @Nullable ParallelCraftingCpuCluster selectedCluster,
            int offline,
            int busy,
            int tooSmall,
            int excluded
    ) {
        private boolean hasAnyUnsuitable() {
            return offline > 0 || busy > 0 || tooSmall > 0 || excluded > 0;
        }

        private UnsuitableCpus unsuitableCpus() {
            return new UnsuitableCpus(offline, busy, tooSmall, excluded);
        }
    }
}
