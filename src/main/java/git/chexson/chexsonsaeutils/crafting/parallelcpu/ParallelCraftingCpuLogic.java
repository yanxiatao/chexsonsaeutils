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
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineSourceCpuContext;
import com.google.common.base.Preconditions;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursiveTaskOrdering;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
    private boolean cantStoreItems;
    private long lastModifiedOnTick = TickHandler.instance().getCurrentTick();
    private boolean registered;
    private boolean suppressChangeNotifications;

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

    ICraftingSubmitResult trySubmitPartialJob(
            IGrid grid,
            ICraftingPlan plan,
            IActionSource src,
            @Nullable ICraftingRequester requester
    ) {
        ICraftingSubmitResult submitResult = trySubmitJob(
                grid,
                CraftingContinuationPartialSubmit.createNativeSubmissionPlan(plan),
                src,
                requester
        );
        if (!submitResult.successful()) {
            return submitResult;
        }
        if (this.job == null) {
            AELog.error("Parallel CPU accepted a continuation job without creating an executing job.");
            return CraftingSubmitResult.CPU_BUSY;
        }

        KeyCounter missingInitialItems = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                plan,
                grid,
                inventory,
                src
        );
        seedInitialWaitingFor(this.job, missingInitialItems, this::postChange);
        lane.cluster().refreshLaneState(lane);
        return submitResult;
    }

    void enableNotifications() {
        this.registered = true;
        this.lane.cluster().refreshLaneState(this.lane);
    }

    boolean tickCraftingLogic(
            IEnergyService energyGrid,
            CraftingService craftingService,
            ParallelCpuMetrics metrics,
            long currentTick,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            @Nullable ParallelCpuProviderBackoff providerBackoff
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
                        metrics,
                        currentTick,
                        budgetLedger,
                        providerBackoff
                );
                if (pushed > 0L) {
                    pushedPatterns += pushed;
                    remainingOperations -= pushed;
                } else {
                    break;
                }
            } while (remainingOperations > 0L);
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
            ParallelCpuMetrics metrics,
            long currentTick,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            @Nullable ParallelCpuProviderBackoff providerBackoff
    ) {
        if (metrics != null) {
            metrics.recordExecuteCraftingCall();
        }
        if (job == null || job.tasks.isEmpty()) {
            return 0L;
        }

        long pushedPatterns = 0L;
        var orderedTasks = DyeablePatternRecursiveTaskOrdering.orderedEntries(
                job.tasks,
                job.dyeableRecursivePlan ? job.dyeableRecursiveInternalItems : null
        );
        taskLoop:
        for (var task : orderedTasks) {
            if (!hasTickBudget(budgetLedger)) {
                break;
            }
            if (pushedPatterns >= maxPatterns) {
                break;
            }
            if (task.getValue().value <= 0L) {
                job.tasks.remove(task.getKey());
                continue;
            }

            var details = task.getKey();
            if (job.dyeableRecursivePlan
                    && DyeablePatternRecursiveTaskOrdering.hasPendingProducer(
                            job.tasks,
                            job.dyeableRecursiveInternalItems
                    )
                    && DyeablePatternRecursiveTaskOrdering.shouldDeferConsumer(
                            details,
                            job.dyeableRecursiveInternalItems,
                            inventory,
                            job.tasks
                    )) {
                continue;
            }
            var expectedOutputs = new KeyCounter();
            var expectedContainerItems = new KeyCounter();

            @Nullable
            var craftingContainer = extractPatternInputsWithinBudget(
                    details,
                    inventory,
                    metrics,
                    budgetLedger,
                    lane.cluster().level(),
                    expectedOutputs,
                    expectedContainerItems
            );

            boolean stopAfterCurrentTask = false;
            if (budgetLedger != null && budgetLedger.isExhausted()) {
                stopAfterCurrentTask = true;
            }
            for (ICraftingProvider provider : craftingService.getProviders(details)) {
                if (craftingContainer == null) {
                    break;
                }

                if (!hasTickBudget(budgetLedger)) {
                    stopAfterCurrentTask = true;
                    break;
                }

                ParallelCpuProviderBackoff.ProviderAvailability availability = checkProviderAvailability(
                        provider,
                        currentTick,
                        budgetLedger,
                        providerBackoff,
                        metrics
                );
                if (availability == ParallelCpuProviderBackoff.ProviderAvailability.BUDGET_EXHAUSTED) {
                    stopAfterCurrentTask = true;
                    break;
                }
                if (availability != ParallelCpuProviderBackoff.ProviderAvailability.READY) {
                    continue;
                }

                while (craftingContainer != null && pushedPatterns < maxPatterns) {
                    if (!hasTickBudget(budgetLedger) || !tryClaimPatternPush(budgetLedger)) {
                        stopAfterCurrentTask = true;
                        break;
                    }
                    var patternPower = CraftingCpuHelper.calculatePatternPower(craftingContainer);
                    if (energyService.extractAEPower(
                            patternPower,
                            Actionable.SIMULATE,
                            PowerMultiplier.CONFIG
                    ) < patternPower - 0.01D) {
                        break;
                    }

                    KeyCounter[] submittedCraftingContainer = craftingContainer;
                    boolean acceptedPush = provider.pushPattern(details, submittedCraftingContainer);

                    if (!acceptedPush) {
                        if (providerBackoff != null) {
                            providerBackoff.recordPushRejected(provider, currentTick);
                        }
                        break;
                    }
                    if (providerBackoff != null) {
                        providerBackoff.recordPushAccepted(provider);
                    }

                    craftingContainer = null;
                    energyService.extractAEPower(
                            patternPower,
                            Actionable.MODULATE,
                            PowerMultiplier.CONFIG
                    );
                    pushedPatterns++;
                    reserveExpectedWaiting(expectedOutputs, expectedContainerItems);
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
                        job.tasks.remove(task.getKey());
                        continue taskLoop;
                    }
                    if (pushedPatterns >= maxPatterns) {
                        break taskLoop;
                    }

                    expectedOutputs.reset();
                    expectedContainerItems.reset();
                    craftingContainer = extractPatternInputsWithinBudget(
                            details,
                            inventory,
                            metrics,
                            budgetLedger,
                            lane.cluster().level(),
                            expectedOutputs,
                            expectedContainerItems
                    );
                    if (budgetLedger != null && budgetLedger.isExhausted()) {
                        stopAfterCurrentTask = true;
                        break;
                    }
                }
                if (stopAfterCurrentTask) {
                    break;
                }
            }

            if (craftingContainer != null) {
                boolean reinjectBudgetClaimed = tryClaimReinjectPatternInputs(budgetLedger);
                CraftingCpuHelper.reinjectPatternInputs(inventory, craftingContainer);
                if (metrics != null) {
                    metrics.recordReinjectPatternInputs(1L);
                }
                if (!reinjectBudgetClaimed) {
                    break;
                }
            }
            if (stopAfterCurrentTask) {
                break;
            }
        }

        return pushedPatterns;
    }

    private static boolean hasTickBudget(@Nullable ParallelCpuGridBudgetLedger budgetLedger) {
        return budgetLedger == null || budgetLedger.hasTimeBudget(System.nanoTime());
    }

    private static boolean tryClaimPatternPush(@Nullable ParallelCpuGridBudgetLedger budgetLedger) {
        return budgetLedger == null || budgetLedger.tryClaimPatternPush();
    }

    private static boolean tryClaimReinjectPatternInputs(@Nullable ParallelCpuGridBudgetLedger budgetLedger) {
        return budgetLedger == null || budgetLedger.tryClaimReinjectPatternInputs();
    }

    private static @Nullable KeyCounter[] extractPatternInputsWithinBudget(
            IPatternDetails details,
            ListCraftingInventory inventory,
            @Nullable ParallelCpuMetrics metrics,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            net.minecraft.world.level.Level level,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        if (!hasTickBudget(budgetLedger)
                || (budgetLedger != null && !budgetLedger.tryClaimExtractPatternInputs())) {
            return null;
        }

        KeyCounter[] craftingContainer = CraftingCpuHelper.extractPatternInputs(
                details,
                inventory,
                level,
                expectedOutputs,
                expectedContainerItems
        );
        if (metrics != null) {
            metrics.recordExtractPatternInputs(1L);
        }
        return craftingContainer;
    }

    private static ParallelCpuProviderBackoff.ProviderAvailability checkProviderAvailability(
            @Nullable ICraftingProvider provider,
            long currentTick,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            @Nullable ParallelCpuProviderBackoff providerBackoff,
            @Nullable ParallelCpuMetrics metrics
    ) {
        if (providerBackoff != null) {
            return providerBackoff.checkProvider(provider, currentTick, budgetLedger, metrics);
        }
        if (provider == null) {
            return ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF;
        }
        if (budgetLedger != null && !budgetLedger.tryClaimProviderCheck()) {
            return ParallelCpuProviderBackoff.ProviderAvailability.BUDGET_EXHAUSTED;
        }
        if (metrics != null) {
            metrics.recordProviderScan();
        }
        if (provider.isBusy()) {
            if (metrics != null) {
                metrics.recordBusyProviderSkip();
            }
            return ParallelCpuProviderBackoff.ProviderAvailability.BUSY;
        }
        return ParallelCpuProviderBackoff.ProviderAvailability.READY;
    }

    private void reserveExpectedWaiting(
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        reserveExpectedWaiting(job, expectedOutputs, expectedContainerItems);
        lane.cluster().refreshLaneState(lane);
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

    static void seedInitialWaitingFor(
            ParallelExecutingCraftingJob job,
            KeyCounter missingInitialItems,
            Consumer<AEKey> postChange
    ) {
        if (missingInitialItems == null || missingInitialItems.isEmpty()) {
            return;
        }

        for (var entry : missingInitialItems) {
            job.waitingFor.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            ParallelExecutingCraftingJob.addMaxItems(
                    job.timeTracker,
                    entry.getLongValue(),
                    entry.getKey().getType()
            );
            if (postChange != null) {
                postChange.accept(entry.getKey());
            }
        }
    }

    void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        inventory.readFromNBT(data.getList("inventory", Tag.TAG_COMPOUND), registries);
        if (data.contains("job", Tag.TAG_COMPOUND)) {
            this.job = new ParallelExecutingCraftingJob(
                    data.getCompound("job"),
                    registries,
                    this::postChange,
                    lane
            );
            if (this.job.finalOutput == null) {
                finishJob(false);
            }
        }
    }

    void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        data.put("inventory", inventory.writeToNBT(registries));
        if (job != null) {
            data.put("job", job.writeToNBT(registries));
        }
    }

    long insert(@Nullable AEKey what, long amount, Actionable type) {
        return insertInternal(what, amount, type).insertedAmount();
    }

    long insertAndGetAccountedAmount(
            @Nullable AEKey what,
            long amount,
            Actionable type
    ) {
        return insertInternal(what, amount, type).accountedAmount();
    }

    private InsertResult insertInternal(
            @Nullable AEKey what,
            long amount,
            Actionable type
    ) {
        if (what == null || job == null) {
            return InsertResult.EMPTY;
        }

        long waitingFor = job.waitingFor.extract(what, amount, Actionable.SIMULATE);
        if (waitingFor <= 0L) {
            return InsertResult.EMPTY;
        }
        if (amount > waitingFor) {
            amount = waitingFor;
        }

        if (type == Actionable.MODULATE) {
            ParallelExecutingCraftingJob.decrementItems(job.timeTracker, amount, what.getType());
            job.waitingFor.extract(what, amount, Actionable.MODULATE);
            postChange(what);
        }

        long inserted = amount;
        if (isDyeableRecursiveInternalItem(job, what)) {
            if (type == Actionable.MODULATE) {
                inventory.insert(what, amount, Actionable.MODULATE);
                finishDyeableRecursiveJobIfComplete();
            }
        } else if (what.matches(job.finalOutput)) {
            inserted = insertFinalOutput(what, amount, type);
            if (type == Actionable.MODULATE) {
                if (job.dyeableRecursivePlan) {
                    finishDyeableRecursiveJobIfComplete();
                } else {
                    job.remainingAmount = Math.max(0L, job.remainingAmount - amount);
                    if (job.remainingAmount <= 0L) {
                        finishJob(true);
                    }
                }
            }
        } else if (type == Actionable.MODULATE) {
            inventory.insert(what, amount, Actionable.MODULATE);
            if (job.dyeableRecursivePlan) {
                finishDyeableRecursiveJobIfComplete();
            } else if (job.remainingAmount <= 0L) {
                finishJob(true);
            }
        }

        return new InsertResult(inserted, amount);
    }

    private static boolean isDyeableRecursiveInternalItem(ParallelExecutingCraftingJob job, AEKey what) {
        if (job == null || !job.dyeableRecursivePlan || what == null) {
            return false;
        }
        for (var entry : job.dyeableRecursiveInternalItems) {
            if (entry.getKey() != null && entry.getLongValue() > 0L && what.equals(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private long insertFinalOutput(AEKey what, long amount, Actionable type) {
        if (job == null || !job.dyeableRecursivePlan) {
            return job == null || job.link == null ? 0L : job.link.insert(what, amount, type);
        }
        if (type == Actionable.MODULATE) {
            inventory.insert(what, amount, Actionable.MODULATE);
        }
        return amount;
    }

    private void finishDyeableRecursiveJobIfComplete() {
        if (!isDyeableRecursiveJobComplete(job)) {
            return;
        }
        job.remainingAmount = 0L;
        finishJob(true);
    }

    static boolean isDyeableRecursiveJobComplete(@Nullable ParallelExecutingCraftingJob job) {
        return job != null
                && job.dyeableRecursivePlan
                && job.tasks.isEmpty()
                && job.waitingFor.list.isEmpty();
    }

    record InsertResult(long insertedAmount, long accountedAmount) {
        private static final InsertResult EMPTY = new InsertResult(0L, 0L);
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

        var finishedJob = job;
        UUID finishedCraftingId = finishedJob.link == null ? null : finishedJob.link.getCraftingID();
        if (success) {
            flushDyeableRecursiveFinalOutput(finishedJob);
            finishedJob.link.markDone();
            if (requesterLink != null) {
                requesterLink.markDone();
            }
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

    private void flushDyeableRecursiveFinalOutput(ParallelExecutingCraftingJob finishedJob) {
        if (finishedJob == null
                || !finishedJob.dyeableRecursivePlan
                || finishedJob.finalOutput == null
                || finishedJob.finalOutput.what() == null
                || finishedJob.link == null) {
            return;
        }

        AEKey finalKey = finishedJob.finalOutput.what();
        long amountToFlush = dyeableRecursiveFinalOutputAmountToFlush(finishedJob);
        if (amountToFlush <= 0L) {
            return;
        }

        long available = inventory.extract(finalKey, amountToFlush, Actionable.SIMULATE);
        if (available <= 0L) {
            return;
        }

        long accepted = finishedJob.link.insert(finalKey, available, Actionable.MODULATE);
        if (accepted > 0L) {
            inventory.extract(finalKey, accepted, Actionable.MODULATE);
        }
    }

    private static long dyeableRecursiveFinalOutputAmountToFlush(ParallelExecutingCraftingJob finishedJob) {
        if (finishedJob.dyeableRecursiveFinalOutputAmount > 0L) {
            return finishedJob.dyeableRecursiveFinalOutputAmount;
        }
        return finishedJob.finalOutput == null ? 0L : finishedJob.finalOutput.amount();
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

    void restoreRequesterLink(Iterable<ICraftingRequester> requesters) {
        if (requesterLink != null || job == null || job.link == null || job.link.isStandalone()) {
            return;
        }
        requesterLink = findRequesterLink(requesters, job.link.getCraftingID());
    }

    @Nullable
    static CraftingLink findRequesterLink(
            @Nullable Iterable<ICraftingRequester> requesters,
            @Nullable UUID craftingId
    ) {
        if (requesters == null || craftingId == null) {
            return null;
        }
        for (ICraftingRequester requester : requesters) {
            if (requester == null) {
                continue;
            }
            for (ICraftingLink link : requester.getRequestedJobs()) {
                if (link instanceof CraftingLink craftingLink
                        && !craftingLink.isStandalone()
                        && craftingId.equals(craftingLink.getCraftingID())) {
                    return craftingLink;
                }
            }
        }
        return null;
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

    ParallelElapsedTimeTracker getElapsedTimeTracker() {
        return job != null ? job.timeTracker : new ParallelElapsedTimeTracker();
    }

    long getStored(@Nullable AEKey template) {
        return template == null ? 0L : inventory.extract(template, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    long getBufferedAmount(@Nullable AEKey template) {
        return getStored(template);
    }

    long extractBuffered(@Nullable AEKey what, long amount, Actionable mode) {
        if (what == null || amount <= 0L || mode == null) {
            return 0L;
        }
        return Math.max(0L, Math.min(amount, inventory.extract(what, amount, mode)));
    }

    long insertBuffered(@Nullable AEKey what, long amount, Actionable mode) {
        if (what == null || amount <= 0L || mode == null) {
            return 0L;
        }
        if (mode == Actionable.MODULATE) {
            inventory.insert(what, amount, Actionable.MODULATE);
        }
        return amount;
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

    boolean hasWaitingFor() {
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
