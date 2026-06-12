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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ParallelCraftingCpuGrid {

    private final IGrid grid;
    private final Set<ParallelCraftingCpuCluster> clusters = new LinkedHashSet<>();
    private final ParallelCpuWaitingIndex waitingIndex = new ParallelCpuWaitingIndex();
    private final ParallelCpuProviderBackoff providerBackoff = new ParallelCpuProviderBackoff();
    private final ParallelCpuMetrics metrics = new ParallelCpuMetrics();
    private long currentTick = Long.MIN_VALUE;
    private int submissionsThisTick;
    private int nextClusterTickCursor;

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
            updateMetrics();
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
        updateMetrics();
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

        var settings = ParallelCraftingCpuConfig.current();
        var budgetLedger = new ParallelCpuGridBudgetLedger(new ParallelCpuGridBudgetLedger.Limits(
                settings.maxPatternPushesPerTickPerGrid(),
                settings.maxProviderChecksPerTickPerGrid(),
                settings.maxPatternPushesPerTickPerGrid(),
                settings.maxPatternPushesPerTickPerGrid(),
                settings.tickBudgetNanosPerGrid()
        ));
        long startedAt = System.nanoTime();
        budgetLedger.resetForTick(currentTick, startedAt);

        List<ParallelCraftingCpuCluster> clusterSnapshot = List.copyOf(clusters);
        if (clusterSnapshot.isEmpty()) {
            metrics.recordTickNanos(System.nanoTime() - startedAt);
            updateMetrics();
            return 0L;
        }

        int visitedClusters = 0;
        int cursor = Math.floorMod(nextClusterTickCursor, clusterSnapshot.size());
        while (visitedClusters < clusterSnapshot.size() && !budgetLedger.isExhausted()) {
            int clusterIndex = (cursor + visitedClusters) % clusterSnapshot.size();
            ParallelCraftingCpuCluster cluster = clusterSnapshot.get(clusterIndex);
            cluster.tick(energyGrid, craftingService, budgetLedger, providerBackoff, metrics, currentTick);
            if (budgetLedger.isExhausted()) {
                nextClusterTickCursor = (clusterIndex + 1) % clusterSnapshot.size();
                break;
            }
            visitedClusters++;
        }
        if (!budgetLedger.isExhausted()) {
            nextClusterTickCursor = (cursor + 1) % clusterSnapshot.size();
        }

        metrics.recordLedgerExhaustion(budgetLedger);
        metrics.recordTickNanos(System.nanoTime() - startedAt);
        updateMetrics();

        long latestChange = 0L;
        for (ParallelCraftingCpuCluster cluster : clusters) {
            latestChange = Math.max(latestChange, cluster.lastModifiedOnTick());
        }
        return latestChange;
    }

    public long insertIntoCpus(AEKey what, long amount, Actionable type, long alreadyInserted) {
        return insertIntoCpus(what, amount, type, alreadyInserted, false);
    }

    public long insertIntoCpus(
            AEKey what,
            long amount,
            Actionable type,
            long alreadyInserted,
            boolean preferBufferFinalOutput
    ) {
        long remaining = amount - Math.max(0L, alreadyInserted);
        if (remaining <= 0L) {
            return 0L;
        }

        long inserted = waitingIndex.insertIntoLanes(what, remaining, type, metrics, preferBufferFinalOutput);
        long remainingAfterIndex = remaining - inserted;
        if (remainingAfterIndex > 0L && activeLaneCount() > 0) {
            inserted += insertIntoActiveLanesFallback(what, remainingAfterIndex, type, preferBufferFinalOutput);
        }
        if (type == Actionable.MODULATE && inserted > 0L) {
            removeInactiveLanes();
        }
        return inserted;
    }

    public long getRequestedAmount(AEKey what) {
        return waitingIndex.getRequestedAmount(what);
    }

    public boolean isRequesting(AEKey what) {
        return waitingIndex.isRequesting(what);
    }

    public boolean isRequestingAny() {
        return waitingIndex.isRequestingAny();
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

    public ParallelCpuMetrics.Snapshot metricsSnapshot() {
        return metrics.snapshot();
    }

    void refreshLane(ParallelCraftingLane lane) {
        waitingIndex.refreshLane(lane);
        waitingIndex.copyMetricsTo(metrics);
    }

    void removeLane(ParallelCraftingLane lane) {
        waitingIndex.removeLane(lane);
        waitingIndex.copyMetricsTo(metrics);
        metrics.recordCompletedVirtualCpu();
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

    private long insertIntoActiveLanesFallback(
            AEKey what,
            long amount,
            Actionable type,
            boolean preferBufferFinalOutput
    ) {
        long inserted = 0L;
        Set<ParallelCraftingLane> activeLanes = new LinkedHashSet<>();
        for (ParallelCraftingCpuCluster cluster : clusters) {
            cluster.appendActiveLanes(activeLanes);
        }
        for (ParallelCraftingLane lane : activeLanes) {
            if (inserted >= amount) {
                break;
            }
            long accepted = Math.max(0L,
                    lane.insertIntoWaiting(what, amount - inserted, type, preferBufferFinalOutput));
            if (lane instanceof ParallelCraftingLaneState laneState) {
                refreshLane(laneState);
                if (accepted > 0L && type == Actionable.MODULATE) {
                    laneState.cluster().wakeLane(laneState);
                }
            }
            if (accepted > 0L) {
                inserted += accepted;
            }
        }
        return inserted;
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
        return cluster.submitJob(grid, job, requestingMachine, src, currentTick, metrics);
    }

    private ICraftingSubmitResult submitToAutoSelectedCluster(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            boolean prioritizePower,
            IActionSource src
    ) {
        AutoSelectionResult result = findAutoSelectionCluster(job, src, prioritizePower);
        if (result.selectedCluster() == null) {
            return null;
        }
        ICraftingSubmitResult submitResult = submitToCluster(result.selectedCluster(), job, requestingMachine, src);
        return submitResult != null && submitResult.successful() ? submitResult : null;
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

        List<ParallelCraftingCpuCluster> validClusters = new ArrayList<>(clusters.size());
        for (ParallelCraftingCpuCluster cluster : clusters) {
            if (!cluster.canProcessJobs()) {
                offline++;
                continue;
            }
            if (!cluster.hasSubmissionCapacity()) {
                busy++;
                continue;
            }
            if (job != null && cluster.storageBytes() < job.bytes()) {
                tooSmall++;
                continue;
            }
            if (!cluster.canBeAutoSelectedFor(src)) {
                excluded++;
                continue;
            }
            validClusters.add(cluster);
        }

        if (validClusters.isEmpty()) {
            return new AutoSelectionResult(null, offline, busy, tooSmall, excluded);
        }

        validClusters.sort(autoSelectionComparator(src, prioritizePower));
        return new AutoSelectionResult(validClusters.getFirst(), offline, busy, tooSmall, excluded);
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
                .thenComparingLong(ParallelCraftingCpuCluster::storageBytes)
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
        waitingIndex.copyMetricsTo(metrics);
    }

    private void removeClusterLanes(ParallelCraftingCpuCluster cluster) {
        Set<ParallelCraftingLane> lanes = new LinkedHashSet<>();
        cluster.appendActiveLanes(lanes);
        for (ParallelCraftingLane lane : lanes) {
            waitingIndex.removeLane(lane);
        }
        waitingIndex.copyMetricsTo(metrics);
    }

    private void updateMetrics() {
        waitingIndex.copyMetricsTo(metrics);
        int remainingCapacityCpuCount = 0;
        for (ParallelCraftingCpuCluster cluster : clusters) {
            if (cluster.canAdvertiseRemainingCapacityCpu()) {
                remainingCapacityCpuCount = saturatedAdd(remainingCapacityCpuCount, 1);
            }
        }
        metrics.setCpuGauges(activeLaneCount(), remainingCapacityCpuCount);
    }

    private static int saturatedAdd(int left, int right) {
        if (right > 0 && left >= Integer.MAX_VALUE - right) {
            return Integer.MAX_VALUE;
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
