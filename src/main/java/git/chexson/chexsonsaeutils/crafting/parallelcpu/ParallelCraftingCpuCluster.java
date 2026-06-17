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
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class ParallelCraftingCpuCluster {

    private final AE2ParallelCpuToolBlockEntity owner;
    private final MachineSource source;
    private final ParallelCraftingCPU remainingCapacityCpu = new ParallelCraftingCPU(this, null);
    private final Map<UUID, ParallelCraftingLaneState> lanes = new LinkedHashMap<>();
    private final Map<ICraftingPlan, ParallelCraftingLaneState> lanesByPlan = new IdentityHashMap<>();
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
        ICraftingSubmitResult submitResult = lane.trySubmitJob(
                grid,
                plan,
                src == null ? source : src,
                requester
        );
        if (submitResult == null || !submitResult.successful()) {
            return submitResult == null ? CraftingSubmitResult.CPU_OFFLINE : submitResult;
        }

        lanes.put(lane.laneId(), lane);
        lanesByPlan.put(plan, lane);
        lane.markRegistered();
        if (metrics != null) {
            metrics.recordSubmittedVirtualCpu();
        }
        return submitResult;
    }

    public ICraftingSubmitResult submitPartialJob(
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
        ICraftingSubmitResult submitResult = lane.trySubmitPartialJob(
                grid,
                plan,
                src == null ? source : src,
                requester
        );
        if (submitResult == null || !submitResult.successful()) {
            return submitResult == null ? CraftingSubmitResult.CPU_OFFLINE : submitResult;
        }

        lanes.put(lane.laneId(), lane);
        lanesByPlan.put(plan, lane);
        lane.markRegistered();
        if (metrics != null) {
            metrics.recordSubmittedVirtualCpu();
        }
        return submitResult;
    }

    public void tick(
            IEnergyService energyGrid,
            appeng.me.service.CraftingService craftingService,
            ParallelCpuMetrics metrics,
            long currentTick
    ) {
        for (ParallelCraftingLaneState lane : List.copyOf(lanes.values())) {
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
            lane.tick(
                    energyGrid,
                    craftingService,
                    metrics,
                    currentTick
            );
            if (!lane.isLaneActive()) {
                removeLane(lane);
            } else {
                refreshLaneState(lane);
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

    long insertIntoActiveLanes(
            @Nullable AEKey what,
            long amount,
            Actionable mode,
            @Nullable ParallelCpuMetrics metrics
    ) {
        return insertIntoActiveLanes(
                List.copyOf(lanes.values()),
                what,
                amount,
                mode,
                metrics,
                lane -> {
                    if (!(lane instanceof ParallelCraftingLaneState laneState)) {
                        return;
                    }
                    refreshLaneState(laneState);
                    if (laneState.isLaneActive()) {
                        wakeLane(laneState);
                    } else {
                        removeLane(laneState);
                    }
                },
                lane -> {
                    if (lane instanceof ParallelCraftingLaneState laneState) {
                        removeLane(laneState);
                    }
                }
        );
    }

    static long insertIntoActiveLanes(
            Iterable<? extends ParallelCraftingLane> activeLanes,
            @Nullable AEKey what,
            long amount,
            Actionable mode,
            @Nullable ParallelCpuMetrics metrics,
            @Nullable Consumer<ParallelCraftingLane> modulationConsumer,
            @Nullable Consumer<ParallelCraftingLane> inactiveLaneConsumer
    ) {
        if (what == null || amount <= 0L || mode == null) {
            return 0L;
        }

        long inserted = 0L;
        long accounted = 0L;
        for (ParallelCraftingLane lane : activeLanes) {
            if (accounted >= amount) {
                break;
            }
            if (lane == null || !lane.isLaneActive()) {
                if (inactiveLaneConsumer != null && lane != null) {
                    inactiveLaneConsumer.accept(lane);
                }
                continue;
            }

            long remaining = amount - accounted;
            long requestedBefore = mode == Actionable.MODULATE ? lane.getRequestedAmount(what) : 0L;
            ParallelCraftingLane.WaitingInsertResult accepted = lane.insertIntoWaitingAndGetResult(
                    what,
                    remaining,
                    mode
            );
            long acceptedPhysical = Math.min(accepted.physicalInserted(), remaining);
            long acceptedAccounted = Math.min(accepted.accounted(), remaining);
            long requestedAfter = mode == Actionable.MODULATE ? lane.getRequestedAmount(what) : requestedBefore;
            if (acceptedAccounted > 0L) {
                inserted = safeAdd(inserted, acceptedPhysical);
                accounted = safeAdd(accounted, acceptedAccounted);
                if (metrics != null) {
                    metrics.recordIndexedInsert(acceptedAccounted);
                }
            }
            if (mode == Actionable.MODULATE
                    && (acceptedAccounted > 0L || requestedAfter < requestedBefore || !lane.isLaneActive())
                    && modulationConsumer != null) {
                modulationConsumer.accept(lane);
            }
        }
        return inserted;
    }

    public void appendActiveLaneLinks(Collection<CraftingLink> target) {
        if (target == null || lanes.isEmpty()) {
            return;
        }
        for (ParallelCraftingLaneState lane : lanes.values()) {
            lane.appendServiceLinks(target);
        }
    }

    public void restoreRequesterLinks(Iterable<ICraftingRequester> requesters) {
        if (requesters == null || lanes.isEmpty()) {
            return;
        }
        for (ParallelCraftingLaneState lane : lanes.values()) {
            lane.restoreRequesterLink(requesters);
        }
    }

    public void appendActiveLanes(Collection<ParallelCraftingLane> target) {
        if (target == null) {
            return;
        }
        target.addAll(lanes.values());
    }

    long getRequestedAmount(@Nullable AEKey what) {
        long requested = 0L;
        for (ParallelCraftingLaneState lane : lanes.values()) {
            requested = safeAdd(requested, lane.getRequestedAmount(what));
        }
        return requested;
    }

    boolean isRequestingAny() {
        for (ParallelCraftingLaneState lane : lanes.values()) {
            if (lane.hasWaitingFor()) {
                return true;
            }
        }
        return false;
    }

    void appendWaitingFor(Set<AEKey> target) {
        appendWaitingFor(lanes.values(), target);
    }

    static long getRequestedAmount(Iterable<? extends ParallelCraftingLane> activeLanes, @Nullable AEKey what) {
        if (activeLanes == null || what == null) {
            return 0L;
        }
        long requested = 0L;
        for (ParallelCraftingLane lane : activeLanes) {
            if (lane == null || !lane.isLaneActive()) {
                continue;
            }
            requested = safeAdd(requested, lane.getRequestedAmount(what));
        }
        return requested;
    }

    static boolean isRequestingAny(Iterable<? extends ParallelCraftingLane> activeLanes) {
        if (activeLanes == null) {
            return false;
        }
        for (ParallelCraftingLane lane : activeLanes) {
            if (lane == null || !lane.isLaneActive()) {
                continue;
            }
            for (var entry : lane.getWaitingStacks()) {
                if (entry.getKey() != null && entry.getLongValue() > 0L) {
                    return true;
                }
            }
        }
        return false;
    }

    static void appendWaitingFor(Iterable<? extends ParallelCraftingLane> activeLanes, Set<AEKey> target) {
        if (target == null) {
            return;
        }
        if (activeLanes == null) {
            return;
        }
        for (ParallelCraftingLane lane : activeLanes) {
            if (lane == null || !lane.isLaneActive()) {
                continue;
            }
            for (var entry : lane.getWaitingStacks()) {
                if (entry.getKey() != null && entry.getLongValue() > 0L) {
                    target.add(entry.getKey());
                }
            }
        }
    }

    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        lanes.clear();
        lanesByPlan.clear();
        ListTag laneTags = data.getList("parallelCpuLanes", Tag.TAG_COMPOUND);
        for (int index = 0; index < laneTags.size(); index++) {
            ParallelCraftingLaneState lane = ParallelCraftingLaneState.readFromNBT(
                    this,
                    laneTags.getCompound(index),
                    registries,
                    lastModifiedOnTick
            );
            if (!lane.isLaneActive()) {
                continue;
            }
            lanes.put(lane.laneId(), lane);
            lane.markRegistered();
            if (gridContext != null) {
                gridContext.refreshLane(lane);
            }
        }
    }

    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        ListTag laneTags = new ListTag();
        for (ParallelCraftingLaneState lane : lanes.values()) {
            if (lane != null && lane.isLaneActive()) {
                laneTags.add(lane.writeToNBT(registries));
            }
        }
        data.put("parallelCpuLanes", laneTags);
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

    public long getBufferedAmountForCraft(@Nullable UUID craftingId, @Nullable AEKey what) {
        ParallelCraftingLaneState lane = findLaneState(craftingId);
        return lane == null ? 0L : lane.logic().getBufferedAmount(what);
    }

    public long extractBufferedForCraft(
            @Nullable UUID craftingId,
            @Nullable AEKey what,
            long amount,
            Actionable mode
    ) {
        ParallelCraftingLaneState lane = findLaneState(craftingId);
        if (lane == null) {
            return 0L;
        }
        long extracted = lane.logic().extractBuffered(what, amount, mode);
        notifyBufferedInventoryChanged(lane, extracted, mode);
        return extracted;
    }

    public long insertBufferedForCraft(
            @Nullable UUID craftingId,
            @Nullable AEKey what,
            long amount,
            Actionable mode
    ) {
        ParallelCraftingLaneState lane = findLaneState(craftingId);
        if (lane == null) {
            return 0L;
        }
        long inserted = lane.logic().insertBuffered(what, amount, mode);
        notifyBufferedInventoryChanged(lane, inserted, mode);
        return inserted;
    }

    public long insertIntoWaitingForCraft(
            @Nullable UUID craftingId,
            @Nullable AEKey what,
            long amount,
            Actionable mode
    ) {
        ParallelCraftingLaneState lane = findLaneState(craftingId);
        if (lane == null) {
            return 0L;
        }
        long accepted = lane.logic().insert(what, amount, mode);
        if (mode == Actionable.MODULATE && (accepted > 0L || !lane.isLaneActive())) {
            refreshLaneState(lane);
            if (!lane.isLaneActive()) {
                removeLane(lane);
            } else {
                wakeLane(lane);
            }
        }
        return accepted;
    }

    public long insertIntoWaitingForCraftAndGetAccountedAmount(
            @Nullable UUID craftingId,
            @Nullable AEKey what,
            long amount,
            Actionable mode
    ) {
        ParallelCraftingLaneState lane = findLaneState(craftingId);
        if (lane == null) {
            return 0L;
        }
        long accounted = lane.logic().insertAndGetAccountedAmount(what, amount, mode);
        if (mode == Actionable.MODULATE && (accounted > 0L || !lane.isLaneActive())) {
            refreshLaneState(lane);
            if (!lane.isLaneActive()) {
                removeLane(lane);
            } else {
                wakeLane(lane);
            }
        }
        return accounted;
    }

    private void notifyBufferedInventoryChanged(
            ParallelCraftingLaneState lane,
            long changedAmount,
            Actionable mode
    ) {
        if (mode != Actionable.MODULATE || changedAmount <= 0L) {
            return;
        }
        refreshLaneState(lane);
        if (lane.isLaneActive()) {
            wakeLane(lane);
        } else {
            removeLane(lane);
        }
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
        refreshLaneState(lane);
    }

    private static long safeAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    @Nullable
    ParallelCraftingLaneState findLaneState(@Nullable UUID craftingId) {
        if (craftingId == null) {
            return null;
        }
        return lanes.get(craftingId);
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
