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
import com.google.common.base.Preconditions;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveTaskOrdering;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class ParallelCraftingCpuLogic {

    /** 缺料退避 TTL（tick）：材料到达后至多 1 秒重试。 */
    private static final long MATERIAL_BACKOFF_TICKS = 20L;

    private final ParallelCraftingLaneState lane;
    private final ListCraftingInventory inventory = new ListCraftingInventory(this::postChange);
    /**
     * 任务级断点续扫索引：时间预算（20ms）耗尽 break 时记录下一个未评估
     * 任务，下 tick 从断点继续。此前任务表每 tick 从头扫，头部活跃任务
     * （持续推送的多推任务）每轮评估耗光时间预算，排序靠后的任务永远
     * 轮不到评估——「CPU 库存有料、任务却永不执行」的任务级饥饿即此
     * （lane 级早有轮转，任务级缺失是最后一块）。
     */
    private int taskResumeIndex;
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

        long pushedPatterns = 0L;

        // CPU 不再按协处理器数、三 tick usedOps 或任务数限制样板份数。
        // 唯一调度上限是 grid/lane 的墙钟预算，MSPT 保护仍由 hasTickBudget 保留。
        if (hasTickBudget(budgetLedger)) {
            var orderedTasks = RecursiveTaskOrdering.orderedEntries(
                    job.tasks,
                    job.dyeableRecursivePlan ? job.dyeableRecursiveInternalItems : null
            );
            pushedPatterns = executeCrafting(
                    Long.MAX_VALUE,
                    orderedTasks,
                    craftingService,
                    energyGrid,
                    currentTick,
                    budgetLedger,
                    providerBackoff
            );
        }
        var gridContext = lane.cluster().gridContext();
        StallProbe.note(pushedPatterns, job, this.inventory,
                gridContext == null ? null : gridContext.grid());
        return pushedPatterns <= 0L;
    }

    private long executeCrafting(
            long maxPatterns,
            List<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> orderedTasks,
            CraftingService craftingService,
            IEnergyService energyService,
            long currentTick,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            @Nullable ParallelCpuProviderBackoff providerBackoff
    ) {

        if (job == null || job.tasks.isEmpty()) {
            return 0L;
        }

        long pushedPatterns = 0L;
        // 卡住归因计数：无推送 tick 结束时随快照一起输出，一眼定位环节。
        long skippedDefer = 0L;
        long skippedNoProvider = 0L;
        long skippedExtractNull = 0L;
        long skippedPushRejected = 0L;
        long skippedMaterialBackoff = 0L;
        int taskCount = orderedTasks.size();
        int startIndex = Math.floorMod(taskResumeIndex, Math.max(1, taskCount));
        taskLoop:
        for (int taskOffset = 0; taskOffset < taskCount; taskOffset++) {
            int taskIndex = (startIndex + taskOffset) % taskCount;
            var task = orderedTasks.get(taskIndex);
            if (!hasTickBudget(budgetLedger)) {
                // 本任务未评估：断点留在本任务，下 tick 重试它
                taskResumeIndex = taskIndex;
                break;
            }
            if (pushedPatterns >= maxPatterns) {
                taskResumeIndex = taskIndex;
                break;
            }
            if (task.getValue().value <= 0L) {
                job.tasks.remove(task.getKey());
                continue;
            }

            var details = task.getKey();
            // 缺料退避：提取失败过的样板 TTL 内零成本跳过——缺料任务的
            // 重复失败提取（多槽 × 大库存扫模板）吃光 20ms 预算，后面的
            // 富料任务每 tick 都轮不到评估（86/138 归因断裂即此形态）。
            // 材料到达由 postChange 按缺口键精准唤醒（见 wakeMaterialBackoff），
            // 绝不整表清空——推送活跃期整表清空会让全部缺料任务下一轮
            // 集体重试提取、预算再次吃光，排序靠后的任务永远轮不到评估
            // （实测：A 持续推送、B 材料充足却永不执行的形态即此）。
            Long backoffUntil = job.materialBackoffUntil.get(details);
            if (backoffUntil != null) {
                if (backoffUntil > currentTick) {
                    skippedMaterialBackoff++;
                    continue;
                }
                job.materialBackoffUntil.remove(details);
                job.materialBackoffMissing.remove(details);
            }
            if (job.dyeableRecursivePlan
                    && RecursiveTaskOrdering.hasPendingProducer(
                            job.tasks,
                            job.dyeableRecursiveInternalItems
                    )
                    && RecursiveTaskOrdering.shouldDeferConsumer(
                            details,
                            job.dyeableRecursiveInternalItems,
                            inventory,
                            job.tasks
                    )) {
                skippedDefer++;
                continue;
            }
            // 提供者预探测：提取输入发生在提供者探测之前曾是纯浪费——
            // 任务的所有提供者都忙时，本次提取 + 之后注回全部作废，还吃掉
            // 20ms 时间预算，排在后面的可派发任务因预算耗尽而饿死（表现为
            // 「发配单线化」）。isBusy 轮询有 providerChecks 预算覆盖且成本
            // 一次字段读，先探测再提取：全忙的任务零成本跳过。
            boolean stopAfterCurrentTask = false;
            boolean anyProviderReady = false;
            boolean neoEcoBatchReady = false;
            var taskProviders = craftingService.getProviders(details);
            for (ICraftingProvider provider : taskProviders) {
                var availability = checkProviderAvailability(provider, details, currentTick, budgetLedger,
                        providerBackoff);
                if (availability == ParallelCpuProviderBackoff.ProviderAvailability.READY) {
                    anyProviderReady = true;
                    if (provider instanceof git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchDispatcher
                            && !(details instanceof git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPattern)) {
                        neoEcoBatchReady = true;
                        break;
                    }
                    continue;
                }
                if (availability == ParallelCpuProviderBackoff.ProviderAvailability.BUDGET_EXHAUSTED) {
                    stopAfterCurrentTask = true;
                    break;
                }
            }
            if (!anyProviderReady) {
                skippedNoProvider++;
                continue taskLoop;
            }

            if (neoEcoBatchReady) {
                long batchPushed = pushNeoEcoBatch(
                        details,
                        task,
                        taskProviders,
                        energyService,
                        currentTick,
                        budgetLedger,
                        providerBackoff,
                        maxPatterns - pushedPatterns
                );
                if (batchPushed > 0L) {
                    pushedPatterns += batchPushed;
                    if (task.getValue().value <= 0L) {
                        job.tasks.remove(task.getKey());
                    }
                    continue taskLoop;
                }
            }

            var expectedOutputs = new KeyCounter();
            var expectedContainerItems = new KeyCounter();
            ParallelInputWitnessState taskWitness = job.exactInputWitness != null
                    && job.exactInputWitness.hasPattern(details)
                    ? job.exactInputWitness
                    : null;
            ParallelInputWitnessState.Prepared taskWitnessPrepared = null;

            @Nullable
            KeyCounter[] craftingContainer;
            boolean taskWitnessBlocked = false;
            if (taskWitness != null) {
                if (!hasTickBudget(budgetLedger)
                        || budgetLedger != null && !budgetLedger.tryClaimExtractPatternInputs()) {
                    craftingContainer = null;
                } else if (taskWitness.maxExecutions(details, inventory, 1L) <= 0L) {
                    craftingContainer = null;
                    taskWitnessBlocked = true;
                } else {
                    taskWitnessPrepared = taskWitness.prepare(
                            details,
                            inventory,
                            1L,
                            expectedOutputs,
                            expectedContainerItems
                    );
                    craftingContainer = taskWitnessPrepared == null ? null : taskWitnessPrepared.holder();
                }
            } else {
                craftingContainer = extractPatternInputsWithinBudget(
                        details,
                        inventory,
                        budgetLedger,
                        lane.cluster().level(),
                        expectedOutputs,
                        expectedContainerItems
                );
            }
            // 同一样板每次提取的输入容器内容一致 → 功耗恒定，按样板算一次在
            // 推送循环内复用，省去每轮推送的逐槽累加。
            double patternPower = craftingContainer == null
                    ? 0.0D
                    : CraftingCpuHelper.calculatePatternPower(craftingContainer);
            if (craftingContainer == null) {
                if (budgetLedger != null && budgetLedger.isExhausted()) {
                    // 提取失败源于预算耗尽（时间/计数）而非缺料：不登记
                    // 退避——20t 惩罚会把富料任务误判成缺料饿死；断点
                    // 续扫，下 tick 预算刷新自然重试。
                    stopAfterCurrentTask = true;
                } else if (taskWitnessBlocked) {
                    skippedDefer++;
                } else {
                    skippedExtractNull++;
                    recordMaterialBackoff(job, details, currentTick);
                }
            }

            if (budgetLedger != null && budgetLedger.isExhausted()) {
                stopAfterCurrentTask = true;
            }
            for (ICraftingProvider provider : taskProviders) {
                if (craftingContainer == null) {
                    break;
                }

                if (!hasTickBudget(budgetLedger)) {
                    stopAfterCurrentTask = true;
                    break;
                }

                ParallelCpuProviderBackoff.ProviderAvailability availability = checkProviderAvailability(
                        provider,
                        details,
                        currentTick,
                        budgetLedger,
                        providerBackoff);
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
                            providerBackoff.recordPushRejected(provider, details, currentTick);
                        }
                        break;
                    }
                    if (providerBackoff != null) {
                        providerBackoff.recordPushAccepted(provider, details);
                    }

                    craftingContainer = null;
                    energyService.extractAEPower(
                            patternPower,
                            Actionable.MODULATE,
                            PowerMultiplier.CONFIG
                    );
                    pushedPatterns++;
                    reserveExpectedWaiting(expectedOutputs, expectedContainerItems);
                    if (taskWitnessPrepared != null) {
                        taskWitness.commit(taskWitnessPrepared);
                        taskWitnessPrepared = null;
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
                        taskResumeIndex = (taskIndex + 1) % taskCount;
                        break taskLoop;
                    }

                    expectedOutputs.reset();
                    expectedContainerItems.reset();
                    if (taskWitness != null) {
                        if (!hasTickBudget(budgetLedger)
                                || budgetLedger != null && !budgetLedger.tryClaimExtractPatternInputs()) {
                            craftingContainer = null;
                        } else if (taskWitness.maxExecutions(details, inventory, 1L) <= 0L) {
                            craftingContainer = null;
                            taskWitnessBlocked = true;
                        } else {
                            taskWitnessPrepared = taskWitness.prepare(
                                    details,
                                    inventory,
                                    1L,
                                    expectedOutputs,
                                    expectedContainerItems
                            );
                            craftingContainer = taskWitnessPrepared == null
                                    ? null
                                    : taskWitnessPrepared.holder();
                        }
                    } else {
                        craftingContainer = extractPatternInputsWithinBudget(
                                details,
                                inventory,
                                budgetLedger,
                                lane.cluster().level(),
                                expectedOutputs,
                                expectedContainerItems
                        );
                    }
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
                // 提取成功却整轮零推送：持有 READY 提供者但 push 被拒——
                // 外部机器满载（如无用模组炉逐 bulk 慢处理）是流水线瓶颈，
                // 与缺料死锁区分开。
                if (anyProviderReady && pushedPatterns <= 0L) {
                    skippedPushRejected++;
                }
                boolean reinjectBudgetClaimed = tryClaimReinjectPatternInputs(budgetLedger);
                CraftingCpuHelper.reinjectPatternInputs(inventory, craftingContainer);

                if (!reinjectBudgetClaimed) {
                    taskResumeIndex = (taskIndex + 1) % taskCount;
                    break;
                }
            }
            if (stopAfterCurrentTask) {
                taskResumeIndex = (taskIndex + 1) % taskCount;
                break;
            }
        }
        // 完整扫完一轮（无断点）：断点归零，下 tick 从头公平开始
        taskResumeIndex = 0;

        StallProbe.noteDetail(pushedPatterns, skippedDefer, skippedNoProvider, skippedExtractNull,
                skippedPushRejected, skippedMaterialBackoff);
        return pushedPatterns;
    }

    private long pushNeoEcoBatch(
            IPatternDetails details,
            Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress> task,
            Iterable<ICraftingProvider> taskProviders,
            IEnergyService energyService,
            long currentTick,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            @Nullable ParallelCpuProviderBackoff providerBackoff,
            long pushAllowance
    ) {
        long requested = Math.min(task.getValue().value, pushAllowance);
        ParallelInputWitnessState taskWitness = job.exactInputWitness != null
                && job.exactInputWitness.hasPattern(details)
                ? job.exactInputWitness
                : null;
        if (taskWitness != null) {
            requested = taskWitness.dispatchLimit(details, inventory, requested);
        }
        if (requested <= 1L || !hasTickBudget(budgetLedger)
                || budgetLedger != null && (!budgetLedger.tryClaimExtractPatternInputs()
                || !budgetLedger.tryClaimReinjectPatternInputs()
                || !budgetLedger.tryClaimPatternPush())) {
            return 0L;
        }
        KeyCounter expectedOutputs = new KeyCounter();
        KeyCounter expectedRemainders = new KeyCounter();
        ParallelInputWitnessState.Prepared witnessPrepared = null;
        KeyCounter[] perCraftInputs;
        if (taskWitness != null) {
            witnessPrepared = taskWitness.prepareDispatch(
                    details, inventory, requested, expectedOutputs, expectedRemainders);
            if (witnessPrepared == null) {
                return 0L;
            }
            perCraftInputs = witnessPrepared.holder();
        } else {
            perCraftInputs = CraftingCpuHelper.extractPatternInputs(
                    details,
                    inventory,
                    lane.cluster().level(),
                    expectedOutputs,
                    expectedRemainders
            );
            if (perCraftInputs == null) {
                return 0L;
            }
        }
        CraftingCpuHelper.reinjectPatternInputs(inventory, perCraftInputs);

        for (ICraftingProvider provider : taskProviders) {
            if (!(provider instanceof git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchDispatcher dispatcher)) {
                continue;
            }
            var availability = checkProviderAvailability(
                    provider, details, currentTick, budgetLedger, providerBackoff);
            if (availability == ParallelCpuProviderBackoff.ProviderAvailability.BUDGET_EXHAUSTED) {
                return 0L;
            }
            if (availability != ParallelCpuProviderBackoff.ProviderAvailability.READY) {
                continue;
            }
            git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoPreparedBatch prepared =
                    dispatcher.chexsonsaeutils$prepareNeoEcoBatch(
                            details,
                            perCraftInputs,
                            expectedOutputs,
                            expectedRemainders,
                            inventory,
                            requested,
                            energyService,
                            lane.cluster().level(),
                            currentSourceCraftingId()
                    );
            PreparedBatchPushResult pushResult = pushPreparedNeoEcoBatch(prepared, inventory, requested);
            if (pushResult == PreparedBatchPushResult.NOT_BATCHED) {
                continue;
            }
            if (pushResult == PreparedBatchPushResult.REJECTED) {
                if (providerBackoff != null) {
                    providerBackoff.recordPushRejected(provider, details, currentTick);
                }
                continue;
            }
            long craftCount = prepared.craftCount();
            if (providerBackoff != null) {
                providerBackoff.recordPushAccepted(provider, details);
            }
            if (taskWitness != null) {
                taskWitness.commit(witnessPrepared, craftCount);
            }
            applyNeoEcoBatchSuccess(job, task.getValue(), prepared, energyService);
            return craftCount;
        }
        return 0L;
    }

    static PreparedBatchPushResult pushPreparedNeoEcoBatch(
            @Nullable git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoPreparedBatch prepared,
            ListCraftingInventory inventory,
            long dispatchLimit
    ) {
        if (prepared == null || prepared.craftCount() <= 1L) {
            return PreparedBatchPushResult.NOT_BATCHED;
        }
        if (prepared.craftCount() > dispatchLimit) {
            return PreparedBatchPushResult.REJECTED;
        }
        return prepared.push(inventory)
                ? PreparedBatchPushResult.ACCEPTED
                : PreparedBatchPushResult.REJECTED;
    }

    static PreparedBatchPushResult pushPreparedNeoEcoBatch(
            @Nullable git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoPreparedBatch prepared,
            ListCraftingInventory inventory
    ) {
        return pushPreparedNeoEcoBatch(prepared, inventory, Long.MAX_VALUE);
    }

    enum PreparedBatchPushResult {
        NOT_BATCHED,
        REJECTED,
        ACCEPTED
    }

    static void applyNeoEcoBatchSuccess(
            ParallelExecutingCraftingJob job,
            ParallelExecutingCraftingJob.TaskProgress task,
            git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoPreparedBatch prepared,
            IEnergyService energyService
    ) {
        long craftCount = prepared.craftCount();
        energyService.extractAEPower(prepared.power(), Actionable.MODULATE, PowerMultiplier.CONFIG);
        reserveExpectedWaiting(job, prepared.outputs(), prepared.remainders());
        for (GenericStack remainder : prepared.remainders()) {
            ParallelExecutingCraftingJob.addMaxItems(
                    job.timeTracker,
                    remainder.amount(),
                    remainder.what().getType()
            );
        }
        task.value -= craftCount;
    }

    private static boolean hasTickBudget(@Nullable ParallelCpuGridBudgetLedger budgetLedger) {
        if (budgetLedger == null) {
            return true;
        }
        long now = System.nanoTime();
        return budgetLedger.hasTimeBudget(now) && !budgetLedger.laneSliceExhausted(now);
    }

    /**
     * 登记缺料退避并记录缺口键索引：库存某缺口键变动时（{@code postChange}
     * 驱动 {@code wakeMaterialBackoff}）即刻唤醒，不等 TTL——退避只为省
     * 预算，不为延迟重试。
     *
     * <p>索引收录全部槽位的全部候选键：真实提取失败已经成立，无需再模拟
     * 猜测缺哪个键——重复槽（单槽够、总量不够）与模糊变体（模拟量够但
     * 模板不匹配）两种形态都会让「按模拟量筛选缺口键」漏掉真正的唤醒键，
     * 漏键只能等 TTL，大批量任务即为伪死锁。多索引一个富余键的代价只是
     * 一次多余重试。
     */
    private static void recordMaterialBackoff(
            ParallelExecutingCraftingJob job,
            IPatternDetails details,
            long currentTick
    ) {
        job.materialBackoffUntil.put(details, currentTick + MATERIAL_BACKOFF_TICKS);
        java.util.Set<AEKey> missing = new java.util.HashSet<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible == null || possible.length == 0) {
                continue;
            }
            for (GenericStack candidate : possible) {
                if (candidate == null || candidate.what() == null || candidate.amount() <= 0L) {
                    continue;
                }
                missing.add(candidate.what());
            }
        }
        job.materialBackoffMissing.put(details, missing);
    }

    /**
     * 库存键变动时唤醒以该键为缺口的退避任务（精准唤醒，替代 TTL 轮询）。
     * 模糊槽的候选键是主键模板，实际到达的可能是同类模糊变体（exact 键不同、
     * 主键相同）——精确匹配失败后按主键再比一次，变体入库同样能唤醒。
     */
    private void wakeMaterialBackoff(@Nullable AEKey what) {
        ParallelExecutingCraftingJob currentJob = job;
        if (what == null || currentJob == null || currentJob.materialBackoffMissing.isEmpty()) {
            return;
        }
        Object primary = null;
        for (var iterator = currentJob.materialBackoffMissing.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (entry.getValue().contains(what)) {
                currentJob.materialBackoffUntil.remove(entry.getKey());
                iterator.remove();
                continue;
            }
            if (primary == null) {
                primary = what.getPrimaryKey();
            }
            for (AEKey missingKey : entry.getValue()) {
                if (missingKey.getPrimaryKey().equals(primary)) {
                    currentJob.materialBackoffUntil.remove(entry.getKey());
                    iterator.remove();
                    break;
                }
            }
        }
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

        return craftingContainer;
    }

    private static long saturatingMultiply(long amount, long executions) {
        if (amount <= 0L || executions <= 0L) {
            return 0L;
        }
        if (amount > Long.MAX_VALUE / executions) {
            return Long.MAX_VALUE;
        }
        return amount * executions;
    }

    private static ParallelCpuProviderBackoff.ProviderAvailability checkProviderAvailability(
            @Nullable ICraftingProvider provider,
            IPatternDetails pattern,
            long currentTick,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            @Nullable ParallelCpuProviderBackoff providerBackoff
    ) {
        if (providerBackoff != null) {
            return providerBackoff.checkProvider(provider, pattern, currentTick, budgetLedger);
        }
        if (provider == null) {
            return ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF;
        }
        if (budgetLedger != null && !budgetLedger.tryClaimProviderCheck()) {
            return ParallelCpuProviderBackoff.ProviderAvailability.BUDGET_EXHAUSTED;
        }

        if (provider.isBusy()) {

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

    private void reserveExpectedWaiting(
            List<GenericStack> expectedOutputs,
            List<GenericStack> expectedContainerItems
    ) {
        if (job == null) {
            return;
        }
        for (GenericStack stack : expectedOutputs) {
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                job.waitingFor.insert(stack.what(), stack.amount(), Actionable.MODULATE);
            }
        }
        for (GenericStack stack : expectedContainerItems) {
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                job.waitingFor.insert(stack.what(), stack.amount(), Actionable.MODULATE);
            }
        }
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

    private static void reserveExpectedWaiting(
            ParallelExecutingCraftingJob job,
            List<GenericStack> expectedOutputs,
            List<GenericStack> expectedContainerItems
    ) {
        if (job == null) {
            return;
        }
        for (GenericStack stack : expectedOutputs) {
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                job.waitingFor.insert(stack.what(), stack.amount(), Actionable.MODULATE);
            }
        }
        for (GenericStack stack : expectedContainerItems) {
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                job.waitingFor.insert(stack.what(), stack.amount(), Actionable.MODULATE);
            }
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
                    var product = PlanAmount.of(output.amount())
                            .multiply(PlanAmount.of(task.getValue().value));
                    count = saturatedAdd(count, Ae2Amounts.toLong(product));
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
            wakeMaterialBackoff(what);
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
    /** 卡住探针：任务非空但连续无推送时低频记一行任务快照，不刷屏。 */
    private static final class StallProbe {
        private static long stalledTicks;
        private static String lastSignature;
        private static long lastDefer;
        private static long lastNoProvider;
        private static long lastExtractNull;
        private static long lastPushRejected;
        private static long lastMaterialBackoff;

        private StallProbe() {
        }

        /** 每轮 executeCrafting 结束调用：累积本轮跳过原因。 */
        static void noteDetail(long pushedPatterns, long skippedDefer, long skippedNoProvider,
                long skippedExtractNull, long skippedPushRejected, long skippedMaterialBackoff) {
            if (pushedPatterns > 0L) {
                return;
            }
            lastDefer = skippedDefer;
            lastNoProvider = skippedNoProvider;
            lastExtractNull = skippedExtractNull;
            lastPushRejected = skippedPushRejected;
            lastMaterialBackoff = skippedMaterialBackoff;
        }
        static void note(long pushedPatterns, @Nullable ParallelExecutingCraftingJob job,
                ListCraftingInventory inventory, @Nullable appeng.api.networking.IGrid grid) {
            if (job == null || pushedPatterns > 0L || job.tasks.isEmpty()) {
                stalledTicks = 0L;
                lastSignature = null;
                return;
            }
            // 任务集合变化即重置计数——只对"同一批任务长期推不动"报警。
            StringBuilder signature = new StringBuilder();
            signature.append(job.tasks.size()).append('|').append(job.remainingAmount).append('|')
                    .append(job.waitingFor.list.size()).append('|');
            for (var task : job.tasks.entrySet()) {
                signature.append(task.getKey() == null ? "null"
                        : task.getKey().getClass().getSimpleName()).append(':')
                        .append(task.getValue() == null ? -1L : task.getValue().value).append(';');
            }
            String current = signature.toString();
            if (!current.equals(lastSignature)) {
                lastSignature = current;
                stalledTicks = 0L;
            }
            if (++stalledTicks == 100L) {
                StringBuilder detail = new StringBuilder();
                for (var task : job.tasks.entrySet()) {
                    if (detail.length() > 0) {
                        detail.append(", ");
                    }
                    detail.append(task.getKey() == null ? "null" : task.getKey().getDefinition())
                            .append(" x ").append(task.getValue() == null ? -1L : task.getValue().value);
                    // 任务状态标记：backoff=缺料退避中（提取曾失败）；
                    // 无标记却被停滞覆盖 = noProvider/pushRejected/预算路径，
                    // 结合头部计数即可定位每个任务的卡点。
                    if (task.getKey() != null && job.materialBackoffUntil.containsKey(task.getKey())) {
                        detail.append(" [backoff]");
                    }
                    if (detail.length() > 600) {
                        detail.append("...(truncated)");
                        break;
                    }
                }
                AELog.info(
                        "[chexsonsaeutils-stall] no push for 100 ticks: tasks=%d remaining=%d waitingFor=%d recursive=%s"
                                + " defer=%d noProvider=%d extractNull=%d pushRejected=%d materialBackoff=%d"
                                + " tasks=[%s]%s",
                        job.tasks.size(), job.remainingAmount, job.waitingFor.list.size(),
                        job.dyeableRecursivePlan, lastDefer, lastNoProvider, lastExtractNull,
                        lastPushRejected, lastMaterialBackoff, detail,
                        describeMissingInputs(job, inventory, grid));
            }
        }

        /**
         * 停滞报警时的一次性缺料扫描：每个任务每槽按首选模板估缺口
         * （需 multiplier×剩余 − 库存现量），聚合后取前 10 键，并对照
         * 网络存量——CPU 缺的键网络里却有海量存量 = 虚拟产出路由丢失
         * 实锤（产出进了普通网络而非回到 CPU 的 waitingFor）。
         */
        private static String describeMissingInputs(
                ParallelExecutingCraftingJob job,
                ListCraftingInventory inventory,
                @Nullable appeng.api.networking.IGrid grid
        ) {
            appeng.api.networking.storage.IStorageService storageService = grid == null
                    ? null
                    : grid.getStorageService();
            java.util.Map<AEKey, Long> deficits = new java.util.LinkedHashMap<>();
            for (var task : job.tasks.entrySet()) {
                IPatternDetails details = task.getKey();
                long remaining = task.getValue() == null ? 0L : task.getValue().value;
                if (details == null || remaining <= 0L) {
                    continue;
                }
                for (IPatternDetails.IInput input : details.getInputs()) {
                    if (input == null) {
                        continue;
                    }
                    GenericStack[] possible = input.getPossibleInputs();
                    if (possible == null || possible.length == 0) {
                        continue;
                    }
                    GenericStack primary = possible[0];
                    if (primary == null || primary.what() == null) {
                        continue;
                    }
                    long needed = saturatingMultiply(input.getMultiplier(), remaining);
                    long available = Math.max(
                            0L, inventory.extract(primary.what(), Long.MAX_VALUE, Actionable.SIMULATE));
                    if (needed > available) {
                        deficits.merge(primary.what(), needed - available, Long::sum);
                    }
                }
            }
            if (deficits.isEmpty()) {
                return "";
            }
            StringBuilder missing = new StringBuilder(" missing=[");
            int printed = 0;
            for (var entry : deficits.entrySet()) {
                if (printed++ >= 10) {
                    missing.append("...(more)");
                    break;
                }
                if (printed > 1) {
                    missing.append(", ");
                }
                missing.append(entry.getKey()).append(" x ").append(entry.getValue());
                if (storageService != null) {
                    long networkAvailable = Math.max(0L, storageService.getInventory()
                            .extract(entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE,
                                    appeng.api.networking.security.IActionSource.empty()));
                    if (networkAvailable > 0L) {
                        missing.append(" (net: ").append(networkAvailable).append(')');
                    }
                }
            }
            return missing.append(']').toString();
        }
    }

    /** [ecoloop] 探针归并器：同组合连续重复只计数，不刷屏。 */
    private static final class EcoLoopProbe {
        private static String lastKey;
        private static long runCount;

        private EcoLoopProbe() {
        }

        static synchronized void note(String providerClass, boolean ecoHandles) {
            String key = providerClass + "|" + ecoHandles;
            if (key.equals(lastKey)) {
                runCount++;
                return;
            }
            flush();
            lastKey = key;
            runCount = 1;
        }

        private static void flush() {
            if (lastKey != null) {
                System.out.println("[ecoloop] provider states x" + runCount + ": " + lastKey);
            }
        }
    }

}
