package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.helpers.MachineSource;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.google.common.collect.ImmutableSet;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class ParallelCraftingCpuCluster {

    private final AE2ParallelCpuToolBlockEntity owner;
    private final MachineSource source;
    private final ParallelCraftingCPU remainingCapacityCpu = new ParallelCraftingCPU(this, null);
    private final Map<UUID, ParallelCraftingLaneState> lanes = new LinkedHashMap<>();
    private final Map<ICraftingPlan, ParallelCraftingLaneState> lanesByPlan = new IdentityHashMap<>();
    private final ArrayDeque<ParallelCraftingLaneState> runnableLanes = new ArrayDeque<>();
    private final PriorityQueue<ParallelCraftingLaneState> delayedLanes =
            new PriorityQueue<>((left, right) -> Long.compare(left.nextEligibleTick(), right.nextEligibleTick()));
    @Nullable
    private ParallelCraftingCpuGrid gridContext;
    private long lastModifiedOnTick;

    public ParallelCraftingCpuCluster(AE2ParallelCpuToolBlockEntity owner) {
        this.owner = owner;
        this.source = new MachineSource(owner);
    }

    public void attachGridContext(ParallelCraftingCpuGrid gridContext) {
        this.gridContext = gridContext;
    }

    public void detachGridContext() {
        this.gridContext = null;
    }

    public CpuSelectionMode getSelectionMode() {
        return owner.getSelectionMode();
    }

    public boolean canBeAutoSelectedFor(IActionSource src) {
        IActionSource effectiveSource = src == null ? source : src;
        return switch (getSelectionMode()) {
            case ANY -> true;
            case PLAYER_ONLY -> effectiveSource.player().isPresent();
            case MACHINE_ONLY -> effectiveSource.player().isEmpty();
        };
    }

    public boolean isPreferredFor(IActionSource src) {
        IActionSource effectiveSource = src == null ? source : src;
        return switch (getSelectionMode()) {
            case ANY -> false;
            case PLAYER_ONLY -> effectiveSource.player().isPresent();
            case MACHINE_ONLY -> effectiveSource.player().isEmpty();
        };
    }

    public boolean hasSubmissionCapacity() {
        return activeLaneCount() < ParallelCraftingCpuConfig.current().maxInternalLanesPerBlock();
    }

    public boolean canAdvertiseRemainingCapacityCpu() {
        return hasSubmissionCapacity() && storageBytes() > 0L;
    }

    public ICraftingSubmitResult submitJob(
            IGrid grid,
            ICraftingPlan plan,
            ICraftingRequester requester,
            IActionSource src,
            long currentTick,
            ParallelCpuMetrics metrics
    ) {
        if (!canProcessJobs()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (!hasSubmissionCapacity()) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (storageBytes() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }

        ParallelCraftingLaneState lane = new ParallelCraftingLaneState(this, UUID.randomUUID(), currentTick);
        ICraftingSubmitResult submitResult = lane.trySubmitJob(grid, plan, src == null ? source : src, requester);
        if (submitResult == null || !submitResult.successful()) {
            lane.flushPendingReinjectInputsWithoutBudget();
            return submitResult == null ? CraftingSubmitResult.CPU_OFFLINE : submitResult;
        }

        lanes.put(lane.laneId(), lane);
        lanesByPlan.put(plan, lane);
        lane.markRegistered();
        enqueueRunnable(lane);
        if (metrics != null) {
            metrics.recordSubmittedVirtualCpu();
        }
        return submitResult;
    }

    public void tick(
            IEnergyService energyGrid,
            appeng.me.service.CraftingService craftingService,
            ParallelCpuGridBudgetLedger budgetLedger,
            ParallelCpuProviderBackoff providerBackoff,
            ParallelCpuMetrics metrics,
            long currentTick
    ) {
        int lanesVisited = 0;
        int shardLimit = Math.max(1, ParallelCraftingCpuConfig.current().laneShardCount());
        promoteDelayedLanes(currentTick, shardLimit);
        while (!runnableLanes.isEmpty() && lanesVisited < shardLimit && !budgetLedger.isExhausted()) {
            ParallelCraftingLaneState lane = runnableLanes.removeFirst();
            lanesVisited++;
            if (!lane.markRunnableDequeued()) {
                continue;
            }
            if (!lanes.containsKey(lane.laneId())) {
                continue;
            }
            if (!lane.isLaneActive()) {
                removeLane(lane);
                continue;
            }

            if (metrics != null) {
                metrics.recordTickCraftingLogic();
            }
            ParallelCraftingLaneState.TickResult tickResult = lane.tick(
                    energyGrid,
                    craftingService,
                    budgetLedger,
                    providerBackoff,
                    metrics,
                    currentTick
            );
            if (!lane.isLaneActive()) {
                removeLane(lane);
            } else if (tickResult.zeroProgress()) {
                lane.delayUntil(currentTick + nextDelayTicks(lane, shardLimit));
                enqueueDelayed(lane);
            } else {
                enqueueRunnable(lane);
            }
        }
    }

    public void appendVisibleCpus(
            ImmutableSet.Builder<ICraftingCPU> builder,
            boolean advertiseRemainingCapacityCpu
    ) {
        for (ParallelCraftingLaneState lane : lanes.values()) {
            builder.add(lane.activeCpu());
        }
        if (advertiseRemainingCapacityCpu) {
            builder.add(remainingCapacityCpu);
        }
    }

    public boolean hasVisibleCpu(ParallelCraftingCPU cpu) {
        if (cpu == null || cpu.cluster() != this) {
            return false;
        }
        if (cpu == remainingCapacityCpu) {
            return canAdvertiseRemainingCapacityCpu();
        }
        ParallelCraftingLaneState lane = findLaneState(cpu.laneId());
        return lane != null && lane.activeCpu() == cpu;
    }

    public void appendActiveVisibleCpus(Collection<ParallelCraftingCPU> target) {
        if (target == null) {
            return;
        }
        for (ParallelCraftingLaneState lane : lanes.values()) {
            target.add(lane.activeCpu());
        }
    }

    public void appendActiveLaneLinks(Collection<CraftingLink> target) {
        if (target == null || lanes.isEmpty()) {
            return;
        }
        for (ParallelCraftingLaneState lane : lanes.values()) {
            lane.appendServiceLinks(target);
        }
    }

    public void appendActiveLanes(Collection<ParallelCraftingLane> target) {
        if (target == null) {
            return;
        }
        target.addAll(lanes.values());
    }

    public int activeLaneCount() {
        return lanes.size();
    }

    public boolean isCraftActive(@Nullable UUID craftingId) {
        ParallelCraftingLaneState lane = findLaneState(craftingId);
        return lane != null && lane.isLaneActive();
    }

    @Nullable
    public ParallelCraftingLane findLaneByCraftingId(@Nullable UUID craftingId) {
        return findLaneState(craftingId);
    }

    @Nullable
    public ParallelCraftingCPU findActiveCpuByCraftingId(@Nullable UUID craftingId) {
        ParallelCraftingLaneState lane = findLaneState(craftingId);
        return lane == null ? null : lane.activeCpu();
    }

    public long getRequestedAmountForCraft(@Nullable UUID craftingId, @Nullable AEKey what) {
        ParallelCraftingLaneState lane = findLaneState(craftingId);
        return lane == null ? 0L : lane.logic().getWaitingFor(what);
    }

    public long insertIntoWaitingForCraft(
            @Nullable UUID craftingId,
            @Nullable AEKey what,
            long amount,
            Actionable mode
    ) {
        return insertIntoWaitingForCraft(craftingId, what, amount, mode, false);
    }

    public long insertIntoWaitingForCraft(
            @Nullable UUID craftingId,
            @Nullable AEKey what,
            long amount,
            Actionable mode,
            boolean preferBufferFinalOutput
    ) {
        ParallelCraftingLaneState lane = findLaneState(craftingId);
        if (lane == null) {
            return 0L;
        }
        long accepted = lane.logic().insert(what, amount, mode, preferBufferFinalOutput);
        if (accepted > 0L && mode == Actionable.MODULATE) {
            refreshLaneState(lane);
            wakeLane(lane);
        }
        return accepted;
    }

    @Nullable
    public UUID findCraftingIdForPlan(@Nullable ICraftingPlan job) {
        ParallelCraftingLaneState lane = job == null ? null : lanesByPlan.get(job);
        if (lane == null || !lane.isLaneActive()) {
            return null;
        }
        return lane.laneId();
    }

    public ParallelCpuMetrics.Snapshot metricsSnapshotForTest() {
        return gridContext == null ? new ParallelCpuMetrics().snapshot() : gridContext.metricsSnapshot();
    }

    public long storageBytes() {
        return ParallelCraftingCpuConfig.current().storageBytes();
    }

    int advertisedCoProcessors() {
        return ParallelCraftingCpuConfig.current().coProcessorsPerVirtualCpu();
    }

    public ParallelCraftingCPU remainingCapacityCpu() {
        return remainingCapacityCpu;
    }

    public CraftingStatus createMenuStatus(ParallelCraftingLaneState lane) {
        if (lane == null) {
            return CraftingStatus.EMPTY;
        }

        ParallelCraftingCpuLogic logic = lane.logic();
        KeyCounter allItems = new KeyCounter();
        logic.getAllItems(allItems);
        List<CraftingStatusEntry> entries = new ArrayList<>(allItems.size());
        long serial = 1L;
        for (AEKey what : allItems.keySet()) {
            entries.add(new CraftingStatusEntry(
                    serial++,
                    what,
                    logic.getStored(what),
                    logic.getWaitingFor(what),
                    logic.getPendingOutputs(what)
            ));
        }

        var elapsedTimeTracker = logic.getElapsedTimeTracker();
        return new CraftingStatus(
                true,
                elapsedTimeTracker.getElapsedTime(),
                elapsedTimeTracker.getRemainingItemCount(),
                elapsedTimeTracker.getStartItemCount(),
                entries,
                logic.isJobSuspended()
        );
    }

    public IGrid grid() {
        return owner.getMainNode().getGrid();
    }

    public net.minecraft.world.level.Level level() {
        return owner.getLevel();
    }

    public IActionSource source() {
        return source;
    }

    public boolean isActive() {
        return owner.isParallelCpuProviderActive();
    }

    public boolean canProcessJobs() {
        return owner.canProcessParallelCpuJobs();
    }

    public AE2ParallelCpuToolBlockEntity owner() {
        return owner;
    }

    long lastModifiedOnTick() {
        return lastModifiedOnTick;
    }

    void cancelLane(@Nullable ParallelCraftingLaneState lane) {
        if (lane == null) {
            return;
        }
        lane.cancel();
        removeLane(lane);
    }

    void removeLane(ParallelCraftingLaneState lane) {
        if (lane == null || lanes.remove(lane.laneId()) == null) {
            return;
        }
        lanesByPlan.values().removeIf(existing -> existing == lane);
        if (gridContext != null) {
            gridContext.removeLane(lane);
        }
    }

    void refreshLaneState(ParallelCraftingLaneState lane) {
        if (lane == null) {
            return;
        }
        lastModifiedOnTick = Math.max(lastModifiedOnTick, lane.getLastModifiedOnTick());
        if (gridContext != null) {
            gridContext.refreshLane(lane);
        }
    }

    void wakeLane(ParallelCraftingLaneState lane) {
        if (lane == null || !lanes.containsKey(lane.laneId())) {
            return;
        }
        enqueueRunnable(lane);
    }

    @Nullable
    ParallelCraftingLaneState findLaneState(@Nullable UUID craftingId) {
        if (craftingId == null) {
            return null;
        }
        return lanes.get(craftingId);
    }

    private void promoteDelayedLanes(long currentTick, int shardLimit) {
        int promoted = 0;
        int limit = Math.max(1, shardLimit);
        while (promoted < limit && !delayedLanes.isEmpty() && delayedLanes.peek().nextEligibleTick() <= currentTick) {
            ParallelCraftingLaneState lane = delayedLanes.poll();
            promoted++;
            if (!lane.markDelayedDequeued()) {
                continue;
            }
            if (lanes.containsKey(lane.laneId())) {
                enqueueRunnable(lane);
            }
        }
    }

    private void enqueueRunnable(ParallelCraftingLaneState lane) {
        if (lane != null && lane.markRunnableQueued()) {
            runnableLanes.addLast(lane);
        }
    }

    private void enqueueDelayed(ParallelCraftingLaneState lane) {
        if (lane != null && lane.markDelayedQueued()) {
            delayedLanes.add(lane);
        }
    }

    private static long nextDelayTicks(ParallelCraftingLaneState lane, int shardLimit) {
        long shardWindow = Math.max(1L, shardLimit);
        long laneHash = lane.laneId().getLeastSignificantBits() ^ lane.laneId().getMostSignificantBits();
        return 1L + Math.floorMod(laneHash, shardWindow);
    }

    public long storedAmountForTest(@Nullable AEKey what) {
        long stored = 0L;
        for (ParallelCraftingLaneState lane : lanes.values()) {
            stored = saturatedAdd(stored, lane.storedAmountForTest(what));
        }
        return stored;
    }

    public long waitingAmountForTest(@Nullable AEKey what) {
        long waiting = 0L;
        for (ParallelCraftingLaneState lane : lanes.values()) {
            waiting = saturatedAdd(waiting, lane.waitingAmountForTest(what));
        }
        return waiting;
    }

    public long pendingAmountForTest(@Nullable AEKey what) {
        long pending = 0L;
        for (ParallelCraftingLaneState lane : lanes.values()) {
            pending = saturatedAdd(pending, lane.pendingAmountForTest(what));
        }
        return pending;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
