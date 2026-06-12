package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.AELog;
import appeng.core.network.ClientboundPacket;
import appeng.core.network.clientbound.CraftingJobStatusPacket;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineSourceCpuContext;
import com.google.common.base.Preconditions;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class ParallelCraftingCpuLogic {

    private final ParallelCraftingLaneState lane;
    private final ListCraftingInventory inventory = new ListCraftingInventory(this::postChange);
    private final long[] usedOps = new long[3];
    private final Set<Consumer<AEKey>> listeners = new HashSet<>();
    @Nullable
    private ParallelExecutingCraftingJob job;
    @Nullable
    private CraftingLink requesterLink;
    @Nullable
    private KeyCounter[] pendingReinjectInputs;
    private boolean cantStoreItems;
    private long lastModifiedOnTick = TickHandler.instance().getCurrentTick();
    private boolean registered;
    private boolean suppressChangeNotifications;
    private int synchronousProviderPushDepth;
    private boolean finishDeferredUntilProviderPushCompletes;

    ParallelCraftingCpuLogic(ParallelCraftingLaneState lane) {
        this.lane = lane;
    }

    ICraftingSubmitResult trySubmitJob(
            IGrid grid,
            ICraftingPlan plan,
            IActionSource src,
            @Nullable ICraftingRequester requester
    ) {
        if (this.job != null) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (!lane.cluster().canProcessJobs()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (lane.cluster().storageBytes() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }

        if (!inventory.list.isEmpty()) {
            AELog.warn("Parallel crafting CPU inventory is not empty yet a job was submitted.");
        }

        var missingIngredient = CraftingCpuHelper.tryExtractInitialItems(plan, grid, inventory, src);
        if (missingIngredient != null) {
            return CraftingSubmitResult.missingIngredient(missingIngredient);
        }

        var playerId = src.player()
                .map(player -> player instanceof ServerPlayer serverPlayer
                        ? IPlayerRegistry.getPlayerId(serverPlayer)
                        : null)
                .orElse(null);
        var craftId = lane.laneId();
        var linkCpu = new CraftingLink(
                CraftingCpuHelper.generateLinkData(craftId, requester == null, false),
                lane.linkCpu()
        );

        suppressChangeNotifications = true;
        this.job = new ParallelExecutingCraftingJob(plan, this::postChange, linkCpu, playerId);
        this.requesterLink = null;
        suppressChangeNotifications = false;

        notifyJobOwner(this.job, CraftingJobStatusPacket.Status.STARTED);

        if (requester != null) {
            this.requesterLink = new CraftingLink(
                    CraftingCpuHelper.generateLinkData(craftId, false, true),
                    requester
            );
            if (grid.getCraftingService() instanceof CraftingService craftingService) {
                craftingService.addLink(linkCpu);
                craftingService.addLink(this.requesterLink);
            }
            return CraftingSubmitResult.successful(this.requesterLink);
        }

        return CraftingSubmitResult.successful(null);
    }

    void enableNotifications() {
        this.registered = true;
        this.lane.cluster().refreshLaneState(this.lane);
    }

    boolean tickCraftingLogic(
            IEnergyService energyGrid,
            CraftingService craftingService,
            ParallelCpuGridBudgetLedger budgetLedger,
            ParallelCpuProviderBackoff providerBackoff,
            ParallelCpuMetrics metrics,
            long currentTick
    ) {
        if (!lane.cluster().canProcessJobs()) {
            return true;
        }

        cantStoreItems = false;
        if (this.job == null) {
            int storedBefore = inventory.list.size();
            this.storeItems();
            if (!this.inventory.list.isEmpty()) {
                cantStoreItems = true;
            }
            return storedBefore == inventory.list.size();
        }

        if (job.link.isCanceled()) {
            cancel();
            return true;
        }

        if (job.suspended) {
            return true;
        }

        long remainingOperations = Math.max(
                0L,
                (long) lane.cluster().advertisedCoProcessors() + 1L
                        - (this.usedOps[0] + this.usedOps[1] + this.usedOps[2])
        );
        long started = remainingOperations;
        long pushedPatterns = 0L;

        if (remainingOperations > 0L) {
            do {
                long pushed = executeCrafting(
                        remainingOperations,
                        craftingService,
                        energyGrid,
                        budgetLedger,
                        providerBackoff,
                        metrics,
                        currentTick
                );
                if (pushed > 0L) {
                    pushedPatterns += pushed;
                    remainingOperations -= pushed;
                } else {
                    break;
                }
            } while (remainingOperations > 0L && budgetLedger.hasTimeBudget(System.nanoTime()));
        }

        this.usedOps[2] = this.usedOps[1];
        this.usedOps[1] = this.usedOps[0];
        this.usedOps[0] = Math.max(0L, started - remainingOperations);
        return pushedPatterns <= 0L;
    }

    private long executeCrafting(
            long maxPatterns,
            CraftingService craftingService,
            IEnergyService energyService,
            ParallelCpuGridBudgetLedger budgetLedger,
            ParallelCpuProviderBackoff providerBackoff,
            ParallelCpuMetrics metrics,
            long currentTick
    ) {
        if (metrics != null) {
            metrics.recordExecuteCraftingCall();
        }
        if (!flushPendingReinjectInputs(budgetLedger, metrics)) {
            return 0L;
        }
        if (job == null || job.tasks.isEmpty()) {
            return 0L;
        }

        long pushedPatterns = 0L;
        var iterator = job.tasks.entrySet().iterator();
        boolean stopAfterReinject = false;
        while (iterator.hasNext() && pushedPatterns < maxPatterns && !budgetLedger.isExhausted()
                && !stopAfterReinject && budgetLedger.hasTimeBudget(System.nanoTime())) {
            var task = iterator.next();
            if (task.getValue().value <= 0L) {
                iterator.remove();
                continue;
            }

            var details = task.getKey();
            var expectedOutputs = new KeyCounter();
            var expectedContainerItems = new KeyCounter();
            if (!budgetLedger.tryClaimExtractPatternInputs()) {
                break;
            }
            if (metrics != null) {
                metrics.recordExtractPatternInputs(1L);
            }
            if (!budgetLedger.hasTimeBudget(System.nanoTime())) {
                stopAfterReinject = true;
                continue;
            }

            @Nullable
            var craftingContainer = CraftingCpuHelper.extractPatternInputs(
                    details,
                    inventory,
                    lane.cluster().level(),
                    expectedOutputs,
                    expectedContainerItems
            );

            for (ICraftingProvider provider : craftingService.getProviders(details)) {
                if (!budgetLedger.hasTimeBudget(System.nanoTime())) {
                    stopAfterReinject = true;
                    break;
                }
                if (craftingContainer == null) {
                    break;
                }

                var availability = providerBackoff.checkProvider(provider, currentTick, budgetLedger, metrics);
                if (availability == ParallelCpuProviderBackoff.ProviderAvailability.BUDGET_EXHAUSTED) {
                    stopAfterReinject = true;
                    break;
                }
                if (availability != ParallelCpuProviderBackoff.ProviderAvailability.READY) {
                    continue;
                }

                var patternPower = CraftingCpuHelper.calculatePatternPower(craftingContainer);
                if (energyService.extractAEPower(
                        patternPower,
                        Actionable.SIMULATE,
                        PowerMultiplier.CONFIG
                ) < patternPower - 0.01D) {
                    break;
                }
                if (!budgetLedger.tryClaimPatternPush()) {
                    stopAfterReinject = true;
                    break;
                }

                reserveExpectedWaiting(job, expectedOutputs, expectedContainerItems);
                boolean acceptedPush = false;
                beginSynchronousProviderPush();
                KeyCounter[] submittedCraftingContainer = craftingContainer;
                try {
                    acceptedPush = FormalMachineSourceCpuContext.withSourceCraftingId(
                            currentSourceCraftingId(),
                            () -> provider.pushPattern(details, submittedCraftingContainer)
                    );
                } finally {
                    if (!acceptedPush) {
                        rollbackReservedWaiting(job, expectedOutputs, expectedContainerItems);
                    }
                    endSynchronousProviderPush();
                }

                if (acceptedPush) {
                    craftingContainer = null;
                    providerBackoff.recordPushAccepted(provider);
                    energyService.extractAEPower(
                            patternPower,
                            Actionable.MODULATE,
                            PowerMultiplier.CONFIG
                    );
                    pushedPatterns++;
                    if (metrics != null) {
                        metrics.recordPushedPattern(1L);
                    }
                    for (var expectedContainerItem : expectedContainerItems) {
                        ParallelExecutingCraftingJob.addMaxItems(
                                job.timeTracker,
                                expectedContainerItem.getLongValue(),
                                expectedContainerItem.getKey().getType()
                        );
                    }

                    task.getValue().value--;
                    if (task.getValue().value <= 0L) {
                        iterator.remove();
                        break;
                    }
                    if (pushedPatterns >= maxPatterns) {
                        stopAfterReinject = true;
                        break;
                    }

                    expectedOutputs.reset();
                    expectedContainerItems.reset();
                    if (!budgetLedger.hasTimeBudget(System.nanoTime())) {
                        stopAfterReinject = true;
                        break;
                    }
                    if (!budgetLedger.tryClaimExtractPatternInputs()) {
                        stopAfterReinject = true;
                        break;
                    }
                    if (metrics != null) {
                        metrics.recordExtractPatternInputs(1L);
                    }
                    craftingContainer = CraftingCpuHelper.extractPatternInputs(
                            details,
                            inventory,
                            lane.cluster().level(),
                            expectedOutputs,
                            expectedContainerItems
                    );
                } else {
                    providerBackoff.recordPushRejected(provider, currentTick);
                }
            }

            if (craftingContainer != null) {
                pendingReinjectInputs = craftingContainer;
                if (!flushPendingReinjectInputs(budgetLedger, metrics)) {
                    stopAfterReinject = true;
                }
            }
        }

        return pushedPatterns;
    }

    private static void reserveExpectedWaiting(
            ParallelExecutingCraftingJob job,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        if (job == null) {
            return;
        }
        for (var expectedOutput : expectedOutputs) {
            if (expectedOutput.getKey() == null || expectedOutput.getLongValue() <= 0L) {
                continue;
            }
            job.waitingFor.insert(
                    expectedOutput.getKey(),
                    expectedOutput.getLongValue(),
                    Actionable.MODULATE
            );
        }
        for (var expectedContainerItem : expectedContainerItems) {
            if (expectedContainerItem.getKey() == null || expectedContainerItem.getLongValue() <= 0L) {
                continue;
            }
            job.waitingFor.insert(
                    expectedContainerItem.getKey(),
                    expectedContainerItem.getLongValue(),
                    Actionable.MODULATE
            );
        }
    }

    private static void rollbackReservedWaiting(
            ParallelExecutingCraftingJob job,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        if (job == null) {
            return;
        }
        for (var expectedOutput : expectedOutputs) {
            if (expectedOutput.getKey() == null || expectedOutput.getLongValue() <= 0L) {
                continue;
            }
            job.waitingFor.extract(
                    expectedOutput.getKey(),
                    expectedOutput.getLongValue(),
                    Actionable.MODULATE
            );
        }
        for (var expectedContainerItem : expectedContainerItems) {
            if (expectedContainerItem.getKey() == null || expectedContainerItem.getLongValue() <= 0L) {
                continue;
            }
            job.waitingFor.extract(
                    expectedContainerItem.getKey(),
                    expectedContainerItem.getLongValue(),
                    Actionable.MODULATE
            );
        }
    }

    private void beginSynchronousProviderPush() {
        synchronousProviderPushDepth++;
    }

    private void endSynchronousProviderPush() {
        if (synchronousProviderPushDepth <= 0) {
            return;
        }
        synchronousProviderPushDepth--;
        if (synchronousProviderPushDepth == 0) {
            finishDeferredProviderPushJobIfReady();
        }
    }

    private void finishDeferredProviderPushJobIfReady() {
        if (!finishDeferredUntilProviderPushCompletes) {
            return;
        }
        finishDeferredUntilProviderPushCompletes = false;
        finishJobIfReady();
    }

    private boolean flushPendingReinjectInputs(
            ParallelCpuGridBudgetLedger budgetLedger,
            @Nullable ParallelCpuMetrics metrics
    ) {
        if (pendingReinjectInputs == null) {
            return true;
        }
        if (!budgetLedger.tryClaimReinjectPatternInputs()) {
            if (metrics != null) {
                metrics.recordBudgetExhausted(ParallelCpuGridBudgetLedger.BudgetType.REINJECT_PATTERN_INPUTS);
            }
            return false;
        }

        CraftingCpuHelper.reinjectPatternInputs(inventory, pendingReinjectInputs);
        pendingReinjectInputs = null;
        if (metrics != null) {
            metrics.recordReinjectPatternInputs(1L);
        }
        return true;
    }

    void flushPendingReinjectInputsWithoutBudget() {
        if (pendingReinjectInputs == null) {
            return;
        }
        CraftingCpuHelper.reinjectPatternInputs(inventory, pendingReinjectInputs);
        pendingReinjectInputs = null;
    }

    long insert(@Nullable AEKey what, long amount, Actionable type) {
        return insert(what, amount, type, false);
    }

    long insert(@Nullable AEKey what, long amount, Actionable type, boolean preferBufferFinalOutput) {
        if (what == null || job == null) {
            return 0L;
        }

        long waitingFor = job.waitingFor.extract(what, amount, Actionable.SIMULATE);
        if (waitingFor <= 0L) {
            return 0L;
        }
        if (amount > waitingFor) {
            amount = waitingFor;
        }

        long inserted = amount;
        if (what.matches(job.finalOutput)) {
            if (job.link.isStandalone() || preferBufferFinalOutput) {
                if (type == Actionable.MODULATE) {
                    ParallelExecutingCraftingJob.decrementItems(job.timeTracker, amount, what.getType());
                    job.waitingFor.extract(what, amount, Actionable.MODULATE);
                    inventory.insert(what, amount, Actionable.MODULATE);
                }
            } else {
                inserted = job.link.insert(what, amount, type);
                if (type == Actionable.MODULATE && inserted > 0L) {
                    ParallelExecutingCraftingJob.decrementItems(job.timeTracker, inserted, what.getType());
                    job.waitingFor.extract(what, inserted, Actionable.MODULATE);
                }
            }
            if (type == Actionable.MODULATE && inserted > 0L) {
                postChange(what);
                job.remainingAmount = Math.max(0L, job.remainingAmount - inserted);
                if (job.remainingAmount <= 0L) {
                    if (synchronousProviderPushDepth > 0) {
                        finishDeferredUntilProviderPushCompletes = true;
                    } else {
                        finishJobIfReady();
                    }
                }
            }
        } else if (type == Actionable.MODULATE) {
            ParallelExecutingCraftingJob.decrementItems(job.timeTracker, amount, what.getType());
            job.waitingFor.extract(what, amount, Actionable.MODULATE);
            inventory.insert(what, amount, Actionable.MODULATE);
            if (job.remainingAmount <= 0L) {
                if (synchronousProviderPushDepth > 0) {
                    finishDeferredUntilProviderPushCompletes = true;
                } else {
                    finishJobIfReady();
                }
            }
        }

        return inserted;
    }

    private @Nullable UUID currentSourceCraftingId() {
        return job == null || job.link == null ? null : job.link.getCraftingID();
    }

    void cancel() {
        if (job == null) {
            return;
        }
        finishJob(false);
    }

    private void finishJob(boolean success) {
        if (job == null) {
            return;
        }

        finishDeferredUntilProviderPushCompletes = false;
        synchronousProviderPushDepth = 0;
        flushPendingReinjectInputsWithoutBudget();
        var finishedJob = job;
        if (success) {
            finishedJob.link.markDone();
        } else {
            finishedJob.link.cancel();
            if (requesterLink != null) {
                requesterLink.cancel();
            }
        }

        finishedJob.waitingFor.clear();
        for (var entry : finishedJob.tasks.entrySet()) {
            for (var output : entry.getKey().getOutputs()) {
                postChange(output.what());
            }
        }

        notifyJobOwner(
                finishedJob,
                success ? CraftingJobStatusPacket.Status.FINISHED : CraftingJobStatusPacket.Status.CANCELLED
        );

        this.job = null;
        this.requesterLink = null;
        this.storeItems();
    }

    private void finishJobIfReady() {
        if (job == null || job.remainingAmount > 0L || hasOutstandingWaiting()) {
            return;
        }
        finishJob(true);
    }

    private boolean hasOutstandingWaiting() {
        if (job == null) {
            return false;
        }
        for (var entry : job.waitingFor.list) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
                return true;
            }
        }
        return false;
    }

    void storeItems() {
        Preconditions.checkState(job == null, "Parallel CPU should not dump items while a job is active.");
        if (inventory.list.isEmpty()) {
            return;
        }

        var grid = lane.cluster().grid();
        if (grid == null) {
            return;
        }

        var storage = grid.getStorageService().getInventory();
        for (var entry : inventory.list) {
            postChange(entry.getKey());
            long inserted = storage.insert(
                    entry.getKey(),
                    entry.getLongValue(),
                    Actionable.MODULATE,
                    lane.cluster().source()
            );
            entry.setValue(entry.getLongValue() - inserted);
        }
        inventory.list.removeZeros();
    }

    void addListener(Consumer<AEKey> listener) {
        listeners.add(listener);
    }

    void removeListener(Consumer<AEKey> listener) {
        listeners.remove(listener);
    }

    @Nullable
    GenericStack getFinalJobOutput() {
        return job != null ? job.finalOutput : null;
    }

    @Nullable
    ICraftingLink getLastLink() {
        return job != null ? job.link : null;
    }

    @Nullable
    CraftingLink getRequesterLink() {
        return requesterLink;
    }

    boolean hasJob() {
        return job != null;
    }

    boolean isActive() {
        return hasJob() || !inventory.list.isEmpty();
    }

    boolean isInventoryEmpty() {
        return inventory.list.isEmpty();
    }

    boolean isCantStoreItems() {
        return cantStoreItems;
    }

    boolean isJobSuspended() {
        return job != null && job.suspended;
    }

    void setJobSuspended(boolean suspended) {
        if (job != null && job.suspended != suspended) {
            job.suspended = suspended;
            postChange(job.finalOutput == null ? null : job.finalOutput.what());
        }
    }

    long getLastModifiedOnTick() {
        return lastModifiedOnTick;
    }

    ElapsedTimeTracker getElapsedTimeTracker() {
        return job != null ? job.timeTracker : new ElapsedTimeTracker();
    }

    long getStored(@Nullable AEKey template) {
        return template == null ? 0L : inventory.extract(template, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    long getWaitingFor(@Nullable AEKey template) {
        if (template == null || job == null) {
            return 0L;
        }
        return job.waitingFor.extract(template, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    void getAllWaitingFor(Set<AEKey> waitingFor) {
        if (job == null || waitingFor == null) {
            return;
        }
        for (var entry : job.waitingFor.list) {
            waitingFor.add(entry.getKey());
        }
    }

    long getPendingOutputs(@Nullable AEKey template) {
        if (template == null || job == null) {
            return 0L;
        }
        long count = 0L;
        for (var task : job.tasks.entrySet()) {
            for (var output : task.getKey().getOutputs()) {
                if (template.matches(output)) {
                    count = saturatedAdd(count, output.amount() * task.getValue().value);
                }
            }
        }
        return count;
    }

    Iterable<it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey>> getWaitingStacks() {
        return job == null ? java.util.List.of() : job.waitingFor.list;
    }

    void getAllItems(KeyCounter out) {
        if (out == null) {
            return;
        }
        out.addAll(inventory.list);
        if (job == null) {
            return;
        }
        out.addAll(job.waitingFor.list);
        for (var task : job.tasks.entrySet()) {
            for (var output : task.getKey().getOutputs()) {
                out.add(output.what(), output.amount() * task.getValue().value);
            }
        }
    }

    @Nullable
    CraftingJobStatus getJobStatus() {
        var finalOutput = getFinalJobOutput();
        if (finalOutput == null) {
            return null;
        }
        var elapsedTimeTracker = getElapsedTimeTracker();
        long progress = Math.max(
                0L,
                elapsedTimeTracker.getStartItemCount() - elapsedTimeTracker.getRemainingItemCount()
        );
        return new CraftingJobStatus(
                finalOutput,
                elapsedTimeTracker.getStartItemCount(),
                progress,
                elapsedTimeTracker.getElapsedTime()
        );
    }

    private void postChange(@Nullable AEKey what) {
        lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        if (registered && !suppressChangeNotifications) {
            lane.cluster().refreshLaneState(lane);
        }
        if (what != null) {
            for (var listener : listeners) {
                listener.accept(what);
            }
        }
    }

    private void notifyJobOwner(ParallelExecutingCraftingJob job, CraftingJobStatusPacket.Status status) {
        this.lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        var playerId = job.playerId;
        if (playerId == null) {
            return;
        }

        var server = lane.cluster().level().getServer();
        var connectedPlayer = IPlayerRegistry.getConnected(server, playerId);
        if (connectedPlayer != null && job.finalOutput != null) {
            var jobId = job.link.getCraftingID();
            ClientboundPacket message = new CraftingJobStatusPacket(
                    jobId,
                    job.finalOutput.what(),
                    job.finalOutput.amount(),
                    job.remainingAmount,
                    status
            );
            connectedPlayer.connection.send(message);
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
