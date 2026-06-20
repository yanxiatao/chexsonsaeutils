package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingLink;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

final class ParallelCraftingLaneState implements ParallelCraftingLane {

    private final ParallelCraftingCpuCluster cluster;
    private final UUID laneId;
    private final ParallelCraftingCPU activeCpu;
    private final ParallelCraftingCpuLogic logic;
    @Nullable
    private ICraftingPlan submittedPlan;

    ParallelCraftingLaneState(ParallelCraftingCpuCluster cluster, UUID laneId, long currentTick) {
        this.cluster = cluster;
        this.laneId = laneId;
        this.activeCpu = new ParallelCraftingCPU(cluster, laneId);
        this.logic = new ParallelCraftingCpuLogic(this);
    }

    static ParallelCraftingLaneState readFromNBT(
            ParallelCraftingCpuCluster cluster,
            CompoundTag data,
            HolderLookup.Provider registries,
            long currentTick
    ) {
        UUID laneId = data.hasUUID("laneId") ? data.getUUID("laneId") : UUID.randomUUID();
        ParallelCraftingLaneState lane = new ParallelCraftingLaneState(cluster, laneId, currentTick);
        lane.logic.readFromNBT(data.getCompound("logic"), registries);
        return lane;
    }

    CompoundTag writeToNBT(HolderLookup.Provider registries) {
        CompoundTag data = new CompoundTag();
        data.putUUID("laneId", laneId);
        CompoundTag logicTag = new CompoundTag();
        logic.writeToNBT(logicTag, registries);
        data.put("logic", logicTag);
        return data;
    }

    ICraftingSubmitResult trySubmitJob(
            IGrid grid,
            ICraftingPlan plan,
            IActionSource src,
            @Nullable ICraftingRequester requester
    ) {
        ICraftingSubmitResult result = logic.trySubmitJob(grid, plan, src, requester);
        if (result != null && result.successful()) {
            this.submittedPlan = plan;
        }
        return result;
    }

    ICraftingSubmitResult trySubmitPartialJob(
            IGrid grid,
            ICraftingPlan plan,
            IActionSource src,
            @Nullable ICraftingRequester requester
    ) {
        ICraftingSubmitResult result = logic.trySubmitPartialJob(grid, plan, src, requester);
        if (result != null && result.successful()) {
            this.submittedPlan = plan;
        }
        return result;
    }

    void markRegistered() {
        logic.enableNotifications();
    }

    boolean tick(
            IEnergyService energyGrid,
            appeng.me.service.CraftingService craftingService,
            ParallelCpuMetrics metrics,
            long currentTick,
            ParallelCpuGridBudgetLedger budgetLedger,
            ParallelCpuProviderBackoff providerBackoff
    ) {
        return logic.tickCraftingLogic(
                energyGrid,
                craftingService,
                metrics,
                currentTick,
                budgetLedger,
                providerBackoff
        );
    }

    @Override
    public UUID getLaneId() {
        return laneId;
    }

    UUID laneId() {
        return laneId;
    }

    ParallelCraftingCpuCluster cluster() {
        return cluster;
    }

    ParallelCraftingCpuLogic logic() {
        return logic;
    }

    ParallelCraftingCPU activeCpu() {
        return activeCpu;
    }

    ParallelCraftingCPU linkCpu() {
        return activeCpu;
    }

    @Override
    public boolean isLaneActive() {
        return logic.isActive();
    }

    @Override
    public Iterable<Object2LongMap.Entry<AEKey>> getWaitingStacks() {
        return logic.getWaitingStacks();
    }

    @Override
    public long getRequestedAmount(@Nullable AEKey what) {
        return logic.getWaitingFor(what);
    }

    boolean hasWaitingFor() {
        return logic.hasWaitingFor();
    }

    void appendWaitingFor(Set<AEKey> target) {
        logic.getAllWaitingFor(target);
    }

    @Override
    public long insertIntoWaiting(AEKey what, long amount, Actionable mode) {
        return logic.insert(what, amount, mode);
    }

    @Override
    public WaitingInsertResult insertIntoWaitingAndGetResult(AEKey what, long amount, Actionable mode) {
        long waitingBefore = mode == Actionable.MODULATE ? logic.getWaitingFor(what) : 0L;
        long physicalInserted = logic.insert(what, amount, mode);
        if (mode != Actionable.MODULATE) {
            return new WaitingInsertResult(physicalInserted, physicalInserted);
        }
        long accounted = Math.max(0L, waitingBefore - logic.getWaitingFor(what));
        return new WaitingInsertResult(physicalInserted, accounted);
    }

    @Nullable
    ICraftingLink getLastLink() {
        return logic.getLastLink();
    }

    @Nullable
    CraftingLink getRequesterLink() {
        return logic.getRequesterLink();
    }

    void restoreRequesterLink(Iterable<ICraftingRequester> requesters) {
        logic.restoreRequesterLink(requesters);
    }

    void appendServiceLinks(Collection<CraftingLink> target) {
        if (target == null) {
            return;
        }
        ICraftingLink cpuLink = getLastLink();
        if (cpuLink instanceof CraftingLink craftingLink) {
            target.add(craftingLink);
        }
        CraftingLink requesterLink = getRequesterLink();
        if (requesterLink != null) {
            target.add(requesterLink);
        }
    }

    @Nullable
    ICraftingPlan submittedPlan() {
        return submittedPlan;
    }

    boolean matchesPlan(@Nullable ICraftingPlan plan) {
        return submittedPlan == plan;
    }

    @Nullable
    CraftingJobStatus getJobStatus() {
        return logic.getJobStatus();
    }

    boolean isSuspended() {
        return logic.isJobSuspended();
    }

    void setSuspended(boolean suspended) {
        logic.setJobSuspended(suspended);
    }

    boolean isCantStoreItems() {
        return logic.isCantStoreItems();
    }

    void cancel() {
        logic.cancel();
    }

    long getLastModifiedOnTick() {
        return logic.getLastModifiedOnTick();
    }

    long storedAmountForTest(@Nullable AEKey what) {
        return logic.getStored(what);
    }

    long waitingAmountForTest(@Nullable AEKey what) {
        return logic.getWaitingFor(what);
    }

    long pendingAmountForTest(@Nullable AEKey what) {
        return logic.getPendingOutputs(what);
    }

}
