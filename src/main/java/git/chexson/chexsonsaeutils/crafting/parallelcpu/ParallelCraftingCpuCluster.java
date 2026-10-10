package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
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
import appeng.core.AELog;
import appeng.me.helpers.MachineSource;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.google.common.collect.ImmutableSet;
import git.chexson.chexsonsaeutils.crafting.hostcpu.ParallelCpuHost;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilities;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalDrops;
import git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilitiesFactory;
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

public final class ParallelCraftingCpuCluster {

    private final ParallelCpuHost host;
    private final MachineSource source;
    private final ProviderBatchCapabilitiesFactory batchCapabilitiesFactory;
    private final ParallelCraftingCPU remainingCapacityCpu = new ParallelCraftingCPU(this, null);
    private final ParallelCpuProviderBackoff providerBackoff = new ParallelCpuProviderBackoff(
            ChexsonsaeutilsCompatibilityConfig.intValue(
                    ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_BASE_TICKS),
            ChexsonsaeutilsCompatibilityConfig.intValue(
                    ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_PROVIDER_BACKOFF_MAX_TICKS)
    );
    private final Map<UUID, ParallelCraftingLaneState> lanes = new LinkedHashMap<>();
    private final Map<ICraftingPlan, ParallelCraftingLaneState> lanesByPlan = new IdentityHashMap<>();
    @Nullable
    private ParallelCraftingCpuGrid gridContext;
    private long lastModifiedOnTick;
    @Nullable
    private CompoundTag pendingRestore;
    @Nullable
    private HolderLookup.Provider pendingRegistries;
    private int nextTickLaneStartIndex;

    public ParallelCraftingCpuCluster(ParallelCpuHost host,
            ProviderBatchCapabilitiesFactory batchCapabilitiesFactory) {
        this.host = host;
        this.source = new MachineSource(host);
        this.batchCapabilitiesFactory = batchCapabilitiesFactory;
    }

    /** 每条产线独立创建适配会话，网格只在适配壳首次创建时现取。 */
    ProviderBatchCapabilities createBatchCapabilities() {
        return batchCapabilitiesFactory.create(this::grid);
    }

    /** 诊断直接现取全场快照，不触发任何产线会话或宿主能力探测。 */
    DeclaredBatchTerminalDrops terminalDrops() {
        return batchCapabilitiesFactory.terminalDrops();
    }

    public void attachGridContext(ParallelCraftingCpuGrid gridContext) {
        restorePendingJobs();
        this.gridContext = gridContext;
    }

    public ParallelCraftingCpuGrid gridContext() {
        return gridContext;
    }

    public void detachGridContext() {
        this.gridContext = null;
        providerBackoff.clear();
        nextTickLaneStartIndex = 0;
    }

    public CpuSelectionMode getSelectionMode() {
        return host.getSelectionMode();
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
        return true;
    }

    public boolean canAdvertiseRemainingCapacityCpu() {
        return hasSubmissionCapacity();
    }

    public ICraftingSubmitResult submitJob(
            IGrid grid,
            ICraftingPlan plan,
            ICraftingRequester requester,
            IActionSource src,
            long currentTick
    ) {
        if (!canProcessJobs()) {
            return offlineSubmission(grid, "host-inactive");
        }
        if (!hasSubmissionCapacity()) {
            return CraftingSubmitResult.CPU_BUSY;
        }

        ParallelCraftingLaneState lane = new ParallelCraftingLaneState(this, UUID.randomUUID(), currentTick);
        ICraftingSubmitResult submitResult = lane.trySubmitJob(
                grid,
                plan,
                src == null ? source : src,
                requester
        );
        if (submitResult == null || !submitResult.successful()) {
            return submitResult == null ? offlineSubmission(grid, "lane-returned-null") : submitResult;
        }

        lanes.put(lane.laneId(), lane);
        lanesByPlan.put(plan, lane);
        lane.markRegistered();
        return submitResult;
    }

    public ICraftingSubmitResult submitPartialJob(
            IGrid grid,
            ICraftingPlan plan,
            ICraftingRequester requester,
            IActionSource src,
            long currentTick
    ) {
        if (!canProcessJobs()) {
            return offlineSubmission(grid, "partial-host-inactive");
        }
        if (!hasSubmissionCapacity()) {
            return CraftingSubmitResult.CPU_BUSY;
        }

        ParallelCraftingLaneState lane = new ParallelCraftingLaneState(this, UUID.randomUUID(), currentTick);
        ICraftingSubmitResult submitResult = lane.trySubmitPartialJob(
                grid,
                plan,
                src == null ? source : src,
                requester
        );
        if (submitResult == null || !submitResult.successful()) {
            return submitResult == null ? offlineSubmission(grid, "partial-lane-returned-null") : submitResult;
        }

        lanes.put(lane.laneId(), lane);
        lanesByPlan.put(plan, lane);
        lane.markRegistered();
        return submitResult;
    }

