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
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;

final class ParallelCraftingLaneState implements ParallelCraftingLane {

    private final ParallelCraftingCpuCluster cluster;
    private final UUID laneId;
    private final ParallelCraftingCpuLogic logic;
    private long nextEligibleTick;
    @Nullable
    private ICraftingPlan submittedPlan;
    private QueueState queueState = QueueState.NONE;

    ParallelCraftingLaneState(ParallelCraftingCpuCluster cluster, UUID laneId, long currentTick) {
        this.cluster = cluster;
        this.laneId = laneId;
        this.logic = new ParallelCraftingCpuLogic(this);
        this.nextEligibleTick = currentTick;
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

    void markRegistered() {
        logic.enableNotifications();
    }

    TickResult tick(
            IEnergyService energyGrid,
            appeng.me.service.CraftingService craftingService,
            ParallelCpuGridBudgetLedger budgetLedger,
            ParallelCpuProviderBackoff providerBackoff,
            ParallelCpuMetrics metrics,
            long currentTick
    ) {
        boolean zeroProgress = logic.tickCraftingLogic(
                energyGrid,
                craftingService,
                budgetLedger,
                providerBackoff,
                metrics,
                currentTick
        );
        return new TickResult(zeroProgress);
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

    ParallelCraftingCPU linkCpu() {
        return cluster.activeSummaryCpu();
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

    @Override
    public long insertIntoWaiting(AEKey what, long amount, Actionable mode) {
        return insertIntoWaiting(what, amount, mode, false);
    }

    @Override
    public long insertIntoWaiting(
            AEKey what,
            long amount,
            Actionable mode,
            boolean preferBufferFinalOutput
    ) {
        return logic.insert(what, amount, mode, preferBufferFinalOutput);
    }

    @Nullable
    ICraftingLink getLastLink() {
        return logic.getLastLink();
    }

    @Nullable
    CraftingLink getRequesterLink() {
        return logic.getRequesterLink();
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

    boolean markRunnableQueued() {
        if (queueState == QueueState.RUNNABLE) {
            return false;
        }
        queueState = QueueState.RUNNABLE;
        return true;
    }

    boolean markDelayedQueued() {
        if (queueState == QueueState.DELAYED) {
            return false;
        }
        queueState = QueueState.DELAYED;
        return true;
    }

    boolean markRunnableDequeued() {
        if (queueState != QueueState.RUNNABLE) {
            return false;
        }
        queueState = QueueState.NONE;
        return true;
    }

    boolean markDelayedDequeued() {
        if (queueState != QueueState.DELAYED) {
            return false;
        }
        queueState = QueueState.NONE;
        return true;
    }

    long nextEligibleTick() {
        return nextEligibleTick;
    }

    void delayUntil(long nextEligibleTick) {
        this.nextEligibleTick = Math.max(this.nextEligibleTick + 1L, nextEligibleTick);
    }

    void cancel() {
        logic.cancel();
    }

    void flushPendingReinjectInputsWithoutBudget() {
        logic.flushPendingReinjectInputsWithoutBudget();
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

    record TickResult(boolean zeroProgress) {
    }

    private enum QueueState {
        NONE,
        RUNNABLE,
        DELAYED
    }
}