    public TickResult tick(
            IEnergyService energyGrid,
            appeng.me.service.CraftingService craftingService,
            long currentTick,
            ParallelCpuGridBudgetLedger budgetLedger
    ) {
        if (!canProcessJobs()) {
            providerBackoff.clear();
        }
        boolean hadActiveLane = false;
        boolean madeProgress = false;
        List<ParallelCraftingLaneState> laneSnapshot = List.copyOf(lanes.values());
        if (laneSnapshot.isEmpty()) {
            nextTickLaneStartIndex = 0;
            return new TickResult(false, false);
        }

        int startIndex = Math.floorMod(nextTickLaneStartIndex, laneSnapshot.size());
        int visitedLanes = 0;
        for (int offset = 0; offset < laneSnapshot.size(); offset++) {
            if (budgetLedger != null && !budgetLedger.hasTimeBudget(System.nanoTime())) {
                break;
            }
            int laneIndex = (startIndex + offset) % laneSnapshot.size();
            ParallelCraftingLaneState lane = laneSnapshot.get(laneIndex);
            visitedLanes = offset + 1;
            if (!lanes.containsKey(lane.laneId())) {
                continue;
            }
            if (!lane.isLaneActive()) {
                removeLane(lane);
                continue;
            }
            hadActiveLane = true;
            // lane 时间片：剩余预算按剩余 lane 均分，先跑完的 lane 让出
            // 余额——多 job（lane）并行公平，不再被第一个 lane 独占 20ms。
            if (budgetLedger != null) {
                budgetLedger.beginLaneSlice(
                        System.nanoTime(), laneSnapshot.size() - visitedLanes + 1);
            }
            boolean zeroProgress = lane.tick(
                    energyGrid,
                    craftingService,
                    currentTick,
                    budgetLedger,
                    providerBackoff
            );
            if (!zeroProgress) {
                madeProgress = true;
            }
            if (!lane.isLaneActive()) {
                removeLane(lane);
            } else {
                refreshLaneState(lane);
            }
            if (budgetLedger != null
                    && (budgetLedger.isExhausted() || budgetLedger.laneSliceExhausted(System.nanoTime()))) {
                continue;
            }
        }
        nextTickLaneStartIndex = computeNextStartIndex(laneSnapshot.size(), startIndex, visitedLanes);
        return new TickResult(hadActiveLane, madeProgress);
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

    /** 世界尚未绑定时保留原始任务，避免配方样板在缺少世界上下文时被丢弃。 */
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        pendingRestore = data.copy();
        pendingRegistries = registries;
        restorePendingJobs();
    }

    /** 宿主绑定世界后、节点入网前恢复任务，保证入网时能登记原有链接和等待索引。 */
    public void restorePendingJobs() {
        if (pendingRestore == null || level() == null) return;
        restoreJobs(pendingRestore, pendingRegistries);
        pendingRestore = null;
        pendingRegistries = null;
    }

    private void restoreJobs(CompoundTag data, HolderLookup.Provider registries) {
        lanes.clear();
        lanesByPlan.clear();
        providerBackoff.clear();
        nextTickLaneStartIndex = 0;
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
        if (pendingRestore != null) {
            data.put("parallelCpuLanes", pendingRestore.getList("parallelCpuLanes", Tag.TAG_COMPOUND).copy());
            return;
        }
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

    /**
     * AE2 接口要求 long，恒 MAX_VALUE 哨兵语义正确。副作用：CraftingCpuLogic 的
     * 字节门槛在并行 CPU 上恒通过，负值账目进 CPU 的唯一防线是规划出口的负值扫描。
     */
    public long storageBytes() {
        return Long.MAX_VALUE;
    }

    int advertisedCoProcessors() {
        return Integer.MAX_VALUE;
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
        return host.getGrid();
    }

    public net.minecraft.world.level.Level level() {
        return host.getLevel();
    }

    public IActionSource source() {
        return source;
    }

    public boolean isActive() {
        return host.isParallelCpuProviderActive();
    }

    public boolean canProcessJobs() {
        return host.canProcessParallelCpuJobs();
    }

    /** 只在拒绝提交时采集状态，区分注册丢失与节点运行条件；不改变离线判据或读取执行库存。 */
    ICraftingSubmitResult offlineSubmission(@Nullable IGrid submittingGrid, String reason) {
        var node = host.getActionableNode();
        var hostGrid = host.getGrid();
        var state = new OfflineSubmissionState(
                reason, host.isParallelCpuProviderActive(), host.canProcessParallelCpuJobs(), node != null,
                node == null ? null : node.isPowered(),
                node == null ? null : node.hasGridBooted(),
                node == null ? null : node.meetsChannelRequirements(),
                node == null ? 0 : node.getConnections().size(),
                hostGrid != null && hostGrid == submittingGrid,
                gridContext != null && gridContext.grid() == submittingGrid);
        AELog.warn("[chexsonsaeutils-submit] CPU_OFFLINE: %s", state);
        return new CraftingSubmitResult(null, CraftingSubmitErrorCode.CPU_OFFLINE, state);
    }

    /** 提交失败的服务端诊断快照；节点不存在时运行条件未知，不能冒充已确认的断电。 */
    record OfflineSubmissionState(String reason, boolean providerReady, boolean canProcess, boolean nodePresent,
            @Nullable Boolean powered, @Nullable Boolean booted, @Nullable Boolean channelsSatisfied,
            int connections, boolean sameGrid, boolean attachedToSubmittingGrid) {
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
        host.saveChanges();
        lanesByPlan.values().removeIf(existing -> existing == lane);
        providerBackoff.clear();
        if (lanes.isEmpty()) {
            nextTickLaneStartIndex = 0;
        } else {
            nextTickLaneStartIndex = Math.floorMod(nextTickLaneStartIndex, lanes.size());
        }
        if (gridContext != null) {
            gridContext.removeLane(lane);
        }
    }

    void refreshLaneState(ParallelCraftingLaneState lane) {
        if (lane == null) {
            return;
        }
        host.saveChanges();
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

    private static int computeNextStartIndex(int size, int startIndex, int visitedCount) {
        if (size <= 0) {
            return 0;
        }
        if (visitedCount <= 0) {
            return Math.floorMod(startIndex, size);
        }
        return Math.floorMod(startIndex + visitedCount, size);
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

    record TickResult(boolean hadActiveLane, boolean madeProgress) {
    }

    @Nullable
    ParallelCraftingLaneState findLaneState(@Nullable UUID craftingId) {
        if (craftingId == null) {
            return null;
        }
        return lanes.get(craftingId);
    }

}
