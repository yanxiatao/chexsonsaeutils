package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import git.chexson.chexsonsaeutils.crafting.plan.PatternRosterReadout;

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
import git.chexson.chexsonsaeutils.bigint.ClampedMath;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchPlannedOutputs;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilities;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatchDispatcher;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveTaskOrdering;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalCarrier;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchReceipt;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputClaim;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalDrops;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class ParallelCraftingCpuLogic {

    /** 缺料退避 TTL（tick）：材料到达后至多 1 秒重试。 */
    private static final long MATERIAL_BACKOFF_TICKS = 20L;

    /** 同一类停滞日志的最小间隔（tick）：一秒一行封顶，绝不刷屏。 */
    private static final long STALL_LOG_TICKS = 100L;

    private final ParallelCraftingLaneState lane;
    /** 池子物理进出账：挂在唯一的实物进出口上，任何提料路径都漏不掉记账。 */
    // 包级可见只为让用例把账记在产线那本<b>同一本</b>账上（停滞行 poolKeys= 那一格读的就是它），
    // 与 newLedgerBackedPool 的同一条理由；生产侧没有第二个读口。
    final ParallelPoolLedger poolLedger = new ParallelPoolLedger();
    /**
     * 物理池：<b>字段声明类型仍是 AE2 基类</b>{@link ListCraftingInventory}，装的是
     * {@link WideCraftingInventory}（主表 + 溢出侧表），与作业侧产出缓冲 {@code waitingFor}
     * （{@code e76b728}）同一条换装规则：只换实现、不碰基类签名。
     *
     * <p>不这么换，同键多笔 ≤{@code long} 的回池累加会把 {@code KeyCounter} 裸加到绕回
     * （{@code 3×Long.MAX → Long.MAX-2}，与老产出缓冲同一条病），越界那一截在池里根本不存在，
     * 退网也就无从交起。换装后溢出键对基类身份调用方（{@code getStored}、{@code extractBuffered}、
     * {@code getAllItems}…）恒报饱和 {@link Long#MAX_VALUE}——与 {@code InfinityCellStore.visibleAmount}
     * 对网络报数同一条口径；精确值只经 {@link WideCraftingInventory#amount} 与
     * {@link WideCraftingInventory#extractExact} 这两个自有宽域读口出去
     * （本文件的调用点：{@link #storeWideInventory} 的分轮交付与停滞日志的 inv= 精确段）。
     */
    private final ListCraftingInventory inventory =
            newLedgerBackedPool(poolLedger, this::postChange);

    /**
     * 物理池的<b>唯一</b>构造口：把进出读数钉在同一个池子对象上（台账 #87 的收口点）。
     *
     * <p>AE2 基类那两条 {@code long} 口（{@code extract}／{@code insert}）覆写好懂，但
     * {@link WideCraftingInventory} 换装后多出一对<b>宽域直写／直取口</b>：
     * <ul>
     *   <li>{@code extractExact} 恒走内部 {@code extractWide}，<b>一次都不经过</b> {@code extract(long)}
     *       ⇒ 退网交付（{@link #storeWideInventory} 每轮把残池分轮回投网络，走的正是这一张嘴）
     *       在 {@code out} 那一格里整段隐身；</li>
     *   <li>{@code insertExact} 只在塞得进 {@code long} 时退回 {@code insert(long)}（那一支本来就
     *       被记），越界那一支直写侧表 ⇒ 续传读回的池子（{@link #readFromNBT} 打的
     *       {@code resume} 那一格）里 {@code > long} 的那一截隐身，同一笔账按大小两种形状只记一半。</li>
     * </ul>
     *
     * <p>修法是在这<b>同一个</b>池子对象上把两条宽域口也覆写一遍、记进<b>同一本</b>账：不新增
     * 账本、不给 {@code in/out} 加第二把读尺（{@code long} 那一笔与宽域那一笔走
     * {@code ParallelPoolLedger#book} 里同一条饱和合并）。
     * {@code insertExact} 只在越界那一支补记，否则与它内部退回的 {@code insert(long)} 双计一次。
     *
     * <p>包级可见只为让用例拿到<b>产线同一个</b>池子形状（{@code ParallelWidePoolRoundStoreTest}
     * 的 {@code in−out＝净变化} 那一格）——在这里复刻一份匿名子类就是测自己写的东西。
     */
    static WideCraftingInventory newLedgerBackedPool(ParallelPoolLedger ledger,
            ListCraftingInventory.ChangeListener listener) {
        return new WideCraftingInventory(listener) {
            @Override
            public long extract(AEKey what, long amount, Actionable mode) {
                long extracted = super.extract(what, amount, mode);
                if (mode == Actionable.MODULATE) {
                    ledger.noteOut(what, extracted);
                }
                return extracted;
            }

            @Override
            public void insert(AEKey what, long amount, Actionable mode) {
                if (mode == Actionable.MODULATE) {
                    ledger.noteIn(what, amount);
                }
                super.insert(what, amount, mode);
            }

            @Override
            public BigInteger extractExact(AEKey what, @Nullable BigInteger amount, Actionable mode) {
                BigInteger got = super.extractExact(what, amount, mode);
                // 这条口从不回调 extract(long)（内部直取侧表）⇒ 无条件补记，不存在双计
                if (mode == Actionable.MODULATE) {
                    ledger.noteOut(what, got);
                }
                return got;
            }

            @Override
            public void insertExact(AEKey what, @Nullable BigInteger amount, Actionable mode) {
                // 只补记越界那一支：塞得进 long 时 super 内部退回 this.insert(long)，已被上面
                // 那条 insert 覆写记过一次，这里再记就是把同一笔投料算两遍
                if (mode == Actionable.MODULATE && amount != null && amount.bitLength() > 63) {
                    ledger.noteIn(what, amount);
                }
                super.insertExact(what, amount, mode);
            }
        };
    }
    /**
     * 任务级断点续扫索引：时间预算（20ms）耗尽 break 时记录下一个未评估
     * 任务，下 tick 从断点继续。此前任务表每 tick 从头扫，头部活跃任务
     * （持续推送的多推任务）每轮评估耗光时间预算，排序靠后的任务永远
     * 轮不到评估——「CPU 库存有料、任务却永不执行」的任务级饥饿即此
     * （lane 级早有轮转，任务级缺失是最后一块）。
     */
    private int taskResumeIndex;
    /** 任务成功会删表，跨 tick 必须按样板身份续扫，旧下标只适用于同一份快照。 */
    @Nullable private IPatternDetails taskResumePattern;
    /** 本 tick 任务循环实际评估了几条（诊断：断点是否真的在推进，还是每 tick 都从头重扫）。 */
    private long lastSweepVisited;
    /** 诊断：提取失败后从网络搬进 CPU 库存的批数与件数（0 批 = 这条通道没起作用）。 */
    private long lastTaskTopUpBatches;
    private long lastTaskTopUpUnits;
    /**
     * 诊断（分刀推进读数）：这条回补通道累计搬进池子的<b>刀数</b>与<b>真值合计</b>。
     *
     * <p>{@link #lastTaskTopUpUnits} 是 {@code long} 的饱和累加：一发真需 {@code >long} 时它第二轮
     * 起就钉在 {@code Long.MAX_VALUE} 上不再前进，停滞行于是把"每轮补进一刀"读成"完全没推进"。
     * 真值这一格走 {@link BigInteger} 才分得出这两件事。<b>纯读数</b>：不参与任何判据，
     * 停滞判定本身一字未改（也不加自动取消/自动终止）。
     */
    private long lastTaskTopUpChunks;
    private BigInteger lastTaskTopUpExactUnits = BigInteger.ZERO;
    /**
     * 诊断（台账 #225 读数 6，<b>逐枚键</b>的回补流水）：与上面那两格<b>同一本账、同一个窗</b>，
     * 只是把聚合拆到键——"这一百拍里这一枚键自己被网络交回几枚"。
     *
     * <p>为什么聚合那一格不够：{@code topUpExact=} 说的是"这条 lane 一共搬进几枚"，同窗里两张样板
     * 三枚键的回补全糊在同一个数上；实机要问的是"卡住这条 lane 的那一枚键到底回没回、回了几枚"，
     * 聚合读数答不了（"这一枚一枚没回"与"这一枚回了但不够"长得一样）。
     *
     * <p>口径与 {@link #lastTaskTopUpExactUnits} 逐字相同：{@code refill.pull} 真交回且真进了池子的
     * 那一刀才记（{@link ParallelBatchExtraction.Probe#refillUnitsPerKey}），lane 侧从探针的累计读数
     * 里<b>取差值</b>折进来（{@link #foldTaskTopUpUnitsPerKey}），跨 tick 不重复记、不夹不发。
     * 值是 {@link BigInteger} 精确原文，越出 {@code long} 不饱和，顶格哨兵不许冒充量。
     *
     * <p><b>纯读数</b>：不参与任何判据，不为它加绕过／重试／兜底，停滞判定与搬运口径一字未动。
     */
    private final java.util.Map<AEKey, BigInteger> lastTaskTopUpUnitsPerKey =
            new java.util.LinkedHashMap<>();
    /** 逐键读数一格里最多点几枚键（纯读数封顶，与 {@code 同族键数} 那一族同一形状）：剩下的只报格数。 */
    private static final int TOP_UP_KEY_READOUT_LIMIT = 4;
    /**
     * 诊断：最后一次 noCand 的键、现场量与配额读数。三个负码各指一个因（见
     * {@link #describeNoCandQuota}）：{@code -1} 无配额口径、{@code -2} 这一格没递出可计数候选、
     * {@code 0} 被配给夹死。
     */
    private String lastNoCandKey = "";
    private long lastNoCandStored;
    private long lastNoCandQuota = -2L;
    /** 义务交割停滞的上一次记行 tick：只在"欠着料却一批都没交割"时低频记一行。 */
    private long lastObligationStallWarnTick;
    /** 每条产线独立的宿主能力会话，CPU 仅依赖契约。 */
    private ProviderBatchCapabilities batchCapabilities;
    private static final String EMPTY_QUEUE_PENDING_READOUT =
            " queuePending[useless侧]=? queuePendingCov=shown=0/total=0";
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

    /** 首次真实探测创建会话，后续整个产线生命周期复用同一份缓存。 */
    private ProviderBatchCapabilities batchCapabilities() {
        if (batchCapabilities == null) {
            batchCapabilities = lane.cluster().createBatchCapabilities();
        }
        return batchCapabilities;
    }

    /** 未探测宿主的产线没有已推送机器，诊断不提前创建会话。 */
    private String queuePendingReadout() {
        return batchCapabilities == null ? EMPTY_QUEUE_PENDING_READOUT : batchCapabilities.queuePendingReadout();
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
            return lane.cluster().offlineSubmission(grid, "lane-host-inactive");
        }

        if (!inventory.list.isEmpty()) {
            AELog.warn("Parallel crafting CPU inventory is not empty yet a job was submitted.");
        }

        poolLedger.reset();
        String submitPath = poolLedger.path("submit");
        var forgivenUnbounded = new java.util.HashSet<AEKey>();
        var missingIngredient = tryExtractInitialItems(
                plan, grid.getStorageService().getInventory(), inventory, src, forgivenUnbounded);
        poolLedger.restore(submitPath);
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
        taskResumeIndex = 0;
        taskResumePattern = null;
        for (AEKey forgiven : forgivenUnbounded) {
            // 提交这一步核对的无限来源键要立刻进作业：后面每一批的配额封顶、占料锁与
            // waitingFor 都按这份集合放行，晚一步就又是一份永远等不到的"正在合成"。
            this.job.noteUnboundedSupply(forgiven);
        }
        this.requesterLink = null;
        suppressChangeNotifications = false;
        initializePlannedInitialItems(this.job, plan, inventory);
        initializeSubmitMaterials(this.job, plan);
        logSubmitBoundary(plan);

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

        String partialPath = poolLedger.path("partial");
        // 部分提交允许未来补料，原计划配额必须保留；每次执行仍只花池内真实材料。
        KeyCounter missingInitialItems = CraftingContinuationPartialSubmit.extractAvailableInitialItems(
                plan, grid, inventory, src);
        poolLedger.restore(partialPath);
        seedInitialInputs(this.job, missingInitialItems, this::postChange,
                grid.getStorageService().getInventory(), src, inventory);
        initializePlannedInitialItems(this.job, plan, inventory);
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

        if (job.link.isCanceled() && !job.cancellationRequested) {
            cancel();
            if (job == null) return false;
        }

        // 暂停只停止派发，不能阻止取消后偿付已经被宿主接受的材料义务。
        if (job.suspended && !job.cancellationRequested && !job.completionRequested) {
            return true;
        }

        if (!job.cancellationRequested) drainStandaloneFinalOutput();
        if (job == null) return false;

        long pushedPatterns = 0L;

        // CPU 不再按协处理器数、三 tick usedOps 或任务数限制样板份数。
        // 唯一调度上限是 grid/lane 的墙钟预算，MSPT 保护仍由 hasTickBudget 保留。
        if (hasTickBudget(budgetLedger)) {
            var orderedTasks = RecursiveTaskOrdering.orderedEntries(
                    job.tasks,
                    ringInternalItemsForOrdering(job)
            );
            pushedPatterns = executeCrafting(
                    orderedTasks,
                    craftingService,
                    energyGrid,
                    currentTick,
                    budgetLedger,
                    providerBackoff
            );
        }
        var gridContext = lane.cluster().gridContext();
        if (ChexsonsaeutilsCompatibilityConfig.parallelCpuDiagnosticsEnabled()) {
            stallProbe.note(pushedPatterns, job, this.inventory,
                    gridContext == null ? null : gridContext.grid());
        }
        // 收尾补问一次同一把尺：递归计划那条判据的三个求值点全在 insertInternal 里，而终点产物走
        // 「申报批→宿主直投网络」那一腿时 AE2 一句 insert 都不进本类（那条通道只写预期入网账、
        // 不写交付账，也碰不到入账口）。任务表此时已经排空、executeCrafting 在 job.tasks.isEmpty()
        // 处直接 return，于是 per100r 与 stall 两条读数一起哑火——实机 lane 00587962 那一跑在
        // 清单={ae2:creative_storage_cell=10000} 之后一行不剩、永远不收尾，环起步借的那颗种跟着
        // 卡在池里（下一条 lane 的环因此饿死）。
        // 判据两臂与三本账一字未动：这里改的只是「什么时候问」——由「只有实物到账才问」扩到「每 tick 也问」。
        if (job.cancellationRequested || job.completionRequested) {
            finishJob(!job.cancellationRequested);
        } else {
            finishDyeableRecursiveJobIfComplete();
            if (isDeclaredNetworkJobComplete(job)) finishJob(true);
        }
        return pushedPatterns <= 0L;
    }

    /**
     * 排序用的环内部件表：本类不另写一份算式，一律读作业自己那一份（{@link
     * ParallelExecutingCraftingJob#orderingInternalItems()}）。
     *
     * <p>这把尺有三条用途共用一个算式：本类的推送顺位（{@link #tickCraftingLogic}）、提交期
     * mint 那趟排水的顺位（{@link #initializeSubmitMaterials}），以及作业侧的族到账摊派顺位。写两份
     * 必然漂移，漂的那天摊派读的就不是当初 mint 配给那个顺位（{@code 8ad7ca8} 留下的账）。
     *
     * <p>计划在提交链上可能被第三方重写成裸 {@code CraftingPlan}，契约字段随之消失，
     * 那时只剩现算的那份——它只看任务表拓扑，所以剥壳也不影响排序结论。
     */
    static KeyCounter ringInternalItemsForOrdering(ParallelExecutingCraftingJob job) {
        return job.orderingInternalItems();
    }

    /** 删除已完成任务后重新定位尚未评估的身份，不能沿用旧快照下标跳过刚变为就绪的任务。 */
    static <T> int resumeTaskIndex(List<Map.Entry<IPatternDetails, T>> tasks,
            @Nullable IPatternDetails nextPattern, int previousIndex) {
        if (tasks.isEmpty()) return 0;
        if (nextPattern == null) return Math.floorMod(previousIndex, tasks.size());
        for (int index = 0; index < tasks.size(); index++) {
            if (tasks.get(index).getKey() == nextPattern) return index;
        }
        return 0;
    }

    private long executeCrafting(
            List<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> orderedTasks,
            CraftingService craftingService,
            IEnergyService energyService,
            long currentTick,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            @Nullable ParallelCpuProviderBackoff providerBackoff
    ) {

        if (job == null) {
            return 0L;
        }
        boolean settlingDeliveredOrder = job.cancellationRequested || job.completionRequested
                || (!job.dyeableRecursivePlan && job.remainingAmount == 0)
                || (job.dyeableRecursivePlan && finalOutputQuantityDelivered(job));
        if (job.tasks.isEmpty() && job.initialItemsRemaining.list.isEmpty()
                && !settlingDeliveredOrder && !job.hasUnsettledBatchObligations()) return 0L;

        var grid = lane.cluster().grid();
        if (grid != null) {
            if (job.unboundedSupplyRefreshPending) {
                refreshJobUnboundedSupply(job, grid.getStorageService().getInventory(), lane.cluster().source(), grid);
                job.unboundedSupplyRefreshPending = false;
            }
            if (!settlingDeliveredOrder) {
                String plannedPath = poolLedger.path("plannedInitialItems");
                try {
                    extractPlannedInitialItems(job, inventory, grid.getStorageService().getInventory(),
                            lane.cluster().source(), () -> hasTickBudget(budgetLedger));
                } finally {
                    poolLedger.restore(plannedPath);
                }
            }
        }

        // 占料账本每 tick 随任务表算一次：多候选槽按顺位取料时，不能把别的任务
        // 唯一能用的现场料吃掉（规划期那格的现场料是空的，账本才看得见真实归属）。
        // 配给本身在提交期定版，这里只按它销过的账折出剩余份额。
        // 环种账也现算一份交进去：环分量自己还没跑完的那一截种子要在取料点上先扣掉，
        // 否则配给那条早退会把池里那颗种一次让旁人抽走（实机 lane 48739137 的饿死形状）。
        // 停止派发后，未执行任务不再占料或保留环种；已接单批次仍按原配额实际付款。
        var planReservation = settlingDeliveredOrder
                ? ParallelPlanReservation.build(List.of(), snapshotPool(inventory),
                        job.materialAllocation, job.unboundedSupplyKeys)
                : ParallelPlanReservation.build(orderedTasks, snapshotPool(inventory),
                        job.materialAllocation, job.unboundedSupplyKeys,
                        job.ringSeeds.seedDemandsByFamily(), job.batchObligations);

        long pushedPatterns = 0L;
        boolean diagnostics = ChexsonsaeutilsCompatibilityConfig.parallelCpuDiagnosticsEnabled();
        BigInteger nonDeclaredPushedExact = BigInteger.ZERO;
        // 本 tick 的可供功率只探一次，随每次推送扣减：逐任务探测会把一次网格
        // 查询放大成 380 次，任务表越大越吃预算。
        double availablePower = settlingDeliveredOrder ? 0.0 : energyService.extractAEPower(
                Double.MAX_VALUE, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        // 卡住归因计数：无推送 tick 结束时随快照一起输出，一眼定位环节。
        long skippedNoProvider = 0L;
        long skippedExtractNull = 0L;
        long skippedPushRejected = 0L;
        long skippedMaterialBackoff = 0L;
        // 批量决策链读数：一次交互推进几发，卡在哪一闸口——
        // cap=宿主认得的份数上界，req=我方请求的份数，got=提取实际给到的份数，
        // pushK=真正推出去的份数，foldHosts/foldNull=折叠宿主的在场与折不动次数。
        long batchCapMax = 0L;
        long batchReqMax = 0L;
        long batchGotMax = 0L;
        long batchPushKMax = 0L;
        long batchFoldHost = 0L;
        long batchFoldNull = 0L;
        long batchPowerMin = Long.MAX_VALUE;
        long batchRemainMax = 0L;
        var batchProbe = new ParallelBatchExtraction.Probe();
        // 先交割上一轮声明批量的义务材料，再谈本轮推送：欠着的料还算在池子里，顺序反了
        // 就会把同一份存量既当"已声明份数的口粮"又当"新批次的可供量"。
        payDeclaredBatchObligations(job, orderedTasks, planReservation, budgetLedger, batchProbe,
                currentTick);
        // 订单实物已交齐：只付清已接单批次，停止新派发和计划原料预取。
        if (settlingDeliveredOrder) {
            // 直投网络回执可先把订单清零；付款后仍须移除原先因欠账而保留的零发任务。
            job.tasks.entrySet().removeIf(task -> taskMayLeaveTable(
                    task.getKey(), task.getValue(), job.batchObligations));
            return 0L;
        }
        // 按宿主类名统计可用性：[READY, BUSY, 退避, 预算耗尽, 推送成功]。
        // 折叠宿主读数为 0 时，先看清是哪一类宿主在真正收料、哪一类永远忙。
        var providerStats = new java.util.HashMap<String, long[]>();
        int taskCount = orderedTasks.size();
        int startIndex = resumeTaskIndex(orderedTasks, taskResumePattern, taskResumeIndex);
        int swept = 0;
        boolean sweepCompleted = true;
        taskLoop:
        for (int taskOffset = 0; taskOffset < taskCount; taskOffset++) {
            swept++;
            int taskIndex = (startIndex + taskOffset) % taskCount;
            var task = orderedTasks.get(taskIndex);
            if (!hasTickBudget(budgetLedger)) {
                // 本任务未评估：断点留在本任务，下 tick 重试它
                taskResumeIndex = taskIndex;
                taskResumePattern = task.getKey();
                sweepCompleted = false;
                break;
            }
            if (taskMayLeaveTable(task.getKey(), task.getValue(), job.batchObligations)) {
                job.tasks.remove(task.getKey());
            }
            if (task.getValue().isFinished()) {
                // 发数跑完还不算收工：声明批量的欠账挂在条目上，材料没交割完就不出表
                // （不出表也就不会被 NBT 落盘时丢掉，claim 也就仍然遮得住它）。
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
                job.materialWakeIndex.remove(details);
            }
            // 环种的"这一轮不许被旁人抽走"不在这里判：它只有取料点上那一份尺（留底见
            // ParallelPlanReservation#spendableAfterRingSeedReserve，夹完的 spendable 由配给分支与
            // 占料分支共用）。过去这里另有一把"单发让路"的尺（只看一发、只看池子、喂契约那份内部物
            // 表），同一判据两份实现——它对"一批吃两枚"那一档压根看不见（实机 taken=3 就是这么漏的），
            // 而留底按整批发数夹得更严；豁免名单也比这里宽（链式环的成员在留底那侧成环、在这里靠
            // 输出喂没喂上待办 producer 判）。原生 AE2 CPU 那一腿仍走这把旧尺（回落腿不动），
            // 见 mixin/ae2/crafting/CraftingCpuLogicRecursivePlanMixin。
            // 提供者预探测：提取输入发生在提供者探测之前曾是纯浪费——
            // 任务的所有提供者都忙时，本次提取 + 之后注回全部作废，还吃掉
            // 20ms 时间预算，排在后面的可派发任务因预算耗尽而饿死（表现为
            // 「发配单线化」）。isBusy 轮询有 providerChecks 预算覆盖且成本
            // 一次字段读，先探测再提取：全忙的任务零成本跳过。
            boolean stopAfterCurrentTask = false;
            boolean anyProviderReady = false;
            boolean preparedBatchReady = false;
            DeclaredBatchHost declaredBatchHost = null;
            long holderBatchCapacity = 0L;
            var taskProviders = craftingService.getProviders(details);
            for (ICraftingProvider provider : taskProviders) {
                // 这一维问的是 holder 腿：申报腿那笔拒收退避不许在这里把整个任务挡掉（B1 要的正是
                // "同轮 holder 路可达"），申报腿自己的探测在下面的声明能力分支里单独问一次。
                var availability = checkProviderAvailability(provider, details, currentTick,
                        providerBackoff, ParallelCpuProviderBackoff.PushLeg.HOLDER);
                noteProviderClass(providerStats, provider, availability);
                if (availability == ParallelCpuProviderBackoff.ProviderAvailability.READY) {
                    anyProviderReady = true;
                    if (declaredBatchHost == null) {
                        declaredBatchHost = batchCapabilities().nativeDeclaredHost(provider, details,
                                host -> declaredProbeReady(host, details, currentTick, providerBackoff));
                    }
                    var holderCapability = batchCapabilities().holderCapability(provider);
                    if (holderCapability != null) {
                        if (holderCapability.foldsPattern()) {
                            batchFoldHost++;
                        }
                        holderBatchCapacity = Math.max(holderBatchCapacity,
                                holderCapability.maxPushExecutions(details));
                    }
                    if (declaredBatchHost == null) {
                        declaredBatchHost = batchCapabilities().adaptedDeclaredHost(provider, details,
                                host -> declaredProbeReady(host, details, currentTick, providerBackoff));
                    }
                    if (provider instanceof PreparedBatchDispatcher) {
                        preparedBatchReady = true;
                        // 继续探测同样板的其他宿主，不能让先出现的 long 准备接口遮住宽域声明接口。
                    }
                    continue;
                }
            }
            if (!anyProviderReady) {
                skippedNoProvider++;
                continue taskLoop;
            }

            var expectedOutputs = new KeyCounter();
            var expectedContainerItems = new KeyCounter();
            long powerCeiling = powerAffordableExecutions(details, availablePower);
            // 份数声明形态：宿主肯照收申报份数（默认不覆写 supportsDeclaredBatch ⇒ 探测不到 ⇒
            // 下面这一段整块不执行，行为与现在逐字一致）
            if (declaredBatchHost != null) {
                DeclaredPush push = attemptDeclaredBatch(
                        declaredBatchHost,
                        details,
                        job,
                        orderedTasks,
                        inventory,
                        lane.cluster().level(),
                        planReservation,
                        poolLedger,
                        expectedOutputs,
                        expectedContainerItems,
                        powerCeiling,
                        holderBatchCapacity,
                        batchProbe,
                        providerStats,
                        providerBackoff,
                        budgetLedger,
                        currentTick
                );
                if (push != null) {
                    if (push.accepted() > 0L) {
                        if (declaredBatchHost instanceof DeclaredBatchTerminalCarrier carrier) {
                            bindDeclaredOutputReceiver(carrier.lastAcceptedBatchReceipt());
                        }
                        // 功率与 k 倍 holder 路径同口径：一次交互整体结算（一发原型功耗 × 实收份数），
                        // 但实际向电网要的电封顶在 int 那一档：份数照常聚合，不为电付两次钱。
                        double powerDue = push.perFiringPower() * (double) push.accepted();
                        availablePower -= energyService.extractAEPower(
                                billedPower(powerDue), Actionable.MODULATE, PowerMultiplier.CONFIG);
                        // 申报份数量级到 1e18，累加必须饱和：回绕成负数就把"本 tick 已推爆"
                        // 读成"一发没推"，任务表断点与停滞判据全被带偏。
                        pushedPatterns = pushedPatterns > Long.MAX_VALUE - push.accepted()
                                ? Long.MAX_VALUE : pushedPatterns + push.accepted();
                        batchPushKMax = Math.max(batchPushKMax, push.accepted());
                        batchGotMax = Math.max(batchGotMax, push.accepted());
                        batchRemainMax = Math.max(batchRemainMax, task.getValue().value);
                        settleImmediateBatch(declaredBatchHost, job, details, inventory,
                                lane.cluster().level(), planReservation, poolLedger, batchProbe,
                                (sourceGrid, what, available) -> sourceGrid == lane.cluster().grid()
                                        ? claimDeclaredOutput(what, available) : BigInteger.ZERO);
                        lane.cluster().refreshLaneState(lane);
                        if (taskMayLeaveTable(task.getKey(), task.getValue(),
                                job.batchObligations)) {
                            job.tasks.remove(task.getKey());
                        }
                        continue taskLoop;
                    }
                    if (push.rejected()) {
                        // 宿主整批拒收：原型料已在 attemptDeclaredBatch 里原数回池、义务账与销账
                        // 一分未挂（那里拒收分支只调 reinjectInputs），所以本张样板<b>同轮继续</b>
                        // 落到下面的 k 倍 holder 段。旧实现在这里 continue taskLoop：宿主每轮整批拒
                        // 就把 holder 路一起吞掉，实机锁成活锁（declaredReject=99/轮、declared=0、
                        // pushed=0）。这里不新增退避、闸门或重试计数，只是让另一条既有推送路走得到。
                        skippedPushRejected++;
                    }
                    // 本轮收不收工只有这一个判据（DeclaredPush#endsTaskRound）：申报批结算才收工，
                    // 拒收不收工——holder 段读的是同一个池子，料已原数回来，提得出几发就推几发。
                    // 原型那一趟凑不齐一发也不再收工：它与 declared==null 同一条出口交回 null，
                    // "从网络回补一次 + 缺料退避"那一条收尾由下面 holder 段那一趟做（旧那支
                    // extractFailed 在两条腿上各做一遍，读数会双记）。
                    if (push.endsTaskRound()) {
                        continue taskLoop;
                    }
                }
            }

            // 原生宽域声明优先：NeoEco 准备接口仍以 long 请求并按输入倍率限量。
            // 声明不适用或拒收后，才尝试其既有准备路径。
            if (preparedBatchReady) {
                long batchPushed = pushPreparedBatch(
                        job,
                        inventory,
                        poolLedger,
                        lane.cluster().level(),
                        currentSourceCraftingId(),
                        details,
                        task,
                        taskProviders,
                        energyService,
                        currentTick,
                        budgetLedger,
                        providerBackoff,
                        planReservation
                );
                if (batchPushed > 0L) {
                    pushedPatterns = ClampedMath.addUp(pushedPatterns, batchPushed);
                    if (diagnostics) {
                        nonDeclaredPushedExact = nonDeclaredPushedExact.add(BigInteger.valueOf(batchPushed));
                    }
                    if (taskMayLeaveTable(task.getKey(), task.getValue(), job.batchObligations)) {
                        job.tasks.remove(task.getKey());
                    }
                    continue taskLoop;
                }
            }
            batchCapMax = Math.max(batchCapMax, holderBatchCapacity);
            batchPowerMin = Math.min(batchPowerMin, powerCeiling);
            batchRemainMax = Math.max(batchRemainMax, task.getValue().value);
            // 请求份数只需"不少于本批上限"的粗上界：饱和读数在这里与精确值同效
            // （越界任务必然远大于宿主容量与功率上限），判完成不看它。
            long requestedBatch = uniformBatchLimit(
                    task.getValue().value, holderBatchCapacity, powerCeiling);
            batchReqMax = Math.max(batchReqMax, requestedBatch);

            var batch = extractBatchWithinBudget(
                    details,
                    budgetLedger,
                    requestedBatch,
                    powerCeiling,
                    holderBatchCapacity,
                    expectedOutputs,
                    expectedContainerItems,
                    planReservation,
                    batchProbe
            );
            @Nullable
            KeyCounter[] craftingContainer = batch == null ? null : batch.holder();
            // 与 craftingContainer 同步的那份批量身份：归位要靠它认出"就地认量的那一格"
            // （见 ParallelBatchExtraction#reinjectInputs），只交 holder 会把凭空的料塞回池子。
            @Nullable
            ParallelBatchExtraction.Batch craftingBatch = batch;
            long preparedExecutions = batch == null ? 1L : batch.executions();
            if (batch != null) {
                batchGotMax = Math.max(batchGotMax, preparedExecutions);
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
                        providerBackoff,
                        ParallelCpuProviderBackoff.PushLeg.HOLDER);
                if (availability != ParallelCpuProviderBackoff.ProviderAvailability.READY) {
                    continue;
                }

                while (craftingContainer != null) {
                    long batchExecutions = preparedExecutions;
                    IPatternDetails pushDetails = details;
                    if (batchExecutions > 1L) {
                        // k 倍 holder 只有认得"这份料是 k 发"的宿主能吃；交给普通供应器
                        // 会只合成一发、把余料吞进机器输入槽。
                        var holderCapability = batchCapabilities().holderCapability(provider);
                        if (holderCapability == null) {
                            break;
                        }
                        pushDetails = holderCapability.preparePushPattern(details, batchExecutions);
                        if (pushDetails == null) {
                            if (holderCapability.foldsPattern()) {
                                batchFoldNull++;
                            }
                            break;
                        }
                    }
                    if (!hasTickBudget(budgetLedger)) {
                        stopAfterCurrentTask = true;
                        break;
                    }
                    // 只问电网要这一档封顶的电：查得起与扣得起必须是同一个数，
                    // 拿未封顶的整批功耗去问，超大批次永远"付不起"，一发都推不出。
                    double chargeable = billedPower(patternPower);
                    if (energyService.extractAEPower(
                            chargeable,
                            Actionable.SIMULATE,
                            PowerMultiplier.CONFIG
                    ) < chargeable - 0.01D) {
                        break;
                    }

                    KeyCounter[] submittedCraftingContainer = craftingContainer;
                    var submittedBatch = craftingBatch;
                    String pushPath = poolLedger.path("push");
                    boolean acceptedPush = provider.pushPattern(pushDetails, submittedCraftingContainer);
                    poolLedger.restore(pushPath);

                    if (!acceptedPush) {
                        if (providerBackoff != null) {
                            providerBackoff.recordPushRejected(provider, details,
                                    ParallelCpuProviderBackoff.PushLeg.HOLDER, currentTick);
                        }
                        break;
                    }
                    if (providerBackoff != null) {
                        providerBackoff.recordPushAccepted(provider, details,
                                ParallelCpuProviderBackoff.PushLeg.HOLDER);
                    }
                    providerStats
                            .computeIfAbsent(provider.getClass().getSimpleName(),
                                    ignored -> new long[4])[3]++;

                    craftingContainer = null;
                    craftingBatch = null;
                    recordTakenInputs(job, details, submittedBatch);
                    availablePower -= energyService.extractAEPower(
                            chargeable,
                            Actionable.MODULATE,
                            PowerMultiplier.CONFIG
                    );
                    pushedPatterns = ClampedMath.addUp(pushedPatterns, batchExecutions);
                    if (diagnostics) {
                        nonDeclaredPushedExact = nonDeclaredPushedExact.add(BigInteger.valueOf(batchExecutions));
                    }
                    batchPushKMax = Math.max(batchPushKMax, batchExecutions);
                    reserveExpectedWaiting(expectedOutputs, expectedContainerItems);

                    for (var expectedContainerItem : expectedContainerItems) {
                        ParallelExecutingCraftingJob.addMaxItems(
                                job.timeTracker,
                                expectedContainerItem.getLongValue(),
                                expectedContainerItem.getKey().getType()
                        );
                    }

                    // 扣减与判完成一律走精确值：越界任务上 value 是饱和哨兵，
                    // 拿它做减法扣到 0 就把"还剩 1e19 发"当成收工。
                    task.getValue().consumeFirings(batchExecutions);
                    if (taskMayLeaveTable(task.getKey(), task.getValue(), job.batchObligations)) {
                        job.tasks.remove(task.getKey());
                        continue taskLoop;
                    }
                    if (batchExecutions > 1L) {
                        // 一次交互已推进 k 发，holder 已耗尽：本任务下轮再评估，
                        // 不在同一轮里逐发回补提取。
                        break;
                    }

                    expectedOutputs.reset();
                    expectedContainerItems.reset();
                    powerCeiling = powerAffordableExecutions(details, availablePower);
                    long nextRequested = uniformBatchLimit(
                            task.getValue().value, holderBatchCapacity, powerCeiling);
                    var nextBatch = extractBatchWithinBudget(
                            details,
                            budgetLedger,
                            nextRequested,
                            powerCeiling,
                            holderBatchCapacity,
                            expectedOutputs,
                            expectedContainerItems,
                            planReservation,
                            batchProbe
                    );
                    craftingContainer = nextBatch == null ? null : nextBatch.holder();
                    craftingBatch = nextBatch;
                    preparedExecutions = nextBatch == null ? 1L : nextBatch.executions();
                    if (nextBatch != null) {
                        batchGotMax = Math.max(batchGotMax, preparedExecutions);
                    }
                    // 重新提取后 holder 可能换了候选键或换了份数，功耗按新 holder 结算。
                    patternPower = craftingContainer == null
                            ? 0.0D
                            : CraftingCpuHelper.calculatePatternPower(craftingContainer);
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
                String reinjectPath = poolLedger.path("reinject");
                // 交 Batch 而不是交 holder：就地认量（无限源）的那几格从没离开过池子，
                // 按 holder 原样还就是把凭空的一份料投进池子，下一刻被 storeItems() 投回网络。
                ParallelBatchExtraction.reinjectInputs(inventory, craftingBatch);
                poolLedger.restore(reinjectPath);

            }
            if (stopAfterCurrentTask) {
                taskResumeIndex = (taskIndex + 1) % taskCount;
                taskResumePattern = orderedTasks.get(taskResumeIndex).getKey();
                sweepCompleted = false;
                break;
            }
        }
        // 只有真的把整张任务表扫完（没有提前退出）才把断点归零，下 tick 从头公平开始。
        // 早退的分支（预算耗尽 / 停在本任务）都已经把 taskResumeIndex 写好，
        // 这里再无条件归零就等于<b>每 tick 都从表头重扫</b>：排在断点之后的任务永远轮不到，
        // 实机形态是若干生产者池里首个候选>0（src=...<-poolFirst>0）却永远 ann=0、整条 lane pushed=0。
        if (sweepCompleted) {
            taskResumeIndex = 0;
            taskResumePattern = null;
        }
        lastSweepVisited = swept;
        lastNoCandKey = batchProbe.noCandKey;
        lastNoCandStored = batchProbe.noCandStored;
        lastNoCandQuota = batchProbe.noCandQuota;

        // 纯读数交接：把本轮 blocked= 选样用的那份顺位交给停滞探针（alloc= 必含格与 blocked= 读
        // 同一份名单、同一批样板，不新建第二本账——这里交的是现成读数的引用，判据一律不读它）。
        if (diagnostics) {
            stallProbe.noteRationOrder(orderedTasks);
            stallProbe.noteDetail(pushedPatterns, skippedNoProvider, skippedExtractNull,
                    skippedPushRejected, skippedMaterialBackoff);
            stallProbe.noteBatch(nonDeclaredPushedExact.add(batchProbe.declaredFiringsSum),
                    batchCapMax, batchReqMax, batchGotMax,
                    batchPushKMax, batchFoldHost, batchFoldNull, batchPowerMin, batchRemainMax,
                    batchProbe, providerStats, inventory, job.declaredTerminals, currentTick);
        }
        return pushedPatterns;
    }

    static long pushPreparedBatch(
            ParallelExecutingCraftingJob job,
            ListCraftingInventory inventory,
            ParallelPoolLedger poolLedger,
            @Nullable Level level,
            @Nullable UUID sourceCraftingId,
            IPatternDetails details,
            Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress> task,
            Iterable<ICraftingProvider> taskProviders,
            IEnergyService energyService,
            long currentTick,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            @Nullable ParallelCpuProviderBackoff providerBackoff,
            ParallelPlanReservation planReservation
    ) {
        // 请求份数只按本任务的剩余发数上界，不再吃"本轮还能推几发"那一维（CPU 早就不按份数限量）：
        // 越界任务给饱和读数即为"远多于任何一批"，真正的发数由下面的份额夹
        // （clampByRemainingQuota）与宿主容量决定。
        long requested = task.getValue().value;
        if (requested <= 1L || !hasTickBudget(budgetLedger)) {
            return 0L;
        }
        KeyCounter expectedOutputs = new KeyCounter();
        KeyCounter expectedRemainders = new KeyCounter();
        // 这里只要一发样本输入给调度器探量，真实发数由调度器按机器容量决定。
        String samplePath = poolLedger.path("ecoSample");
        var batch = ParallelBatchExtraction.prepare(
                details,
                inventory,
                level,
                1L,
                expectedOutputs,
                expectedRemainders,
                planReservation
        );
        KeyCounter[] perCraftInputs = batch == null ? null : batch.holder();
        poolLedger.restore(samplePath);
        if (perCraftInputs == null) {
            return 0L;
        }
        String sampleBackPath = poolLedger.path("ecoSample");
        // 走 Batch 的归位出口而不是 AE2 那个：无限源那一格是就地认量的，从没离开池子，
        // 按 holder 原样 insert 回去就是凭空多一份料，下一刻还会被 storeItems() 投回网络。
        ParallelBatchExtraction.reinjectInputs(inventory, batch);
        poolLedger.restore(sampleBackPath);

        // 一发样本吃了哪些键几份已经定下来，调度器却按机器容量自己定发数——
        // 发数必须先在规划配额上夹一刀：perFiring × 发数 超过份额就是"超出计划数量的消耗"
        // （实机 NeoEco 一次吞掉 2,570,674,176 枚 ae2:silicon，而表只给它 960 枚）。
        // 同一刀还要在环种留底上夹一刀（池子现量读 inventory）：份额足得很而环自己还欠着的那一截，
        // 过去这一条腿压根不读留底，种就是在环转起来之前被别的样板按发数申报走的。
        requested = clampByRemainingQuota(planReservation, details, perCraftInputs, requested,
                inventory);
        if (requested <= 1L) {
            return 0L;
        }

        for (ICraftingProvider provider : taskProviders) {
            if (!(provider instanceof PreparedBatchDispatcher dispatcher)) {
                continue;
            }
            var availability = checkProviderAvailability(
                    provider, details, currentTick, providerBackoff,
                    ParallelCpuProviderBackoff.PushLeg.HOLDER);
            if (availability != ParallelCpuProviderBackoff.ProviderAvailability.READY) {
                continue;
            }
            PreparedBatch prepared =
                    dispatcher.prepareBatch(
                            details,
                            perCraftInputs,
                            expectedOutputs,
                            expectedRemainders,
                            inventory,
                            requested,
                            energyService,
                            level,
                            sourceCraftingId
                    );
            // 宿主 prepare 在机器 capacity<=0 时明写交回 null（见 mixin ExtremeAssemblerNeoEcoBatchProviderMixin#eco$prepareFastPath）：
            // 这一条样板对这位宿主不适用。走既有"这一批不适用"的出口——与 pushPreparedBatchCandidate
            // 交回 NOT_BATCHED 后本循环 continue 同形状，不新增日志闸门、不新增拒绝原因。
            if (prepared == null) {
                continue;
            }
            // push <b>之前</b>再核一次"这一批要交出去的量"落在份额内的发数：宿主按机器容量自己定 craftCount，
            // inputTotal 的每键请求量也要在份额内（样本那一维上面已夹过，这一维看对方申报的每发件数）。
            // 越界就少请求——照夹后的发数向同一位宿主另要一批，不夹销账、不归零、不新增拒绝分支。
            long affordable = dispatchFiringsInsideQuota(
                    planReservation, details, perCraftInputs,
                    prepared.inputTotal(), prepared.craftCount(), inventory);
            if (affordable < prepared.craftCount()) {
                // 纯读数（不参与任何判据）：这一轮请求侧把宿主申报的发数折回了份额内的那一发数。
                // "折了几次/折掉几发"与"预算耗尽/NOT_BATCHED/宿主交回 null"在既有读数上逐字同形，
                // 这里分型出来只为让实机日志能认出这条路走过；不许据它早退或拒绝。
                poolLedger.noteEcoFold(prepared.craftCount() - affordable);
                requested = affordable;
                if (requested <= 1L) {
                    return 0L;
                }
                prepared = dispatcher.prepareBatch(
                        details, perCraftInputs, expectedOutputs, expectedRemainders,
                        inventory, requested, energyService, level, sourceCraftingId);
                // 同一位宿主在"少要一批"这一次同样可能交回 null（capacity 在两次申报之间掉到 0）：
                // 走与上面第一处、以及 pushPreparedBatchCandidate 交回 NOT_BATCHED 完全同一个出口。
                if (prepared == null) {
                    continue;
                }
            }
            // 销账量由执行侧真动作派生：push 前后各读一次池子的宽域现量，差值就是这批真出账。
            var ecoOutflowKeys = neoEcoOutflowKeys(details, perCraftInputs, prepared.inputTotal());
            String ecoPushPath = poolLedger.path("ecoPush");
            PreparedBatchPushResult pushResult;
            var poolOutflow = new WideKeyCounter();
            try {
                var poolBeforePush = readPoolWide(inventory, ecoOutflowKeys);
                pushResult = pushPreparedBatchCandidate(prepared, inventory, requested);
                poolOutflow = measurePoolOutflow(inventory, ecoOutflowKeys, poolBeforePush);
                poolLedger.noteEcoOut(describeEcoOut(poolOutflow, prepared.inputTotal()));
            } finally {
                poolLedger.restore(ecoPushPath);
            }
            if (pushResult == PreparedBatchPushResult.NOT_BATCHED) {
                continue;
            }
            if (pushResult == PreparedBatchPushResult.REJECTED) {
                if (providerBackoff != null) {
                    providerBackoff.recordPushRejected(provider, details,
                            ParallelCpuProviderBackoff.PushLeg.HOLDER, currentTick);
                }
                continue;
            }
            long craftCount = prepared.craftCount();
            if (providerBackoff != null) {
                providerBackoff.recordPushAccepted(provider, details,
                        ParallelCpuProviderBackoff.PushLeg.HOLDER);
            }
            applyPreparedBatchSuccess(job, task.getValue(), prepared, energyService);
            recordNeoEcoTakenInputs(job, details, perCraftInputs, craftCount,
                    prepared.inputTotal(), poolOutflow, planReservation);
            return craftCount;
        }
        return 0L;
    }

    /**
     * 这一批交出去的量落在份额内的发数：每键请求量 = 一发样本的件数 ∪ 对方自己申报的每发件数
     * （{@code inputTotal()} 那份合计按发数折回每发），除法是 {@link #clampByRemainingQuota}
     * 那同一份 {@code quotaExact} 算式，不新写第二道判据。
     *
     * <p>为什么要多看对方那一维：{@link #clampByRemainingQuota} 只看得见样本槽里的键与件数，
     * 而 {@code extractExact} 交出去的是宿主自己那份配方按发数乘出来的量——两个数一分叉（模糊槽改道、
     * 无限源就地认量），请求侧就没夹过它，份额之外的量已经物理出账了。
     *
     * <p>把这一维挂到样本数组之外的多出来那一号，且只补样本没看见的那一截
     * （{@code 对方每发量 − 样本每发合计}，非正就不进）。
     *
     * <p>对方那份读数是 {@code long}：等于 {@link Long#MAX_VALUE} 时它是哨兵不是量
     * （单键越出天花板就被钉死在那里），拿它除发数就是把假数当分母——那一档不进取请求侧，
     * 由 {@link #attach} 那一道同份封顶与 {@code ecoOut=} 对照读数负责。
     *
     * <p><b>没有池子读数的那一个重载</b>只按配额折发数、读不到环种留底那一刀：它在<b>主代码里零调用点</b>，
     * 只有离线用例走它（{@code ParallelCraftingCpuLogicTest} 那几条不配池子的请求侧判据）。
     * 真接机器的那一处调用点（{@code pushPreparedBatch} 里 push 前的复核）交的是本 lane 的 {@code inventory}，
     * 走带 {@code pool} 的那一个重载。
     */
    static long dispatchFiringsInsideQuota(@Nullable ParallelPlanReservation reservation,
            IPatternDetails details, @Nullable KeyCounter[] perCraftInputs,
            @Nullable List<GenericStack> reportedInputTotal, long craftCount
    ) {
        return dispatchFiringsInsideQuota(reservation, details, perCraftInputs,
                reportedInputTotal, craftCount, null);
    }

    /**
     * 同上，多一个<b>本 lane 池子</b>读数：折出来的发数除了配额，还要落在"扣完环种留底的可花量"里
     * （见 {@link #clampByRemainingQuota(ParallelPlanReservation, IPatternDetails, KeyCounter[], long,
     * ListCraftingInventory)} 那一刀的口径与理由——这一条腿正是 {@code bigint}/贪心宿主那条，
     * 宿主申报的发数不经过取料点，留底过去在这里没有牙）。
     */
    static long dispatchFiringsInsideQuota(@Nullable ParallelPlanReservation reservation,
            IPatternDetails details, @Nullable KeyCounter[] perCraftInputs,
            @Nullable List<GenericStack> reportedInputTotal, long craftCount,
            @Nullable ListCraftingInventory pool
    ) {
        if (reservation == null || details == null || perCraftInputs == null || craftCount <= 1L) {
            return craftCount;
        }
        var seenPerFiring = new java.util.HashMap<AEKey, Long>();
        for (var slot : perCraftInputs) {
            if (slot == null) {
                continue;
            }
            for (var entry : slot) {
                if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                    continue;
                }
                seenPerFiring.merge(entry.getKey(), entry.getLongValue(),
                        ClampedMath::addUp);
            }
        }
        var blind = new KeyCounter();
        for (GenericStack stack : reportedInputTotal == null ? List.<GenericStack>of()
                : reportedInputTotal) {
            AEKey key = stack == null ? null : stack.what();
            long total = stack == null ? 0L : stack.amount();
            // 哨兵那份 long 读数不是量：拿它 ÷ 发数会把分母做成天花板，那一维不进取请求侧
            // （由 attach 事后封顶与样本那一维的恒夹兜）。
            if (key == null || total <= 0L || total == Long.MAX_VALUE) {
                continue;
            }
            // 只对"配给表里确有口径"的键施夹：没口径（这一族压根没被配给／模具／自偿还／无限源）
            // 的键不参与折发数——把"没有配额口径"当"份额用完"夹成 0，就是静默永久关掉这一张样板的
            // NeoEco 快路径，且与"配额真用尽"逐字同形（假信号）。
            if (!reservation.hasQuotaBasis(details, key)) {
                continue;
            }
            long perFiringFromTotal = 1L + (total - 1L) / craftCount;
            long excess = perFiringFromTotal - seenPerFiring.getOrDefault(key, 0L);
            if (excess > blind.get(key)) {
                blind.set(key, excess);
            }
        }
        // 恒跑样本那一维的夹子（同一份 quotaExact 算式），不再"无盲发就整批跳过"：宿主按机器容量定的
        // craftCount 可以远大于份额折得出的发数，而顶到天花板的申报键又不进盲发——只在有盲发时才夹的话，
        // 那一型的请求侧压根没夹，物理多拿的那一截仍无主（不进任何配额账、网络不退，池子对别的样板虚高）。
        // 无盲发时 demand 就是样本本身：对非贪心宿主这是恒等（line913 已按同一算式夹过 requested），
        // 只对 craftCount>requested 的贪心宿主才把发数折回份额内那一发，不放大、不新增第二道封顶。
        var demand = blind.isEmpty() ? perCraftInputs
                : java.util.Arrays.copyOf(perCraftInputs, perCraftInputs.length + 1);
        if (!blind.isEmpty()) {
            demand[demand.length - 1] = blind;
        }
        return clampByRemainingQuota(reservation, details, demand, craftCount, pool);
    }

    /** 同一批出账的两种读数配对：键:池子差值/reported=对方 long 读数（纯读数，不进判据）。 */
    static String describeEcoOut(WideKeyCounter poolOutflow,
            @Nullable List<GenericStack> reportedInputTotal
    ) {
        var described = new StringBuilder();
        for (var entry : poolOutflow.keys()) {
            if (described.length() > 0) {
                described.append('/');
            }
            described.append(entry).append(':')
                    .append(Ae2Amounts.toLong(poolOutflow.getAmount(entry)));
            long reported = -1L;
            if (reportedInputTotal != null) {
                for (GenericStack stack : reportedInputTotal) {
                    if (stack != null && entry.equals(stack.what())) {
                        reported = stack.amount();
                        break;
                    }
                }
            }
            described.append("/reported=").append(reported);
            if (described.length() > 200) {
                break;
            }
        }
        return described.length() == 0 ? "" : described.toString();
    }

    /**
     * 停滞行 {@code locked=[...]} 的整串渲染（纯读数，不进判据）：点名"哪张样板的第几格、哪个键、
     * 现场多少、每发几枚、账面可用多少"。收成方法只为让这条读数有直接判据的用例入口。
     *
     * <p><b>格内子字段一律用 {@code ,} 连（不带空白）</b>：这一格进 per100r 那一行后仍要能被
     * {@code grep ',stored='} 一类跨格对账寻址，把边界折进空白就等于把子字段名藏进下一段里。
     *
     * <p><b>出口一次折空白</b>（与 {@link #describeDeclaredReject}、{@code RefillBlockedCell#describe}
     * 同一把尺）：{@code owner} 是 {@code getDefinition()} 的 {@code toString()}、{@code key} 是
     * {@code AEKey#toString}（带组件时写成 {@code <id> (with patches)}），都是第三方串——只在这一处
     * 折成 {@code "_"}，于是 {@code "_"} 在读数里恒等于"别人家的空格"，自家边界恒是 {@code ","}。
     */
    static String describeLockedReadout(String owner, int slotIndex, String key,
            long stored, long units, long usable
    ) {
        return (owner + "#slot" + slotIndex + "," + key
                + ",stored=" + stored + ",units=" + units + ",usable=" + usable)
                .replace(' ', '_');
    }

    static PreparedBatchPushResult pushPreparedBatchCandidate(
            @Nullable PreparedBatch prepared,
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

    static PreparedBatchPushResult pushPreparedBatchCandidate(
            @Nullable PreparedBatch prepared,
            ListCraftingInventory inventory
    ) {
        return pushPreparedBatchCandidate(prepared, inventory, Long.MAX_VALUE);
    }

    enum PreparedBatchPushResult {
        NOT_BATCHED,
        REJECTED,
        ACCEPTED
    }

    static void applyPreparedBatchSuccess(
            ParallelExecutingCraftingJob job,
            ParallelExecutingCraftingJob.TaskProgress task,
            PreparedBatch prepared,
            IEnergyService energyService
    ) {
        long craftCount = prepared.craftCount();
        // 与另两条腿同一条口径：整批功耗按实际份数算，向电网要的电封顶在 int 那一档
        energyService.extractAEPower(billedPower(prepared.power()),
                Actionable.MODULATE, PowerMultiplier.CONFIG);
        reserveExpectedWaiting(job, prepared.outputs(), prepared.remainders());
        for (GenericStack remainder : prepared.remainders()) {
            ParallelExecutingCraftingJob.addMaxItems(
                    job.timeTracker,
                    remainder.amount(),
                    remainder.what().getType()
            );
        }
        // 官方批量实收几发就精确扣几发：越界任务上 value 是饱和哨兵，减法必须走精确值。
        task.consumeFirings(craftCount);
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
    static void recordMaterialBackoff(
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
        job.materialWakeIndex.register(details, missing);
    }

    /**
     * 库存键变动时唤醒以该键为缺口的退避任务（精准唤醒，替代 TTL 轮询）。
     * 模糊槽的候选键是主键模板，实际到达的可能是同类模糊变体（exact 键不同、
     * 主键相同）——精确匹配失败后，还必须由输入槽确认实际变体合法，才能唤醒。
     */
    private void wakeMaterialBackoff(@Nullable AEKey what) {
        if (what == null || job == null || job.materialBackoffMissing.isEmpty()) return;
        wakeMaterialBackoff(job, what, lane.cluster().level());
    }

    /** 同物品的组件变体只有真正通过输入槽 isValid 时才唤醒，压缩等级不能互相唤醒。 */
    static void wakeMaterialBackoff(@Nullable ParallelExecutingCraftingJob currentJob,
            @Nullable AEKey what, @Nullable net.minecraft.world.level.Level level) {
        if (what == null || currentJob == null || currentJob.materialBackoffMissing.isEmpty()) {
            return;
        }
        for (var pattern : currentJob.materialWakeIndex.candidates(what)) {
            var missing = currentJob.materialBackoffMissing.get(pattern);
            if (missing == null) {
                currentJob.materialWakeIndex.remove(pattern);
                continue;
            }
            if (missing.contains(what) || acceptsArrivingVariant(pattern, what, level)) {
                currentJob.materialBackoffUntil.remove(pattern);
                currentJob.materialBackoffMissing.remove(pattern);
                currentJob.materialWakeIndex.remove(pattern);
            }
        }
    }

    private static boolean acceptsArrivingVariant(IPatternDetails pattern, AEKey arriving,
            @Nullable net.minecraft.world.level.Level level) {
        for (var input : pattern.getInputs()) {
            for (var candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what().getPrimaryKey().equals(arriving.getPrimaryKey())
                        && input.isValid(arriving, level)) return true;
            }
        }
        return false;
    }

    /**
     * 一次交互推进几发：任务剩余次数压到宿主可接受的份数上限与网络付得起的发数。
     *
     * <p>没有按 holder 实际量反推份数的宿主在册时保持 AE2 原生的一发一交互，
     * k 倍 holder 交给普通供应器会只合成一发、把余料吞进机器输入槽。
     */
    private static long uniformBatchLimit(
            long remainingExecutions,
            long holderBatchCapacity,
            long powerCeiling
    ) {
        long limit = holderBatchCapacity <= 0L ? 1L : remainingExecutions;
        limit = Math.min(limit, powerCeiling);
        limit = Math.min(limit, remainingExecutions);
        return Math.max(1L, limit);
    }

    /**
     * 一次交互实际向电网扣的电封顶在这一档（2026-09-22 定稿口径）：份数照常聚合，
     * 超出这一档的电量不再向电网要。
     */
    static final double PER_INTERACTION_POWER_CAP_AE = Integer.MAX_VALUE;

    /** 本批实际计费的电量：只封顶，不放大；NaN 与负数原样交回（由调用方按付不起处理）。 */
    static double billedPower(double powerDue) {
        return powerDue > PER_INTERACTION_POWER_CAP_AE ? PER_INTERACTION_POWER_CAP_AE : powerDue;
    }

    /**
     * 网络此刻付得起几发。
     *
     * <p>k 倍 holder 的功耗按一次交互整体结算：付不起时推送会原地放弃、物料回注，
     * 批量越大越发都推不出，所以先把份数压到付得起的量级。每发功耗按样板定义取
     * 各候选中的上界，宁可少推也不让本应能开工的单发被误判成付不起。
     *
     * <p>电网现量已经够付这一档封顶（{@link #PER_INTERACTION_POWER_CAP_AE}）时电这一闸直接放行：
     * 一次交互最多只扣那一档，再拿"每发功耗"去折算份数就是拿一份付不到的钱当约束，
     * 把本可以一次推完的超大批次夹小。
     */
    static long powerAffordableExecutions(IPatternDetails details, double availablePower) {
        double perFiringPower = 0.0D;
        for (IPatternDetails.IInput input : details.getInputs()) {
            if (input == null || input.getMultiplier() <= 0L || input.getPossibleInputs() == null) {
                continue;
            }
            double slotWorst = 0.0D;
            for (var candidate : input.getPossibleInputs()) {
                if (candidate == null || candidate.what() == null || candidate.amount() <= 0L) {
                    continue;
                }
                double perOperation = Math.max(1.0D, (double) candidate.what().getAmountPerOperation());
                slotWorst = Math.max(slotWorst,
                        input.getMultiplier() * (double) candidate.amount() / perOperation);
            }
            perFiringPower += slotWorst;
        }
        if (!(perFiringPower > 0.0D) || !Double.isFinite(perFiringPower)) {
            return Long.MAX_VALUE;
        }
        if (!Double.isFinite(availablePower) || availablePower >= PER_INTERACTION_POWER_CAP_AE) {
            // 电网够付这一档封顶：一次交互最多只扣那么多，电这一闸就不再夹份数
            return Long.MAX_VALUE;
        }
        if (availablePower <= 0.0D) {
            return 1L;
        }
        double affordable = availablePower / perFiringPower;
        if (!Double.isFinite(affordable) || affordable >= (double) Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) affordable);
    }

    private @Nullable ParallelBatchExtraction.Batch extractBatchWithinBudget(
            IPatternDetails details,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            long requestedExecutions,
            long powerCeiling,
            long hostBatchCapacity,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems,
            ParallelPlanReservation planReservation,
            ParallelBatchExtraction.Probe batchProbe
    ) {
        if (!hasTickBudget(budgetLedger)) {
            return null;
        }
        batchProbe.noteRequestContext(requestedExecutions, powerCeiling, hostBatchCapacity, details);

        String holderPath = poolLedger.path("holder");
        var batch = ParallelBatchExtraction.prepare(
                details,
                inventory,
                lane.cluster().level(),
                requestedExecutions,
                expectedOutputs,
                expectedContainerItems,
                planReservation,
                batchProbe
        );
        poolLedger.restore(holderPath);
        return batch;
    }

    /**
     * 声明形态一次推送的结果：实收份数与<b>单发原型</b>的功耗（功率由调用方按实收份数结算），
     * 外加整批拒收这一个出口。
     *
     * <p>这里不再有"提取失败"那一支：原型那一趟凑不齐一发时 {@link #attemptDeclaredBatch} 与
     * {@code declared==null} 同一条出口交回 {@code null}，本张样板同轮继续走既有的 k 倍 holder 段。
     */
    record DeclaredPush(long accepted, double perFiringPower, boolean rejected, boolean awaitingSettlement) {
        DeclaredPush(long accepted, double perFiringPower, boolean rejected) {
            this(accepted, perFiringPower, rejected, false);
        }

        /**
         * 这一张样板<b>本轮是否就此收工</b>（推送循环唯一的出口判据）。
         *
         * <p>{@code accepted > 0} 收工：申报批已按实收结算，份数与账都在声明这条腿上，本轮不再走
         * holder 段（这一支的行为与改前逐字相同）。
         *
         * <p>等待上一批材料交割时也收工：已预留的材料不能再经 holder 推出。
         * 其余两种形状各走各的同轮退路：
         * <ul>
         * <li><b>整批拒收</b>：宿主拒的是"一次吃下 k 倍料"这一形态，原型料已在
         * {@link #attemptDeclaredBatch} 里原数回池、义务账一分未挂，于是这张样板同轮继续走既有的
         * k 倍 holder 段（单发/小批推送）。旧实现这里 {@code continue taskLoop}，宿主每轮整批拒
         * （机器忙/降频/背压）就把 holder 路一起吞掉，实机锁成活锁：{@code declaredReject=99}
         * 每轮恰一次、{@code declared=0}、{@code pushed=0}。</li>
         * <li><b>原型那一趟凑不齐一发</b>（交回 {@code null}，压根不产出本记录）：两趟不同轴——原型走
         * {@code ParallelBatchExtraction#prepareUnitPrototype}（{@code prepareUniform} 份数写死 1、
         * 每槽只认一个键），holder 走 {@code prepare}，它在 {@code prepareUniform} 失败后还有
         * {@code prepareSingleFiring} 的逐槽混取窗口。上界尺（{@code declaredPayableCeiling}）每格
         * 独立除、跨槽同键不累加，报得出 1 发而原型腿交不出，正是那一窗真能推出去的一发。
         * "从网络把缺的料搬一次"那条收尾也由 holder 段那一趟（{@code craftingContainer==null}
         * 分支）做，一次都不少。</li>
         * </ul>
         */
        boolean endsTaskRound() {
            return accepted > 0L || awaitingSettlement;
        }
    }

    /**
     * 整批拒收的点名格：{@code 样板@宿主#机器出口}；宿主说不出出口就是 {@code <silent>}（纯读数）。
     *
     * <p><b>在这个出口把整串折一次空白</b>（一次性、一个地方）：{@code reason} 是宿主给回的人话
     * （bigint 通道的 {@code UselessBigIntegerBatchClient.RejectCells#describe}，机器生成、随时可能
     * 带空白），样板键的 {@code AEItemKey#toString} 带组件时也带空白——不去改第三方字符串本身、
     * 也不给每个来源各加一次折，{@code declaredReject=} 那一格按空白切才恒是一个 token。
     */
    private static String describeDeclaredReject(DeclaredBatchHost host, IPatternDetails details) {
        var reason = host.lastDeclaredBatchRejectReason();
        return (details.getDefinition() + "@" + host.getClass().getSimpleName()
                + "#" + (reason == null || reason.isEmpty() ? "<silent>" : reason)).replace(' ', '_');
    }

    /**
     * 键的<b>单 token 读数</b>形态（纯读数，只服务日志串）：AE2 的 {@code AEItemKey#toString}
     * 在带数据组件（编码后的加工／合成样板必是这个形状）时写成 {@code <id> (with patches)}、
     * {@code AEFluidKey} 写成 {@code <id> (+components)}——两个空白。把按空白切 token 的下游读数
     * （per100r）劈成三段的正是这两个空白。
     *
     * <p>做法：{@code toString()} 逐字保留，仅把空白折成 {@code "_"}——无组件时形状不变，带组件时
     * 仍认得出"是哪张样板"（item id 加组件标记），不引 hash、不简化成 class 名。
     * 用点：{@code ParallelBatchExtraction} 的回补样板头、{@code inv=} 的每个库存键。
     * 手里已经是 {@code String}（{@code getDefinition()} 或 {@code AEKey#toString} 的原文）的那一格，
     * 折点放在<b>那一格的出口</b>整串折一次（{@link #describeDeclaredReject}、
     * {@code describeLockedReadout}、{@code describeWorstCell}、{@code RefillBlockedCell#describe}），
     * 不给每个来源各写一次。
     * <b>不</b>作为排序键的替代（{@code String.valueOf(getDefinition())} 的排序口径不许跟着漂）。
     */
    static String describeKey(AEKey key) {
        if (key == null) {
            return "null";
        }
        return key.toString().replace(' ', '_');
    }

    /**
     * "单发原型材料 + 份数声明"形态的一次推送（静态本体，可离线核账）。
     *
     * <p>申报份数 = 精确剩余发数 ∩ 宿主真实容量 ∩ 单批可表示上界 ∩ 现场可付上界 ∩ 电力上界。
     * BigInteger 宿主的实收发数、产出和容器预期保持宽域精确值；窄域宿主仍受可表示上界约束。
     *
     * @return {@code null} 表示不适用（该样板还挂着未清的义务、申报算不出多于 1 发、<b>或单发原型
     *         那一趟凑不齐一发</b>），调用方退回 k 倍 holder 路径：那一趟走的是
     *         {@code ParallelBatchExtraction#prepareUniform} 的"每槽一个键"，holder 那趟在它失败后
     *         还有 {@code prepareSingleFiring} 的逐槽混取窗口，同轮不收工
     */
    static @Nullable DeclaredPush attemptDeclaredBatch(
            DeclaredBatchHost host,
            IPatternDetails details,
            ParallelExecutingCraftingJob job,
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> orderedTasks,
            ListCraftingInventory inventory,
            @Nullable net.minecraft.world.level.Level level,
            ParallelPlanReservation planReservation,
            ParallelPoolLedger poolLedger,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems,
            long powerCeiling,
            long holderBatchCapacity,
            @Nullable ParallelBatchExtraction.Probe batchProbe,
            @Nullable java.util.HashMap<String, long[]> providerStats,
            @Nullable ParallelCpuProviderBackoff providerBackoff,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            long currentTick
    ) {
        ParallelExecutingCraftingJob.TaskProgress progress = job.taskProgress(details);
        if (progress == null) {
            return null;
        }
        if (job.batchObligations.hasPending(details)) {
            // 一张样板同时只挂一笔未清的义务：申报上界读的是"池子现在拿得出几发"，
            // 上一笔欠账的材料还躺在池子里，叠两笔申报就是把同一份存量许诺两次。
            // 等待交割不是拒收：不允许回落到 holder 再取走已被义务占用的材料。
            noteDeclaredNull(batchProbe, "pending");
            return new DeclaredPush(0L, 0.0D, false, true);
        }
        BigInteger declared = declaredBatchFirings(host, details, progress, inventory, level,
                planReservation, powerCeiling,
                () -> pendingPoolClaims(job.batchObligations, orderedTasks, inventory,
                        planReservation),
                batchProbe);
        if (declared == null) {
            // 算不出多于 1 发：交回 k 倍 holder 路径
            return null;
        }
        if (!hasTickBudget(budgetLedger)) {
            return null;
        }
        if (batchProbe != null) {
            batchProbe.noteRequestContext(Ae2Amounts.toLong(PlanAmount.of(declared)),
                    powerCeiling, holderBatchCapacity, details);
        }
        // 单发原型：与 prepareUniform 同一套 primaryCandidate 顺位与族配额夹（份数写死 1）。
        // 原型自己那一份回流容器不进账：账按实收份数走 addDeclaredReserves，否则只挂到一发。
        ParallelBatchExtraction.Batch prototype;
        String prototypePath = poolLedger.path("declaredPrototype");
        try {
            prototype = ParallelBatchExtraction.prepareUnitPrototype(
                    details, inventory, level, new KeyCounter(), planReservation, batchProbe);
        } finally {
            poolLedger.restore(prototypePath);
        }
        if (prototype == null) {
            // 原型那一趟凑不齐一发：与上面 declared==null 同一条出口交回 null，同轮落到既有的
            // k 倍 holder 段。旧那支交 new DeclaredPush(..., extractFailed=true) ⇒ endsTaskRound()
            // 为真 ⇒ holder 段整段被吞，而它在 prepareUniform 交白卷之后还有 prepareSingleFiring
            // 的逐槽混取窗口，同一份池子里那一发本可以推出去（两把尺分叉那一档：上界尺每格独立除、
            // 跨槽同键不累加，取料点按"该键每发合计"当分母）。回补与缺料退避不必在这一支上重做：
            // 由下面 holder 段 craftingContainer==null 那一趟做，一次都不少。
            return null;
        }
        declared = declaredPrototypeQuota(details, prototype, planReservation, declared);
        if (declared.signum() <= 0) {
            ParallelBatchExtraction.reinjectInputs(inventory, prototype);
            return null;
        }
        BigInteger accepted;
        String pushPath = poolLedger.path("declaredPush");
        try {
            accepted = host.pushDeclaredBatch(details, prototype.holder(), declared);
        } finally {
            poolLedger.restore(pushPath);
        }
        // 宿主把产物不经 CPU 池子直接交出去时，它自报的「计划入网」那几枚从这条口取走（每键 BigInteger 真值）。
        // 取回的是<b>预期</b>不是交付证据：本批到底有几枚真进了存储，这条通道一个字都不报。
        // 无论本批成不成都要取走一次：留着上一批的计划数，下一批就会把它算到自己头上。
        // 没实现该口的宿主（含既有全部声明宿主）恒为 null ⇒ 下面逐字保持三参口径。
        WideKeyCounter plannedToNetwork = host instanceof DeclaredBatchPlannedOutputs reporter
                ? reporter.takePlannedNetworkOutputs() : null;
        if (accepted == null || accepted.signum() <= 0) {
            // 整批拒收：原型料原数归位（分过刀的那一格按真值还），任务表与义务账一概不动
            String reinjectPath = poolLedger.path("declaredReinject");
            ParallelBatchExtraction.reinjectInputs(inventory, prototype);
            poolLedger.restore(reinjectPath);
            // 退避记在真宿主身上：壳（UselessBigIntegerDeclaredHost）只在调用方当路由用，
            // 而 checkProvider 查询用的是真方块实体那一个身份——记在壳上这笔退避永久空转。
            // 腿是 DECLARED：这一笔拒收只说"这台机器现在不接声明批"，同轮 k 倍 holder 段读的是
            // 同一个池子同一份料，不许被这一笔堵死（实机 lane 09a75f4a 那一型）。
            if (providerBackoff != null) {
                providerBackoff.recordPushRejected(host.declaredBatchBackoffOwner(), details,
                        ParallelCpuProviderBackoff.PushLeg.DECLARED, currentTick);
            }
            if (batchProbe != null) {
                batchProbe.declaredRejected++;
                // 点名三件套：样板身份 + 宿主/机器标识 + 是哪个出口拒的（宿主说不出就是 <silent>）
                batchProbe.noteDeclaredRejectedCell(describeDeclaredReject(host, details));
            }
            return new DeclaredPush(0L, 0.0D, true);
        }
        // 只认申报值以内的实收：宿主报多了不收，任务表与义务账一律按"申报 ∩ 实收"结算
        accepted = accepted.min(declared);
        // long 仅供百轮统计和已封顶的交互功耗读数；发数和产出挂账保留 accepted 真值。
        long acceptedFirings = Ae2Amounts.toLong(PlanAmount.of(accepted));
        // 在飞记录与交割账挂账都排在 reserveExpectedWaiting 之后：交割对平要拿的是同一处算式（min(自报,
        // 申报)）划出的那一份待交割枚数，先划账才能把这一批的待交割量交进在飞台账。见下方那一段。
        if (providerBackoff != null) {
            // 与拒收同一身份、同一腿：清账清的必须是被写进去的那一个桶，否则接受一次也解不掉那笔退避
            providerBackoff.recordPushAccepted(host.declaredBatchBackoffOwner(), details,
                    ParallelCpuProviderBackoff.PushLeg.DECLARED);
        }
        if (providerStats != null) {
            providerStats.computeIfAbsent(host.getClass().getSimpleName(), ignored -> new long[4])[3]++;
        }
        // 一发原型离开池子即净消耗，销账走既有槽级口径（真值越界的那一格按宽域出账）；
        // 剩下 (k-1) 发的材料挂进义务账分批交割。
        recordTakenInputs(job, details, prototype);
        job.batchObligations.incur(details, accepted.subtract(BigInteger.ONE));
        if (batchProbe != null) {
            batchProbe.declaredPushes++;
            batchProbe.declaredFiringsSum = batchProbe.declaredFiringsSum.add(accepted);
        }
        var wideOutputs = new WideKeyCounter();
        var wideContainers = new WideKeyCounter();
        ParallelBatchExtraction.addDeclaredReserves(
                details, prototype.holder(), accepted, wideOutputs, wideContainers);
        // 旧调用者的窄域结果只在可表示时填写；真实预留始终读取上面的宽域账。
        for (var key : wideOutputs.keys()) {
            var amount = wideOutputs.getExact(key);
            if (amount.bitLength() <= 63) {
                expectedOutputs.add(key, amount.longValueExact());
            }
        }
        for (var key : wideContainers.keys()) {
            var amount = wideContainers.getExact(key);
            if (amount.bitLength() <= 63) {
                expectedContainerItems.add(key, amount.longValueExact());
            }
        }
        // #227 K1：这一笔<b>能不能挂进在飞台账</b>，必须在划账之前判。划成"直投网络"的那几枚只有一个出口
        // 能转成已交割——该笔终局 SUCCESS（见 {@link DeclaredBatchTerminalLedger}），而终局凭据就是这里读的
        // 那枚 cpuToken 上的 batchId。挂不上台账却照划 ⇒ "实物没人等、在飞看不见、planNet 记满、出表照旧"
        // 那一型（实机 2026-10-02 19:55：push=81／declared=113／全场只 3 条终局／inflightBatches=0
        // undelivered=NONE pendingDelivery=NONE、化学红石缺 1.27e20）。
        // 判定三档缺一不可：宿主是 {@code DeclaredBatchTerminalCarrier} ＋ 交回了该笔的 receipt ＋
        // receipt 上有 batchId。判定不成立 ⇒ plannedToNetwork 交 {@code null} 进 reserveExpectedWaiting
        // ⇒ 整批走既有的"全额挂 waitingFor 等实物"那条路——语义出自 UselessBigIntegerBatchClient 的
        // FIRST_MISSING_PLAN 那条 WARN（"机器收了 N 发却没回报计划产出 ⇒ 这一批只能整批挂 waitingFor
        // 等实物"），本仓定过版，此处复用不新建。料已经进了机器，所以只能等实物或如实欠着：
        // <b>不</b>新增拒绝分支／退避／重试／超时／开关，也<b>不</b>把这一笔判成已交割。
        DeclaredBatchTerminalCarrier terminalCarrier =
                host instanceof DeclaredBatchTerminalCarrier carrier ? carrier : null;
        DeclaredBatchReceipt batchReceipt =
                terminalCarrier == null ? null : terminalCarrier.lastAcceptedBatchReceipt();
        boolean inflightRegistrable = batchReceipt != null && batchReceipt.batchId() != null;
        // 第四参与交割账挂账：申报 E 枚、自报计划 P 枚 ⇒ min(P,E) 枚进预期入网账（待交割）、
        // E - min(P,E) 枚挂 waitingFor 等实物 ⇒ 合计恒等于 E。交回的那一份 min 即这一批的待交割枚数，
        // 与 planNet 同一处算式（不另建第二把尺）：它挂进在飞台账，只有该批终局 SUCCESS 到齐才由
        // {@link DeclaredBatchTerminalLedger} 转成<b>已交割</b>并计入交割账。
        // 而这一份 min 只在上面那一判成立时才交出去——挂不上台账的那一批，min 恒 0、全额挂实物。
        Map<AEKey, BigInteger> recordedPlanned =
                reserveExpectedWaitingWide(job, wideOutputs, wideContainers,
                        inflightRegistrable ? plannedToNetwork : null);
        // 在飞记录：只有把该笔的 cpuToken（带 batchId）交回来的宿主才挂得上——capability 按
        // "接口/回调是否实现"判（DeclaredBatchTerminalCarrier），不按读数、不按超时、不是闸门。
        // SUCCESS 结掉并交割；REJECTED/CANCELLED 沿这里交进去的回退出口把那一批发数退回待办（材料欠账
        // 原样挂着，重新申报时由既有 incur 重挂），其待交割随在飞记录一起撤下、不转已交割。
        // 永远等不到终局的那一笔既进 inflightBatches=/oldestInflightTicks= 两格读数，也把收工判据的
        // 第二臂挡住（报满而货不到不许判成收工）——但<b>不拦出表</b>：出表判据仍只有 taskMayLeaveTable
        // 那两格，一个字都不读本台账（#213 边界）。
        // 纯读数（台账 #225 读数 2）：申报批按宿主型别分档累计——carrier 那一型交回了该笔 cpuToken、
        // 因而可能挂在飞台账；别的申报宿主天生挂不上（不是被谁夹掉的）。只数一笔，
        // 上面那一句判的就是同一件事，判据与流向一字未动。
        job.declaredTerminals.noteDeclaredHostShape(terminalCarrier != null);
        if (terminalCarrier != null) {
            var pendingWide = recordedPlanned;
            boolean clamped = batchReceipt != null && batchReceipt.plannedOutputsClamped();
            // 两个静默 return（receipt==null / batchId==null）一字未改：上游不再依赖它必成功，
            // 划账那一头已经按同一件事先行判定过了。
            // 负向终局走的就是这一个回退出口：那一批发数退回该样板的待办（既有那一个宽域入口
            // TaskProgress#addFirings），而这一笔当初划进 planNet 的那几枚直投预期<b>随同一句一起退</b>
            // （#227 K2 的硬前置：台账的负向分支把该笔 pendingDelivery 整笔丢弃、不转已交割，而收工
            // 第二臂读的是差值 ⇒ 不退这一截就永远非零，一笔 CANCELLED 就把整张作业永久钉在"未收工"上，
            // 即 #209／4816673 那一型被回退过的病）。退账用的就是 planNet 那本现成的宽域账——不建第二本、
            // 不加第二把尺，也<b>不</b>把它判成已交割（交割账那一格一个字都不动）。
            // 回退落空那一档（条目已出表 ⇒ rollBackFirings 交回 false）照样退账：这一笔的凭据已经用掉，
            // 差值不许留着冒充"还欠着交割"。
            DeclaredBatchTerminalLedger.Rollback negativeTerminal =
                    (rollbackPattern, rollbackFirings) -> {
                        job.revokeDeclaredBatchPlannedNetworkOutputs(pendingWide);
                        return job.rollBackDeclaredBatchFirings(rollbackPattern, rollbackFirings);
                    };
            job.declaredTerminals.noteAdmitted(batchReceipt,
                    details, accepted, currentTick,
                    negativeTerminal, pendingWide, clamped,
                    job::creditDeclaredBatchDelivered);
        }
        for (AEKey key : wideContainers.keys()) {
            job.timeTracker.addMaxItems(PlanAmount.of(wideContainers.getExact(key)), key.getType());
        }
        progress.consumeFirings(accepted);
        return new DeclaredPush(acceptedFirings,
                CraftingCpuHelper.calculatePatternPower(prototype.holder()), false);
    }

    /**
     * 即时装配宿主在同一轮内付清本批材料并回流真实产物，让随后依赖任务继续执行。
     * 继续使用既有精确取料、配额、义务和销账入口；付不清时保留原回流与重试路径。
     */
    static void settleImmediateBatch(DeclaredBatchHost host, ParallelExecutingCraftingJob job,
            IPatternDetails details, ListCraftingInventory inventory,
            @Nullable net.minecraft.world.level.Level level, ParallelPlanReservation reservation,
            ParallelPoolLedger ledger, @Nullable ParallelBatchExtraction.Probe probe,
            git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver receiver) {
        if (!(host instanceof git.chexson.chexsonsaeutils.crafting.hostbatch.ImmediateBatchOutputHost immediate)) return;
        var owed = job.batchObligations.pendingFirings(details);
        if (owed.signum() > 0) {
            payObligationBatch(job, details, owed, inventory, level, reservation, ledger, null, probe);
        }
        if (!job.batchObligations.hasPending(details)) immediate.deliverBufferedBatchOutputs(receiver);
    }

    /** 按实际原型逐族合并一发用量；跨槽同族不能各自重复使用整份计划配额。 */
    static BigInteger declaredPrototypeQuota(IPatternDetails details, ParallelBatchExtraction.Batch prototype,
            ParallelPlanReservation reservation, BigInteger requested) {
        var demand = new java.util.HashMap<Object, BigInteger>();
        BigInteger limit = requested;
        var inputs = details.getInputs();
        for (int index = 0; index < prototype.holder().length; index++) {
            var held = prototype.holder()[index];
            if (held == null || index >= inputs.length) {
                continue;
            }
            for (var entry : held) {
                var key = entry.getKey();
                var quota = reservation.quotaExact(key, details, inputs[index]);
                if (quota == null) {
                    continue;
                }
                BigInteger units = prototype.exactPaid() != null && prototype.exactPaid()[index] != null
                        ? prototype.exactPaid()[index].getExact(key) : BigInteger.valueOf(entry.getLongValue());
                if (units.signum() <= 0) {
                    continue;
                }
                BigInteger combined = demand.merge(key.getPrimaryKey(), units, BigInteger::add);
                limit = limit.min(quota.exactValue().max(BigInteger.ZERO).divide(combined));
            }
        }
        return limit;
    }

    /**
     * 这一趟申报要从池子里遮住的那一份别人口粮：<b>顺位排水算出的派生读数</b>，不是账本。
     *
     * <p>每次调用现算，绝不在 tick 开头算一份留着用：同一 tick 里前一张样板刚挂上的欠账，
     * 后一张必须当场看见——留着 tick 开头的快照就等于允许两张样板在同一轮里把同一份存量
     * 各许一次（正是 owed 挂死那一型）。成本有界：没有欠账时一次池子都不读，有欠账时只点
     * 阅欠账样板候选表上的那几个键。
     *
     * <p>排水口径交给 {@link SlotAccount}（全仓唯一的一份顺位尺），欠账发数与池子真值分别是
     * 义务账与宽域池的现读读数；判据只有"遮住多少"，不参与任何扣料。
     *
     * <p>池子真值那一头交的是池账唯一口径（{@link ParallelBatchExtraction#exactStored}）：
     * 这里点名的键出自欠账样板的<b>候选表</b>（{@code BatchObligationLedger#seedRemaining}），
     * 不是池子里那一份实例——第三方自定义键把主键存成值对象时按声明键裸读恒 0，
     * 排水就只能"排出去 0 枚"⇒ 欠着的料一枚也不遮（旁人反复申报同一份存量那一型）。
     * 只换读法，顺位与算式一字未动。
     *
     * <p>只有明确的无限源不占用有限池料。未来产量在 long 投影中溢出，
     * 不代表池内没有实物，也不能撤销已声明批次对现有材料的预留。
     */
    static WideKeyCounter pendingPoolClaims(
            BatchObligationLedger obligations,
            @Nullable Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>>
                    orderedTasks,
            ListCraftingInventory inventory,
            ParallelPlanReservation planReservation
    ) {
        return obligations.pendingPoolClaims(orderedTasks,
                // 池子真值走池账唯一口径：候选表上那一枚是声明键，不是池子里那一份实例
                key -> ParallelBatchExtraction.exactStored(inventory, key),
                planReservation::isUnboundedSupply);
    }

    /**
     * 申报份数：四个上界的交集——精确剩余发数、宿主真实容量、单批可表示上界、现场可付上界，
     * 再被本 tick 付得起的发数夹一刀。
     *
     * <p><b>份数归规划、本批归现场</b>：精确剩余／宿主容量／可表示上界／电那几道是申报形态自身的
     * 门槛（与 {@code <=1} 一起交 {@code null} 退回 k 倍 holder）；最后那道<b>现场可付上界</b>夹的是
     * "本批这一轮真凑得齐的发数"，凑齐 1 发即申报 1 发（能推就推），只有夹到 0 才 {@code null} 退回
     * 既有的回补腿——那一支不改任务表、不动总份数，总份数只由规划定版。
     *
     * <p>BigInteger 宿主的发数、产出和容器预期均为宽域账，不使用 long 的可表示上界。
     * 普通窄域宿主仍按真实容量及单批可表示量限制；现场材料和计划配额始终保留约束。
     *
     * <p>刻意不拿 {@code holderBatchCapacity} 夹——那份上界描述的正是"k 倍料装不装得进
     * {@code KeyCounter}"，声明形态要拆的就是它。
     *
     * <p>"别人已经申出去的池料"交的是 {@code Supplier} 而不是现成的表——claim 每次都要重排一遍
     * 欠账样板的候选，而前面那几道夹子常常当场就把申报判成"不适用"，先算就是白算；离线用例没有
     * 欠账可遮时显式交 {@code null}。
     */
    static @Nullable BigInteger declaredBatchFirings(
            DeclaredBatchHost host,
            IPatternDetails details,
            ParallelExecutingCraftingJob.TaskProgress progress,
            ListCraftingInventory inventory,
            @Nullable net.minecraft.world.level.Level level,
            @Nullable ParallelPlanReservation planReservation,
            long powerCeiling,
            @Nullable java.util.function.Supplier<WideKeyCounter> poolClaims
    ) {
        return declaredBatchFirings(host, details, progress, inventory, level, planReservation,
                powerCeiling, poolClaims, null);
    }

    /**
     * 同上，多一个 {@code probe}：<b>只加分型读数</b>（哪一支夹子先把申报打到 ≤1 ⇒ 交回 {@code null}），
     * 逐字保持交回值与判据不变——每支夹子都是 {@code min} 的不等式，一旦 {@code declared<=1} 后续 {@code min}
     * 只会更小，所以"逐支先命中即交回 null"与原来"四支 {@code min} 完再判 {@code <=1}"交回的是同一个数
     * （非 {@code null} 时一字不动，{@code null} 时同为 {@code null}）。
     */
    static @Nullable BigInteger declaredBatchFirings(
            DeclaredBatchHost host,
            IPatternDetails details,
            ParallelExecutingCraftingJob.TaskProgress progress,
            ListCraftingInventory inventory,
            @Nullable net.minecraft.world.level.Level level,
            @Nullable ParallelPlanReservation planReservation,
            long powerCeiling,
            @Nullable java.util.function.Supplier<WideKeyCounter> poolClaims,
            @Nullable ParallelBatchExtraction.Probe probe
    ) {
        BigInteger declared = progress.exactRemaining().exactValue();
        if (declared.compareTo(BigInteger.ONE) <= 0) {
            noteDeclaredNull(probe, "fold");
            return null;
        }
        // 真正的 BigInteger 宿主使用宽域产出及容器账；只有窄域宿主受 long 可表示性限制。
        boolean unboundedFirings = host.declaredBatchFiringsUnbounded();
        if (!unboundedFirings) {
            declared = declared.min(host.declaredBatchCapacity(details));
            if (declared.compareTo(BigInteger.ONE) <= 0) {
                noteDeclaredNull(probe, "cap");
                return null;
            }
        }
        if (!unboundedFirings) {
            declared = declared.min(ParallelBatchExtraction.representableInputCeiling(details));
            if (declared.compareTo(BigInteger.ONE) <= 0) {
                noteDeclaredNull(probe, "rep-in");
                return null;
            }
        }
        if (!unboundedFirings) {
            declared = declared.min(ParallelBatchExtraction.representableOutputCeiling(details));
        }
        if (declared.compareTo(BigInteger.ONE) <= 0) {
            noteDeclaredNull(probe, "rep-out");
            return null;
        }
        if (powerCeiling != Long.MAX_VALUE) {
            declared = declared.min(BigInteger.valueOf(Math.max(1L, powerCeiling)));
        }
        if (declared.compareTo(BigInteger.ONE) <= 0) {
            noteDeclaredNull(probe, "power");
            return null;
        }
        // 前面几道夹子（精确剩余、宿主容量、可表示上界、电）都是免费的不等式，全过了才付排水这一次
        declared = ParallelBatchExtraction.declaredPayableCeiling(
                details, inventory, level, planReservation, declared,
                poolClaims == null ? null : poolClaims.get());
        // 现场这一轮凑得出几发就申报几发：凑齐 1 发就推 1 发，逐批推进直到对上规划那份。
        // 这一支夹的是<b>本批发数</b>，不是总份数——只有真一发都凑不齐（夹到 0）才交回 null 退回
        // 既有的回补腿（计划原材料分段续取），那一支不碰任务表、不改总份数。
        // 前面几道（精确剩余／宿主容量／可表示上界／电）仍在 {@code <=1} 交 null：那是"这批不值得
        // 走声明形态、退回 k 倍 holder"，与现场量无关。
        if (declared.signum() <= 0) {
            noteDeclaredNull(probe, "payable");
            return null;
        }
        return declared;
    }

    /**
     * 记下申报被<b>哪一支夹子先打空</b>（纯读数，见 {@link #declaredBatchFirings}）：一次申报只认
     * 先命中的那一支，不叠第二套、不参与任何判据。分型口径：{@code fold}（精确剩余 ≤1）／{@code cap}
     * （宿主容量）／{@code rep-in}（输入侧可表示上界）／{@code rep-out}（产出侧可表示上界）／
     * {@code power}（电）／{@code payable}（现场可付上界夹到 0——凑不齐一发，退回回补腿，不动总份数）。
     */
    private static void noteDeclaredNull(@Nullable ParallelBatchExtraction.Probe probe, String why) {
        if (probe != null) {
            probe.noteDeclaredRejectReason(why);
        }
    }

    /** 一轮 tick 最多交割几批义务材料（每批每键 ≤ long）：欠账不占满本轮推送预算。 */
    private static final int DECLARED_OBLIGATION_BATCHES_PER_TICK = 4;
    /** 每 tick 的计划原料提取刀数上限，为任务推送和义务交割保留时间。 */
    private static final int PLANNED_INITIAL_CHUNKS_PER_TICK = 64;

    /**
     * 交割声明批量的义务材料：宿主按申报份数收了料，CPU 就欠下剩下 (k-1) 发的提取。
     *
     * <p>交割只取内部缓冲；计划原材料分段续取已在本轮执行前完成。
     * 由 {@link #payObligationBatch} 走 {@link ParallelBatchExtraction#payDeclaredObligation}
     * （= prepareUniform 本体）伸手取。顺位、族配额、"取不足即整批归位"与 k 倍 holder 路径
     * 同一条口径；销账一律走既有 {@link #recordTakenInputs} 的槽级判据——这里不建第二套提料账。
     *
     * <p>交不出（网络也没料、或该键的配给已尽）时欠账原样留着，下轮再试：
     * 这份账只欠材料，不欠推送——任务的剩余发数已在申报那一刻按实收扣过。
     *
     * <p>{@code orderedTasks} 只喂停滞行的 {@code claimed=} 那一格读数（与申报遮池同一条排水），
     * 交割本身的顺序仍按义务账自己的快照走，不参与任何判据。
     */
    private void payDeclaredBatchObligations(
            ParallelExecutingCraftingJob job,
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> orderedTasks,
            ParallelPlanReservation planReservation,
            @Nullable ParallelCpuGridBudgetLedger budgetLedger,
            ParallelBatchExtraction.Probe batchProbe,
            long currentTick
    ) {
        if (job == null || !job.batchObligations.hasAnyPending()) {
            return;
        }
        var level = lane.cluster().level();
        long paidFirings = payObligationBatches(job, inventory, level, planReservation,
                poolLedger, null, batchProbe, () -> hasTickBudget(budgetLedger));
        BigInteger stillOwed = job.batchObligations.totalPending();
        if (ChexsonsaeutilsCompatibilityConfig.parallelCpuDiagnosticsEnabled()
                && obligationStalled(paidFirings, stillOwed)
                && currentTick - lastObligationStallWarnTick >= STALL_LOG_TICKS) {
            // 申报出去的份数拿不到料：这份模型唯一的真窟窿，必须看得见（低频一行，不刷屏）。
            // AELog 走 String.format，占位符必须是 %s，写成 {} 会整行参数丢失。
            //
            // 判据一字未动（仍是"一批都没交割且还挂着欠账"），但这一行<b>必须带上分刀推进的
            // 读数</b>：一发真需 >long 时天然要 N 轮才凑得齐（AE2 一轮最多交一刀 long），
            // 前 N−1 轮 owed 不动、obPaid=0 —— 只看那两格会把"正在累付"报成"停滞"。
            // obChunks/obPull 是本轮补进池子的刀数与真值合计，obPayChunks/obPaid 是本轮伸手
            // 付出去的刀数与真值合计（凑不齐整发会整批归位，故它只证明料在动、不证明付成）。
            // claimed= 是"这份池子里有多少正被谁的欠账遮着"（同一条排水的读数，纯诊断）：
            // 没有它，pool= 摆着有料而申报只肯报几发的那一段在日志里就是一条没来由的缺口。
            // 这里<b>不印</b>本轮交割批数：判据要求 {@code paidFirings == 0}，而 {@code batches++}
            // 只在 {@code paid > 0} 之后 ⇒ 停进行的它恒为 0，证不了任何事（旧串里那一格就是这个
            // 形状，留着就是下一次误判的入口）。批数仍是循环自己的计数，只用来夹每 tick 几批。
            lastObligationStallWarnTick = currentTick;
            // "%s" 而非单参：AELog 对消息串无条件再跑 String.format，成品行交给参数位才不会被
            // 值里可能出现的 '%' 二次解析炸掉（旧形状里值从不过 format）。
            AELog.warn("%s", describeObligationStallLine(lane.laneId(), stillOwed, job, orderedTasks,
                    inventory, planReservation, batchProbe, currentTick));
        }
    }

    /** 按轮转顺序交割本轮欠账；批数和墙钟预算只终止本轮，不撤销未尝试条目的优先顺序。 */
    static long payObligationBatches(
            ParallelExecutingCraftingJob job,
            ListCraftingInventory inventory,
            @Nullable Level level,
            ParallelPlanReservation planReservation,
            ParallelPoolLedger poolLedger,
            @Nullable ParallelBatchExtraction.ObligationRefill refill,
            ParallelBatchExtraction.Probe batchProbe,
            java.util.function.BooleanSupplier hasBudget
    ) {
        long paidFirings = 0L;
        int batches = 0;
        for (IPatternDetails details : job.batchObligations.pendingPatterns()) {
            BigInteger owed = job.batchObligations.pendingFirings(details);
            if (owed.signum() <= 0) {
                continue;
            }
            batchProbe.obligationOwedMax = Math.max(batchProbe.obligationOwedMax,
                    owed.compareTo(WideCeilings.LONG_ACCOUNT) >= 0
                            ? Long.MAX_VALUE : owed.longValue());
            if (batches >= DECLARED_OBLIGATION_BATCHES_PER_TICK || !hasBudget.getAsBoolean()) {
                break;
            }
            if (onlyUnboundedInputs(details, planReservation)) {
                // 无限源只认量，没有实物出池；整笔销清，避免按 long 分段空转数万亿次。
                job.batchObligations.noteSettlementAttempt(details);
                job.batchObligations.settle(details, owed);
                batchProbe.obligationBatches++;
                long paid = Ae2Amounts.toLong(PlanAmount.of(owed));
                batchProbe.obligationFiringsSum = ClampedMath.addUp(batchProbe.obligationFiringsSum, paid);
                paidFirings = ClampedMath.addUp(paidFirings, paid);
                batches++;
                continue;
            }
            long paid = payObligationBatch(job, details, owed, inventory, level,
                    planReservation, poolLedger, refill, batchProbe);
            if (paid <= 0L) {
                continue;
            }
            batches++;
            paidFirings = ClampedMath.addUp(paidFirings, paid);
        }
        return paidFirings;
    }

    /** 只有所有有效输入候选都被明确认定为无限源时，才允许无需实物提取的整笔交割。 */
    private static boolean onlyUnboundedInputs(IPatternDetails details, ParallelPlanReservation reservation) {
        var inputs = details.getInputs();
        if (inputs == null || inputs.length == 0) {
            return false;
        }
        for (var input : inputs) {
            if (input == null || input.getMultiplier() <= 0L || input.getPossibleInputs() == null) {
                return false;
            }
            boolean hasCandidate = false;
            for (var candidate : input.getPossibleInputs()) {
                if (candidate == null || candidate.what() == null || candidate.amount() <= 0L) {
                    continue;
                }
                hasCandidate = true;
                if (!reservation.isUnboundedSupply(candidate.what())) {
                    return false;
                }
            }
            if (!hasCandidate) {
                return false;
            }
        }
        return true;
    }

    /**
     * 停滞行的<b>唯一渲染口</b>（格式串与实参只在这一处）：{@code payDeclaredBatchObligations}
     * 拿它产出的整行交 {@code AELog.warn}，用例从<b>同一个口</b>把整行取回按空白切 token——
     * 一格一 token 的段数判据由
     * {@code ParallelDeclaredBatchObligationTest#stallLineSplitsIntoExactlyTheKnownCellNames}
     * 钉在这里交出的行上，不许有第二个"看起来同一行"的拼串处。
     *
     * <p>除首两格（标签与 {@code per100r} 同族的 {@code obligation_stalled} 标记）外，每一格由
     * 自己的<b>格出口</b>折成单 token：{@code pool=}/{@code claimed=} 走
     * {@code describeInventory(Exact)}，{@code blocked=} 走 {@link #describeBlockedObligation}
     * 的出口折点。数值口径一字未动：这里搬的是拼串位置，不是算式。
     *
     * <p><b>欠料与欠交付是两件事，各占各的格，不许再混成一句话</b>：
     * <ul>
     *   <li>{@code owed=} / {@code pool=} / {@code claimed=} / {@code obPull=} / {@code obPaid=} /
     *       {@code blocked=} 这一族是<b>欠料</b>——申报出去的发数还没从池子里把材料交割掉，
     *       {@code obligation_stalled} 这个标记归因归的就是它；</li>
     *   <li>{@code inflightBatches=} / {@code oldestInflightTicks=} / {@code undelivered=}
     *       这一族是<b>万象炉申报批的交付口径</b>：料已经被机器吃掉，问的是"这一笔收没收场"。
     *       {@code undelivered=<样板>#<累计欠交付>枚/已回退<退回待办>枚@batch[<短id>,…]} 只点
     *       <b>被负向终局（REJECTED/CANCELLED）点名过</b>的那几笔，{@code 已回退} 少掉的那一截
     *       就是"条目已出表、退回来无处可落"的那一笔（只点名，不复活条目）；
     *       挂了却还没等到任何终局的那一笔走 {@code inflightBatches=} 与
     *       {@code oldestInflightTicks=<龄>@<batchId 前缀>}——那一型就是"报满而终局没来"，
     *       它只被点名、<b>不</b>进任何出表判据（口径与逐字证据见
     *       {@code DeclaredBatchTerminalLedger} 类注释）。三格全是纯读数，不参与判据——
     *       出表判据只有 {@link #taskMayLeaveTable} 那两格（发数跑完 + 材料欠账清了）。</li>
     * </ul>
     *
     * @param currentTick 在飞记录 ages 的基准 tick（由调用方那一趟的 {@code currentTick} 带入，
     *                    不在这里现查 {@code TickHandler}：离线用例要能钉住龄）
     */
    static String describeObligationStallLine(
            Object laneId,
            BigInteger stillOwed,
            ParallelExecutingCraftingJob job,
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>>
                    orderedTasks,
            ListCraftingInventory inventory, ParallelPlanReservation planReservation,
            ParallelBatchExtraction.Probe batchProbe,
            long currentTick
    ) {
        return String.format("[chexsonsaeutils-declared] lane=%s obligation_stalled owed=%s"
                        + " pool=%s claimed=%s prodUntrusted=%d"
                        + " obChunks=%d obPull=%s obPayChunks=%d obPaid=%s"
                        + " inflightBatches=%d oldestInflightTicks=%s undelivered=%s pendingDelivery=%s blocked=%s",
                laneId, stillOwed, describeInventory(snapshotPool(inventory)),
                describePoolClaims(pendingPoolClaims(job.batchObligations, orderedTasks,
                        inventory, planReservation)),
                // 纯读数：因"产出这本贷方账放不下可信口径"而不锁的键数（usableFor 里那条
                // !productionUntrusted 放开）。它与"本来就不该锁"同读数（resClamp 不记），
                // 不打出来实机就分不出这两型。不参与任何判据。
                planReservation == null ? 0 : planReservation.untrustedProductionKeyCount(),
                batchProbe.refillChunks, batchProbe.refillExactUnits,
                batchProbe.paidChunks, batchProbe.paidExactUnits,
                // ↓ 欠交付那一族（纯读数）：不接这条通道的作业恒 0 / 0@none / NONE / NONE
                job.declaredTerminals.inflightBatchCount(),
                job.declaredTerminals.describeOldestInflight(currentTick),
                job.declaredTerminals.describeUndelivered(),
                // 待交割那一格：还挂着没等到终局的申报批欠网络几枚产物（带 clamped 标记）；
                // 报满而终局永不来那一型就是从这一格与 inflightBatches= 一起露出来的
                job.declaredTerminals.describePendingDelivery(),
                describeBlockedObligation(job, orderedTasks, inventory, planReservation,
                        batchProbe));
    }

    /**
     * 停滞行的 {@code blocked=} 读数：<b>哪张样板、哪一格、每发吃几枚、池里有多少、配额还剩多少</b>。
     *
     * <p>此前这条腿只能报出"没推进"（owed 不动、obPull=0、obPaid=0），报不出<b>为什么</b>：
     * 是这一格现场真没料、是配给判成已尽、还是候选键压根对不上——三型在日志里长得一模一样，
     * 只能靠猜（已猜错三轮）。取还挂着欠账的样板按 {@code owed} 降序<b>最多三张</b>
     * （见 {@link #BLOCKED_TEMPLATE_LIMIT}），每张印它<b>前两格 + 这张样板本轮自己算不出
     * <em>一发</em>的那一格</b>（后者由 {@link ParallelBatchExtraction.Probe#failingCell} 按样板带出
     * 现场）。旧形状只点第一张：实机连续 7 行都是同一张人工太阳卡住、而链条上一环（flux 那格的
     * 产者）被顶名挤掉就永远看不见——点名的样板从一张扩到三张，每段的格名与形状一字不变。
     *
     * <p><b>这一行里的份数读数只有一把尺</b>：{@code 该格份数=} 与 {@code 真失败格[现场商,族商]}
     * 取的是同一次提取的那同一个数（批内 scope——取料点真扣配额的那一份），对不上号的那些格
     * 明写"非本轮该格"，<b>不</b>在此另算一个整张样板 scope 的"看起来对的数"（那是回补侧搬运上限
     * 的口径，摆进同一行就是下一次误判的入口）。纯读数：不参与任何判据，也不额外记账。
     *
     * <p>{@code 真失败格[...]} 那一格由 {@code ParallelBatchExtraction.Probe#cellReadout} 交出，
     * 与 per100r 的 {@code minCell[...]=} <b>逐字同一形状</b>（格内子字段用不带空白的 {@code ,}、
     * 键的第三方空白在格出口折成 {@code "_"}）：同一格在两行里不许再有两种字面——那会让拿 grep 串
     * 或 diff 对账的人读出假差异。
     *
     * <p><b>整格按 32c44e1 三条口径收成一格一 token</b>（停滞行与 per100r 同一把尺，由
     * {@code ParallelDeclaredBatchObligationTest#stallLineSplitsIntoExactlyTheKnownCellNames} 钉住
     * 段数==格名数）：这一格<b>内部</b>的子字段边界一律用不带空白的 {@code ,}（样板头与 {@code owed=}、
     * 候选之间、候选自己的 {@code 每发,池内,配额,可付发,该格份数}、{@code 真失败格=}／
     * {@code 本轮最后算的那张样板=}／{@code 回补受阻[...]} 各段之间都用 {@code ,}；同一槽的多个候选
     * 之间用 {@code /}，与 {@code obWhy=} 的格间分隔同字面），第三方空白（{@code AEKey#toString}
     * 带组件的 {@code " (with patches)"}）只在<b>这一格的出口</b>折一次 {@code "_"}——不在各来源
     * 处各写一折。看到 {@code "_"} 就是别人家的空格，看到 {@code ","} 就是我们自己的子字段边界。
     */
    static String describeBlockedObligation(ParallelExecutingCraftingJob job,
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>>
                    orderedTasks,
            ListCraftingInventory inventory, ParallelPlanReservation planReservation,
            @Nullable ParallelBatchExtraction.Probe batchProbe
    ) {
        if (job == null || orderedTasks == null) {
            return "无";
        }
        var templates = blockedObligationTemplates(job, orderedTasks);
        if (templates.isEmpty()) {
            return "无欠账样板";
        }
        var described = new StringBuilder();
        for (var template : templates) {
            described.append(describeBlockedTemplate(job, template.getKey(), template.getValue(),
                    inventory, planReservation, batchProbe));
        }
        // 格出口一次折净：格内剩下的空白恒是别人家的（样板头/候选键的 AEItemKey#toString
        // 带组件时的 " (with patches)"、本轮最后算的那张样板的键头）；自家子字段边界已经全是
        // 不带空白的 ","，候选分隔也是 "/"。折点只在整格出口这一处，不给每个来源各写一折——
        // 多张样板的段与段之间因此也切不出第二个 token（停滞行 12 格的段数判据靠这一点撑着）。
        return described.toString().replace(' ', '_');
    }

    /** {@code blocked=} 一格最多点几张欠账样板（纯读数封顶，不是判据）：按 {@code owed} 降序取前三张。 */
    private static final int BLOCKED_TEMPLATE_LIMIT = 3;

    /**
     * {@code blocked=} 的选样板名单（<b>唯一选样口径</b>）：挂着 {@code owed>0} 的样板按 {@code owed}
     * 降序（同 owed 保持 {@code orderedTasks} 的既有顺位，不随哈希遍历序翻），最多
     * {@link #BLOCKED_TEMPLATE_LIMIT} 张。义务停滞行的 {@code blocked=} 格与停滞行的 {@code alloc=}
     * 必含格读<b>同一份名单</b>——两格点的必须是同一批样板，不许两处各选各的。
     */
    static java.util.List<Map.Entry<IPatternDetails, BigInteger>> blockedObligationTemplates(
            ParallelExecutingCraftingJob job,
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>>
                    orderedTasks
    ) {
        var rows = new java.util.ArrayList<Map.Entry<IPatternDetails, BigInteger>>();
        for (var task : orderedTasks) {
            IPatternDetails details = task == null ? null : task.getKey();
            BigInteger owed = details == null ? BigInteger.ZERO
                    : job.batchObligations.pendingFirings(details);
            if (details == null || owed.signum() <= 0L) {
                continue; // 只报真欠着料的样板
            }
            rows.add(Map.entry(details, owed));
        }
        // List.sort 稳定：owed 并列时保持 orderedTasks 的顺位（台账 #135 那把全序尺）。
        rows.sort(Map.Entry.<IPatternDetails, BigInteger>comparingByValue(
                java.util.Comparator.reverseOrder()));
        return rows.size() > BLOCKED_TEMPLATE_LIMIT
                ? rows.subList(0, BLOCKED_TEMPLATE_LIMIT) : rows;
    }

    /**
     * {@code blocked=} 里<b>一张样板一段</b>的段体（逐字形状与旧单张那一段一字未动）：
     * {@code <样板头#i身份,owed=…,slot#0[…],slot#1[…],真失败格[…]/…,回补受阻[…]>}。
     * 空白不在这里折——整格的折点只在 {@link #describeBlockedObligation} 的出口那一处。
     */
    private static String describeBlockedTemplate(ParallelExecutingCraftingJob job,
            IPatternDetails details, BigInteger owed,
            ListCraftingInventory inventory, ParallelPlanReservation planReservation,
            @Nullable ParallelBatchExtraction.Probe batchProbe
    ) {
        var outputs = details.getOutputs();
        var head = outputs == null || outputs.isEmpty() || outputs.get(0) == null
                || outputs.get(0).what() == null
                ? "?" : outputs.get(0).what().toString();
        var described = new StringBuilder().append('<').append(head)
                // 样板实例身份标：与 noCandWhy=／quotaBlind= 的 src=／本轮最后算的那张样板=
                // 同一把尺（见 patternIdentityTag），否则"这一张欠着料的样板"与"那一张被跳过的
                // 样板"在日志里永远是两个名字、配不上号。
                .append(patternIdentityTag(details))
                .append(",owed=").append(owed);
        var slots = details.getInputs();
        // 停滞行点名的那一格（同一次提取的批内读数）；没有则所有格都明写"非本轮该格"
        var namedCell = batchProbe == null ? null : batchProbe.failingCell(details);
        int listed = 0;
        if (slots != null) {
            for (int index = 0; index < slots.length && listed < 2; index++, listed++) {
                var input = slots[index];
                var candidates = input == null ? null : input.getPossibleInputs();
                if (candidates == null || candidates.length == 0 || candidates[0] == null) {
                    described.append(",slot#").append(index).append("=空");
                    continue;
                }
                described.append(",slot#").append(index).append("[");
                // 最多打前 3 个候选：首位候选族配额已尽时，取料点会退到下一个候选——
                // "这一格到底还有没有可花候选、它的配额多少"（实机 slot#1[... 配额=0] 唯一
                // 没答的问题）只能从这里看见。纯读数：不参与任何判据，不新增拒绝分支。
                int shown = 0;
                for (var candidate : candidates) {
                    if (shown >= 3) {
                        break;
                    }
                    if (candidate == null || candidate.what() == null
                            || candidate.amount() <= 0L) {
                        continue; // 与取料点同一候选过滤（firstCandidate）：跳过零量与空项
                    }
                    AEKey key = candidate.what();
                    var perFiring = git.chexson.chexsonsaeutils.crafting.material.SlotAccount
                            .unitsPerFiringExact(input, candidate);
                    // 池内读数走池账唯一口径：候选表上那一枚是<b>声明键</b>，不是池子真装着的那一枚。
                    // 值对象主键的第三方键（实机 MekanismKey 那一型）按声明键裸读恒 0，而这一格是
                    // 用户下一轮排障要发回来的读数——读数以 0 撒谎比没读数更坏。纯读数，算式未动。
                    var stored = ParallelBatchExtraction.exactStored(inventory, key);
                    var quota = planReservation == null ? null
                            : planReservation.quotaExact(key, details, input);
                    if (shown > 0) {
                        described.append('/');
                    }
                    // 这一格算得出的份数：只印<b>取料点本轮真算过的那一份</b>（批内 scope，
                    // 见 ParallelBatchExtraction.Probe#FailingCell）——同一条 blocked= 行里
                    // "该格份数="与"真失败格[现场商/族商]"于是恒等，漂不到两处去。
                    // 这里<b>不再</b>调整张样板 scope 的那份分母另算一个"看起来对的数"：
                    // 同一个判据摆两种 scope、两种分母，就是下一次误判的入口（那把尺现在只在
                    // 回补侧活着，量的是搬运上限）。取料点本轮没算过这一格就明写非本轮该格。
                    described.append(key)
                            .append(",每发=").append(perFiring)
                            .append(",池内=").append(stored)
                            .append(",配额=").append(quota == null ? "无口径"
                                    : quota.exactValue().toString())
                            // 可付发走唯一饱和出口 Ae2Amounts.toLong：>long 的 BigInteger 商
                            // 直接 longValue() 会回绕成负数（实机逐字打出
                            // 可付发=-8627497601038215996）。
                            .append(",可付发=").append(perFiring.signum() <= 0L ? "?"
                                    : Ae2Amounts.toLong(PlanAmount.of(
                                            stored.divide(perFiring))))
                            .append(",该格份数=");
                    if (namedCell == null || namedCell.slotIndex() != index
                            || !java.util.Objects.equals(namedCell.key(), key)) {
                        described.append("非本轮该格");
                    } else {
                        described.append(namedCell.payableFirings() == null
                                ? "不夹" : namedCell.payableFirings().toString());
                    }
                    shown++;
                }
                if (shown == 0) {
                    described.append("无有效候选");
                }
                described.append(']');
            }
        }
        // 前两格在 >long 的份数下常常全都放行（配额 ÷ 族每发合计远大于它），而 payInChunks
        // 那一支按 obligationBatchFirings 的 cap 定义结构性不可达：真失败的那一格于是总是排在
        // 第 3 格往后。取料点真正算不出<b>一发</b>的那一格由 Probe 点出来（纯读数，同一条算式）。
        //
        // 点名按<b>样板</b>取（Probe#failingCell）：probe 每 tick 每 lane 新建一份，本轮却给
        // 十几张欠账样板各跑过一次提取，而 blockedCell 每次 prepareUniform 开头清一次 ⇒ 单格
        // 里停着的是<b>最后一次</b>提取那一格，这里点的却是<b>第一张</b>欠账样板 ⇒ 张冠李戴
        // （上一轮执行人自己就把这个负分支写进过报告）。这张样板本轮真没记过失败格才明写。
        if (namedCell != null) {
            described.append(",真失败格[").append(namedCell.line()).append(']');
        } else {
            described.append(batchProbe != null && !batchProbe.blockedCell.isEmpty()
                    ? ",真失败格=非本轮该样板" : ",真失败格=本轮未算");
            // 本轮没有真失败格时再补一格：这一张到底是<b>压根没被算</b>（每 tick 批数闸／tick
            // 预算闸把它挡在 payDeclaredBatchObligations 的 continue 上，回补那条腿一次都没为它
            // 开跑 ⇒ 这里报 <none> 或报出另一张样板），还是<b>算了却交 0</b>（报的就是这一张）。
            // 纯读数：只在拼串里出现，判据一律不读它。样板头里的第三方空白（AEItemKey#toString
            // 带组件时的 " (with patches)"）由整格出口的折点一次折掉，不在这里各折一遍。
            described.append(",本轮最后算的那张样板=")
                    .append(batchProbe == null || batchProbe.lastRefillPattern.isEmpty()
                            ? "<none>" : batchProbe.lastRefillPattern);
        }
        // 被点名的这一张<b>自己那一格</b>的回补读数（按 (样板,槽) 分桶的那本，含它自己的
        // 网络sim=/同族[…]下界）：旧形状是每个 Probe 只留第一格，于是这里印的常常是别张样板
        // 的 slot#1，而这一张真卡住的 slot#3 一个字都没有（实机 2026-09-28 那一跑）。
        // topUp=0/0 因此被读成"没伸手"，其实它证的是"伸了手、网络交回 0 枚"。纯读数。
        var refillCells = batchProbe == null ? "" : batchProbe.refillBlockedCellsFor(details);
        if (!refillCells.isEmpty()) {
            described.append(",回补受阻[").append(refillCells).append(']');
        }
        return described.append('>').toString();
    }

    /**
     * 停滞行的 {@code claimed=} 紧凑读数：欠账正把池子里的哪几个键占着多少（逐键宽域合计）。
     *
     * <p>{@code pool=} 只报"池子里现在摆着什么"，看不出其中多少已经被人申出去、还没交割——
     * 那一截在下一张样板的申报上界里是被遮掉的，于是"池子看着有料、申报却只肯报那么几发"在
     * 日志里读成一条没有来由的缺口。纯读数：与 {@link #pendingPoolClaims} 同源一条算法，
     * 不参与任何判据，也不额外记一份账。
     */
    static String describePoolClaims(WideKeyCounter claims) {
        var exact = new java.util.LinkedHashMap<AEKey, BigInteger>();
        if (claims != null) {
            for (AEKey key : claims.keys()) {
                BigInteger amount = claims.getExact(key);
                if (key != null && amount.signum() > 0L) {
                    exact.put(key, amount);
                }
            }
        }
        return describeInventoryExact(exact);
    }

    /**
     * 这一条任务能不能从任务表上撤下来：发数跑完不算收工，申报出去但还没交割的材料必须还挂着。
     *
     * <p>它同时是 {@link #pendingPoolClaims} 的覆盖前提——排水读的是任务表，条目一旦提前出表，
     * 那笔欠账就再也遮不住池子，"同一份存量许诺两次"逐字复发（实机形态 owed 挂死）。
     * 判据本体与并条之前的四处写法逐字同效，只是终于能被用例直接钉住。
     *
     * <p><b>判据只有这两格，而且全仓只在这一处</b>（#209）：万象炉申报批的<b>终局</b>不进这里的
     * 任何一格。负向终局的处置是<b>回退</b>而不是<b>拦表</b>——那一笔的发数沿
     * {@code ParallelExecutingCraftingJob#rollBackDeclaredBatchFirings} 退回第一格的待办、
     * 材料欠账原样挂着，这张样板从此重新备料、重新申报（沿用既有那条"接受后才 commit 游标；
     * 拒绝／能耗缩批先回注且不前移"）；台账里的"欠交付"自始至终只是停滞行 {@code undelivered=}
     * 那一格读数，与既有全部宿主一样落在"行为一字不变"这一档。
     *
     * <p><b>为什么这一格不许由台账来拦</b>：能解开"欠交付拦表"的只有"这一批重新申报出去"，
     * 而重新申报要料——料永远不来那一档（现场缺料、配给已尽）就把一张<b>发数已经归零</b>的样板
     * 钉在表上永不放行，而钉住它的那一格自己没有任何一条腿能把它救出来。那正是 12:54 那一刀
     * 被回退的病（{@code 永不出表／作业永不收工}），也是本仓显式选定"不索要、不拦表、只点名"的
     * 理由，不许复发。退回这两格之后<b>没有任何一条路径</b>能让一张发数归零的样板因为这条通道
     * 而永远出不了表：还拦得住它的只有"欠宿主的材料没交割"，而那一截照旧由既有的交割循环还清；
     * 反复 {@code CANCELLED} 的形状只由 {@code undelivered=}/{@code inflightBatches=}/
     * {@code oldestInflightTicks=} 三格点名，这里<b>不新增闸门、退避夹子或超时兜底</b>。
     *
     * <p><b>这一刀选定"只点名、不抑制"，如实写下取舍</b>：现成的两条退避都不适用——
     * ① {@code ParallelCpuProviderBackoff} 记的是<b>拒收</b>（{@code recordPushRejected}），
     *    而 {@code CANCELLED} 发生在<b>接单之后</b>（{@code recordPushAccepted} 已把那笔退避清掉），
     *    把"接单后被取消"记成一次拒收就是拿它当新闸门、且会顺手把同轮的 k 倍 holder 段堵死；
     * ② 没有一条既有的 {@code materialBackoffUntil} 能表达"这张样板刚被取消、先别重报"——
     *    硬造一枚就是新建计数器，本刀禁止。于是这里<b>会空转</b>：一台反复取消的机器
     *    （区块反复卸载／机器被拆后重建）会把同一张样板的 tick 预算吃在"退回→重报→再被取消"这一圈上。
     *    症状：停滞行 {@code inflightBatches} 反复回到 1、{@code undelivered=} 的累计只增、
     *    {@code 已回退} 那一截跟着涨，而这张样板的产出一枚不落地不推进。
     *    代价被压在"只点名"这一格、不拦表——比原病（作业<b>永不</b>收工）轻，也是本仓显式选定的方向。
     */
    static boolean taskMayLeaveTable(@Nullable IPatternDetails details,
            @Nullable ParallelExecutingCraftingJob.TaskProgress progress,
            BatchObligationLedger obligations
    ) {
        return progress != null && progress.isFinished() && !obligations.hasPending(details);
    }

    /**
     * 停滞告警的唯一闸口：一批都没交割且还挂着欠账。
     *
     * <p>抽出来只为让"网络也没料 ⇒ 仍然欠着、仍然报警"这条底线能在离线用例里钉住。
     *
     * <p><b>这一格的归因是欠料，不是欠交付</b>：它问的是"申报出去的那几发，材料还没从池子里交割"。
     * 万象炉申报批的<b>欠交付</b>（机器吃了料、终局把这批判成没交出来）是另一件事，它的读数在
     * 停滞行 {@code undelivered=} 那一格、它的处置是<b>把那一批发数退回待办</b>
     * （{@code ParallelExecutingCraftingJob#rollBackDeclaredBatchFirings}），<b>不是</b>拦表——
     * 两族不许混成一句，也不许拿 {@code obligation_stalled} 这一格去说交付的事。
     */
    static boolean obligationStalled(long paidFirings, @Nullable BigInteger stillOwed) {
        return paidFirings == 0L && stillOwed != null && stillOwed.signum() > 0L;
    }

    /**
     * 交割一张样板的一批义务材料：内部缓冲优先，缺口走网络回补通道。
     *
     * <p>回补只搬料不记账——料进缓冲后仍由 {@link ParallelBatchExtraction#payDeclaredObligation}
     * 伸手取，销账仍只有 {@link #recordTakenInputs} 那一个出口。
     *
     * <p>{@code planReservation} 是<b>编译期非空</b>的形参：调用方只有
     * {@link #payDeclaredBatchObligations} 一处，拿的是每 tick 现建的那份配给表。上一条
     * "配给表为 null 就把缺口注进缓冲"的退路已删，收口之后"认量绝不进池"不再依赖任何假设。
     *
     * @param refill 网络回补通道；{@code null}（离线用例/网格掉了）即只吃内部缓冲，行为与本刀之前一致
     * @return 本批交割的发数；0 表示交不出，欠账原样留着
     */
    static long payObligationBatch(
            ParallelExecutingCraftingJob job,
            IPatternDetails details,
            long payableFirings,
            ListCraftingInventory inventory,
            @Nullable net.minecraft.world.level.Level level,
            ParallelPlanReservation planReservation,
            ParallelPoolLedger poolLedger,
            @Nullable ParallelBatchExtraction.ObligationRefill refill,
            @Nullable ParallelBatchExtraction.Probe probe
    ) {
        return payObligationBatch(job, details, BigInteger.valueOf(payableFirings), inventory, level,
                planReservation, poolLedger, refill, probe);
    }

    /** 内部实物交割保持精确发数；返回 long 仅用于推进及诊断读数。 */
    static long payObligationBatch(
            ParallelExecutingCraftingJob job,
            IPatternDetails details,
            BigInteger payableFirings,
            ListCraftingInventory inventory,
            @Nullable net.minecraft.world.level.Level level,
            ParallelPlanReservation planReservation,
            ParallelPoolLedger poolLedger,
            @Nullable ParallelBatchExtraction.ObligationRefill refill,
            @Nullable ParallelBatchExtraction.Probe probe
    ) {
        if (job == null || details == null || payableFirings.signum() <= 0) {
            return 0L;
        }
        // 成功或缺料都移到队尾；下轮从尚未获得交割机会的样板开始。
        job.batchObligations.noteSettlementAttempt(details);
        String topUpPath = poolLedger.path("declaredObligationTopUp");
        long pulled;
        try {
            pulled = ParallelBatchExtraction.refillObligationBatch(
                    details, inventory, level, Ae2Amounts.toLong(PlanAmount.of(payableFirings)),
                    planReservation, refill, probe);
        } finally {
            poolLedger.restore(topUpPath);
        }
        ParallelBatchExtraction.Batch paid;
        String path = poolLedger.path("declaredObligation");
        try {
            paid = ParallelBatchExtraction.payDeclaredObligation(
                    details, inventory, level, payableFirings, planReservation, probe);
        } finally {
            poolLedger.restore(path);
        }
        if (paid == null || paid.executions() <= 0L) {
            return 0L;
        }
        recordTakenInputs(job, details, paid);
        job.batchObligations.settle(details, paid.exactExecutions());
        if (probe != null) {
            probe.obligationBatches++;
            probe.obligationFiringsSum = ClampedMath.addUp(
                    probe.obligationFiringsSum, paid.executions());
            if (pulled > 0L) {
                probe.obligationRefillBatches++;
                probe.obligationRefillUnits = ClampedMath.addUp(probe.obligationRefillUnits, pulled);
            }
        }
        return paid.executions();
    }

    /**
     * 把这一趟回补里<b>逐枚键</b>新加的那几枚折进 lane 的流水（台账 #225 读数 6，纯读数）：
     * 与 {@code lastTaskTopUpChunks}／{@code lastTaskTopUpExactUnits} 同一个取差值口径——
     * 探针那张表在同一个 tick 里只会往上走，差值恒非负，所以这里<b>没有一个条件</b>，
     * 既不看池子也不看配额，更不参与任何判据。
     */
    void foldTaskTopUpUnitsPerKey(java.util.Map<AEKey, BigInteger> perKeyBefore,
            ParallelBatchExtraction.Probe probe) {
        probe.refillUnitsPerKey.forEach((key, now) -> lastTaskTopUpUnitsPerKey.merge(key,
                now.subtract(perKeyBefore.getOrDefault(key, BigInteger.ZERO)), BigInteger::add));
    }

    /**
     * {@code topUpKeys=} 那一格（台账 #225 读数 6）：逐枚键 {@code 键=枚数} 的流水，
     * 键与键用 {@code ,} 连，第三方键原文自带的空白（{@code AEItemKey#toString} 带组件时的
     * {@code " (with patches)"}）在<b>格出口</b>一次折成 {@code "_"}；一枚都没有即 {@code <none>}，
     * 不摆空壳。一格一 token、值内零空白。
     */
    static String describeTopUpUnitsPerKeyCells(java.util.Map<AEKey, BigInteger> perKey) {
        var cells = new java.util.ArrayList<String>();
        for (var entry : perKey.entrySet()) {
            if (cells.size() >= TOP_UP_KEY_READOUT_LIMIT) {
                break;
            }
            cells.add(entry.getKey() + "=" + entry.getValue());
        }
        if (cells.isEmpty()) {
            return "<none>";
        }
        return ("[" + String.join(",", cells) + "]").replace(' ', '_');
    }

    /**
     * {@code topUpKeysCov=} 那一格（读数 6 的覆盖面自报）：{@code shown=<这一格真点出几枚键>}／
     * {@code total=<流水里真有几枚键>}，形状照 {@link PatternRosterReadout}（同一把尺，
     * 截断后不许把"看到的四枚"报成"一共四枚"）。
     */
    static String describeTopUpUnitsPerKeyCoverage(java.util.Map<AEKey, BigInteger> perKey) {
        return "shown=" + Math.min(perKey.size(), TOP_UP_KEY_READOUT_LIMIT) + "/total=" + perKey.size();
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

    /** 纯读数：按宿主类名把可用性结论累进 [READY, BUSY, 退避, 推送成功]。 */
    private static void noteProviderClass(
            java.util.HashMap<String, long[]> stats,
            @Nullable ICraftingProvider provider,
            ParallelCpuProviderBackoff.ProviderAvailability availability
    ) {
        long[] cells = stats.computeIfAbsent(
                provider == null ? "<null>" : provider.getClass().getSimpleName(),
                ignored -> new long[4]);
        switch (availability) {
            case READY -> cells[0]++;
            case BUSY -> cells[1]++;
            case BACKED_OFF -> cells[2]++;
        }
    }

    static ParallelCpuProviderBackoff.ProviderAvailability checkProviderAvailability(
            @Nullable ICraftingProvider provider,
            IPatternDetails pattern,
            long currentTick,
            @Nullable ParallelCpuProviderBackoff providerBackoff,
            ParallelCpuProviderBackoff.PushLeg leg
    ) {
        if (providerBackoff != null) {
            return providerBackoff.checkProvider(provider, pattern, leg, currentTick);
        }
        if (provider == null) {
            return ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF;
        }

        if (provider.isBusy()) {

            return ParallelCpuProviderBackoff.ProviderAvailability.BUSY;
        }
        return ParallelCpuProviderBackoff.ProviderAvailability.READY;
    }

    /**
     * 申报腿的预探测：读退避表里 {@code DECLARED} 那一桶（与 holder 腿分开记，
     * 见 {@link ParallelCpuProviderBackoff.PushLeg}）。
     *
     * <p>为什么不借 holder 腿那一次探测：申报批被拒说的是"这台机器现在不接声明形态"，而同轮
     * k 倍 holder 段读的是同一个池子同一份料，路必须还开着（实机 lane 09a75f4a 那一型）。
     *
     * <p>身份取 {@code declaredBatchBackoffOwner()}：与 {@link #attemptDeclaredBatch} 写那笔退避的
     * 同一个身份，壳（{@code UselessBigIntegerDeclaredHost}）不当查询键。
     */
    private static boolean declaredProbeReady(
            DeclaredBatchHost host,
            IPatternDetails details,
            long currentTick,
            @Nullable ParallelCpuProviderBackoff providerBackoff
    ) {
        return checkProviderAvailability(host.declaredBatchBackoffOwner(), details, currentTick,
                providerBackoff, ParallelCpuProviderBackoff.PushLeg.DECLARED)
                == ParallelCpuProviderBackoff.ProviderAvailability.READY;
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
            reserveOne(job, stack);
        }
        for (GenericStack stack : expectedContainerItems) {
            reserveOne(job, stack);
        }
        lane.cluster().refreshLaneState(lane);
    }

    private static void reserveExpectedWaiting(
            ParallelExecutingCraftingJob job,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        reserveExpectedWaiting(job, expectedOutputs, expectedContainerItems, null);
    }

    /**
     * 一批推送的预期挂账，允许其中一部分产物<b>按宿主的自报计划直接入网</b>。
     *
     * <p>{@code plannedToNetwork} 是宿主自报的"这批不经 CPU 池子直接交出去"的每键枚数（每键 ≤
     * {@code long}，机器的切段粒度）；{@code null} 即"全部走实物回池"，与既有各条推送路径逐字同效。
     *
     * <p>两条去向的划分只在 {@link #reserveOne(ParallelExecutingCraftingJob, AEKey, long, long)}
     * 一处发生：申报 E 枚、自报计划 P 枚 ⇒ 记 {@code min(P,E)} 枚进预期入网账、余下
     * {@code E - min(P,E)} 枚进 {@code waitingFor} 等实物 ⇒ 合计恒等于 E，不重复计也不漏计。
     * 本作业后续仍需的中间产物不参与直投分流：全额挂实物接收，沿 AE2 合成存储拦截回 CPU。
     *
     * <p><b>宿主报在申报表之外的键一律不认</b>（改前那一支会把它们全额记进"已入网"并顺手
     * {@code accountAnnounced} 复加一次）。不认的理由有两条：
     * <ul>
     *   <li>申报表就是 {@code details.getOutputs()}，而 bigint 通道的自报计划就是同一张表的
     *       {@code pattern.getOutputs() × 份数}（{@code AlloyFurnaceBigIntegerCrafting#plannedOutputs}）
     *       ⇒ 生产侧两表同键，那一支在真宿主上永不命中；</li>
     *   <li>它真命中时记的是凭空量：这张样板没申报过这个键，报称账与预期账各白添一笔
     *       （键的计划量还能超 {@code long}，那份夹到天花板的哨兵就此被当成量用）。</li>
     * </ul>
     * 动态产物的真实键本来也不在这里，它在 {@code onBatchOutputs} 那侧点名（见
     * 集成侧逐键自报量差口）。
     */
    static Map<AEKey, Long> reserveExpectedWaiting(
            ParallelExecutingCraftingJob job,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems,
            @Nullable KeyCounter plannedToNetwork
    ) {
        var recordedPlanned = new java.util.LinkedHashMap<AEKey, Long>();
        if (job == null) {
            return recordedPlanned;
        }
        for (var expectedOutput : expectedOutputs) {
            if (expectedOutput.getKey() == null || expectedOutput.getLongValue() <= 0L) {
                continue;
            }
            long planned = reserveOne(job, expectedOutput.getKey(), expectedOutput.getLongValue(),
                    plannedToNetwork == null
                            ? 0L : plannedToNetwork.get(expectedOutput.getKey()));
            if (planned > 0L) {
                // 这一格即"这批产物按宿主自报计划划成待交割的那几枚"——与 planNet 同一处算式
                // （reserveOne 的 min(自报, 申报)），交回申报腿挂进交割账，交割对平只认这一份。
                recordedPlanned.merge(expectedOutput.getKey(), planned, Long::sum);
            }
        }
        for (var expectedContainerItem : expectedContainerItems) {
            if (expectedContainerItem.getKey() == null || expectedContainerItem.getLongValue() <= 0L) {
                continue;
            }
            // 回流容器（模具/桶这类投下去原样退回的键）只有一条路：它必须实物回 CPU 才再投得出去，
            // 没有"预期入网"口径，也就不会被分流。
            reserveOne(job, expectedContainerItem.getKey(),
                    expectedContainerItem.getLongValue());
        }
        return recordedPlanned;
    }

    /** 报称产出与 waitingFor 挂账的唯一出口：任何推送路径的账都从这里过。 */
    private static void reserveOne(ParallelExecutingCraftingJob job, @Nullable GenericStack stack) {
        if (stack == null) {
            return;
        }
        reserveOne(job, stack.what(), stack.amount());
    }

    private static void reserveOne(
            ParallelExecutingCraftingJob job,
            @Nullable AEKey what,
            long amount
    ) {
        reserveOne(job, what, amount, 0L);
    }

    /**
     * 唯一的挂账出口，同时也是<b>唯一的分流口</b>。
     *
     * <p>本作业的中间口粮全额进入 {@code waitingFor}，通过 AE2 合成存储拦截回池子。
     * 不再用于本作业的产物才按 {@code plannedToNetwork} 分流到
     * {@link ParallelExecutingCraftingJob#outputsExpectedToNetwork}；剩余部分仍挂实物接收。
     *
     * <p><b>这本账是预期，不是交付</b>：那份枚数出自宿主按配方自报的计划量，里面没有一枚"插进存储
     * 成功"的回执，而<b>产物侧也没有任何能证明"真进了网络"的回执读数</b>。{@code blocked=} 那一格
     * 拼的 {@code 网络sim=/同族[…]下界}（{@code ParallelBatchExtraction#describeRefillNetworkReadout}）
     * 是<b>投入侧</b>读数——义务回补时那一格<b>材料</b>向网络现问的 SIMULATE 现量，产物键从不进它
     * 那一格；旧写法把它说成产物"网络里到底有没有这份料"的读数，是声称存在却在代码中不存在的判据
     * （照 9a3bd59 删 ann-ret-预期入网 假判据的口径改口）。
     *
     * <p>先夹 {@code planned ∈ [0, amount]} 再划分 ⇒ 两条去向合计恒等于申报量：自报多少就
     * 少挂多少实物，"实物进容器"与"预期入网"结构上重复计不了；宿主报超出申报的那部分一律不认
     * （与"申报 ∩ 实收"同一条口径）。
     *
     * <p>{@code accountAnnounced} 仍按<b>全额</b>记：它是"报称会产出多少"的流水账，与产物走哪条路
     * 无关。<b>没有</b>任何判据在这里做 {@code ann − ret − 预期入网} 那道减法——代码里没这个算式，
     * 三本账（{@code accountAnnounced}／{@code accountReturned}／{@code outputsExpectedToNetwork}）
     * 只在 {@code alloc=} 那一行并排印成 {@code ann=}／{@code ret=}／{@code planNet=} 三个读数，
     * "没回来的那一截"是<b>人</b>拿这三个数对出来的（由
     * {@code ParallelDeclaredBatchNetworkDeliveryTest} 钉住这三个格名与数值）。
     *
     * <p>它与 {@code waitingFor} 吃<b>同一条申报流</b>（本方法一处写这两本）：产出缓冲能记
     * {@code > long}，这本账同样能，所以它也是宽域账——批批精确相加、不回绕。这本账对外的读数
     * 只在 {@code ParallelExecutingCraftingJob#expectedToNetwork} 那一个 accessor 出口（宽域
     * 精确、<b>不</b>经饱和），停滞日志 {@code planNet=} 走的就是它；代码里没有任何把这本账折成
     * 饱和 {@code long} 的对外口——旧注释那句"对外的 long 读数在停滞日志那一条口上饱和"在代码中
     * 不存在，照实改口。
     *
     * <p><b>{@code planned} 这一截一律不进终点产物的交付账</b>（{@code 22bbc22} 留下的那条虚报腿，
     * 本刀摘掉）。全仓唯一交出 {@code plannedToNetwork} 的口是
     * {@link DeclaredBatchPlannedOutputs#takePlannedNetworkOutputs()}，实现者只有
     * {@code UselessBigIntegerDeclaredHost} 一个（useless 的 bigint 通道），而它交回的是宿主按配方
     * 现算的<b>自报计划量</b>——{@code DeclaredBatchReceipt} 的类注释自己写着"这一格里没有一枚是
     * '已经进网络'的证据"，机器后来报的"实际产出"也是同一个计划数。
     *
     * <p>逐条数过"产物确已入网、却不经实物腿"的通道有没有：交付账唯一的实物写入点是
     * {@link #insertInternal} 里 {@code what.matches(job.finalOutput)} 那一支，它的上游是
     * {@code ParallelCraftingLaneState#insertIntoWaiting}/{@code insertIntoWaitingAndGetResult} 与
     * {@code ParallelCraftingCpuCluster#insertIntoWaitingForCraftAndGetAccountedAmount} 这两个到账口，
     * 再往上就是 AE2 交货到 CPU 的那一句 {@code insert}。<b>没有第二条合法通道</b>：宿主直写 ME 网络
     * 那一型在通道里<b>拿不到实物回执</b>，所以诚实的结果是<b>实物</b>交付判据
     * （{@link #finalOutputFullyDelivered}）对它<b>不成立</b>——不许拿自报量凑订单量把作业判成交齐。
     * 它唯一可能的收工是第二臂（{@link #declaredNetworkDeliveriesSettled}）：任务表空 ∧ 产出缓冲空 ∧
     * {@code planNet − 已交割 == 0}，而 {@code 已交割} 只由该笔终局 {@code SUCCESS} 写下
     * （#227 K2 换的就是这一格的尺子；改前它只问在飞表有没有挂着待交割那一笔）。
     */
    private static long reserveOne(
            ParallelExecutingCraftingJob job,
            @Nullable AEKey what,
            long amount,
            long plannedToNetwork
    ) {
        return reserveOne(job, what, BigInteger.valueOf(amount), BigInteger.valueOf(plannedToNetwork))
                .longValueExact();
    }

    /** 实物等待与直投计划只在此划分；单批超过 long 也按精确值守恒。 */
    private static BigInteger reserveOne(ParallelExecutingCraftingJob job, @Nullable AEKey what,
            BigInteger amount, BigInteger plannedToNetwork) {
        if (job == null || what == null || amount.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger planned = requiresOutputInCpu(job, what) ? BigInteger.ZERO
                : plannedToNetwork.max(BigInteger.ZERO).min(amount);
        job.accountAnnounced.add(what, amount);
        job.addExpectedToNetwork(what, planned);
        BigInteger stillExpected = amount.subtract(planned);
        if (stillExpected.signum() > 0) {
            // waitingFor 在两个构造入口均固定使用 WideCraftingInventory。
            ((WideCraftingInventory) job.waitingFor).insertExact(what, stillExpected, Actionable.MODULATE);
        }
        return planned;
    }

    /** 整批声明的宽域预留；返回同批终局必须交割的精确计划量。 */
    static Map<AEKey, BigInteger> reserveExpectedWaitingWide(ParallelExecutingCraftingJob job,
            WideKeyCounter outputs, WideKeyCounter containers, @Nullable WideKeyCounter plannedToNetwork) {
        var recorded = new java.util.LinkedHashMap<AEKey, BigInteger>();
        for (var key : outputs.keys()) {
            var planned = reserveOne(job, key, outputs.getExact(key),
                    plannedToNetwork == null ? BigInteger.ZERO : plannedToNetwork.getExact(key));
            if (planned.signum() > 0) {
                recorded.put(key, planned);
            }
        }
        for (var key : containers.keys()) {
            reserveOne(job, key, containers.getExact(key), BigInteger.ZERO);
        }
        return recorded;
    }

    /**
     * 产物是否仍是本作业的口粮：环内部件，或未完成任务及未交割义务的声明输入。
     * 按键类型与本体族识别替代输入；只决定实物回收去向，不改变份额或执行发数。
     */
    private static boolean requiresOutputInCpu(ParallelExecutingCraftingJob job, AEKey what) {
        // 有收货方的终点必须回 CPU 再交付，不能仅凭直投网络回执结单。
        if (job.isFinalOutputKey(what) && job.link != null && !job.link.isStandalone()) {
            return true;
        }
        if (isDyeableRecursiveInternalItem(job, what)) {
            return true;
        }
        for (var task : job.tasks.entrySet()) {
            if (task.getValue().isFinished() && !job.batchObligations.hasPending(task.getKey())) {
                continue;
            }
            for (var input : task.getKey().getInputs()) {
                for (var candidate : input.getPossibleInputs()) {
                    if (candidate != null && candidate.what().getType() == what.getType()
                            && candidate.what().getPrimaryKey().equals(what.getPrimaryKey())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 批量推送的销账：<b>逐槽</b>记，模具槽豁免只看它自己那一格。
     *
     * <p>一次成功推送实际从池里提走了多少料：流水账记一笔，提交期配给按同一笔销账。
     *
     * <p>封顶侧（{@code ParallelPlanReservation.usableFor}）的例外是<b>槽级</b>的
     * （{@code SlotAccount.selfRepaying(slot, key)}），销账侧原先却是<b>样板级</b>的
     * {@code isRepaidKey(details, key)}——同一张样板只要有任何一格自偿还，这个键的提走量就整笔
     * 不进账，份额永远扣不完，下一次还能按满额取 ⇒ "每个键定死几份"被吃穿
     * （实机 {@code site=batch ... minecraft:glass ate=11992458237 share=11992458236}）。
     * 两边现在共用同一条槽级判据。
     *
     * <p><b>两本账的写入只发生在这一个方法里</b>：NeoEco 批量快路径那条也不再自己记账，它把按
     * 键汇总的明细按一发样本的槽形状归回槽号后交回这里（{@link #recordNeoEcoTakenInputs}），
     * 豁免判据因此只剩槽级这一条，不存在第二个销账出口。
     *
     * <p><b>这里记的就是净消耗，不需要再减"退回池子的量"</b>：holder 里的每一枚在提取那一刻
     * 已经离开池子（{@code ParallelBatchExtraction} 的 {@code inventory.extract(MODULATE)}），
     * 成功推送后没有任何路径把它送回 CPU——AE2 侧 {@code PatternProviderLogic#pushPattern}
     * 把整份 holder 交给 sink（送不出去的进 provider 自己的 sendList，不回 CPU），
     * 装配型宿主 {@code MolecularAssemblerBlockEntity#fillGrid} 收整份 holder、有剩直接抛异常，
     * NeoEco 快路径 {@code ECOBatchCraftingHelper#extractExact} 全有全无。
     * 回池的唯一入口 {@link #insertInternal} 只收 {@code waitingFor} 挂过账的键
     * （产出与 {@code getRemainingKey} 容器件），而"原样退回的键"在两侧都不进配额账。
     * 因此 {@code ate > share} 只能有一个来源：<b>伸手那一刻没夹住</b>
     * （见 {@code ParallelBatchExtraction.executionsInsideFamilyQuota}）。
     *
     * <p>两笔账分工不变：{@code accountTaken} 是<b>诊断流水</b>（跨批复加可越界，已换宽域账），
     * 真正参与"每个键定数、不超不少"判据的是 {@code addPlanMaterialTaken} 那本配额销账，
     * 它走自己既有的饱和合并口径——本刀一字未动。
     */
    static void recordTakenInputs(
            ParallelExecutingCraftingJob job,
            @Nullable IPatternDetails details,
            @Nullable KeyCounter[] container
    ) {
        recordTakenInputs(job, details, container, null, null, null);
    }

    /**
     * 一批提取的销账：{@link ParallelBatchExtraction.Batch#exactPaid()} 在场就按<b>真值</b>记，
     * 否则 holder 的读数即真值。出口仍是同一个 {@code recordTakenInputs}，不建第二本账。
     *
     * <p>那一格真需 {@code >long} 时 holder 只能钉在天花板（AE2 的 {@code KeyCounter} 单键是
     * {@code long}），拿那份读数销账就是把 1.8e19 枚记成 9.2e18 枚——池子已经按真值空了，
     * 账上却还欠着一截，下一批再伸手就把别人的口粮吃干。
     */
    static void recordTakenInputs(
            ParallelExecutingCraftingJob job,
            @Nullable IPatternDetails details,
            @Nullable ParallelBatchExtraction.Batch batch
    ) {
        if (batch == null) {
            return;
        }
        recordTakenInputs(job, details, batch.holder(), batch.exactPaid(), batch.mintedUnits(), null);
    }

    /**
     * 批量销账的本体：{@code exactPaid} 里记了真值的那一格按真值出账，其余格按 holder 读数出账。
     *
     * <p>逐槽记，模具槽豁免只看它自己那一格；两笔账的分工与改前一致（见上方注释）。
     *
     * @param quotaPaid 只喂配额账的那一份夹后值（NeoEco 快路径在 {@link #attach} 里按
     *                  {@code quotaExact} 封顶算出来）；{@code null} 即与出账真值同一个数——
     *                  另两条腿本来就是按格取料、份额在取料点就夹住了。流水账 {@code accountTaken}
     *                  恒收未夹原值：封顶不许把超销那一截从诊断账上一起抹掉。
     */
    static void recordTakenInputs(
            ParallelExecutingCraftingJob job,
            @Nullable IPatternDetails details,
            @Nullable KeyCounter[] container,
            @Nullable WideKeyCounter[] exactPaid,
            @Nullable WideKeyCounter[] mintedUnits,
            @Nullable WideKeyCounter[] quotaPaid
    ) {
        if (job == null || container == null) {
            return;
        }
        var slots = details == null ? null : details.getInputs();
        for (int index = 0; index < container.length; index++) {
            var held = container[index];
            if (held == null) {
                continue;
            }
            IPatternDetails.IInput slot = slots != null && index < slots.length
                    ? slots[index] : null;
            var exactSlot = exactPaid != null && index < exactPaid.length
                    ? exactPaid[index] : null;
            for (var entry : held) {
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                if (key == null || amount <= 0L) {
                    continue;
                }
                BigInteger exact = exactSlot == null
                        ? BigInteger.valueOf(amount) : exactSlot.getExact(key);
                if (exact == null || exact.signum() <= 0L) {
                    continue;
                }
                // 就地认量（无限源）的那几枚从未离开池子，也不是从配给份额里花的：记进 ate 会把
                // 凭空的一份算成"这张样板吃掉的份额"，真键的配额被它吃穿 ⇒ usableFor=0 ⇒ 整条 lane
                // 锁死（实机 ncLocked=857、池里 1.8e19 富余一格不放）。与 reinjectTaken 同一条口径。
                var minted = mintedUnits == null || index >= mintedUnits.length
                        ? null : mintedUnits[index];
                if (minted != null) {
                    exact = exact.subtract(minted.getExact(key));
                }
                if (exact.signum() <= 0L) {
                    continue;
                }
                job.accountTaken.add(key, exact);
                if (slot != null && SlotAccount.selfRepaying(slot, key)) {
                    continue;
                }
                BigInteger quota = exact;
                if (quotaPaid != null && index < quotaPaid.length) {
                    var quotaSlot = quotaPaid[index];
                    if (quotaSlot != null && quotaSlot.containsKey(key)) {
                        quota = quotaSlot.getExact(key).min(exact);
                    }
                }
                // 零与负的那一截不在这里挡：下游 addPlanMaterialTaken 的同一口径（signum<=0 即不记）
                // 是这一件事唯一的一份判据。
                job.addPlanMaterialTaken(details, key, quota);
            }
        }
    }

    /**
     * NeoEco 快路径要量出账的键：一发样本槽里出现过的 ∪ 样板声明的候选 ∪ 对方点名的键。
     *
     * <p>取并集只为<b>不漏量</b>（对方漏报的键照样要在池子的现量差里被抓出来）；这里只借
     * {@code reportedInputTotal} 的<b>键名</b>，它那份 {@code long} 数值不进任何账。
     */
    static java.util.Set<AEKey> neoEcoOutflowKeys(@Nullable IPatternDetails details,
            @Nullable KeyCounter[] unitPrototype, @Nullable List<GenericStack> reportedInputTotal
    ) {
        var keys = new java.util.LinkedHashSet<AEKey>();
        if (reportedInputTotal != null) {
            for (GenericStack stack : reportedInputTotal) {
                if (stack != null && stack.what() != null) {
                    keys.add(stack.what());
                }
            }
        }
        if (unitPrototype != null) {
            for (KeyCounter sample : unitPrototype) {
                if (sample == null) {
                    continue;
                }
                for (var entry : sample) {
                    if (entry.getKey() != null) {
                        keys.add(entry.getKey());
                    }
                }
            }
        }
        var slots = details == null ? null : details.getInputs();
        if (slots != null) {
            for (IPatternDetails.IInput slot : slots) {
                GenericStack[] possible = slot == null ? null : slot.getPossibleInputs();
                if (possible == null) {
                    continue;
                }
                for (GenericStack candidate : possible) {
                    if (candidate != null && candidate.what() != null) {
                        keys.add(candidate.what());
                    }
                }
            }
        }
        return keys;
    }

    /** 出账前的池子现量快照：只读要点名的那几枚键的<b>宽域</b>真值（走池账唯一口径
     *  {@link ParallelBatchExtraction#exactStored}——声明键先换成池子真装着的那一枚），不扫全池。 */
    static Map<AEKey, BigInteger> readPoolWide(
            ListCraftingInventory inventory, java.util.Collection<AEKey> keys
    ) {
        var readout = new java.util.LinkedHashMap<AEKey, BigInteger>();
        if (keys == null) {
            return readout;
        }
        for (AEKey key : keys) {
            if (key != null) {
                readout.put(key, ParallelBatchExtraction.exactStored(inventory, key));
            }
        }
        return readout;
    }

    /**
     * 出账真值 = 现量差，只夹 0 底（池子净收的那一截不算出账：回池本来就轮不到销账）。
     *
     * <p>宽域进、宽域出：一发真需越出 {@code long} 时这份差值照样是精确值，不做任何饱和
     * （饱和只许发生在 {@code Ae2Amounts#toLong} 那一道出口）。
     *
     * <p>差值两头读的是同一本池账：push 后这一头走池账唯一口径
     * （{@link ParallelBatchExtraction#exactStored}），push 前那一头由调用方交来的
     * {@link #readPoolWide} 快照给出同一口径。要点名的键出自宿主自报清单与样板候选表
     * （{@link #neoEcoOutflowKeys}），压根不是池子里那一份实例。第三方自定义键把主键存成值对象时，
     * 按声明键裸读的两个桶恒 0 ⇒ 差值恒 0 ⇒ 销账 {@code ate=0}（配额账不扣、旁人可反复申报）；
     * 只一头裸读则把池里剩下的那一截也算成出账。只换读法，差值算式与调用契约一字未动。
     */
    static WideKeyCounter measurePoolOutflow(ListCraftingInventory inventory,
            java.util.Collection<AEKey> keys, Map<AEKey, BigInteger> before
    ) {
        var outflow = new WideKeyCounter();
        if (keys == null) {
            return outflow;
        }
        for (AEKey key : keys) {
            if (key == null) {
                continue;
            }
            var was = before == null ? null : before.get(key);
            if (was == null) {
                continue;
            }
            var delta = was.subtract(ParallelBatchExtraction.exactStored(inventory, key));
            if (delta.signum() > 0L) {
                outflow.add(key, delta);
            }
        }
        return outflow;
    }

    /** 这一键的出账真值：只从池子现量差这本宽域账读，没有就是 0，绝不退回对方那份读数。 */
    private static BigInteger outflowOf(@Nullable WideKeyCounter outflow, AEKey key) {
        return outflow == null ? BigInteger.ZERO : outflow.getExact(key);
    }

    /**
     * NeoEco 快路径的提走量：调度器 push 就是从我们这个池子里提料，<b>销账量按池子的宽域现量差
     * 派生</b>（{@link #measurePoolOutflow}），不按对方报的数。
     *
     * <p><b>{@code inputTotal()} 那份 {@code long} 读数今后只许用于点名键与对平/诊断，不许进账</b>：
     * 它是按键汇总的 {@code KeyCounter} 读数，单键越界时被它自己的 {@code long} 天花板钉死成
     * <b>哨兵</b>而不是量。用 {@code BigInteger.valueOf(stack.amount())} 撑宽再当真值入账，方向就是
     * 造大、量级正好一个天花板（实机 2026-09-26 那一跑 {@code ate=9223372036854775807
     * share=1056640106232500} ⇒ {@code ParallelPlanReservation#quotaExact}（配给 − 已吃再夹零）恒 0
     * ⇒ {@code usableFor} 交 0（{@code locked502} 就是这一档）⇒ 该族批次永远开不了工）。
     *
     * <p><b>这一条不是第二个销账出口</b>：它只把出账真值摊回一发样本 holder 的<b>槽号</b>
     * （每格 {@code 样本量 × 份数}，与池子现量差对得上才摊），再交回唯一出口
     * {@link #recordTakenInputs}——两本账的写入只在那一个方法里发生。
     *
     * <p>改前这一条自己写两本账、并且用<b>样板级</b> {@code isRepaidKey} 判豁免，与
     * {@link #recordTakenInputs} 的<b>槽级</b>判据分叉：同一张样板只要有任何一格把该件原样退回，
     * 这个键在快路径上的提走量就整笔不进配额账 ⇒ 份额永远扣不完 ⇒ "每个键定死几份"被吃穿。
     *
     * <p>摊不平（出账真值与 {@code 样本 × 份数} 对不上，或该键压根不在任何样本槽里）时
     * 先按样本把<b>自偿还格</b>那一截归回它自己那一格（模具槽只进流水账、不进配额账），
     * 剩下的残量才归<b>真消耗</b>它的那一格（非自偿还），退到任一收得出它的样本槽，最后挂最后一号
     * （{@code slot == null}）。<b>每一号都过同一道封顶</b>（见 {@link #capByRemainingShare}，
     * 落在 {@link #attach} 里）：取料侧的 {@code clampByRemainingQuota} 只夹样本槽看得见、且
     * {@code quotaExact} 交得出份额的那几维，而池子净差值可以大于 {@code 样本 × 份数}（对方按它自己
     * 那份配方交料）——残量一旦落到<b>有槽</b>那一号就绕过了封顶，正是实机
     * {@code over=[minecraft:water ate=9223372036854775807 share=1056640106232500]} 的进账口
     * （配额账顶到天花板 ⇒ {@code quotaExact} 恒 0 ⇒ {@code usableFor=0} ⇒ {@code locked502}）。
     * 这里拿<b>同一份</b> {@code quotaExact} 算式夹住每一格，不是新写第二道判据，也不是新增闸门：
     * 让已经存在的封顶在这条腿上同样成立。夹完仍按<b>照实少销</b>走——夹到份额上限
     * 就交份额，夹出 0 是因为这一族确实已无份额可花，随后 {@code clampByRemainingQuota} 拒推下一条腿
     * 用的是同一个数；静默归零、或反过来放开不夹，都是把这笔账记到别人头上。
     * <b>只有配额账吃夹后的值</b>：流水账 {@code accountTaken} 收未夹原值（{@link #recordTakenInputs}
     * 那两本账的分工），否则超销那一截在账上根本不存在，下一次照样看不见。
     * 反方向也钉死：整笔搬到普通格上就是多销，尾段少一枚永远开不了工。
     */
    static void recordNeoEcoTakenInputs(
            ParallelExecutingCraftingJob job,
            @Nullable IPatternDetails details,
            @Nullable KeyCounter[] unitPrototype,
            long craftCount,
            @Nullable List<GenericStack> reportedInputTotal,
            @Nullable WideKeyCounter poolOutflow,
            @Nullable ParallelPlanReservation reservation
    ) {
        if (job == null) {
            return;
        }
        var totals = new java.util.LinkedHashMap<AEKey, BigInteger>();
        if (reportedInputTotal != null) {
            for (GenericStack stack : reportedInputTotal) {
                AEKey key = stack == null ? null : stack.what();
                if (key != null) {
                    totals.merge(key, outflowOf(poolOutflow, key), BigInteger::add);
                }
            }
        }
        if (poolOutflow != null) {
            // 池子动了、对方却没点名的键也照样进账：点名表漏了不等于没出账
            for (AEKey key : poolOutflow.keys()) {
                if (key != null && !totals.containsKey(key)) {
                    totals.merge(key, outflowOf(poolOutflow, key), BigInteger::add);
                }
            }
        }
        var slots = details == null ? null : details.getInputs();
        int shape = Math.max(unitPrototype == null ? 0 : unitPrototype.length,
                slots == null ? 0 : slots.length);
        // 最后一号是"归不到任何一格"的那一截：recordTakenInputs 读不到槽 ⇒ 不豁免，只销账。
        var taken = new KeyCounter[shape + 1];
        var exactPaid = new WideKeyCounter[shape + 1];
        // 第三本宽域账只喂配额账：每格交出去的量都先过同一份 quotaExact 的<b>批内余量</b>（见 #attach），
        // 而 holder/exactPaid 那份原值照旧进流水账——夹到 0 就不许把超销那一截一并抹掉。
        var quotaPaid = new WideKeyCounter[shape + 1];
        // 族级<b>批内</b>余量：同一批里同族的每一号 attach 都从同一份 quotaExact 读数里扣，扣完即 0。
        // 就地新建、随这一笔销账返回即丢弃：不落 NBT、不跨 tick、不进 ParallelExecutingCraftingJob，
        // 份额真值仍只由 ParallelPlanReservation#quotaExact 一处交、销账出口仍只有一个
        // recordTakenInputs——它不是第四本账，只是把"同一批不许把整份份额花两遍"记在栈上。
        var familyQuotaRemaining = new java.util.HashMap<Object, BigInteger>();
        var count = BigInteger.valueOf(Math.max(0L, craftCount));
        for (var pending : totals.entrySet()) {
            AEKey key = pending.getKey();
            BigInteger exact = pending.getValue();
            if (key == null || exact == null || exact.signum() <= 0L) {
                continue;
            }
            BigInteger sampleSum = BigInteger.ZERO;
            for (int index = 0; unitPrototype != null && index < shape; index++) {
                var sample = unitPrototype[index];
                if (sample != null && sample.get(key) > 0L) {
                    sampleSum = sampleSum.add(BigInteger.valueOf(sample.get(key)));
                }
            }
            if (sampleSum.signum() > 0L && sampleSum.multiply(count).equals(exact)) {
                // 摊得平：逐槽按样本 × 份数归位，模具槽那一格由槽级判据自己豁免
                for (int index = 0; unitPrototype != null && index < shape; index++) {
                    var sample = unitPrototype[index];
                    long sampleUnits = sample == null ? 0L : sample.get(key);
                    if (sampleUnits > 0L) {
                        attach(taken, exactPaid, quotaPaid, familyQuotaRemaining, reservation, details, index, key,
                                BigInteger.valueOf(sampleUnits).multiply(count));
                    }
                }
                continue;
            }
            // 摊不平（工具耐久、组件变化这类）：先把"算得清的自偿还格"那一截归回它自己那一格，
            // 剩下的残量才交给真消耗格。模具槽不进配额账——把它的样本量整体搬到普通格上，
            // 就是实机 over=[ate=N share=N−1] 那一型（多销 ⇒ 尾段少一枚永远开不了工）。
            BigInteger residual = exact;
            for (int index = 0; unitPrototype != null && index < shape; index++) {
                var sample = unitPrototype[index];
                long sampleUnits = sample == null ? 0L : sample.get(key);
                if (sampleUnits <= 0L) {
                    continue;
                }
                IPatternDetails.IInput input = slots != null && index < slots.length
                        ? slots[index] : null;
                if (input == null || !SlotAccount.selfRepaying(input, key)) {
                    continue;
                }
                var share = BigInteger.valueOf(sampleUnits).multiply(count);
                var billed = share.min(residual);
                if (billed.signum() <= 0L) {
                    continue;
                }
                attach(taken, exactPaid, quotaPaid, familyQuotaRemaining, reservation, details, index, key, billed);
                residual = residual.subtract(billed);
            }
            if (residual.signum() > 0L) {
                // 残量落到哪一格都由 #attach 那一道同份封顶夹（有槽支不再是漏口）
                attach(taken, exactPaid, quotaPaid, familyQuotaRemaining, reservation, details,
                        takingSlot(unitPrototype, slots, shape, key), key, residual);
            }
        }
        // 第三本账（就地认量）在这一条腿上<b>结构性为空</b>，不是"假设宿主诚实"：
        // inputTotal 是 push 时真从 CPU 池子里提走的那些枚，而池子里根本不可能躺着凭空的量——
        // 就地认量只在取料点的 holder 上记一笔（ParallelUnboundedSourceExtractionTest
        // #mintedUnitsNeverLandInThePoolOnSuccessNorOnRollback 钉住），网络回补那条腿认下即收手、
        // 一枚都不注池：注池那一支（"配给表为 null 就把缺口注入缓冲"）已经删了，配给表在
        // {@code ParallelBatchExtraction#refillObligationBatch}、{@code #payDeclaredObligation} 与
        // 本类 {@link #payObligationBatch} 三处形参上收口成<b>编译期非空</b>——"认量绝不进池"
        // 因此不再是要靠现场去证的假设。这里交 null 与交一份空账本逐字同效；
        // 真有哪一天又注进池子，先红的是那三条签名与钉住它们的用例。
        recordTakenInputs(job, details, taken, exactPaid, null, quotaPaid);
    }

    /**
     * 把这一键的提走真值挂到指定槽号上：{@code holder} 只需给出"这一格确实动了料"的形状。
     *
     * <p>宽域那一本<b>必须累加，不能覆盖</b>：同一个 {@code (index,key)} 可以被归两次——"摊不平"
     * 分支先按样本把<b>自偿还格</b>那一截归回它自己那一格，再让 {@link #takingSlot} 在没有非自偿还
     * 格可退时<b>退到同一格</b>，两次 {@code attach} 落在同一个槽号同一个键上。老写法是
     * {@code WideKeyCounter#setExact}（覆盖）：第二次把第一次抹掉，而 {@code holder} 那一本
     * {@code add} 了两笔 ⇒ 宽域账比物理出账少一截 ⇒ 判据读到的已吃偏小、流水账也少记。
     * 累加之后两本账同向：{@code holder} 给形状，宽域给真值。
     */
    private static void attach(KeyCounter[] taken, WideKeyCounter[] exactPaid,
            WideKeyCounter[] quotaPaid, java.util.Map<Object, BigInteger> familyQuotaRemaining,
            @Nullable ParallelPlanReservation reservation,
            @Nullable IPatternDetails details, int index, AEKey key, BigInteger exact
    ) {
        var held = taken[index];
        if (held == null) {
            held = taken[index] = new KeyCounter();
        }
        long readout = exact.min(WideCeilings.LONG_ACCOUNT).longValue();
        if (readout <= 0L) {
            return;
        }
        held.add(key, readout);
        var wide = exactPaid[index];
        if (wide == null) {
            wide = exactPaid[index] = new WideKeyCounter();
        }
        wide.add(key, exact);
        // 每一格过同一份 quotaExact 的批内余量：有槽那一支同样不许把份额之外的量记到配额账上，
        // 同族不同变体键也不许各按整份份额再夹一遍（配额账要到批末 recordTakenInputs 才前进）。
        // 槽级判据走真 slot：与 clampByRemainingQuota 同一轴，原样退回的那一格没有配额口径。
        var slots = details == null ? null : details.getInputs();
        IPatternDetails.IInput slot = slots != null && index < slots.length ? slots[index] : null;
        var capped = capByRemainingShare(exact, reservation, details, key, slot,
                familyQuotaRemaining);
        var quota = quotaPaid[index];
        if (quota == null) {
            quota = quotaPaid[index] = new WideKeyCounter();
        }
        // 用 setExact 而不是 add：夹到 0 也要把键位留下——那是"这一格确实过了封顶、封顶给 0"
        // 与"这一格压根没进封顶"的唯一区别。
        quota.setExact(key, quota.getExact(key).add(capped));
    }

    /**
     * 与正常格同一道封顶：份额只在 {@link ParallelPlanReservation#quotaExact} 那一个算式里读，
     * 没有配额口径（{@code null}：这张样板不在表里、这一格原样退回、或这一族没有数值上界）
     * 就不夹——与 {@link #clampByRemainingQuota} 完全同向，这里不写第二道判据。
     *
     * <p>夹在每一次 {@link #attach} 上（不是只夹"归不到任何一格"那一号）：有槽那一支交的是池子净差值，
     * 它可以大于 {@code 样本 × 份数}（对方按自己那份配方交料），不夹就是把整族份额一口吃空
     * （配额账只能等于份额，多销的那一截永远是别人那份口粮）。
     *
     * <p><b>批内族级余量</b>（{@code familyQuotaRemaining}）：同一批里同族可能落在<b>不同精确键</b>
     * （变体）上，而 {@code quotaExact} 读的是这本配给账<b>批前</b>的快照——配额账要到批末
     * {@link #recordTakenInputs} 才前进。逐号各按整份夹，一张样板的两枚同族变体就能各吃一份
     * （最多 {@code 2 × share}）。这里把首号读到的份额真值记在<b>调用栈上</b>的那张局部表里，
     * 夹一笔扣一笔、扣完即 0：随这一批新建、随这一批丢弃，不落 NBT、不跨 tick、不进
     * {@code ParallelExecutingCraftingJob}，也不新建第二道封顶算式。
     *
     * <p>{@code quotaExact} 交 {@code null} 的那几号<b>不占这张表</b>：没有配额口径就没有"花掉"，
     * 把它记进余量表就是把同族其它格的份额替它扣了（少销那一型）。
     *
     * @param slot 这一号归属的那一格；只有"归不到任何一格"的残量支才交 {@code null}（那一支本就没有槽）。
     */
    static BigInteger capByRemainingShare(@Nullable BigInteger exact,
            @Nullable ParallelPlanReservation reservation,
            @Nullable IPatternDetails details, @Nullable AEKey key,
            @Nullable IPatternDetails.IInput slot,
            @Nullable java.util.Map<Object, BigInteger> familyQuotaRemaining
    ) {
        if (exact == null || exact.signum() <= 0L
                || reservation == null || details == null || key == null) {
            return exact == null ? BigInteger.ZERO : exact;
        }
        var share = reservation.quotaExact(key, details, slot);
        if (share == null) {
            return exact;
        }
        var whole = share.exactValue().max(BigInteger.ZERO);
        if (familyQuotaRemaining == null) {
            return exact.min(whole);
        }
        var family = key.getPrimaryKey();
        var remaining = familyQuotaRemaining.computeIfAbsent(family, ignored -> whole);
        var capped = exact.min(remaining);
        familyQuotaRemaining.put(family, remaining.subtract(capped));
        return capped;
    }

    /**
     * 这一键的提走量归哪一格：优先<b>真消耗</b>它的那一格（非自偿还），退到任一收得出它的样本槽，
     * 最后才是"没有槽可归"的那一号。
     */
    private static int takingSlot(@Nullable KeyCounter[] unitPrototype,
            @Nullable IPatternDetails.IInput[] slots, int shape, AEKey key
    ) {
        int fallback = shape;
        if (unitPrototype != null) {
            for (int index = 0; index < unitPrototype.length && index < shape; index++) {
                var sample = unitPrototype[index];
                if (sample == null || sample.get(key) <= 0L) {
                    continue;
                }
                IPatternDetails.IInput input = slots != null && index < slots.length
                        ? slots[index] : null;
                if (input == null || !SlotAccount.selfRepaying(input, key)) {
                    return index;
                }
                if (fallback == shape) {
                    fallback = index;
                }
            }
        }
        return fallback;
    }

    /**
     * 按剩余配额夹住这一批能跑的发数（<b>这一趟没有池子读数＝只夹配额</b>）。
     *
     * <p>环种留底那一刀要读池子现量，这一个重载读不到它：它在<b>主代码里零调用点</b>，只有离线用例
     * 走它（{@code ParallelCraftingCpuLogicTest} 那几条不配池子的请求侧判据、
     * {@code ParallelRingSeedReserveAtExtractionTest} 里"交不出池子读数那一档照旧只按配额折"那一条对照）。
     * 真接机器的两处都在带池子读数的重载上——{@code pushPreparedBatch} 里 push 前的复核交本 lane 的
     * {@code inventory}、{@code dispatchFiringsInsideQuota} 交点名的 {@code pool}，见
     * {@link #clampByRemainingQuota(ParallelPlanReservation, IPatternDetails, KeyCounter[], long,
     * ListCraftingInventory)}。
     */
    static long clampByRemainingQuota(@Nullable ParallelPlanReservation reservation,
            IPatternDetails details, @Nullable KeyCounter[] perCraftInputs, long requested
    ) {
        return clampByRemainingQuota(reservation, details, perCraftInputs, requested, null);
    }

    /**
     * 按剩余配额夹住这一批能跑的发数：一发样本在每个键上吃 {@code perFiring} 份，
     * 发数就不能超过 {@code 剩余份额 / 该族每发合计份数}。
     *
     * <p><b>两道刀各走各的，不叠在同一格上</b>：配额那一刀只夹"表里有这一键份额"的键
     * （{@code quotaExact} 交 {@code null} 表示没有配额口径或这一格原样退回，那一刀不夹），
     * 否则没进表的样板会被整批饿死；而<b>留底那一刀不问配额口径</b>——取料点
     * {@link ParallelPlanReservation#usableFor} 是没有配额口径照样夹（{@code quota >= 0} 那一条早退与
     * 占料那一条分支共用同一份 {@code spendable}），这一条腿跟着配额口径走就是两把尺对同一件事
     * 给两个答案：不在配给表里／原样退回的模具槽那一型，折发数时压根不读留底，种照样被按发数申报走。
     * 形状与网络回补那条腿（{@code ParallelBatchExtraction#refillObligationBatch}）一致：留底独立施夹。
     * {@code quotaExact} 交 {@code null}＝没有口径（那一刀不夹）、交 {@code 0}＝份额用完（那一刀夹死），
     * 这两型在配额那一臂里照旧不许混。
     *
     * <p>配额那一刀的分母取<b>物品本体族</b>的每发合计份数、判据取<b>槽级</b>，与执行侧
     * {@code ParallelBatchExtraction} 和 {@code recordTakenInputs} 同一条口径：
     * 份额是整族一份，同族两个槽各按整份配额放行就等于把定数吃穿；
     * 反过来，模具槽那份不在配给里，按精确键去查表会拿到 0 份额而把整批饿死。
     * 判据与执行侧 {@code ParallelBatchExtraction#primaryCandidate} 取的是<b>同一个读口</b>
     * （{@code quotaExact}），不再一处走 {@code quotaFor<0}、一处走 {@code quotaExact==null}。
     *
     * <p><b>这道除法整条在宽域上做</b>：分子是 {@code quotaExact} 的族份额真值，分母是各槽每发
     * 件数的精确合计（{@link PlanAmount}），商做完才 {@link Ae2Amounts#toLong} 窄化一次。
     * 换之前两侧都是 {@code long} 的"宁高估"读数——族份额与族合计同顶天花板时
     * {@code Long.MAX_VALUE / Long.MAX_VALUE = 1}，明明还付得起 2 发的一批被这道商夹成 1 发；
     * 那是把哨兵当了量，不是保守估计。样本侧（{@code perCraftInputs} 的一发件数）恒为精确
     * {@code long}：一发真需越界时那条窄域提取口压根交不出样本，走不到这里。
     *
     * <p><b>这道除法过去只看得见配额，看不见环种留底</b>：配额说的是"每人能花多少"，
     * 留底说的是"这一截还不许任何人花"。取料点那把尺
     * （{@link ParallelPlanReservation#usableFor}）折的是<b>池子现量</b>，而这一条腿折的是
     * <b>宿主自己按机器容量定的发数</b>，它压根不经过取料点——于是规划侧 {@code kept} 留在池里那颗种
     * 在环转起来之前就被别的样板<b>按发数申报</b>走（实机 {@code 1e11} 那一跑 lane {@code 96d7b67a}，
     * {@code ae2:creative_storage_cell x 100000000000}：留种判决行与取料点留底读数都是对的，
     * 增殖腿最后 {@code 每发=1,池内=0,网络sim=0}）。
     *
     * <p>可花量只读环种留底的<b>唯一读口</b>
     * {@link ParallelPlanReservation#spendableAfterRingSeedReserve}——与 {@code usableFor}
     * <b>同一个 {@code owed}</b>（{@code ret >= need} 那一档留的正是整份剩余需求），这里不另算一截。
     * 该读口交回 {@code null} 的三档（这一族不在账上／这一张认不出来／这一张正是<b>环分量的成员</b>）
     * 一概不夹：把豁免写成"照样按池子现量折"，就是给增殖腿新加一道它过去没有的夹子，
     * 链式环里 A 等 B 的 K、B 等 A 的 L 会互等死锁。
     *
     * <p><b>留底这一刀按精确键各除各的每发量、商再取最小</b>：读口交回的是"这一枚键扣掉<b>整族</b>
     * {@code owed} 之后"的读数，族下 N 枚各读一次再复加，就是把同一截留底扣 N 次；而"族可花量合计 ÷
     * 族每发合计"那道除法还顺手让同族<b>富余那一枚</b>去补<b>被扣空那一枚</b>的亏空——{@code owed}
     * 记在 K 上（K 现量 50 全在环的口粮里）、同族另一枚 V 现量 100 时，两格各吃一枚的那张旁人照样
     * 折出 {@code (0+50)/2 = 25} 发，可它伸手那一格 K 一枚也拿不到。族是<b>查表</b>口径
     * （{@code shareOf} 与 {@link ParallelPlanReservation#spendableAfterRingSeedReserve} 都按族找条目），
     * 记账与施夹按<b>精确键</b>——与 {@code f8679c6} 定下的那一条一致，也与取料点同尺
     * （{@code usableFor} 本来就是一格一键一次读数）。
     *
     * <p><b>读池先过池账唯一口径</b>（{@link ParallelBatchExtraction#exactStored}）：这一条腿伸手的键
     * 出自样本槽与宿主自报清单（{@link #dispatchFiringsInsideQuota} 的 {@code blind} 那一支），
     * <b>不是池子里那一份实例</b>；第三方自定义键把主键存成值对象时，按引用分格的主表读出来恒 0
     * ⇒ 留底那一刀把自己的池子现量读成空，NeoEco 这一条腿恒夹。这里复用现成的那一句解析，
     * 不写第二份。
     *
     * <p>无限源那一档与取料点同判（{@link ParallelPlanReservation#isUnboundedSupply} 早退）：
     * 它没有数值上界，拿薄池现量折它就是假数。
     *
     * @param pool 本 lane 的池子（快路径那一批的料就是从这一份里交出去的）；
     *             {@code null}＝这一趟没有池子读数，只按配额夹
     */
    static long clampByRemainingQuota(@Nullable ParallelPlanReservation reservation,
            IPatternDetails details, @Nullable KeyCounter[] perCraftInputs, long requested,
            @Nullable ListCraftingInventory pool
    ) {
        if (reservation == null || details == null || perCraftInputs == null) {
            return requested;
        }
        var slots = details.getInputs();
        long capped = requested;
        // 臂①：配额那一刀（只在有配额口径的键上，算式与换刀前一字不动）
        var familyDemandPerFiring = new java.util.HashMap<Object, PlanAmount>();
        for (int index = 0; index < perCraftInputs.length; index++) {
            var slot = perCraftInputs[index];
            if (slot == null) {
                continue;
            }
            IPatternDetails.IInput input = slots != null && index < slots.length
                    ? slots[index] : null;
            for (var entry : slot) {
                long perFiring = entry.getLongValue();
                if (entry.getKey() == null || perFiring <= 0L) {
                    continue;
                }
                PlanAmount mine = reservation.quotaExact(entry.getKey(), details, input);
                if (mine == null) {
                    continue;
                }
                var family = entry.getKey().getPrimaryKey();
                PlanAmount demand = familyDemandPerFiring
                        .getOrDefault(family, PlanAmount.ZERO).add(PlanAmount.of(perFiring));
                familyDemandPerFiring.put(family, demand);
                capped = Math.min(capped, Ae2Amounts.toLong(mine.divide(demand)));
            }
        }
        if (pool == null) {
            // 这一趟交不出池子读数：留底那一刀没有现量可削，只按配额夹（与老调用点同口径）
            return capped;
        }
        // 臂②：环种留底那一刀，独立施夹（不问配额口径，与网络回补那条腿同一形状）。
        // 每发合计按【精确键】记账（同一枚键跨槽复加），各除各的、商取最小：读口交回的已经是
        // "这一枚键扣掉整族 owed"之后的读数，按族复加就是把同一截留底扣 N 次，
        // 而族合计那道除法还会让同族富余那一枚去补被扣空那一枚的亏空（种被吃掉那一型）。
        var perKeyDemandPerFiring = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        for (var slot : perCraftInputs) {
            if (slot == null) {
                continue;
            }
            for (var entry : slot) {
                long perFiring = entry.getLongValue();
                if (entry.getKey() == null || perFiring <= 0L) {
                    continue;
                }
                perKeyDemandPerFiring.merge(entry.getKey(), PlanAmount.of(perFiring), PlanAmount::add);
            }
        }
        for (var demand : perKeyDemandPerFiring.entrySet()) {
            var key = demand.getKey();
            if (reservation.isUnboundedSupply(key)) {
                // 与 usableFor 开头那一句同判：无限源没有数值上界，不许拿薄池现量折它
                continue;
            }
            // 读池走池账唯一口径（声明键先换成池子真装着的那一枚，不写第二份解析）
            var spendable = reservation.spendableAfterRingSeedReserve(key,
                    ParallelBatchExtraction.exactStored(pool, key), details);
            if (spendable == null) {
                // 这一枚键对这一张没有要扣的留底（这一族不在账上／这一张是环分量的成员）：不夹
                continue;
            }
            capped = Math.min(capped, Ae2Amounts.toLong(
                    PlanAmount.of(spendable).divide(demand.getValue())));
        }
        return capped;
    }

    /**
     * 把这一批申报的产出与回流容器整笔挂进 {@code waitingFor} 等实物：走
     * {@link #reserveOne(ParallelExecutingCraftingJob, GenericStack)} 那条"零入网"的口径，
     * 不做预期入网分流（要分流的推送路径用带 {@code plannedToNetwork} 的那条出口）。
     */
    private static void reserveExpectedWaiting(
            ParallelExecutingCraftingJob job,
            List<GenericStack> expectedOutputs,
            List<GenericStack> expectedContainerItems
    ) {
        if (job == null) {
            return;
        }
        for (GenericStack stack : expectedOutputs) {
            reserveOne(job, stack);
        }
        for (GenericStack stack : expectedContainerItems) {
            reserveOne(job, stack);
        }
    }

    /** 保存计划 used + missing 尚未到账的外部原料，独立于已经派发的产出等待账。 */
    static void initializePlannedInitialItems(ParallelExecutingCraftingJob job, ICraftingPlan plan,
            ListCraftingInventory pool) {
        job.initialItemsRemaining.clear();
        var required = CraftingContinuationPartialSubmit.requiredInitialAmounts(plan);
        for (AEKey key : required.keys()) {
            if (key == null || job.isUnboundedSupply(key)) continue;
            BigInteger remaining = required.getExact(key).subtract(WideCraftingInventory.amount(pool, key));
            if (remaining.signum() > 0) job.initialItemsRemaining.insertExact(key, remaining, Actionable.MODULATE);
        }
    }

    /** 精确来源的身份与当前提交者的提取权限必须同时成立，饱和库存不算无限供给。 */
    private static Set<AEKey> availableUnboundedSupply(appeng.api.storage.MEStorage storage, IActionSource source) {
        return availableUnboundedSupply(storage, source, null);
    }

    private static Set<AEKey> availableUnboundedSupply(appeng.api.storage.MEStorage storage,
            IActionSource source, @Nullable IGrid grid) {
        Set<AEKey> declared;
        if (storage instanceof git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply exact) {
            declared = exact.readExactSupply().unbounded();
        } else if (grid != null) {
            declared = git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.currentUnboundedSupplies(grid);
        } else {
            return Set.of();
        }
        var result = new java.util.HashSet<AEKey>();
        for (AEKey key : declared) {
            if (storage.extract(key, 1, Actionable.SIMULATE, source) == 1) result.add(key);
        }
        return result;
    }

    /** 旧存档补录真实无限供给，只撤销未提入的计划原料；不删除库存和在途产出。 */
    static void refreshJobUnboundedSupply(ParallelExecutingCraftingJob job,
            appeng.api.storage.MEStorage storage, IActionSource source) {
        refreshJobUnboundedSupply(job, storage, source, null);
    }

    private static void refreshJobUnboundedSupply(ParallelExecutingCraftingJob job,
            appeng.api.storage.MEStorage storage, IActionSource source, @Nullable IGrid grid) {
        var live = availableUnboundedSupply(storage, source, grid);
        if (grid != null || storage instanceof git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply) {
            // 完整挂载快照也能撤销旧启发式留下的误判或已被移除的生成器身份。
            job.unboundedSupplyKeys.removeIf(key -> !live.contains(key));
        }
        for (AEKey key : live) {
            if (!job.isUnboundedSupply(key)) {
                job.noteUnboundedSupply(key);
                AELog.info("[chexsonsaeutils-submit] 重新识别无限供给: %s", key);
            }
        }
        for (AEKey key : java.util.List.copyOf(job.initialItemsRemaining.list.keySet())) {
            if (job.isUnboundedSupply(key)) {
                job.initialItemsRemaining.extractExact(key, job.initialItemsRemaining.amount(key), Actionable.MODULATE);
            }
        }
    }

    /** 只续取提交计划的原材料余量，逐笔销账；不按缺料样板或义务发数新增网络取料。 */
    static BigInteger extractPlannedInitialItems(ParallelExecutingCraftingJob job,
            ListCraftingInventory pool, appeng.api.storage.MEStorage storage, IActionSource source,
            java.util.function.BooleanSupplier hasBudget) {
        BigInteger total = BigInteger.ZERO;
        var keys = java.util.List.copyOf(job.initialItemsRemaining.list.keySet());
        if (keys.isEmpty()) {
            return total;
        }
        var finishedThisTick = new java.util.HashSet<AEKey>();
        // 独立的提取工作量上限给正常推送留时间；每刀轮转键，不让巨额原料饿死后面的键。
        for (int chunk = 0; chunk < PLANNED_INITIAL_CHUNKS_PER_TICK && finishedThisTick.size() < keys.size()
                && hasBudget.getAsBoolean(); chunk++) {
            int index = Math.floorMod(job.initialItemsCursor, keys.size());
            AEKey key = keys.get(index);
            job.initialItemsCursor = (index + 1) % keys.size();
            if (finishedThisTick.contains(key)) {
                continue;
            }
            BigInteger remaining = job.initialItemsRemaining.amount(key);
            if (job.isUnboundedSupply(key) || remaining.signum() <= 0) {
                job.initialItemsRemaining.extractExact(key, remaining, Actionable.MODULATE);
                finishedThisTick.add(key);
                continue;
            }
            BigInteger wanted = pool instanceof WideCraftingInventory
                    ? remaining : remaining.min(WideCeilings.LONG_ACCOUNT);
            BigInteger extracted = git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                    storage, key, wanted, Actionable.MODULATE, source);
            if (extracted.signum() > 0) {
                job.initialItemsRemaining.extractExact(key, extracted, Actionable.MODULATE);
                if (pool instanceof WideCraftingInventory wide) wide.insertExact(key, extracted, Actionable.MODULATE);
                else pool.insert(key, extracted.longValueExact(), Actionable.MODULATE);
                total = total.add(extracted);
            }
            if (extracted.compareTo(wanted) < 0 || remaining.equals(extracted)) {
                finishedThisTick.add(key);
            }
        }
        return total;
    }

    /**
     * 按计划 USED 真值初次取料；明确的无限源不提取实物，有限无限盘仍按实际库存扣料。
     * 精确库存向宽域 CPU 一次交完整需求；普通库存只交一个 long 窗口，剩余按计划续取。
     * 首次至少给满 min(需求, Long.MAX_VALUE) 才接受提交，保留普通库存原有拒单规则。
     * 提交失败按实际池量退料；缺口只在 GenericStack 的显示边界饱和，不以其倒推真值。
     */
    static @Nullable GenericStack tryExtractInitialItems(ICraftingPlan plan,
            appeng.api.storage.MEStorage storage, ListCraftingInventory inventory, IActionSource src,
            @Nullable Set<AEKey> forgivenUnbounded) {
        var unbounded = new java.util.HashSet<>(WideAmountPlanCarrier.resolveUnboundedKeys(plan));
        var liveUnbounded = availableUnboundedSupply(storage, src);
        unbounded.addAll(liveUnbounded);
        if (forgivenUnbounded != null) forgivenUnbounded.addAll(liveUnbounded);
        var wideLedgers = WideAmountPlanCarrier.resolveWideLedgers(plan);
        for (var entry : plan.usedItems()) {
            var what = entry.getKey();
            long toExtract = entry.getLongValue();
            if (what == null || toExtract <= 0L
                    || ParallelExecutingCraftingJob.unboundedSupplyContains(unbounded, what)) {
                continue;
            }
            // 需求真值：宽域账有则用真值（可 >long），否则 AE2 的 long 读数就是真值。
            var overflowed = wideLedgers.get(WideAmountKind.USED, what);
            var remaining = overflowed != null ? overflowed : BigInteger.valueOf(toExtract);
            if (remaining.signum() <= 0L) {
                // 宽域账报出不增料的数：这一键连一刀都不该伸（外层 toExtract>0 只看那份 long 读数）
                continue;
            }
            BigInteger wanted = inventory instanceof WideCraftingInventory
                    ? remaining : remaining.min(WideCeilings.LONG_ACCOUNT);
            BigInteger extracted = git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                    storage, what, wanted, Actionable.MODULATE, src);
            if (extracted.signum() > 0) {
                if (inventory instanceof WideCraftingInventory wide)
                    wide.insertExact(what, extracted, Actionable.MODULATE);
                else inventory.insert(what, extracted.longValueExact(), Actionable.MODULATE);
            }
            if (extracted.compareTo(remaining.min(WideCeilings.LONG_ACCOUNT)) < 0) {
                returnMaterialsToNetwork(storage, inventory, src);
                BigInteger shortfall = remaining.subtract(extracted);
                return new GenericStack(what, shortfall.min(WideCeilings.LONG_ACCOUNT).longValueExact());
            }
        }
        return null;
    }

    /**
     * 提交失败时把已抽进 CPU 池的料原路还网络：按<b>宽域真值</b>分 ≤long 的 chunk 还，
     * 每一刀都<b>先从池里真扣、再交网络、网络没吃下的那一截立刻放回池</b>。
     *
     * <p>三个旧形状各自会丢料或凭空多料：
     * <ul>
     *   <li>读主表 {@code list} 再 {@code clear()}：累计超界那一截在溢出侧表里根本看不见
     *       （执行侧每 tick 回补一刀，跨批累加完全可以是 k×{@code Long.MAX}），还回一个天花板就清池，
     *       等于把玩家的料凭空蒸发；</li>
     *   <li>先 {@code insert} 后按"网络报回的实收"扣账：宿主回一个比递给它的量更大的读数，
     *       池子就少扣一截，同一份料同时存在于池和网络；</li>
     *   <li>按申报量扣账：网络拒收的那部分既从池里消失、也没进网络。</li>
     * </ul>
     * 先扣后交 + 拒收回滚让三个形状都不可表达：离开池子的每一枚要么进了网络，要么回了池。
     *
     * <p>整口被拒时剩下的留在池里，由 {@link #storeItems()} 每 tick 按精确值分轮续交，
     * 不在这里就地丢掉。
     */
    static void returnMaterialsToNetwork(appeng.api.storage.MEStorage storage,
            ListCraftingInventory inventory, IActionSource src) {
        if (inventory instanceof WideCraftingInventory wide
                && storage instanceof git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion) {
            for (var key : java.util.List.copyOf(wide.list.keySet())) {
                BigInteger accepted = git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion.insert(
                        storage, key, wide.amount(key), Actionable.MODULATE, src);
                if (accepted.signum() > 0) wide.extractExact(key, accepted, Actionable.MODULATE);
            }
            return;
        }
        for (AEKey key : inventory.list.keySet()) {
            if (key == null) {
                continue;
            }
            var left = WideCraftingInventory.amount(inventory, key);
            while (left.signum() > 0) {
                long chunk = left.min(WideCeilings.LONG_ACCOUNT).longValue();
                long taken = inventory.extract(key, chunk, Actionable.MODULATE);
                if (taken <= 0L) {
                    break;
                }
                left = left.subtract(BigInteger.valueOf(taken));
                long refused = taken - Math.max(0L, Math.min(taken,
                        storage.insert(key, taken, Actionable.MODULATE, src)));
                if (refused > 0L) {
                    inventory.insert(key, refused, Actionable.MODULATE);
                    left = left.add(BigInteger.valueOf(refused));
                    if (refused == taken) {
                        break; // 这一口整口被拒：剩下的交给 storeItems() 分轮续交
                    }
                }
            }
        }
    }

    static void seedInitialInputs(
            ParallelExecutingCraftingJob job,
            KeyCounter missingInitialItems,
            Consumer<AEKey> postChange,
            @Nullable appeng.api.storage.MEStorage storage,
            IActionSource src,
            @Nullable ListCraftingInventory pool
    ) {
        if (missingInitialItems == null || missingInitialItems.isEmpty()) {
            return;
        }

        if (storage != null) refreshJobUnboundedSupply(job, storage, src);
        var mintedOnDemand = new KeyCounter();
        for (var entry : missingInitialItems) {
            if (entry.getKey() == null) {
                continue;
            }
            long gap = entry.getLongValue();
            if (job.isUnboundedSupply(entry.getKey())) {
                // 无限盘的料不挂"正在合成"：那一笔是在等一份永远不会从网络来的料。
                // 提交侧对越出 long 的需求只喂一刀（{@code tryExtractInitialItems}），抽不到的那一截
                // 不再留在挂账上——界面那个 9.2E 会让作业永远判不了完成（waitingFor 必须为空）。
                // 需要量由取料点就地认量（ParallelBatchExtraction#prepareUniform 的 unboundedSource
                // 那一支），不经网络、也不经池子。
                mintedOnDemand.add(entry.getKey(), gap);
                continue;
            }
            if (storage != null && pool != null && gap > 0L) {
                // 有限来源再次实际提取缺口，只有收到的实物能减少等待量。
                var awaitedKey = entry.getKey();
                long delivered = storage.extract(awaitedKey, gap, Actionable.MODULATE, src);
                if (delivered > 0L) {
                    pool.insert(awaitedKey, delivered, Actionable.MODULATE);
                    gap = delivered >= gap ? 0L : gap - delivered;
                }
                if (gap <= 0L) {
                    continue;
                }
            }
            // 尚未得到的原材料不是已派发产物；由后续网络提取逐笔销账。
            job.initialItemsRemaining.insertExact(entry.getKey(), BigInteger.valueOf(gap), Actionable.MODULATE);
            if (postChange != null) {
                postChange.accept(entry.getKey());
            }
        }
        if (!mintedOnDemand.isEmpty() && ChexsonsaeutilsCompatibilityConfig.parallelCpuDiagnosticsEnabled()) {
            AELog.info("[chexsonsaeutils-submit] infinite supply not awaited, minted on demand:"
                    + " keys=%d %s", mintedOnDemand.size(), mintedOnDemand);
        }
    }

    void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        taskResumeIndex = 0;
        taskResumePattern = null;
        String resumePath = poolLedger.path("resume");
        inventory.readFromNBT(data.getList("inventory", Tag.TAG_COMPOUND), registries);
        poolLedger.restore(resumePath);
        if (data.contains("job", Tag.TAG_COMPOUND)) {
            this.job = new ParallelExecutingCraftingJob(
                    data.getCompound("job"),
                    registries,
                    this::postChange,
                    lane
            );
            if (ChexsonsaeutilsCompatibilityConfig.parallelCpuDiagnosticsEnabled()) {
                AELog.info(
                    "[chexsonsaeutils-resume] lane=%s plan=%s recursive=%s"
                            + " tasks=%d remaining=%d output=%s",
                    lane.laneId(), job.planClass, job.dyeableRecursivePlan,
                    job.tasks.size(), job.remainingAmount, job.finalOutput);
            }
            if (this.job.finalOutput == null) {
                finishJob(false);
            }
        }
    }

    /** 提交边界一行：计划身份 + 任务表规模 + 提交瞬间的池子快照与配平结论。 */
    private void logSubmitBoundary(ICraftingPlan plan) {
        if (!ChexsonsaeutilsCompatibilityConfig.parallelCpuDiagnosticsEnabled()) {
            return;
        }
        // AELog 用 String.format，占位符必须是 %s/%d，写成 {} 会整行参数丢失。
        // planId 与 [chexsonsaeutils-fold] 的 ret= 对同一实例，对不上说明提交链还有别人换表。
        AELog.info(
                "[chexsonsaeutils-submit] lane=%s plan=%s planId=%d recursive=%s"
                        + " ringSeedKeys=%d unboundedKeys=%d%s %s tasks=%d remaining=%d inv=%s output=%s%s",
                lane.laneId(), plan.getClass().getSimpleName(), System.identityHashCode(plan),
                job.dyeableRecursivePlan,
                job.ringSeeds.seedAmounts().size(),
                job.unboundedSupplyKeys.size(),
                job.unboundedSupplyKeys.isEmpty() ? "" : "前几个=",
                describeUnboundedKeys(job),
                job.tasks.size(), job.remainingAmount, describeInventoryExact(snapshotPoolExact(inventory)),
                job.finalOutput,
                describePlanBalance(job, plan, stallProbe.blockedNamedKeys(job))
                        + job.ringSeeds.derivationReport());
    }

    /** 无限键集只打前六个：这一行要回答的是"计划到底把哪些键当无限源带下来了"，不是清单。 */
    private static String describeUnboundedKeys(ParallelExecutingCraftingJob job) {
        var described = new StringBuilder();
        int listed = 0;
        for (var key : job.unboundedSupplyKeys) {
            if (described.length() > 0) {
                described.append(", ");
            }
            described.append(key);
            if (++listed >= 6) {
                described.append("...");
                break;
            }
        }
        return described.toString();
    }

    /**
     * 计划自配平检查：按执行侧口径（multiplier × 模板量 × 发数）核整张任务表的每键供需，
     * 供给 = 任务表自产的同键总量 + {@code usedItems} 从网络取到的量。
     *
     * <p>所有槽共用一份余量池、按候选顺位逐槽扣减，与执行侧同一条规则。
     * 每槽各自挑"哪个候选供给最能盖住"会把同一份富余量重复许诺给多个槽，缺口就此被抹平。
     *
     * <p>{@code missingItems} 不算进供给：那些键在规划期就已经提不到，现场只会一直等。
     *
     * <p>逐键流水行里的 {@code uncoveredNeed=} 那一格印的是<b>未被供给完全盖住的那些槽的整段需求之
     * 和</b>（每格 = 候选模板量 × 倍率 × 该任务全部发数），<b>不是这个键跨全表的真需</b>：被供给
     * 全额盖住的槽不进这一格。实机对账时别把它读成"配给少配了多少"——少配的那一截是同一行的
     * {@code hole=}，{@code uncoveredNeed=} 只告诉你这些没盖住的槽总共想吃多少。
     *
     * <p>{@code hole=} 的分子只算<b>排水实记</b>（配给表减掉封套那一本，见
     * {@link #billPlannedMaterialHoles}）；这一行因此按 {@code hole} 从大到小排前 8 时，排在前面的
     * 就是真缺得最多的那几个键，不再被"整槽许可上界"那种假洞挤掉。
     *
     * <p>提交建账由 {@link #initializeSubmitMaterials} 独立完成。
     * 此入口只读取已有材料账并计算本次诊断，不重新采用计划、不登记配给、不累加自产。
     */
    static String describePlanBalance(
            ParallelExecutingCraftingJob job,
            ICraftingPlan plan
    ) {
        return describePlanBalance(job, plan, java.util.List.of());
    }

    /**
     * 提交边界那一行的真渲染口。第三参 {@code blockedNamedKeys} 是<b>本窗 {@code blocked=}／
     * {@code 回补受阻} 已经点名的键</b>（{@link StallProbe#blockedNamedKeys} 那一份现成名单，
     * 不新开第二本账）：它们走 {@code alloc=} 同一条<b>必含格</b>口径，排序挤不进前 8 也要成行。
     *
     * <p>为什么这一格非要必含格：{@code holes} 只按 {@code hole} 大小排、可见区封顶前 8——缺口键随
     * 规模增长时，被 {@code blocked=} 逐字点名的那一枚（回补腿每批 {@code got=0} 的现场证人）恰恰
     * 可能是洞最小的那一格，被八个更大的洞挤出可见区，下一跑日志就只剩"这格卡死"与"这格没在数"
     * 两个对不上的读数。名单缺席（常态：提交瞬间还没有本窗 Probe）时行为与旧口径逐字相同。
     */
    static String describePlanBalance(
            ParallelExecutingCraftingJob job,
            ICraftingPlan plan,
            java.util.Collection<AEKey> blockedNamedKeys
    ) {
        // 顺位只有一把尺：任务表原样交给 RecursiveTaskOrdering，与推送那一路（#tickCraftingLogic）
        // 和族到账摊派（ParallelExecutingCraftingJob#rationPatternsInExecutionOrder）喂进去的是
        // 同一份 Map、同一个算式 ⇒ 并列项（谁都不是环成员）保持任务表的插入序。
        // 过去这里先按 getDefinition() 的字符串预排一遍再套尺子，那是第二把尺：并列项的次序两行
        // 可以相反，报出来的 hole 归属与"现场真实扣料的先后"就不是同一回事（台账 #134 的读法）。
        var orderedTasks = RecursiveTaskOrdering.orderedEntries(
                job.tasks, ringInternalItemsForOrdering(job));

        // 这几本外层一律 LinkedHashMap：它们的键序＝这趟账的登记序（＝推送底序），不是 AEKey 的哈希序。
        // 并列 hole 的先后由 holes 的遍历序决定（ranked 只按 hole 数值稳定排序，不动并列项），
        // 外层是 HashMap 时"前 8 点名哪几个键、按什么次序点名"就随会话变（读数不回写，P2）。
        var emitted = new java.util.LinkedHashMap<AEKey, Long>();
        var producers = new java.util.HashMap<AEKey, java.util.List<long[]>>();
        var producerNames = new java.util.HashMap<AEKey, java.util.List<String>>();
        long taskFirings = 0L;
        for (var task : orderedTasks) {
            IPatternDetails details = task.getKey();
            long firings = task.getValue() == null ? 0L : task.getValue().value;
            if (details == null || firings <= 0L || details.getOutputs() == null) {
                continue;
            }
            taskFirings = ClampedMath.addUp(taskFirings, firings);
            // 发数读任务表的宽域真值（TaskProgress#exactRemaining）：value 那份 long 是
            // AE2 patternTimes 被饱和后的<b>代表值</b>，真值随计划对象出门（WideCraftSnapshot）并
            // 在建表那一刻落进侧值——侧值缺席即"那份 long 就是真值"，两种情形都只有一条路，
            // 没有"缺记录当 0"那一档。它与 ParallelPlanReservation 的产出贷方账读的是同一张嘴。
            PlanAmount wideFirings = task.getValue().exactRemaining();
            for (var output : details.getOutputs()) {
                if (output == null || output.what() == null) {
                    continue;
                }
                emitted.merge(output.what(), saturatingMultiply(output.amount(), firings),
                        ClampedMath::addUp);
                describeProducer(producers, producerNames, output.what(), details,
                        output.amount(), firings);
            }
        }
        var supply = new java.util.LinkedHashMap<AEKey, Long>();
        for (var entry : emitted.entrySet()) {
            supply.merge(entry.getKey(), entry.getValue(), ClampedMath::addUp);
        }
        var usedItems = plan.usedItems();
        var missingItems = plan.missingItems();
        addAllPositive(supply, usedItems);
        // 规划出口已经把"哪个键几份"定版随计划下发时，配给照它建，本趟排水只作对账用；
        // 只有计划没带表（旧出口/别的算法）才在这里按现场口径重算一份。
        boolean mintAllocation = job.materialAllocation.source() != MaterialAllocation.Source.PLAN;
        var holes = new java.util.LinkedHashMap<AEKey, BigInteger[]>();
        var consumers = new java.util.LinkedHashMap<AEKey, java.util.Map<String, BigInteger>>();
        if (mintAllocation) {
            for (var drain : drainSubmitMaterials(orderedTasks, exactSubmitSupply(job, plan))) {
                billSubmitMaterialHole(drain, holes, consumers);
            }
        } else {
            billPlannedMaterialHoles(job, supply, holes, consumers);
        }
        var summary = new StringBuilder(" planBalance=[");
        // assigned= 是配给的覆盖面：执行侧照单取料的份额就来自这张表，为 0 说明这份账没建起来。
        summary.append(" assigned=").append(job.materialAllocation.allocatedPatternCount())
                .append(" src=").append(mintAllocation ? "submit" : "plan");
        appendPlanIdentity(summary, plan, taskFirings);
        // M3（纯读数）：把"这一格真会伸手、却被 quotaExact 判成没配额口径"的键逐一点名，
        // 下一跑一眼分出"配给没记这族"（出现在这里）与"记了但被花光"（不出现在这里、读数 0）。
        // 只进字符串：不参与任何判据、不新增第二本账、份额仍只由 quotaExact 一处读、最多前 6 个防炸日志。
        appendNoBasisReachKeys(summary, job, orderedTasks, supply);
        if (holes.isEmpty()) {
            return summary.append(" ok keys=").append(emitted.size()).append(']').toString();
        }
        var ranked = new java.util.ArrayList<>(holes.entrySet());
        // 只按 hole 数值排这一把尺（并列项不再装第二把尺，见 e5e5a45）：List.sort 稳定 ⇒ 并列 hole
        // 保持 holes 的遍历序。那份序由上面那本外层 LinkedHashMap 交回＝这趟账的登记序＝推送底序；
        // 外层若是 HashMap，并列 hole 的键次序与"前 8 点名谁"就随 AEKey 哈希（Item 身份哈希）随会话变。
        ranked.sort(java.util.Comparator.comparing(
                (java.util.Map.Entry<AEKey, BigInteger[]> entry) -> entry.getValue()[0]).reversed());
        summary.append(" unbalanced=").append(holes.size()).append('/').append(emitted.size());
        var printed = new java.util.LinkedHashSet<AEKey>();
        for (int i = 0; i < Math.min(8, ranked.size()); i++) {
            var entry = ranked.get(i);
            AEKey key = entry.getKey();
            summary.append(describeHoleRow(key, entry.getValue(), emitted, plan, usedItems,
                    missingItems, producers, producerNames, consumers));
            if (key != null) {
                printed.add(key);
            }
        }
        // 必含格：本窗 blocked=／回补受阻 点名的键有洞却没挤进前 8，仍要逐字成行（同一只渲染器、
        // 同一把尺，名单的登记序即打印序，不重排）——与 alloc= 的必含格同一口径。
        for (AEKey key : blockedNamedKeys) {
            if (key == null || printed.contains(key)) {
                continue;
            }
            var forced = holes.get(key);
            if (forced == null) {
                continue;   // 名单点名而缺口账里没有它：这一趟账没给它立洞，不硬造一行
            }
            summary.append(describeHoleRow(key, forced, emitted, plan, usedItems, missingItems,
                    producers, producerNames, consumers));
            printed.add(key);
        }
        return summary.append(']').toString();
    }

    /**
     * {@code holes} 一格的<b>唯一渲染器</b>：前 8 与必含格走同一串格名、同一个算式，
     * 必含格不许另造一副读数控（两副读数迟早分叉，正是 {@code alloc=} 那边收口的老路）。
     */
    private static String describeHoleRow(AEKey key, BigInteger[] hole,
            java.util.Map<AEKey, Long> emitted, ICraftingPlan plan,
            @Nullable KeyCounter usedItems, @Nullable KeyCounter missingItems,
            java.util.Map<AEKey, java.util.List<long[]>> producers,
            java.util.Map<AEKey, java.util.List<String>> producerNames,
            java.util.Map<AEKey, java.util.Map<String, BigInteger>> consumers
    ) {
        return new StringBuilder().append(' ').append(key)
                .append(" hole=").append(hole[0])
                .append(" uncoveredNeed=").append(hole[1])
                .append(" emit=").append(poolAmount(emitted, key))
                .append(" planEmit=").append(plan.emittedItems().get(key))
                .append(" used=").append(usedItems == null ? 0L : Math.max(0L, usedItems.get(key)))
                .append(" miss=").append(missingItems == null ? 0L : Math.max(0L, missingItems.get(key)))
                .append(" by=").append(describeProducers(producers, producerNames, key))
                .append(" want=").append(describeConsumers(consumers, key)).toString();
    }

    /**
     * M3 纯读数：点名"这一格真会伸手、却被 {@link ParallelPlanReservation#quotaExact} 判成没配额口径"
     * 的键（最多前 6 个），带 {@code assigned}/{@code eaten} 两笔族级宽域真值。
     *
     * <p>它只回答上一跑分不出的那一问：{@code locked}/{@code nolist} 到底是<b>配给没记这族</b>
     * （出现在这里，{@code quotaExact} 交 {@code null}）还是<b>记了但被花光</b>（不在这里，份额读数 0）。
     * <b>只进字符串</b>：读的是既有的 {@code planMaterialPlan}/{@code planMaterialEaten} 两本账与
     * 同一份 {@code quotaExact} 算式，不新增第二本账、不参与任何判据、不加拒绝分支。
     */
    static void appendNoBasisReachKeys(StringBuilder summary,
            ParallelExecutingCraftingJob job,
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> orderedTasks,
            Map<AEKey, Long> pool
    ) {
        var reservation = ParallelPlanReservation.build(orderedTasks, pool,
                job.materialAllocation, job.unboundedSupplyKeys,
                job.ringSeeds.seedDemandsByFamily(), job.batchObligations);
        var listed = new StringBuilder();
        int shown = 0;
        int missed = 0;
        for (var task : orderedTasks) {
            IPatternDetails details = task.getKey();
            if (details == null || details.getInputs() == null) {
                continue;
            }
            for (var input : details.getInputs()) {
                if (input == null) {
                    continue;
                }
                GenericStack reach = SlotAccount.firstCandidate(input);
                AEKey key = reach == null ? null : reach.what();
                if (key == null || reservation.isUnboundedSupply(key)
                        || SlotAccount.selfRepaying(input, key)) {
                    continue;
                }
                // 只点名"没配额口径"那一型：quotaExact 交回非 null（含份额 0）都归"记了但被花光"，
                // 不在这一格里，由 usable=/over= 那几支读数负责。模具槽/无限源同由判据排除。
                if (reservation.quotaExact(key, details, input) != null) {
                    continue;
                }
                missed++;
                if (shown >= 6) {
                    continue;
                }
                shown++;
                if (listed.length() > 0) {
                    listed.append(", ");
                }
                listed.append(key)
                        .append(" assigned=")
                        .append(job.materialAllocation.assignedFamilyAmount(details, key))
                        .append(" eaten=")
                        .append(job.materialAllocation.eatenFamilyAmount(details, key));
            }
        }
        if (missed == 0) {
            return;
        }
        summary.append(" noBasisReach=").append(missed).append('[').append(listed);
        if (missed > shown) {
            summary.append(", ...");
        }
        summary.append(']');
    }

    /**
     * 提交期自算配给趟（{@code mintAllocation == true} 的那一整支）：逐槽按执行侧顺位排水，
     * 通过 {@code MaterialAllocation.mintShare} 登记配给。计划没带配给表时（{@link #adoptPlannedMaterialPlan}
     * 的空表出口：无环分支交回 AE2 原生计划、闸门判 violation 或覆盖不过交白卷），这一趟是
     * {@code ParallelPlanReservation#quotaExact} <b>唯一的份额来源</b>——交回按天花板乘出来的
     * 假数，下游就直接产出"配额=0 / 可花却付 0"那一型实机症状。
     *
     * <p><b>发数读 {@code TaskProgress#exactRemaining()} 的宽域真值</b>，不是 {@code value}
     * 那份饱和镜像：剩余发数越出 {@code long} 时 {@code value} 是 {@link Long#MAX_VALUE} 哨兵，
     * 拿它当乘数就是把 1.8e19 发读成 9.2e18 发。排水走 {@link SlotAccount#drainSlotWide}
     * （与窄域 {@code drainSlot} 同一把尺、只换算术域），配给、缺口（{@code unfundedFirings} 与
     * {@link SlotAccount#chargedUnitsWide} 的乘减）全在宽域上算；这一趟<b>没有任何 long 通道的
     * 计划吃料流水账要跟</b>——那条只写不读的账已整本删掉，饱和仍只留在判据那一道出口
     * （{@code ParallelPlanReservation#remainingQuota} 里的 {@link Ae2Amounts#toLong}）。
     */
    static void mintSubmitAllocation(
            ParallelExecutingCraftingJob job,
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> orderedTasks,
            java.util.Map<AEKey, BigInteger> supply,
            java.util.Map<AEKey, BigInteger[]> holes,
            java.util.Map<AEKey, java.util.Map<String, BigInteger>> consumers
    ) {
        for (var task : orderedTasks) {
            if (task.getKey() != null && task.getKey().getInputs() != null
                    && task.getValue() != null && task.getValue().exactRemaining().signum() > 0) {
                job.materialAllocation.registerSubmitPattern(task.getKey());
            }
        }
        for (var drain : drainSubmitMaterials(orderedTasks, supply)) {
            for (var paid : drain.result().funded().entrySet()) {
                if (!SlotAccount.selfRepaying(drain.slot(), paid.getKey())) {
                    job.materialAllocation.mintShare(drain.pattern(), paid.getKey(), paid.getValue());
                }
            }
            GenericStack reported = drain.result().reported();
            if (drain.result().unfundedFirings().signum() > 0 && reported != null
                    && !SlotAccount.selfRepaying(drain.slot(), reported)) {
                job.materialAllocation.mintShare(drain.pattern(), reported.what(), SlotAccount.chargedUnitsWide(
                        drain.slot(), reported, drain.result().unfundedFirings()));
            }
            billSubmitMaterialHole(drain, holes, consumers);
        }
    }

    /** 提交边界唯一建账入口；独立于日志开关，在真到账收窄前执行。 */
    static void initializeSubmitMaterials(ParallelExecutingCraftingJob job, ICraftingPlan plan) {
        var ordered = RecursiveTaskOrdering.orderedEntries(job.tasks, ringInternalItemsForOrdering(job));
        for (var task : ordered) {
            var pattern = task.getKey();
            if (pattern == null || task.getValue() == null || pattern.getOutputs() == null) {
                continue;
            }
            for (var output : pattern.getOutputs()) {
                if (output != null && output.what() != null) {
                    job.accountPlannedEmit.add(output.what(),
                            PlanAmount.of(output.amount()).multiply(task.getValue().exactRemaining()));
                }
            }
        }
        if (adoptPlannedMaterialPlan(job, plan)) {
            mintSubmitAllocation(job, ordered, exactSubmitSupply(job, plan),
                    new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>());
        }
    }

    /** 自产与初始供给在宽域复加；long 仅用于日志显示，不参与候选分配。 */
    private static java.util.Map<AEKey, BigInteger> exactSubmitSupply(
            ParallelExecutingCraftingJob job, ICraftingPlan plan) {
        var supply = new java.util.LinkedHashMap<AEKey, BigInteger>();
        for (AEKey key : job.accountPlannedEmit.keys()) {
            supply.put(key, job.accountPlannedEmit.getExact(key));
        }
        for (var entry : plan.usedItems()) {
            if (entry.getLongValue() > 0L) {
                supply.merge(entry.getKey(), BigInteger.valueOf(entry.getLongValue()), BigInteger::add);
            }
        }
        return supply;
    }

    /** 每槽排水结果；初始化与诊断共用计算，不把账本写口带入只读诊断。 */
    private record SubmitMaterialDrain(IPatternDetails pattern, IPatternDetails.IInput slot,
            BigInteger firings, SlotAccount.WideDrain result) { }

    /** 只修改调用方本次新建的供给池；保持任务序、槽序和候选序。 */
    private static java.util.List<SubmitMaterialDrain> drainSubmitMaterials(
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> orderedTasks,
            java.util.Map<AEKey, BigInteger> supply) {
        var drains = new java.util.ArrayList<SubmitMaterialDrain>();
        for (var task : orderedTasks) {
            var pattern = task.getKey();
            var progress = task.getValue();
            if (pattern == null || pattern.getInputs() == null || progress == null
                    || progress.exactRemaining().signum() <= 0) {
                continue;
            }
            var firings = progress.exactRemaining().exactValue();
            for (var slot : pattern.getInputs()) {
                drains.add(new SubmitMaterialDrain(pattern, slot, firings,
                        SlotAccount.drainSlotWide(supply, slot, firings, null)));
            }
        }
        return drains;
    }

    /** 缺口渲染只消费槽计算结果，不登记配给或消费。 */
    private static void billSubmitMaterialHole(SubmitMaterialDrain drain,
            java.util.Map<AEKey, BigInteger[]> holes,
            java.util.Map<AEKey, java.util.Map<String, BigInteger>> consumers) {
        var result = drain.result();
        GenericStack reported = result.reported();
        if (result.unfundedFirings().signum() <= 0 || reported == null) {
            return;
        }
        var unit = SlotAccount.unitsPerFiringExact(drain.slot(), reported);
        var selfRepaying = SlotAccount.selfRepaying(drain.slot(), reported);
        noteHole(holes, consumers, reported.what(),
                SlotAccount.chargedUnitsWide(drain.slot(), reported, result.unfundedFirings()),
                unit.multiply(drain.firings()),
                describeConsumer(drain.pattern(), unit, drain.firings(), selfRepaying));
    }

    /**
     * 计划自带规划定版的配给表时照单采用；返回 false 表示本趟排水不用再 mint 一份归属。
     *
     * <p>表的来源是守恒闸门同一趟账（{@code MaterialClosureGate.buildMaterialPlan}）：
     * 配给与计划供给读同一份供给、同一个候选宇宙、同一条顺位。提交期再按 AE2 原始候选表
     * 重算一份，就会对出两个缺口数字（实机：闸门判"够"、配给报 {@code hole=960}）。
     *
     * <p>规划侧交来的已经是 {@link WideKeyCounter}：整本搬过去就行，<b>不在这里降级</b>。
     * 老口径这张表是 {@code Map<AEKey, Long>}，计划出口那一次 {@code toLong} 已经把配额钉在
     * {@link Long#MAX_VALUE} 上——这本账是被减数，被减数一被截短，"配给 − 已吃"就恒等于少算
     * 一截（已吃账是第四刀换的宽域账，逐批精确），还能推的批次被 {@code quotaFor} 夹小。
     * 包级可见只为让"照单采用"这一段有直接判据的用例入口，与 {@code recordTakenInputs} 同口径。
     *
     * <p>封套那一本（{@code chexsonsaeutils$planMaterialEnvelopes}）走<b>同一条穿壳路</b>一起收下，
     * 记进 {@code materialAllocation 的封套账}：它只给 {@link #billPlannedMaterialHoles} 当被减数，
     * <b>不进</b>配额夹的读数（配额夹照旧读 {@code materialAllocation 的配给账} 整本，含封套——那正是
     * 封套存在的理由）。返回的"要不要 mint 第二份归属"只由配给表本体决定，封套不参与。
     */
    static boolean adoptPlannedMaterialPlan(
            ParallelExecutingCraftingJob job, ICraftingPlan plan
    ) {
        return !(plan instanceof RecursivePlanContract contract)
                || !job.materialAllocation.adopt(contract.chexsonsaeutils$planMaterialPlan(),
                        contract.chexsonsaeutils$planMaterialEnvelopes());
    }

    /** 逐键记一笔缺口：{@code shortfall} 是没来源的量，{@code need} 是这一槽的整段需求（都是宽域真值）。 */
    private static void noteHole(java.util.Map<AEKey, BigInteger[]> holes,
            java.util.Map<AEKey, java.util.Map<String, BigInteger>> consumers,
            AEKey key, BigInteger shortfall, BigInteger need, String consumer
    ) {
        var aggregated = holes.computeIfAbsent(key,
                ignored -> new BigInteger[] { BigInteger.ZERO, BigInteger.ZERO });
        aggregated[0] = aggregated[0].add(shortfall);
        aggregated[1] = aggregated[1].add(need);
        consumers.computeIfAbsent(key, ignored -> new java.util.LinkedHashMap<>())
                .merge(consumer, shortfall, BigInteger::add);
    }

    /**
     * 配给表对计划自身供给的逐键差额：需求是表上写下的<b>排水实记</b>份数，来源只有
     * "任务表自产 + usedItems"。
     *
     * <p><b>先减封套，再与供给比</b>（{@code materialAllocation 的封套账}，随契约同一条穿壳路
     * 从规划出口带下来）。配给表里<b>不全是</b>计划真取走的量：
     * {@code MaterialClosureGate#billReachKeySlot} 给模糊槽首位候选补记的那一笔是<b>整槽份额的
     * 许可上界</b>（"执行侧若向这一族伸手，至多吃掉整格"），那一族的计划供给常态为 0。整本当
     * need 的话，首位族那一档 {@code hole=} 恰等于封套全额、还随发数放大（规模相关的假数），
     * 并按 {@code hole} 从大到小排在真洞前面、把真洞挤出前 8 的可见区。
     *
     * <p>改后口径（把这句老话术按实际口径改口）：<b>{@code hole=} 只覆盖排水实记</b>，
     * 封套不进这个数——所以这一格报出的才是"表上要付、计划却没来源"的真洞。封套那一截照样
     * 在配额账上生效（{@link ParallelPlanReservation#quotaExact} 读整本 {@code planMaterialPlan}），
     * 少减一处就把首位族现场有量那一型夹回 0。
     *
     * <p>表由守恒闸门那一趟账产出（同一份供给、同一个候选宇宙、同一条顺位）；这里的 need 与闸门
     * 另记的 {@code envelopes} 用同一条"剔掉封套只核实记"的减式；再按 AE2 原始候选表排一遍归属，只是把两个宇宙的差当成
     * 缺口（{@code hole=960} 的来路）。
     *
     * <p><b>减掉封套之后还剩下一件事：那一族的需求不能就此隐身</b>（{@link #billReachSlotDemand}）。
     * 封套不进 {@code need} 是对的（它不是计划真取走的量），但它也不是"执行侧不会碰这一族"——
     * {@code billReachKeySlot} 记的正是取料点按声明顺位真会伸手的那一枚，每发要 N 枚、这张样板还有
     * 发数，这一格就会按 {@code N × 发数} 吃它。所以缺口判据的 need 还要从<b>取料点那一侧</b>再核
     * 一遍：同族既没有存量、也没有生产者、又不在无限源里时，这一格就是真缺料，照旧走同一只
     * {@code holes} 口袋如实上报（不新增第三种结局、不拒规划、不动任何既有判据）。
     */
    private static void billPlannedMaterialHoles(ParallelExecutingCraftingJob job,
            java.util.Map<AEKey, Long> supply, java.util.Map<AEKey, BigInteger[]> holes,
            java.util.Map<AEKey, java.util.Map<String, BigInteger>> consumers
    ) {
        var claims = drainClaimsByKey(job);
        var need = narrowDrainClaims(claims);
        for (var entry : need.entrySet()) {
            BigInteger required = BigInteger.valueOf(entry.getValue());
            BigInteger available = BigInteger.valueOf(supply.getOrDefault(entry.getKey(), 0L));
            if (required.compareTo(available) <= 0) {
                continue;
            }
            noteHole(holes, consumers, entry.getKey(), required.subtract(available),
                    required, "planMaterialPlan");
        }
        billReachSlotDemand(job, claims, supply, holes, consumers);
    }

    /**
     * 封套那一族的<b>需求侧</b>读数（台账 #196）：模糊槽即使排水实记为 0，只要这张样板还在任务表上
     * 有发数、取料点这一格每发真伸 N 枚过去，这一格的需求就是 {@code N × 发数}——按执行侧取料点
     * 的算式（{@link SlotAccount#unitsPerFiringExact} × 宽域发数）计，不经过 {@code long} 出口。
     *
     * <p><b>为什么这一格过去整条核不到</b>：{@code src=plan} 那条路的 need 只有
     * {@code 配给整本 − 封套本} 一个来路，"这一格只被记了封套、排水实记为 0"的键被<b>整条减掉</b>
     * ⇒ {@code holes} 空 ⇒ {@code describePlanBalance} 在 {@code holes.isEmpty()} 那一句直接 return
     * {@code ok keys=}（分母，不是判决）⇒ 照样下单。执行侧随后每发吃 1 枚、池内与网络都是 0
     * ⇒ 逐次 {@code got=0 ⇒ ranDry}，尾段 100 tick 零推送（实机 {@code ae2:creative_storage_cell
     * x 1e11} 的 {@code mekanismsun:artificial_sun_port#slot0} 那一格，台账 #194/#196）。
     * 规划按"排水实记"算需求、执行按"每发件数 × 发数"取料，两套口径就是这么对不上的。
     *
     * <p><b>缺口里立的是"没有实记对应的那一截"</b>，不是封套整本：{@code assigned} 分子照旧逐键剔掉
     * 封套（{@code 121/123/126} 定过的口径一字未动——把封套整个塞回分子会把整张配给表顶成假超配/白卷）。
     * 这一腿只补上被剔掉的那一族<b>自己那一格</b>的取料点需求：需求按取料点同一枚候选的
     * {@code 每发件数 × 发数} 计，与"任务表自产 ＋ 现场到账 ＋ 无限源"这一本供给比，核不过即如实立洞。
     *
     * <p><b>顺位悲观的那颗牙必须留着</b>（历史上 {@code #36}「出口按毛账报缺料」就是因为它把可执行
     * 的计划误拦过，整笔回退）：只有这一族<b>三样都没有</b>时才立缺口——同族有排水实记（需求已经在
     * 上面那一趟核过，报两遍就是把同一格算成两笔欠账）、同族有存量或生产者（现场拿得到，退路成立）、
     * 同族已被认成无限源（本就不该向网络抽它）——任一项成立都照旧放行。这里<b>不</b>按规模豁免：
     * 30 枚与 2.37e12 枚是同一笔账的两种大小。
     *
     * <p>落点仍只有 {@code holes} 这一只既有口袋（真缺料如实上报那一条腿）：不新增闸门、不新增
     * 结局、不拒整条规划、不改 {@code ParallelBatchExtraction} 的任何算式。发数取任务表的
     * {@code exactRemaining()} 宽域真值（与 {@link #mintSubmitAllocation} 同一份读口），
     * 逐键缺口与 {@code want=} 都是 {@code BigInteger}，一个都不在天花板上做。
     */
    private static void billReachSlotDemand(ParallelExecutingCraftingJob job,
            WideKeyCounter claims, java.util.Map<AEKey, Long> supply,
            java.util.Map<AEKey, BigInteger[]> holes,
            java.util.Map<AEKey, java.util.Map<String, BigInteger>> consumers
    ) {
        // 点名次序与 need 同一把尺：按配给表的全序读口走（台账 #135 第 2 条），不碰身份哈希序
        for (var row : job.materialRows()) {
            var pattern = row.pattern();
            if (row.envelopes().isEmpty()) {
                continue;
            }
            var progress = job.tasks.get(pattern);
            BigInteger firings = progress == null ? BigInteger.ZERO
                    : progress.exactRemaining().exactValue();
            if (firings.signum() <= 0L) {
                continue;   // 这张样板在任务表上已经没有发数 ⇒ 取料点不会再为它伸一次手
            }
            IPatternDetails.IInput[] slots = pattern.getInputs();
            if (slots == null) {
                continue;
            }
            for (var amount : row.envelopes()) {
                AEKey reachKey = amount.key();
                if (reachKey == null) {
                    continue;
                }
                if (familyClaimed(claims, reachKey) || familySupplied(supply, reachKey)
                        || job.isUnboundedSupply(reachKey)) {
                    continue;   // 同族有实记 / 有存量或生产者 / 在无限源里：任一项成立就照旧放行
                }
                IPatternDetails.IInput slot = reachSlot(slots, reachKey);
                GenericStack candidate = slot == null ? null : reachCandidate(slot, reachKey);
                if (candidate == null || SlotAccount.selfRepaying(slot, candidate)) {
                    continue;   // 交不回"取料点真伸手的这一格"就不立这一笔（宁缺不假）
                }
                BigInteger perFiring = SlotAccount.unitsPerFiringExact(slot, candidate);
                if (perFiring.signum() <= 0L) {
                    continue;   // 与 SlotAccount#unitsPerFiring 的非法输入口径一致：不计费
                }
                BigInteger required = perFiring.multiply(firings);
                BigInteger available = BigInteger.valueOf(supply.getOrDefault(reachKey, 0L));
                if (required.compareTo(available) <= 0L) {
                    continue;
                }
                noteHole(holes, consumers, reachKey, required.subtract(available), required,
                        describeConsumer(pattern, perFiring, firings, false));
            }
        }
    }

    /**
     * 这一格是哪一格：封套键来自 {@code billReachKeySlot} 的伸手候选，它就一定出现在这张样板某个槽的
     * 原始候选表里（声明顺位 ∩ 规划宇宙）。按同一把尺回找——物品本体族命中<b>且该枚候选真按件数计费</b>
     * 才交回那个槽，否则交回 {@code null}（调用方不立这一笔，绝不用别的槽凑数）。
     */
    private static @Nullable IPatternDetails.IInput reachSlot(
            IPatternDetails.IInput[] slots, AEKey reachKey
    ) {
        for (IPatternDetails.IInput slot : slots) {
            if (reachCandidate(slot, reachKey) != null) {
                return slot;
            }
        }
        return null;
    }

    /** 该槽里属于这一族的那一枚候选（每发件数就从它身上读，与取料点同一枚）。 */
    private static @Nullable GenericStack reachCandidate(@Nullable IPatternDetails.IInput slot,
            AEKey reachKey
    ) {
        var candidates = slot == null ? null : slot.getPossibleInputs();
        if (candidates == null) {
            return null;
        }
        var family = reachKey.getPrimaryKey();
        for (GenericStack candidate : candidates) {
            if (candidate != null && candidate.what() != null && candidate.amount() > 0L
                    && family.equals(candidate.what().getPrimaryKey())) {
                return candidate;
            }
        }
        return null;
    }

    /** 这一族在<b>排水实记</b>那一本里有没有任一笔：有就等于需求已经过上面那一趟核，不许报两遍。 */
    private static boolean familyClaimed(WideKeyCounter claims, AEKey key) {
        var family = key.getPrimaryKey();
        for (AEKey claimed : claims.keys()) {
            if (claimed != null && family.equals(claimed.getPrimaryKey())
                    && claims.getAmount(claimed).signum() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 这一族在现场拿不拿得到：{@code supply} 就是"任务表自产（生产者）+ usedItems（存量）"那一本。 */
    private static boolean familySupplied(java.util.Map<AEKey, Long> supply, AEKey key) {
        var family = key.getPrimaryKey();
        for (var entry : supply.entrySet()) {
            AEKey supplied = entry.getKey();
            if (supplied != null && entry.getValue() != null && entry.getValue() > 0L
                    && family.equals(supplied.getPrimaryKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 配给表按键汇总的<b>排水实记</b>总份数（宽域原样交回）：跨样板复加走 {@link WideKeyCounter} 的
     * 宽域精确账，先整本并入、再逐键<b>减掉封套那一本</b>（减完 ≤0 的键即"这一族只有上界、没有实记"，
     * 由 {@link WideKeyCounter#add} 的零标记自然退出）。
     *
     * <p>减法必须在<b>宽域</b>上做：先在 {@link Ae2Amounts#toLong} 那一道天花板出口上各读一份
     * {@code long} 再相减，就是拿两个哨兵当量（30 减 30 算出 0 是运气，算出假数的那一截才是常态）。
     *
     * <p>这一本宽域账有两个读口：{@link #narrowDrainClaims} 是既有那条对账读数（出口窄化一次，
     * 哨兵语义见 {@link Ae2Amounts}），{@link #billReachSlotDemand} 拿它判"同族有没有实记"——
     * 两侧读的是同一趟减式，不另起第二本账。
     */
    private static WideKeyCounter drainClaimsByKey(ParallelExecutingCraftingJob job) {
        var wide = new WideKeyCounter();
        var rows = job.materialRows();
        for (var row : rows) {
            for (var amount : row.assigned()) {
                wide.add(amount.key(), amount.amount());
            }
        }
        for (var row : rows) {
            for (var amount : row.envelopes()) {
                wide.add(amount.key(), amount.amount().negate());
            }
        }
        return wide;
    }

    /** 既有那条对账读数：宽域实记逐键窄化一次（哨兵语义见 {@link Ae2Amounts}）。 */
    private static java.util.Map<AEKey, Long> narrowDrainClaims(WideKeyCounter claims) {
        var need = new java.util.LinkedHashMap<AEKey, Long>();
        for (AEKey key : claims.keys()) {
            if (key != null) {
                need.put(key, Ae2Amounts.toLong(claims.getAmount(key)));
            }
        }
        return need;
    }

    /** 缺口键的前三名生产者：带 details 实现类名，用来认包装样板（翻倍/动态产出）。 */
    private static void describeProducer(
            java.util.Map<AEKey, java.util.List<long[]>> producers,
            java.util.Map<AEKey, java.util.List<String>> producerNames,
            AEKey key, IPatternDetails details, long perFiring, long firings
    ) {
        var shares = producers.computeIfAbsent(key, ignored -> new java.util.ArrayList<>());
        var names = producerNames.computeIfAbsent(key, ignored -> new java.util.ArrayList<>());
        String described = details.getDefinition() + "#"
                + details.getClass().getSimpleName() + " " + perFiring + "x" + firings;
        long total = saturatingMultiply(perFiring, firings);
        int index = -1;
        long bestTotal = -1L;
        for (int i = 0; i < shares.size(); i++) {
            if (described.equals(names.get(i))) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            shares.get(index)[0] = ClampedMath.addUp(shares.get(index)[0], total);
            return;
        }
        for (int i = 0; i < shares.size(); i++) {
            if (shares.get(i)[0] > bestTotal) {
                bestTotal = shares.get(i)[0];
                index = i;
            }
        }
        if (index < 0) {
            shares.add(new long[] { total });
            names.add(described);
        } else if (total > bestTotal) {
            shares.set(index, new long[] { total });
            names.set(index, described);
        }
    }

    private static String describeProducers(
            java.util.Map<AEKey, java.util.List<long[]>> producers,
            java.util.Map<AEKey, java.util.List<String>> producerNames, AEKey key
    ) {
        var shares = producers.get(key);
        var names = producerNames.get(key);
        if (shares == null || shares.isEmpty()) {
            return "[]";
        }
        var paired = new java.util.ArrayList<java.util.Map.Entry<String, Long>>();
        for (int i = 0; i < shares.size(); i++) {
            paired.add(java.util.Map.entry(names.get(i), shares.get(i)[0]));
        }
        paired.sort(java.util.Map.Entry.<String, Long>comparingByValue().reversed());
        var described = new StringBuilder("[");
        for (var entry : paired) {
            if (described.length() > 1) {
                described.append(", ");
            }
            described.append(entry.getKey());
        }
        return described.append(']').toString();
    }

    /** 消费者标签：带上每发 unit 与发数（宽域真值），折叠后的槽在这里现出被放大多少倍。 */
    private static String describeConsumer(
            IPatternDetails details, BigInteger unit, BigInteger firings, boolean selfRepaying
    ) {
        return details.getDefinition() + "#" + details.getClass().getSimpleName()
                + " unit=" + unit + "x" + firings
                + (selfRepaying ? " selfRepay" : "");
    }

    /** 缺口键的前三名消费者（按缺口量排序）：只点生产者无法区分"真多要"和"起步量被放大"。 */
    private static String describeConsumers(
            java.util.Map<AEKey, java.util.Map<String, BigInteger>> consumers, AEKey key
    ) {
        var shares = consumers.get(key);
        if (shares == null || shares.isEmpty()) {
            return "[]";
        }
        var ranked = new java.util.ArrayList<>(shares.entrySet());
        ranked.sort(java.util.Map.Entry.<String, BigInteger>comparingByValue().reversed());
        var described = new StringBuilder("[");
        for (var entry : ranked.subList(0, Math.min(3, ranked.size()))) {
            if (described.length() > 1) {
                described.append(", ");
            }
            described.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return described.append(']').toString();
    }

    /** 计划身份：样板实现类分布 + 计划发数与任务表发数，提交链换包装或改发数在这里现形。 */
    private static void appendPlanIdentity(
            StringBuilder summary, ICraftingPlan plan, long taskFirings
    ) {
        var counts = new java.util.TreeMap<String, Long>();
        long planFirings = 0L;
        var patternTimes = plan.patternTimes();
        if (patternTimes != null) {
            for (var entry : patternTimes.entrySet()) {
                long executions = Math.max(0L, entry.getValue() == null ? 0L : entry.getValue());
                planFirings = ClampedMath.addUp(planFirings, executions);
                counts.merge(entry.getKey() == null ? "null"
                        : entry.getKey().getClass().getSimpleName(), 1L, Long::sum);
            }
        }
        var described = new StringBuilder();
        for (var entry : counts.entrySet()) {
            if (described.length() > 0) {
                described.append('+');
            }
            described.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append("classes=[").append(described)
                .append("] planFirings=").append(planFirings)
                .append(" taskFirings=").append(taskFirings);
    }

    /** 模具类槽位：这一发扣下去的键会原样退回池子（getRemainingKey 返回同一个键），整段只需一份起步量。 */
    private static void addAllPositive(java.util.Map<AEKey, Long> supply, @Nullable KeyCounter added) {
        if (added == null) {
            return;
        }
        for (var entry : added) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
                supply.merge(entry.getKey(), entry.getLongValue(), ClampedMath::addUp);
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

    InsertResult insertAndGetResult(AEKey what, long amount, Actionable mode) {
        return insertInternal(what, amount, mode);
    }

    long insertAndGetAccountedAmount(
            @Nullable AEKey what,
            long amount,
            Actionable type
    ) {
        return insertInternal(what, amount, type).accountedAmount();
    }

    /** 登记等待量后绑定真实交付口；机器合并批次时按同网实际欠量分配，不依赖批次计划清单。 */
    void bindDeclaredOutputReceiver(@Nullable DeclaredBatchReceipt receipt) {
        if (!(receipt instanceof DeclaredBatchOutputClaim claim)) {
            return;
        }
        var submittedGrid = lane.cluster().grid();
        claim.bindOutputReceiver((sourceGrid, what, available) -> {
            var context = lane.cluster().gridContext();
            if (sourceGrid != submittedGrid || lane.cluster().grid() != submittedGrid
                    || context == null || context.grid() != submittedGrid) {
                return BigInteger.ZERO;
            }
            return context.claimDeclaredOutputs(what, available);
        });
    }

    /** 中间产物及手动订单终点直接入宽域池；有 requester 的终点保留既有交付口。 */
    BigInteger claimDeclaredOutput(AEKey what, BigInteger available) {
        if (!lane.cluster().canProcessJobs() || job == null || job.link.isCanceled() || routingStandaloneOutput) {
            return BigInteger.ZERO;
        }
        String returnPath = poolLedger.path("return-wide");
        try {
            if (!job.dyeableRecursivePlan && what.matches(job.finalOutput) && job.link.isStandalone()) {
                return claimDeclaredStandaloneOutput(job, (WideCraftingInventory) inventory,
                        what, available, this::postChange);
            }
            return claimDeclaredIntermediateOutput(job, (WideCraftingInventory) inventory,
                    what, available, this::postChange);
        } finally {
            poolLedger.restore(returnPath);
        }
    }

    /** 手动订单只收尚欠且未缓存在池中的终点；磁盘实收前不扣最终订单余量。 */
    static BigInteger claimDeclaredStandaloneOutput(ParallelExecutingCraftingJob job,
            WideCraftingInventory pool, AEKey what, BigInteger available, Consumer<AEKey> changed) {
        if (available.signum() <= 0 || job.dyeableRecursivePlan || !job.link.isStandalone()
                || job.link.isCanceled() || !what.matches(job.finalOutput)) return BigInteger.ZERO;
        var remaining = job.exactRemainingAmount == null ? BigInteger.valueOf(job.remainingAmount)
                : job.exactRemainingAmount.exactValue();
        var bufferedKey = ParallelBatchExtraction.resolvePoolKey(pool, what);
        var unbuffered = remaining.subtract(pool.amount(bufferedKey)).max(BigInteger.ZERO);
        return claimDeclaredBufferedOutput(job, pool, what, available.min(unbuffered), changed);
    }

    /** 按精确等待账转移实物；库存、进度、等待量与到账流水全程使用精确数量。 */
    static BigInteger claimDeclaredIntermediateOutput(ParallelExecutingCraftingJob job,
            WideCraftingInventory pool, AEKey what, BigInteger available, Consumer<AEKey> changed) {
        if (available.signum() <= 0 || (what.matches(job.finalOutput)
                && !isDyeableRecursiveInternalItem(job, what)
                && !(job.dyeableRecursivePlan && job.link != null && !job.link.isStandalone()))) {
            return BigInteger.ZERO;
        }
        return claimDeclaredBufferedOutput(job, pool, what, available, changed);
    }

    /** 按实际等待份额完成一次精确到账，所有调用者共用同一套库存与进度记账。 */
    private static BigInteger claimDeclaredBufferedOutput(ParallelExecutingCraftingJob job,
            WideCraftingInventory pool, AEKey what, BigInteger available, Consumer<AEKey> changed) {
        AEKey owedKey = ParallelBatchExtraction.resolvePoolKey(job.waitingFor, what);
        var waiting = (WideCraftingInventory) job.waitingFor;
        BigInteger accepted = waiting.extractExact(owedKey, available, Actionable.SIMULATE);
        if (accepted.signum() <= 0) {
            return BigInteger.ZERO;
        }
        pool.insertExact(what, accepted, Actionable.MODULATE);
        waiting.extractExact(owedKey, accepted, Actionable.MODULATE);
        job.accountReturned.add(what, accepted);
        job.timeTracker.decrementItems(PlanAmount.of(accepted), what.getType());
        if (job.dyeableRecursivePlan && job.isFinalOutputKey(what)
                && !isDyeableRecursiveInternalItem(job, what)) {
            job.addFinalOutputDelivered(what, accepted);
            job.recordFinalOutputAccepted(PlanAmount.of(accepted));
        }
        changed.accept(what);
        // Useless 扣完真实队列并送达终局后，由正常 tick 收尾，避免交付回调内重入 finishJob。
        return accepted;
    }

    /**
     * 实物交回到账口那一步：先问产出缓冲还欠这一枚几枚，夹住、认账、摘账。
     *
     * <p><b>问账与摘账伸的那一枚必须是缓冲真装着的那一枚</b>（{@link
     * ParallelBatchExtraction#resolvePoolKey}——与池账那几条同一个解析，不写第二份）：
     * {@code waitingFor} 那一格是{@link #reserveOne} 按<b>样板申报</b>的键实例挂上去的，
     * 而这一脚的 {@code what} 是 AE2 交货时那一份（{@code CraftingServiceStorage.insert} →
     * {@code insertIntoCpus} → 本方法，另一条是 {@code ParallelActiveCpuHandle#insert}）——
     * 第三方自定义键把主键存成值对象时两者 {@code equals} 相等而引用不等，AE2
     * {@code KeyCounter} 的主索引按引用分格（{@code KeyCounter.java:48}，取数 {@code :124-131}）
     * ⇒ 裸读恒 0 ⇒ 这一笔回补被判成 {@code InsertResult.EMPTY}（一枚不收）。
     * 索引那半边（{@code ParallelCpuWaitingIndex#lanesByKey} 是 {@code HashMap}，按 {@code equals}）
     * 本来就认得出这一枚，"找得到 lane 却认不出账"就是这一族的形状。
     *
     * <p>只放开问账那一脚而 {@code MODULATE} 仍按申报键伸手是不成立的：挂账清不掉 ⇒
     * {@link #isDyeableRecursiveJobComplete} 那一格的 {@code waitingFor.list.isEmpty()} 恒不成立
     * ⇒ 作业永不收工，池里那份实物（含环起步借的那颗种）跟着卡住。判据形状（{@code <= 0} 即
     * {@code EMPTY}、超出欠量的部分夹回、三本流水账仍按交来的那一枚记）一字未动。
     */
    /** 手动订单转交网络时，禁止同一 CPU 再拦截自己的输出。 */
    private boolean routingStandaloneOutput;

    private InsertResult insertInternal(
            @Nullable AEKey what,
            long amount,
            Actionable type
    ) {
        if (what == null || amount <= 0L || job == null || routingStandaloneOutput) {
            return InsertResult.EMPTY;
        }

        long initial = insertInitialItems(what, amount, type);
        InsertResult output = insertExpectedOutput(what, amount - initial, type);
        return new InsertResult(initial + output.insertedAmount(), initial + output.accountedAmount());
    }

    /** 外部补料只销初始缺口，不冒充已派发产物，也不推进产出计时或根订单进度。 */
    private long insertInitialItems(AEKey what, long amount, Actionable mode) {
        if (!acceptsInitialItems()) return 0L;
        AEKey owedKey = ParallelBatchExtraction.resolvePoolKey(job.initialItemsRemaining, what);
        long accepted = job.initialItemsRemaining.extract(owedKey, amount, Actionable.SIMULATE);
        if (accepted > 0L && mode == Actionable.MODULATE) {
            // 先销缺口再通知库存变化，索引与后续网络预取都立即看到剩余真值。
            job.initialItemsRemaining.extract(owedKey, accepted, Actionable.MODULATE);
            inventory.insert(what, accepted, Actionable.MODULATE);
        }
        return accepted;
    }

    private boolean acceptsInitialItems() {
        return job != null && !job.cancellationRequested && !job.completionRequested
                && (job.link == null || !job.link.isCanceled());
    }

    private InsertResult insertExpectedOutput(AEKey what, long amount, Actionable type) {
        if (job == null || amount <= 0L) return InsertResult.EMPTY;

        AEKey owedKey = ParallelBatchExtraction.resolvePoolKey(job.waitingFor, what);
        long waitingFor = job.waitingFor.extract(owedKey, amount, Actionable.SIMULATE);
        if (waitingFor <= 0L) {
            return InsertResult.EMPTY;
        }
        if (amount > waitingFor) {
            amount = waitingFor;
        }

        if (job.cancellationRequested && what.matches(job.finalOutput)
                && !isDyeableRecursiveInternalItem(job, what)) return InsertResult.EMPTY;

        if (!job.dyeableRecursivePlan && what.matches(job.finalOutput)) {
            boolean standalone = job.link.isStandalone();
            var remaining = job.exactRemainingAmount == null ? BigInteger.valueOf(job.remainingAmount)
                    : job.exactRemainingAmount.exactValue();
            if (standalone) {
                var bufferedKey = ParallelBatchExtraction.resolvePoolKey(inventory, what);
                remaining = remaining.subtract(WideCraftingInventory.amount(inventory, bufferedKey)).max(BigInteger.ZERO);
            }
            amount = Math.min(amount, remaining.min(WideCeilings.LONG_ACCOUNT).longValueExact());
            long accepted = standalone ? amount : job.link.insert(what, amount, type);
            if (type == Actionable.MODULATE && accepted > 0) {
                if (standalone) inventory.insert(what, accepted, Actionable.MODULATE);
                job.waitingFor.extract(owedKey, accepted, Actionable.MODULATE);
                ParallelExecutingCraftingJob.decrementItems(job.timeTracker, accepted, what.getType());
                job.accountReturned.add(what, accepted);
                if (!standalone) job.recordFinalOutputAccepted(accepted);
                postChange(what);
                if (job.remainingAmount == 0) finishJob(true);
            }
            return new InsertResult(accepted, accepted);
        }

        if (type == Actionable.MODULATE) {
            ParallelExecutingCraftingJob.decrementItems(job.timeTracker, amount, what.getType());
            job.waitingFor.extract(owedKey, amount, Actionable.MODULATE);
            job.accountReturned.add(what, amount);
            postChange(what);
        }

        long inserted = amount;
        String returnPath = poolLedger.path("return");
        try {
            if (isDyeableRecursiveInternalItem(job, what)) {
                if (type == Actionable.MODULATE) {
                    inventory.insert(what, amount, Actionable.MODULATE);
                    finishDyeableRecursiveJobIfComplete();
                }
            } else if (what.matches(job.finalOutput)) {
                inserted = insertFinalOutput(what, amount, type);
                if (type == Actionable.MODULATE) {
                    if (job.dyeableRecursivePlan) {
                        // 递归计划这一支交到入账口就是交出去一笔：先记账再判收工。原先它只调
                        // finishDyeableRecursiveJobIfComplete()，而那条判据只看任务表清没清空——
                        // 尾巴排不下去的作业于是永远不 finishJob，库存连补种那颗种一起卡在 CPU 里。
                        job.addFinalOutputDelivered(what, inserted);
                        job.recordFinalOutputAccepted(inserted);
                        finishDyeableRecursiveJobIfComplete();
                    } else {
                        job.recordFinalOutputAccepted(inserted);
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
        } finally {
            poolLedger.restore(returnPath);
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

    /** 在网络插入回调之外排出手动订单缓冲；AE2 网络禁止嵌套 insert。 */
    void drainStandaloneFinalOutput() {
        var current = job;
        if (current == null || current.dyeableRecursivePlan || !current.link.isStandalone()) return;
        var grid = lane.cluster().grid();
        if (grid == null || current.finalOutput == null) return;
        var what = ParallelBatchExtraction.resolvePoolKey(inventory, current.finalOutput.what());
        routingStandaloneOutput = true;
        try {
            for (int chunk = 0; chunk < 64 && job == current; chunk++) {
                BigInteger remaining = current.exactRemainingAmount == null
                        ? BigInteger.valueOf(current.remainingAmount) : current.exactRemainingAmount.exactValue();
                var storage = grid.getStorageService().getInventory();
                BigInteger limit = storage instanceof git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion
                        ? remaining : remaining.min(WideCeilings.LONG_ACCOUNT);
                BigInteger offered = WideCraftingInventory.amount(inventory, what).min(limit);
                if (offered.signum() <= 0) return;
                BigInteger accepted = git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion.insert(
                        storage, what, offered, Actionable.MODULATE, lane.cluster().source());
                if (accepted.signum() <= 0) return;
                ((WideCraftingInventory) inventory).extractExact(what, accepted, Actionable.MODULATE);
                current.recordFinalOutputAccepted(PlanAmount.of(accepted));
                postChange(what);
                if (current.remainingAmount == 0) { finishJob(true); return; }
                // 网络虽支持精确路由，实际接收盘可能只吃一个 long 窗口；满窗口按原预算继续交。
                if (accepted.compareTo(offered.min(WideCeilings.LONG_ACCOUNT)) < 0) return;
            }
        } finally {
            routingStandaloneOutput = false;
        }
    }

    private void finishDyeableRecursiveJobIfComplete() {
        if (job != null && job.cancellationRequested) return;
        if (!isDyeableRecursiveJobComplete(job)) {
            return;
        }
        job.remainingAmount = 0L;
        if (job.exactRemainingAmount != null) job.exactRemainingAmount = PlanAmount.ZERO;
        finishJob(true);
    }

    static boolean isDyeableRecursiveJobComplete(@Nullable ParallelExecutingCraftingJob job) {
        return job != null
                && job.dyeableRecursivePlan
                && !job.batchObligations.hasAnyPending()
                && (finalOutputFullyDelivered(job)
                        || (job.tasks.isEmpty() && job.waitingFor.list.isEmpty()
                                && job.initialItemsRemaining.list.isEmpty()
                                && declaredNetworkDeliveriesSettled(job)));
    }

    /** 非递归直投订单只在真实交割完成、任务和所有待交付账清空后结束。 */
    static boolean isDeclaredNetworkJobComplete(@Nullable ParallelExecutingCraftingJob job) {
        return job != null && !job.dyeableRecursivePlan && job.remainingAmount == 0
                && job.tasks.isEmpty() && job.waitingFor.list.isEmpty()
                && job.initialItemsRemaining.list.isEmpty() && !job.batchObligations.hasAnyPending()
                && declaredNetworkDeliveriesSettled(job);
    }

    /**
     * 申报批<b>直投网络</b>那一截交割干净了没有——收工判据第二臂唯一的"够不够"，全仓就这一处。
     *
     * <p><b>#227 K2：这一臂读的是真差值 {@code planNet − 已交割 == 0}</b>
     * （{@code ParallelExecutingCraftingJob#declaredNetworkUndeliveredTotal}，逐键走
     * {@code #declaredNetworkUndelivered} 那<b>同一道</b>算式相加——不新建账本、不摆第二把尺）。
     * 改前它读的是 {@code DeclaredBatchTerminalLedger#hasUndeliveredDeclaredOutput()}——一把
     * <b>只扫 {@code inflight} 那张表</b>的布尔尺，问"还有没有一笔挂着待交割"，一个字都不减这本账。
     * 那把尺会被<b>表的尺寸</b>骗：账上 {@code planNet} 记满、在飞表却是空的（登记没成、条目已出表之后
     * 终局才到、或回执侧静默收手那一档），它恒真 ⇒ "报满零实物"照样判成 {@code FINISHED}——
     * 实机 2026-10-02 19:55 那一跑读到的正是这一格（{@code inflightBatches=0 undelivered=NONE
     * pendingDelivery=NONE} 而 {@code ret=0}）。
     *
     * <p>凭据没变：{@code SUCCESS} 终局本身带数量含义（2.3.9 javap：{@code notifyCpuBatchCompleted}
     * 只在 {@code PendingCraftingOutput.isEmpty()} 时发，而 {@code isEmpty} 由
     * {@code attributeDeliveredOutputs} 按真插进网络的量逐批 {@code consume} 决定）⇒ 交割账只在
     * {@code SUCCESS} 到齐那刻记已交割。负向终局那一笔<b>不转已交割</b>，而是把当初划进 {@code planNet}
     * 的那一截<b>原样退掉</b>（{@code ParallelExecutingCraftingJob#revokeDeclaredBatchPlannedNetworkOutputs}，
     * 随同一条回滚出口一起落）——不退就是差值永远非零，一笔 {@code CANCELLED} 把整张作业永久钉在
     * "未收工"上，即 {@code #209}／{@code 4816673} 那一型被回退过的病。
     *
     * <p>与既有实物路径逐字同效：接 bigint 通道之前没有申报批直投网络这一型，{@code planNet} 恒空 ⇒
     * 这一格恒真 ⇒ 第二臂退化成改前的"任务表空 且 产出缓冲空"。也不与 {@code waitingFor} 重复计：
     * 走实物回池那一截本就挂在 {@code waitingFor} 上、由 {@code waitingFor.list.isEmpty()} 拦，
     * 这一格只拦"不经 {@code waitingFor}、直投网络"那一截。
     *
     * <p>{@code #213} 边界不破：这一格只拦<b>收工</b>（作业级），一个字都不进 {@code taskMayLeaveTable}
     * （样板级出表）——永不终局那一型照样出表、只在 {@code inflightBatches=} 点名。
     */
    static boolean declaredNetworkDeliveriesSettled(ParallelExecutingCraftingJob job) {
        return job.declaredNetworkUndeliveredTotal().signum() == 0L;
    }

    /**
     * 递归计划的<b>交付判据</b>：终点产物交齐订单量就该收工，<b>不等</b>任务表自己清空。
     *
     * <p>为什么这条不是"卡住就放弃"：它读的是一件<b>已经发生的事实</b>——这一单要的产物已经<b>实物</b>
     * 交到作业的入账口（{@link #insertInternal} 认成终点产物那一支，AE2 真把料交进 CPU 账口的那一刻），
     * 量对得上订单。没有超时、没有自动终止、没有砍需求：订单量算不出来（{@link ParallelExecutingCraftingJob#finalOutputOrderAmount}
     * 交回 {@code null}）或<b>任一已接单批次</b>还欠着宿主那头的材料
     * （{@link #finalOutputObligationsSettled}）都不判收工。
     * 宿主自报"不经池子直接交出"的那一截<b>不算</b>在这里（分流口 {@code reserveOne} 只摘
     * {@code waitingFor}、只记预期账，不记交付——那条通道里没有实物回执，见该方法注释）。
     *
     * <p>两侧同一把尺：交付账是宽域账（{@code WideKeyCounter}），订单量读的也是宽域真值——
     * {@code long} 出口读出 {@link Long#MAX_VALUE} 那道哨兵时，订单量由计划随件出门的宽域份数账
     * （终点份数 × 每发终点量，<b>加上</b>不经任务表直接出门的那一截终点键）反推
     * （{@link ParallelExecutingCraftingJob#finalOutputOrderAmount}），
     * 比较全程在宽域上做，任何一侧都不在比较前窄化。
     *
     * <p>收工走的是既有那条 {@link #finishJob(boolean)} 成功腿：先 {@code flushDyeableRecursiveFinalOutput}
     * 把池子里那份终点产物交给 requester，再由 {@code storeItems()} 把残池（含环起步借的那颗种——
     * 它从来不是可花库存，#43/#45 的口径）原路投回网络。判据换的是<b>什么时候</b>收尾，收尾该结的账
     * 一笔都不少。requester 每笔接口仍使用 long，但未交付余额按宽域保存；拒收或部分接收时
     * 保留作业和库存，后续 tick 继续交付，全部接收后才标记完成。
     */
    static boolean finalOutputFullyDelivered(@Nullable ParallelExecutingCraftingJob job) {
        return finalOutputQuantityDelivered(job) && finalOutputObligationsSettled(job);
    }

    /** 最终产物实收量已齐，仅表示不再派发；已接单批次的材料结算仍必须继续。 */
    private static boolean finalOutputQuantityDelivered(@Nullable ParallelExecutingCraftingJob job) {
        if (job == null) {
            return false;
        }
        PlanAmount order = job.finalOutputOrderAmount();
        return order != null
                && job.finalOutputDeliveredAmount().compareTo(order) >= 0;
    }

    /** 最终交货不能免除已经接单的中间批次材料；所有已声明义务都须结清。 */
    static boolean finalOutputObligationsSettled(ParallelExecutingCraftingJob job) {
        return !job.hasUnsettledBatchObligations();
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
        if (!success) {
            finishedJob.cancellationRequested = true;
            finishedJob.link.cancel();
            if (requesterLink != null) requesterLink.cancel();
        } else if (!finishedJob.cancellationRequested) {
            finishedJob.completionRequested = true;
        }
        success = !finishedJob.cancellationRequested;
        if (finishedJob.hasUnsettledBatchObligations()
                || (success && !flushDyeableRecursiveFinalOutput(finishedJob))) {
            if (lane.cluster() != null) lane.cluster().refreshLaneState(lane);
            return;
        }
        if (success) {
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
        taskResumeIndex = 0;
        taskResumePattern = null;
        this.requesterLink = null;
        this.storeItems();
    }

    /**
     * 把池子里的终点产物交给收货方（递归计划收尾那一步）。
     *
     * <p><b>伸手的那一枚必须是池账真装着的那一枚</b>（{@link ParallelBatchExtraction#resolvePoolKey}，
     * 与停滞行 {@code 池内=}／销账那几条同一个解析，不写第二份）：终点键出自计划的
     * {@code finalOutput}，而池子里那一格是<b>交付那一刻</b>的键实例——第三方自定义键把主键存成
     * 值对象时两者 {@code equals} 相等而引用不等，AE2 {@code KeyCounter} 的主索引按引用分格
     * （{@code KeyCounter.java:48}，取数 {@code :124-131}）⇒ 按申报键裸读恒 0，这一脚的
     * {@code available <= 0} 就把终点产物（含环起步借的那颗种）永远留在 CPU 库存里。
     *
     * <p>读与扣走同一枚不是顺手：只放开读数而 {@code MODULATE} 那一脚仍按申报键伸手，池子扣不走
     * 而收货方照收 ⇒ 收尾的 {@link #storeItems()} 把同一份料再投回网络＝凭空多出一份。
     * 只有真实接收的数量才从库存和交付余额同时扣除；部分或拒绝接收会保留余额供后续 tick。
     *
     * <p>包级可见只为让用例打在产线这两脚上（同 {@link #newLedgerBackedPool} 的理由）。
     */
    boolean flushDyeableRecursiveFinalOutput(ParallelExecutingCraftingJob finishedJob) {
        if (finishedJob == null || !finishedJob.dyeableRecursivePlan
                || finishedJob.finalOutput == null || finishedJob.finalOutput.what() == null
                || finishedJob.link == null || finishedJob.link.isStandalone()) {
            return true;
        }
        if (finishedJob.requesterDeliveryRemaining == null) {
            PlanAmount order = finishedJob.finalOutputOrderAmount();
            if (order == null) return false;
            finishedJob.requesterDeliveryRemaining = order;
        }
        AEKey poolKey = ParallelBatchExtraction.resolvePoolKey(inventory, finishedJob.finalOutput.what());
        for (int chunk = 0; chunk < 64 && finishedJob.requesterDeliveryRemaining.signum() > 0; chunk++) {
            long offer = finishedJob.requesterDeliveryRemaining.exactValue()
                    .min(WideCeilings.LONG_ACCOUNT).longValueExact();
            long available = inventory.extract(poolKey, offer, Actionable.SIMULATE);
            if (available <= 0 && finishedJob.completionRequested) {
                // 旧作业可能已把终点直投网络。只在收尾且池为空时取回真实存量；
                // requester 拒收的实物留在池内，下一 tick/重载后不会重复提取。
                var grid = lane.cluster().grid();
                if (grid != null) {
                    BigInteger recovered = git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                            grid.getStorageService().getInventory(), finishedJob.finalOutput.what(),
                            BigInteger.valueOf(offer), Actionable.MODULATE, lane.cluster().source());
                    if (recovered.signum() > 0) {
                        ((WideCraftingInventory) inventory).insertExact(
                                finishedJob.finalOutput.what(), recovered, Actionable.MODULATE);
                        poolKey = ParallelBatchExtraction.resolvePoolKey(inventory, finishedJob.finalOutput.what());
                        available = inventory.extract(poolKey, offer, Actionable.SIMULATE);
                    }
                }
            }
            if (available <= 0) return false;
            long accepted = Math.max(0L, Math.min(available,
                    finishedJob.link.insert(poolKey, available, Actionable.MODULATE)));
            if (accepted <= 0) return false;
            inventory.extract(poolKey, accepted, Actionable.MODULATE);
            finishedJob.requesterDeliveryRemaining = finishedJob.requesterDeliveryRemaining
                    .subtract(PlanAmount.of(accepted));
            if (accepted < available) break;
        }
        return finishedJob.requesterDeliveryRemaining.signum() == 0;
    }

    /**
     * 残池回网：作业不在跑时把 CPU 池子里剩下的<b>真料</b>原路投回网络。
     *
     * <p>这里<b>没有</b>"凭空多出一份料"的敞口（旧注释把它写成已知敞口，前提是"就地注入的那一截
     * 会跟着残池投回网络"）：无限源那份量从来只记在取料点的 holder 上（{@code Batch#mintedUnits}），
     * 不经池子；网络回补那条腿（{@code ParallelBatchExtraction#refillObligationBatch}）也只搬
     * 网络真交出的枚数、认下无限源即收手。注池那两条退路都已删掉，配给表形回收口成编译期非空，
     * 于是池子里躺着的一定是从网络真提走的东西——投回去就是归还，不是复制。
     *
     * <p>为什么这里仍不带注销账本：池子里的料是可替换的，"哪几枚属于哪一笔账"在这里没有答案。
     * 按某笔总量注销会把真投进来的同键料一起毁掉（少料 ⇒ 作业又跑不完），而这一条路根本不需要
     * 答案：按精确值分轮交出去（{@link #storeWideInventory}），交不掉的留在池里下一轮再交。
     */
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
        if (inventory instanceof WideCraftingInventory wide
                && storage instanceof git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion) {
            for (var what : java.util.List.copyOf(wide.list.keySet())) {
                BigInteger accepted = git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion.insert(
                        storage, what, wide.amount(what), Actionable.MODULATE, lane.cluster().source());
                if (accepted.signum() > 0) {
                    wide.extractExact(what, accepted, Actionable.MODULATE);
                    postChange(what);
                }
            }
            return;
        }
        if (inventory instanceof WideCraftingInventory wide) {
            storeWideInventory(wide, (what, offer) -> {
                postChange(what);
                return storage.insert(what, offer, Actionable.MODULATE, lane.cluster().source());
            });
            return;
        }
        // 兜底：非宽域池（本文件不构造这种池）逐字保留老口径——主表单轮报多少交多少。
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

    /**
     * 分轮回池交付（neoeco {@code drain} 的同一手法）：一轮每键最多交
     * {@code min(精确值, Long.MAX)} —— 退网口 {@code MEStorage.insert} 只吃得下 {@code long}。
     *
     * <p>三条硬规则：
     * <ul>
     *   <li>本轮可交量从 {@link WideCraftingInventory#extractExact} 的 SIMULATE 读出，
     *       是精确值夹天花板，不是拿主表饱和哨兵当真数；</li>
     *   <li>扣账<b>只按网络实收</b>：{@code inserter} 报回几枚就从宽域台账抽几枚，
     *       申报出去的 offer 永不进扣账（再被接收器报数与 available 各夹一道，多报也扣不穿）；</li>
     *   <li>拒收或失败（实收 ≤0）一分不扣：键、主表条目与溢出侧表原样留在池里，
     *       下一轮（job 为空时每 tick 的 {@link #storeItems()}，收尾后残池的再入池再交）
     *       继续按精确值分轮交，同一键跨轮可续，直到精确值清零、条目整条撤走。</li>
     * </ul>
     *
     * <p>取数只经 {@code extractExact} 这一张嘴，不再对 {@code list} 条目做
     * {@code entry.setValue(...)} 直改——那会让主表与侧表脱钩，把越界那一截凭空留下或抹掉。
     * 键集取主表快照：宽域不变式保证侧表键在主表恒挂 {@code Long.MAX} 行，
     * 主表快照即全量键集，一轮不漏。
     */
    static void storeWideInventory(WideCraftingInventory pool, WidePoolInserter inserter) {
        for (AEKey what : java.util.List.copyOf(pool.list.keySet())) {
            if (what == null) {
                continue;
            }
            BigInteger offer = pool.extractExact(what, WideCeilings.LONG_ACCOUNT, Actionable.SIMULATE);
            if (offer.signum() <= 0L) {
                continue;
            }
            long accepted = inserter.insert(what, offer.longValueExact());
            if (accepted > 0L) {
                pool.extractExact(
                        what,
                        BigInteger.valueOf(Math.min(accepted, offer.longValueExact())),
                        Actionable.MODULATE
                );
            }
        }
    }

    /** 分轮交付的退网口：交出这一轮这一键的可交量，返回网络<b>实收</b>枚数（≤0 即拒收）。 */
    @FunctionalInterface
    interface WidePoolInserter {
        long insert(AEKey what, long offer);
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
            if (!suspended && job.hasUnresolvedTasks()) {
                AELog.error("并行 CPU 无法继续：存在未解码的存档任务，请先恢复对应样板支持");
                return;
            }
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
        AEKey outputKey = ParallelBatchExtraction.resolvePoolKey(job.waitingFor, template);
        long output = job.waitingFor.extract(outputKey, Long.MAX_VALUE, Actionable.SIMULATE);
        if (!acceptsInitialItems() || output == Long.MAX_VALUE) return output;
        AEKey initialKey = ParallelBatchExtraction.resolvePoolKey(job.initialItemsRemaining, template);
        return output + job.initialItemsRemaining.extract(
                initialKey, Long.MAX_VALUE - output, Actionable.SIMULATE);
    }

    void getAllWaitingFor(Set<AEKey> waitingFor) {
        if (job == null || waitingFor == null) {
            return;
        }
        for (var entry : getWaitingStacks()) {
            waitingFor.add(entry.getKey());
        }
    }

    boolean hasWaitingFor() {
        if (job == null) {
            return false;
        }
        if (acceptsInitialItems() && !job.initialItemsRemaining.list.isEmpty()) return true;
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
                    // 只显示用：按精确剩余算完再饱和回 long（越界任务不把假数摆到界面上）
                    var product = PlanAmount.of(output.amount())
                            .multiply(task.getValue().exactRemaining());
                    count = ClampedMath.addUp(count, Ae2Amounts.toLong(product));
                }
            }
        }
        return count;
    }

    Iterable<it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey>> getWaitingStacks() {
        if (job == null) return java.util.List.of();
        if (!acceptsInitialItems() || job.initialItemsRemaining.list.isEmpty()) return job.waitingFor.list;
        // 两本账只在接收索引边界合并；不改变 waitingFor 的产出语义。
        var totals = new java.util.HashMap<AEKey, BigInteger>();
        for (var entry : job.waitingFor.list)
            mergeExact(totals, entry.getKey(), WideCraftingInventory.amount(job.waitingFor, entry.getKey()));
        for (var entry : job.initialItemsRemaining.list)
            mergeExact(totals, entry.getKey(), job.initialItemsRemaining.amount(entry.getKey()));
        var snapshot = new KeyCounter();
        writeSaturated(snapshot, totals);
        return snapshot;
    }

    void getAllItems(KeyCounter out) {
        getAllItems(inventory, job, out);
    }

    /**
     * 状态栏聚合：三个来源（池、产出缓冲、未发任务产出）逐键走<b>精确读口</b>，
     * 出口对 AE2 仍交饱和 {@code long}——唯一调用方是 {@code createMenuStatus}
     * （菜单同步，逐键 {@code long} 口径）。
     *
     * <p>与旧写法（{@code out.addAll(...)} + {@code out.add(...)} 裸加）的差别只在跨源相加：
     * {@code KeyCounter.add} 底层 fastutil 是回绕不是饱和，"池里 MAX + 缓冲里 MAX"会加绕成负，
     * 把假负数摆进界面；这里逐键合计按饱和夹进 {@link Long#MAX_VALUE}，报小不报大且永不回绕。
     * 单源、单键在 {@code long} 域内的读数与旧写法逐位一致。
     */
    static void getAllItems(
            ListCraftingInventory pool,
            @Nullable ParallelExecutingCraftingJob job,
            KeyCounter out
    ) {
        if (out == null) {
            return;
        }
        var totals = new java.util.HashMap<AEKey, BigInteger>();
        if (pool != null) {
            for (var entry : pool.list) {
                mergeExact(totals, entry.getKey(), WideCraftingInventory.amount(pool, entry.getKey()));
            }
        }
        if (job == null) {
            writeSaturated(out, totals);
            return;
        }
        for (var entry : job.waitingFor.list) {
            mergeExact(totals, entry.getKey(),
                    WideCraftingInventory.amount(job.waitingFor, entry.getKey()));
        }
        if (!job.cancellationRequested && !job.completionRequested) {
            for (var entry : job.initialItemsRemaining.list)
                mergeExact(totals, entry.getKey(), job.initialItemsRemaining.amount(entry.getKey()));
        }
        for (var task : job.tasks.entrySet()) {
            for (var output : task.getKey().getOutputs()) {
                // 状态栏读数：份数与单发产出先按精确值相乘，出口统一饱和，
                // 绝不能用裸乘法把回绕的负数摆进表里。
                mergeExact(totals, output.what(), PlanAmount.of(output.amount())
                        .multiply(task.getValue().exactRemaining()).exactValue());
            }
        }
        writeSaturated(out, totals);
    }

    private static void mergeExact(
            java.util.Map<AEKey, BigInteger> totals, @Nullable AEKey key, @Nullable BigInteger amount
    ) {
        if (key == null || amount == null || amount.signum() <= 0L) {
            return;
        }
        totals.merge(key, amount, BigInteger::add);
    }

    /** 使用与原生状态条目一致的分量，先精确乘加，再交给可选显示层。 */
    static git.chexson.chexsonsaeutils.crafting.contract.CraftingCpuAmounts exactMenuAmounts(
            @Nullable ListCraftingInventory pool, @Nullable ParallelExecutingCraftingJob job) {
        var stored = new java.util.HashMap<AEKey, BigInteger>();
        var active = new java.util.HashMap<AEKey, BigInteger>();
        var pending = new java.util.HashMap<AEKey, BigInteger>();
        if (pool != null) for (var entry : pool.list)
            mergeExact(stored, entry.getKey(), WideCraftingInventory.amount(pool, entry.getKey()));
        if (job != null) {
            for (var entry : job.waitingFor.list)
                mergeExact(active, entry.getKey(), WideCraftingInventory.amount(job.waitingFor, entry.getKey()));
            if (!job.cancellationRequested && !job.completionRequested) {
                for (var entry : job.initialItemsRemaining.list)
                    mergeExact(pending, entry.getKey(), job.initialItemsRemaining.amount(entry.getKey()));
            }
            for (var task : job.tasks.entrySet()) for (var output : task.getKey().getOutputs())
                mergeExact(pending, output.what(), PlanAmount.of(output.amount())
                        .multiply(task.getValue().exactRemaining()).exactValue());
        }
        return new git.chexson.chexsonsaeutils.crafting.contract.CraftingCpuAmounts(stored, active, pending);
    }

    git.chexson.chexsonsaeutils.crafting.contract.CraftingCpuAmounts exactMenuAmounts() {
        return exactMenuAmounts(inventory, job);
    }

    BigInteger exactOrderRemaining() {
        if (job == null) return BigInteger.ZERO;
        if (job.requesterDeliveryRemaining != null) return job.requesterDeliveryRemaining.exactValue();
        return job.exactRemainingAmount == null
                ? BigInteger.valueOf(job.remainingAmount) : job.exactRemainingAmount.exactValue();
    }

    private static void writeSaturated(KeyCounter out, java.util.Map<AEKey, BigInteger> totals) {
        for (var entry : totals.entrySet()) {
            long capped = Ae2Amounts.toLong(PlanAmount.of(entry.getValue()));
            out.set(entry.getKey(), ClampedMath.addUp(out.get(entry.getKey()), capped));
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

    /** CPU 库存快照：按键汇总正量条目，供停滞探针在同一瞬间读同一份池子。 */
    private static java.util.Map<AEKey, Long> snapshotPool(ListCraftingInventory inventory) {
        var pool = new java.util.LinkedHashMap<AEKey, Long>();
        for (var entry : inventory.list) {
            if (entry == null || entry.getKey() == null || entry.getLongValue() <= 0L) {
                continue;
            }
            pool.merge(entry.getKey(), entry.getLongValue(), ClampedMath::addUp);
        }
        return pool;
    }

    /**
     * CPU 库存的<b>精确</b>快照：溢出键交出侧表加总的真值，而不是主表那个饱和 {@code Long.MAX}。
     *
     * <p>只喂诊断渲染（停滞日志的 {@code inv=} 段）；缺料扫描、配给封顶与 {@code stored=} 对账
     * 仍读 {@link #snapshotPool} 的饱和 {@code long} 口径——那是与 AE2 判据同轴的读数，
     * 换精确值会把"池里现量够不够这一批"的判定拖出 {@code long} 域。
     * 键集取主表遍历：宽域不变式保证侧表键在主表恒挂非零行，主表即全量键集。
     */
    static java.util.Map<AEKey, BigInteger> snapshotPoolExact(ListCraftingInventory inventory) {
        var pool = new java.util.LinkedHashMap<AEKey, BigInteger>();
        for (var entry : inventory.list) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            BigInteger exact = WideCraftingInventory.amount(inventory, entry.getKey());
            if (exact.signum() > 0L) {
                pool.merge(entry.getKey(), exact, BigInteger::add);
            }
        }
        return pool;
    }

    private static long poolAmount(java.util.Map<AEKey, Long> pool, AEKey key) {
        return key == null ? 0L : pool.getOrDefault(key, 0L);
    }

    /**
     * CPU 库存摘要：先分清"池子被抽干"与"池子里是全错的键"。
     *
     * <p>只按量取前 6 个键，避免上万种库存刷爆一行日志。
     *
     * <p><b>一格一 token</b>：{@code kinds}/{@code total}/{@code top} 与每个键的现量都用 {@code ,}
     * 连（不带空白），键走 {@link #describeKey} 那一个折点——AE2 的 {@code toString()} 带组件时的
     * 空白只在那一处折成 {@code "_"}。旧形状 {@code "kinds=2, total=5, top=<键> x 3"} 按空白切成
     * 6 段，per100r 那一行就分不清"这是库存的一格"还是"六格"。
     */
    private static String describeInventory(java.util.Map<AEKey, Long> pool) {
        record InvRow(AEKey key, long amount) {
        }
        var rows = new java.util.ArrayList<InvRow>();
        long total = 0L;
        for (var entry : pool.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            rows.add(new InvRow(entry.getKey(), entry.getValue()));
            total = ClampedMath.addUp(total, entry.getValue());
        }
        if (rows.isEmpty()) {
            return "EMPTY";
        }
        rows.sort(java.util.Comparator.comparingLong(InvRow::amount).reversed());
        var described = new StringBuilder("kinds=")
                .append(rows.size())
                .append(",total=").append(total)
                .append(",top=");
        for (int i = 0; i < Math.min(6, rows.size()); i++) {
            if (i > 0) {
                described.append(',');
            }
            var row = rows.get(i);
            described.append(describeKey(row.key())).append(",x").append(row.amount());
        }
        return described.toString();
    }

    /**
     * 停滞日志 {@code inv=} 段的精确渲染：与 {@link #describeInventory} 同一形状
     * （kinds / total / top 前 6，子字段一律 {@code ,} 连、键走 {@link #describeKey} 折点），
     * 但溢出键的 {@code top} 与 {@code total} 打
     * {@link #snapshotPoolExact} 的精确十进制真值，不再把饱和 {@code Long.MAX} 摆成现量。
     */
    static String describeInventoryExact(java.util.Map<AEKey, BigInteger> pool) {
        record InvRow(AEKey key, BigInteger amount) {
        }
        var rows = new java.util.ArrayList<InvRow>();
        BigInteger total = BigInteger.ZERO;
        for (var entry : pool.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().signum() <= 0L) {
                continue;
            }
            rows.add(new InvRow(entry.getKey(), entry.getValue()));
            total = total.add(entry.getValue());
        }
        if (rows.isEmpty()) {
            return "EMPTY";
        }
        rows.sort(java.util.Comparator.comparing(InvRow::amount).reversed());
        var described = new StringBuilder("kinds=")
                .append(rows.size())
                .append(",total=").append(total)
                .append(",top=");
        for (int i = 0; i < Math.min(6, rows.size()); i++) {
            if (i > 0) {
                described.append(',');
            }
            var row = rows.get(i);
            described.append(describeKey(row.key())).append(",x").append(row.amount());
        }
        return described.toString();
    }

    /**
     * noCand 的配额读数点名：{@code -1} 与 {@code -2} 是两个不同的因，混成同族负码就修错地方。
     *
     * <ul>
     * <li>{@code -1}＝这一格<b>有可计数候选</b>，只是这张样板在这个键上没有配额口径
     * （续传作业 / 模具槽豁免：不夹）——要问的是"表为什么没建这一键"；</li>
     * <li>{@code -2}＝这一格<b>一个可计数候选都没递出来</b>（压根没有配给表可问也算这一型）——
     * 要问的是"候选宇宙为什么空"，与份额无关；</li>
     * <li>{@code 0}＝被配给夹死；正数＝现场量口径。</li>
     * </ul>
     *
     * <p><b>纯读数</b>：只把同一个数说成两种话，判据与取值口径一字未动。
     */
    static String describeNoCandQuota(long quota) {
        if (quota == -1L) {
            return "无配额口径(-1)";
        }
        if (quota == -2L) {
            return "没递出可计数候选(-2)";
        }
        return String.valueOf(quota);
    }

    /**
     * 样板<b>实例</b>身份标（纯读数）：{@code #i} + {@code System.identityHashCode(details)} 的十六进制。
     *
     * <p>要修的断点是"点名某张样板的那几格配不上同一张样板"：AE2 的样板实现一律按
     * {@code getDefinition()} 覆写 {@code equals}（{@code AECraftingPattern:142} 等四条同形），
     * 同一张样板物品摆两个实例在任何按样板记账的容器里就是<b>同一个键</b>；实机那一跑
     * {@code quotaBlind=} 的 {@code src=} 点的与 {@code noCandWhy=} 点的都是
     * {@code useless_mod:omniversal_pattern}，而它们是<b>两张不同的实例</b>——光看名字永远分不出
     * "这一格欠的料"与"那一张被跳过的样板"是不是同一张，归因就在这一跳断掉。
     *
     * <p>旧串里的 {@code #1625664262962646930102} 是 <b>plan 的号</b>，不是实例身份（见
     * {@code [chexsonsaeutils-plan]} 那一行的出处），拿它对不上任何一本按样板记的账，故不复用它。
     *
     * <p>形状：{@code #i<hex>#s<hex>}，<b>零空白</b>——粘在样板头/键头后面不许把一格劈成两段（32c44e1
     * 三条口径：格间只用空格、格内子字段用逗号、下划线只表示折掉的外来空格）。
     * 前段 {@code #i<hex>} 是实例哈希（同会话内区分两张同名实例），后段 {@code #s<hex>} 是
     * <b>跨会话稳定</b>的样板身份摘要（{@link #patternStableDigest}）：换一个 JVM 重启，实例哈希会变、
     * {@code #s} 摘要不变，于是"少掉的那张样板是谁"能跨轮/跨会话对上。摘要<b>只当指纹</b>，
     * 不参与任何判据，也不新增账本。
     */
    static String patternIdentityTag(@Nullable IPatternDetails details) {
        return git.chexson.chexsonsaeutils.crafting.plan.PatternIdentity.patternIdentityTag(details);
    }

    static String patternStableDigest(@Nullable IPatternDetails details) {
        return git.chexson.chexsonsaeutils.crafting.plan.PatternIdentity.patternStableDigest(details);
    }

    /**
     * {@code [chexsonsaeutils-batch]} 一行里的两格闸口读数（<b>纯读数</b>，不参与任何判据）：
     * {@code materialBackoff=} 与 {@code extractNull=}。
     *
     * <p>过去这两格只在停滞行（{@code [chexsonsaeutils-stall]}）里有，per100r 一个字都没 ⇒
     * "20t 的材料退避吃掉了多少轮"在批量行里判不出来（实机那一跑 {@code evaluated=30} 就是这样
     * 读不出来的：只看得见评估了 30 条，看不见其中多少条是被退避挡掉的）。
     *
     * <p>口径与停滞行<b>同一格</b>：取 {@link StallProbe#noteDetail} 已经记下的那一轮读数
     * （最近一个"没推出去"的轮次里，被缺料退避跳过的任务数 / 提取交回 {@code null} 的任务数），
     * <b>不新记账</b>、不跨窗累加——多加一本账就是下一次两个数对不上的入口。
     */
    static String describeBatchGateReadouts(long materialBackoff, long extractNull) {
        return " materialBackoff=" + materialBackoff + " extractNull=" + extractNull;
    }

    /**
     * {@code [chexsonsaeutils-batch]} 一行里的两格<b>在飞</b>读数（<b>纯读数</b>，不参与任何判据）：
     * {@code inflightBatches=} 与 {@code oldestInflightTicks=}。
     *
     * <p>过去这两格只在义务停滞行（{@code [chexsonsaeutils-declared]}）里有，而那一行只在
     * "一批都没交割且还欠着料"那一档才打（{@link #obligationStalled}）——于是<b>纯"申报出去没回执、
     * 又不欠料"</b>那一跑连一行读数都不留（{@code e105b10} 的回报自己承认的那一格：只剩桥侧
     * WARN/debug，看不见那一档）。批量行每 100 轮必打，把同一把尺搬过去就看得见。
     *
     * <p><b>不新建第三本账、也不新算任何一个数</b>：读的就是
     * {@link DeclaredBatchTerminalLedger#inflightBatchCount()} 与
     * {@link DeclaredBatchTerminalLedger#describeOldestInflight(long)} 那两格既有读数，与停滞行
     * 同格名、同字面、同一把尺——同一格在两行里不许有两种字面。
     *
     * <p>这一格照旧只点名、<b>不</b>闸门：出表判据 {@link #taskMayLeaveTable} 一个字都不读它，
     * 这里也不许加退避夹子或超时兜底（#209 定下的"不索要、不拦表、只点名"）。
     *
     * <p>一格一 token：这两格里<b>没有</b>第三方串——笔数是 {@code int}、龄是 {@code long}、
     * batchId 前缀是 UUID 的十六进制段，三者都切不出空白，所以这一处不需要格出口折 {@code "_"}；
     * "将来万一漂进一个空格"由 {@code per100r} 那条 census（段数==格名数）兜，不靠一个永不相干的
     * 折点自证。
     *
     * @param currentTick 龄的基准 tick：与停滞行同一口径，由调用方那一趟带进来，不在这里现查
     *                    {@code TickHandler}（离线用例要能钉住龄）
     */
    static String describeInflightBatchReadouts(DeclaredBatchTerminalLedger terminals,
            long currentTick) {
        return " inflightBatches=" + terminals.inflightBatchCount()
                + " oldestInflightTicks=" + terminals.describeOldestInflight(currentTick);
    }

    /**
     * {@code [chexsonsaeutils-batch]} 一行里的<b>申报批覆盖面</b>读数族（台账 #225 读数 1–3 与 5，
     * <b>纯读数</b>，不参与任何判据）：同一本 {@link DeclaredBatchTerminalLedger} 交出的累计快照
     * ＋ {@link DeclaredBatchReceipt} 那两支静默收手的全场累计 ＋ 机器回网队列那两格现读。
     *
     * <p><b>要解决的正是"三个不同来源的数凑不出一个判决"</b>：实机 2026-10-02 19:55 那一跑同时有
     * {@code UselessBigIntegerDeclaredHost[...push=81]}（宿主行、100 轮窗）、
     * {@code declared=113 obBatch=110}（批量行、100 轮窗）、全场 3 条 {@code 批次已终局(SUCCESS)}，
     * 而 {@code inflightBatches=0 undelivered=NONE pendingDelivery=NONE}。三把数各自来自不同的窗与
     * 不同的账，谁也对不平谁。这里把分档与静默口收成<b>一行十格、同一本账</b>：
     * <ul>
     *   <li>{@code declaredBigint[累计]=}：宿主型别是 {@code DeclaredBatchTerminalCarrier}
     *       （能把该笔 {@code cpuToken} 交回来、因而<b>可能</b>挂在飞台账）的申报批笔数；</li>
     *   <li>{@code declaredOther[累计]=}：别的申报宿主（天生挂不上台账，不是被谁夹掉的）；</li>
     *   <li>{@code inflightRegistered[累计]=}：{@code noteAdmitted} 真走进 {@code inflight.put} 的笔数
     *       ——这一格与前两格并排，才对得平：实机那一跑若 {@code declaredBigint=81} 而
     *       {@code inflightRegistered=3}，缺的 78 笔就在下面两格里；</li>
     *   <li>{@code noReceipt[累计]=}／{@code noBatchId[累计]=}：那两支<b>静默 return</b> 的累计笔数，
     *       各带<b>枚数合计</b>（这一批已被 {@code reserveExpectedWaiting} 从实物预期里摘走的待交割枚数，
     *       走既有宽域出口印精确原文）与最新一枚样板的身份标；</li>
     *   <li>{@code terminalSuccess[累计]=}／{@code terminalCancelled[累计]=}／
     *       {@code terminalOther[累计]=}：终局三档各自笔数；</li>
     *   <li>{@code terminalUnmatched[累计]=}：终局来了、这本台账的在飞表里却没有这一笔；</li>
     *   <li>{@code terminalDropped[全场JVM]=}：集成回执的终局接收口 那两支
     *       静默收手（没挂台账／id 对不上）。这一格是 <b>JVM 静态累计</b>、跨所有 lane 与作业，
     *       格名里就把覆盖面写死，<b>不许</b>被读成"这一条 lane 的笔数"。</li>
     *   <li>{@code queuePending[useless侧]=}／{@code queuePendingCov=}（读数 5）：机器那侧回网队列的
     *       条目数与上限、以及"问到几台／这轮碰到几台"。这两格<b>不是台账里的数</b>，是推送那
     *       一轮真碰过的机器身上现读的口（见能力会话的只读队列口）。</li>
     * </ul>
     *
     * <p><b>覆盖面</b>：标 {@code [累计]} 的八格是本台账自 {@code new} 起来的<b>全生命周期</b>累计
     * （不随 100 轮窗清零，与同行的 {@code declared=}/{@code obBatch=} 那两格<b>不是一个窗</b>，
     * 对账时按 {@code [累计]} 与 {@code 百窗} 分清）；标 {@code [全场JVM]} 的一格跨 lane 跨作业。
     * 静默口的"枚数合计"只统计<b>真走到那一支</b>的批次，读不到样板就印 {@code <none>}，不拿别的
     * 集合冒充。
     *
     * <p>一格一 token：笔数是 {@code long}、枚数是 BigInteger 原文、身份标 {@code #i…#s…} 零空白；
     * 唯一可能带空白的是第三方 {@code getDefinition()}（带组件的键原文），在格出口折成 {@code "_"}。
     */
    static String describeDeclaredCoverageReadouts(DeclaredBatchTerminalLedger terminals,
            String queuePendingReadout, DeclaredBatchTerminalDrops terminalDrops) {
        var counters = terminals.coverageCounters();
        return " declaredBigint[累计]=" + counters.declaredBigint()
                + " declaredOther[累计]=" + counters.declaredOther()
                + " inflightRegistered[累计]=" + counters.inflightRegistered()
                + " noReceipt[累计]=" + counters.silentNoReceipt()
                + ",枚数合计=" + counters.silentNoReceiptUnits()
                + ",最新样板=" + describePatternIdentity(counters.silentNoReceiptPattern())
                + " noBatchId[累计]=" + counters.silentNoBatchId()
                + ",枚数合计=" + counters.silentNoBatchIdUnits()
                + ",最新样板=" + describePatternIdentity(counters.silentNoBatchIdPattern())
                + " terminalSuccess[累计]=" + counters.terminalSuccess()
                + " terminalCancelled[累计]=" + counters.terminalCancelled()
                + " terminalOther[累计]=" + counters.terminalOther()
                + " terminalUnmatched[累计]=" + counters.terminalUnmatched()
                + " terminalDropped[全场JVM]=noLedger="
                + terminalDrops.noLedger()
                + "/idMismatch=" + terminalDrops.idMismatch()
                // 读数 5（台账 #225）：机器回网队列的深度，从申报批这一轮<b>真碰过</b>的那几台机器
                // 现读（public 两口的换算式在桥那一侧）；问到几台/碰到几台由 cov 那一格自报，
                // 一台都没问到即整格问号。
                + queuePendingReadout;
    }

    /**
     * 停滞行三处网络读数（{@code backoffBlind=…网络sim=}／{@code missing=…(net: )}／
     * {@code quotaBlind=…net=}）各带的那一位 {@code mountsInUse=}：<b>问不出，如实 {@code ?}</b>
     * （台账 #225 读数 7）。
     *
     * <p>它要补的是"网络真有、我们读 0"的最后一格残留。那三格走的都是同一条嘴：
     * {@code grid.getStorageService().getInventory().extract(key, Long.MAX_VALUE, SIMULATE, …)}，
     * 而 {@code getInventory()} 交回的是 {@code appeng.me.storage.NetworkStorage}
     * （AE2 19.2.17 {@code StorageService.java:195-197}）。那一类在 {@code NetworkStorage.java:48}
     * 有一个 {@code private boolean mountsInUse}——AE2 自己的<b>递归闸</b>：
     * {@code insert} 在 {@code :94-96}、{@code extract} 在 {@code :173-176}、
     * {@code getAvailableStacks} 在 {@code :204-207} 三处都写着"闸抬着就直接
     * {@code return 0}／{@code return}"，只在各自的 {@code finally}（{@code :137}／{@code :195}／
     * {@code :217}）落下。闸挡住的那一趟，我们拿到的是<b>一个干净的 0</b>——与"网络真没有这一枚"
     * 在那三格的字面上完全同形，这正是这三格今天唯一还说不出口的一档。
     *
     * <p><b>为什么问不出</b>：那一个字段是 {@code private}，{@code NetworkStorage}（19.2.17 全文
     * {@code mountsInUse} 只出现在那一个类里）没有任何 getter；我们手上只有
     * {@code IStorageService} 与 {@code appeng.api.storage.MEStorage} 两个<b>接口</b>，
     * 后者对外只有 {@code isPreferredStorageFor}／{@code insert}／{@code extract}／
     * {@code getAvailableStacks}／{@code getDescription}（{@code MEStorage.java:62-108}），
     * 压根没有"此刻在不在递归里"这一问的口。要么反射去捅那个私有字段、要么 mixin 改 AE2 那一闸，
     * 两条都超出"只加读数"，本轮都不做。
     *
     * <p><b>严禁冒充</b>：这一格永远不许改印 {@code net}／{@code 网络sim} 自己的值，也不许拿
     * "我们这次调用没抛异常"读成"闸门落着"——读出 0 与闸门挡住在这一格里长得一样，
     * 把问号改成任何一个 {@code true}/{@code false} 都是拿猜顶读数。问号就是问号。
     */
    static String mountsInUseReadout() {
        return "?";
    }

    /**
     * 样板点名的一格头：{@code <definition 折空白>#i<hex>#s<hex>}；{@code null} 如实交 {@code <none>}，
     * 不拿别的样板冒充。身份标只有一把尺——{@link #patternIdentityTag}。
     */
    private static String describePatternIdentity(@Nullable IPatternDetails details) {
        if (details == null) {
            return "<none>";
        }
        return String.valueOf(details.getDefinition()).replace(' ', '_') + patternIdentityTag(details);
    }

    /**
     * 停滞行的<b>四格跳过归因</b>（纯读数）：这一轮没推出去的任务分别被哪道闸口挡住。
     *
     * <p>过去这里还有一格 {@code defer=}，量的是任务循环头上那道"单发让路"——它在自有并行 CPU 这一路
     * 已经不是那颗种子的守门人（守门的是取料点上的环种留底，唯一那把尺见
     * {@code ParallelPlanReservation#spendableAfterRingSeedReserve}），那一刀与这一格一起删掉；
     * 判据与用例的去留见 {@code ParallelRingSeedReserveAtExtractionTest
     * #singleFiringYieldAddsNothingOnTopOfTheSeedReserve}。
     *
     * <p>剩下四格都有牙（每格都对应一条真 {@code continue}/{@code break} 的出口），不许退化成字面量：
     * 每格都随 {@link StallProbe#noteDetail} 记下的那一轮读数走，两档取值必须印出两个不同的数。
     *
     * <p>非 {@code private} 只为这几格能被钉住：渲染点仍只有停滞行一处，本方法不记账、不判据。
     */
    static String describeSkipAttribution(long noProvider, long extractNull,
            long pushRejected, long materialBackoff) {
        return " noProvider=" + noProvider + " extractNull=" + extractNull
                + " pushRejected=" + pushRejected + " materialBackoff=" + materialBackoff;
    }

    /**
     * 排查日志的任务份数读数：走 {@code TaskProgress.exactRemaining()} 的宽域账。
     *
     * <p>越界（{@code >long}）任务的 {@code value} 镜像只是饱和哨兵，摆进日志会被读成
     * "快跑完了"；这里交 {@link git.chexson.chexsonsaeutils.bigint.PlanAmount}
     * 的精确十进制原文。域内任务逐位不变；空任务沿用 {@code -1} 形态。
     */
    static String describeStallTaskFirings(
            @Nullable ParallelExecutingCraftingJob.TaskProgress progress) {
        return progress == null ? "-1" : progress.exactRemaining().toString();
    }

    /** 每条 lane 一个探针实例：停滞计时与跳过原因计数必须可按 lane 归属。 */
    private final StallProbe stallProbe = new StallProbe();

    /** 卡住探针：任务非空但连续无推送时低频记一行任务快照，不刷屏。 */
    final class StallProbe {
        private long stalledTicks;
        private String lastSignature;
        private long lastNoProvider;
        private long lastExtractNull;
        private long lastPushRejected;
        private long lastMaterialBackoff;
        /**
         * 本轮两份现成读数的交接引用（<b>纯读数交接，不新建任何账</b>）：
         * {@code lastBatchProbe} ＝本轮批量提取的 Probe（真失败格／回补受阻就记在它那一本现成的账里），
         * {@code lastRationOrder} ＝本轮 {@code blocked=} 选样板用的那份顺位。
         * 停滞行的 {@code alloc=} 必含格经 {@link #blockedNamedKeys} 读的就是这两份——与义务停滞行的
         * {@code blocked=} 同一批样板、同一本账；没有任何判据读它们。
         */
        @Nullable
        private ParallelBatchExtraction.Probe lastBatchProbe;
        @Nullable
        private java.util.List<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>>
                lastRationOrder;

        /** 每轮 executeCrafting 收尾调用：交出本轮 {@code blocked=} 选样板用的那份顺位引用（纯交接，见 {@link #lastRationOrder}）。 */
        void noteRationOrder(
                java.util.List<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>>
                        orderedTasks) {
            lastRationOrder = orderedTasks;
        }

        /** 每轮 executeCrafting 结束调用：累积本轮跳过原因。 */
        void noteDetail(long pushedPatterns, long skippedNoProvider,
                long skippedExtractNull, long skippedPushRejected, long skippedMaterialBackoff) {
            if (pushedPatterns > 0L) {
                return;
            }
            lastNoProvider = skippedNoProvider;
            lastExtractNull = skippedExtractNull;
            lastPushRejected = skippedPushRejected;
            lastMaterialBackoff = skippedMaterialBackoff;
        }

        /** 批量读数的 100 轮累加：求和看推送量，取最大看每个闸口的上界。 */
        private long batchRounds;
        /** 每轮批量决策的推送份数合计（100 轮窗）：宽域真值，饱和哨兵不再冒充"推进量"（见 {@link #noteBatch}）。 */
        private BigInteger batchPushedSum = BigInteger.ZERO;
        private long batchCapMax;
        private long batchReqMax;
        private long batchGotMax;
        private long batchPushKMax;
        private long batchFoldHostSum;
        private long batchFoldNullSum;
        private long batchPowerMin;
        private long batchRemainMax;
        private long batchUniformOk;
        private long batchUniformFallback;
        private long batchNoCandidate;
        /**
         * noCand 归因合计（百轮窗、跨样板累计，见 {@code ncSplit[百窗累计]=}）：
         * 池内不够一发 / 候选递不出来 / 被自己配给夹死 / 跨槽与族份额压不到一发。
         * <b>{@code 池内不够一发}（旧标签 {@code empty}）的对外话术与实现判据对齐</b>：这一档落进的
         * 判据是"每个可计数候选：池内 &lt; 每发件数"（{@code ParallelBatchExtraction} 的
         * {@code noteNoCandidate} 出口，实机有 7 枚、每发要更多也被记进这一档），它<b>不是</b>
         * "所有候选在现场都是 0——真没料"；旧话术把"还差一截"报成"一枚没有"。判据算式一字未动，
         * 这里只改话术。
         */
        private long batchNcEmpty;
        private long batchNcNoListing;
        private long batchNcLocked;
        private long batchNcShort;
        /** 本轮第一例「现场有料却被配给夹死」的现场读数。 */
        /** 被配给夹死那一格的<b>样板身份与槽号</b>（同 Probe#FailingCell 的 owner 形状）：只有键与三个数点不出名。 */
        private String batchNcLockedOwner = "?";
        private int batchNcLockedSlot = -1;
        private String batchNcLockedKey = "";
        private long batchNcLockedStored;
        private long batchNcLockedUnits;
        private long batchNcLockedUsable;
        private long batchTakenMismatch;
        private long batchResClamp;
        private long batchFamilyClamp;
        private long batchExecSum;
        /** 份数声明形态：推送次数、实收份数合计、整批拒收次数。 */
        private long batchDeclaredPush;
        private BigInteger batchDeclaredFirings = BigInteger.ZERO;
        private long batchDeclaredRejected;
        /**
         * 100 轮里的拒收<b>点名格</b>（{@code 样板@宿主#机器出口} → 次数）与其后因为封顶没留下的
         * 格数。<b>纯读数</b>：不参与任何判据，只让 {@code declaredReject=} 那一格说得出是谁、
         * 在哪个出口拒的（过去四型同影，只有一个聚合数）。渲染封顶四格，见
         * {@link #describeDeclaredRejects(java.util.Map, long)}。
         */
        private final java.util.Map<String, Long> batchDeclaredRejectCells = new java.util.LinkedHashMap<>();
        private long batchDeclaredRejectDropped;
        /** 义务交割：批次数、累计交割份数、一轮里挂着的最欠账份数。 */
        private long batchObligationBatches;
        private long batchObligationFirings;
        private long batchOwedMax;
        /** 义务交割的网络回补：动了几批、搬进缓冲的合计枚数（0 = 这条通道没动过）。 */
        private long batchObligationRefillBatches;
        private long batchObligationRefillUnits;
        /**
         * 义务交割的<b>分刀推进读数</b>（100 轮合计）：回补刀数与<b>真值</b>合计、伸手付料的
         * 刀数与真值合计。
         *
         * <p>为什么不能只看上面那两格：一发真需 {@code >long} 的料天然要 N 轮才凑得齐，
         * {@code obNetPull} 与 {@code obPaid} 第二轮起就分别钉在天花板与 0 上——把"每轮补进一刀"
         * 读成"停滞"就是这么来的。真值合计走 {@link BigInteger} 才看得见它每轮都在前进。
         * <b>纯读数</b>：不参与任何判据，停滞判定一字未改。
         */
        private long batchObligationRefillChunks;
        private BigInteger batchObligationRefillExact = BigInteger.ZERO;
        private long batchObligationPaidChunks;
        private BigInteger batchObligationPaidExact = BigInteger.ZERO;
        /**
         * 100 轮里的两格<b>纯读数</b>（不参与任何判据、不新增拒绝分支）：
         * {@code batchRefillBlocked} = 回补这条腿上被挡的格（见
         * {@code ParallelBatchExtraction.Probe#describeRefillBlockedCells()}：每 (样板, 槽) 各一格、
         * 每 tick 每 lane 封顶四格、印不下的补一格 {@code obWhy更多=N} 紧跟在 {@code obWhy=} 后面）；
         * {@code batchDeclaredNull} = 申报交回 {@code null} 的分型计数（哪一支夹子先把申报打到 ≤1，
         * 见 {@code ParallelCraftingCpuLogic#declaredBatchFirings}）。过去 {@code declaredReject}
         * 只证得了"没申报"、证不了"为什么"，这两格补的正是那一格看不见。
         */
        private String batchRefillBlocked = "";
        private final java.util.Map<String, Long> batchDeclaredNull = new java.util.LinkedHashMap<>();
        /** 同一任务的份数损失配对：跨任务取 max 归不出因，这里只留丢得最狠的那一次。 */
        private long batchLostCount;
        private long batchLostFirings;
        private long batchLostReq;
        private long batchLostGot;
        private long batchLostPwr;
        private long batchLostCap;
        private String batchLostCls = "";
        private String batchLostBind = "";
        /**
         * 100 轮里<b>每趟取料腿各自</b>算得出份数却最紧的那一格（{@code minCell[原型]=}／
         * {@code minCell[holder]=}／{@code minCell[义务]=} 三格，纯读数）。
         *
         * <p>{@code bind=} 只在"设新最小值且非无限源"时改写，而 {@code 真失败格} 只记
         * {@code executions < 1} 的那一格：{@code lost=1 worst(req=4.75e9,got=1,cls=X,bind=Y,stored=Z}
         * {@code ,demand=1)} 里 {@code stored÷demand} 算得出几十亿发，说明钉住 1 发的<b>不是</b> bind 那格，
         * 也不是失败格（它算得出 1 发）。这一格直接点名"本批算出的最小份数那一格"，
         * 于是"每交互只推进 1 发"那一型不用再猜是配额夹、跨槽线性约束还是单发回落。
         *
         * <p><b>必须分三格</b>：{@code ParallelBatchExtraction.Probe#noteMinCell} 被三趟取料腿喂
         * （k 倍 holder、义务交割的 {@code payableFirings}、单发原型那一趟份数写死 {@code 1L}），
         * 旧那"单格取份数最小"于是永远被原型那一趟赢——实机因此打印 {@code minCell=slot#0 … cap=1
         * 份数=1}，把"批处理上限"印成 1，而同一窗 {@code req=23969280000} 证明 holder 腿拿到
         * 2.4e10。三格各留最紧的一格、各按自己的 scope 比，谁也别冒充谁。
         *
         * <p>不参与任何判据：唯一的读者是拼串，串由
         * {@code ParallelBatchExtraction.Probe#describeMinCells(String[])} 拼好，这里只留每趟最紧的那一份。
         */
        private String[] batchMinCells = ParallelBatchExtraction.Probe.newMinCellCells();
        private long[] batchMinCellFirings = ParallelBatchExtraction.Probe.newMinCellFirings();
        private final java.util.HashMap<String, long[]> batchHosts = new java.util.HashMap<>();

        /**
         * 一次交互推进几发：每 100 轮记一行决策链读数 + 一行宿主可用性读数。
         *
         * <p>cap=宿主认得的份数上界（0 表示没有按份数收料的宿主在册）、req=我方请求份数、
         * got=提取实际给到的份数（只在真拿到批量时记）、pushK=真正推出去的份数、
         * foldHost/foldNull=折叠宿主在场与折不动的次数、pwr=本段最差电力上界（-1 表示全程不设限）、
         * rem=最大剩余发数、uni=均一批量走通次数、uniAvg=均一批量的平均份数、mix=退回单发混取次数、
         * noCand=有槽连一发都挑不出候选的次数、mismatch=真扣料与估算不符的次数、
         * resClamp=被占料账本夹小存量的次数、
         * familyClamp=份数被"本批该族剩余份额"夹小的次数（配额定数钉住批量；只记 k 倍 holder 腿，
         * 宽域那一支的族份额夹走 batchScopePayable、不进这一格）。
         *
         * <p>{@code lost} 是"请求份数没被满足"的次数，{@code worst(req,got,pwr,cap,cls,bind)} 是丢得最狠
         * 那一次的<b>同一次提取</b>配对读数：跨任务取 max 时 req 与 got 可能分属两个任务，归不出因。
         *
         * <p>判读：{@code mix} 高且 {@code noCand} 高是现场量真的只够一发；{@code uni} 高但
         * {@code uniAvg=1} 且 {@code resClamp} 高是占料锁把批量锁没；{@code familyClamp} 高而
         * {@code inv} 里该族仍有富余量，就是规划给这一族定的份数把批量夹住（配额是硬上限）；
         * {@code worst} 里
         * {@code pwr<req} 是本 tick 的电被前序推送吃干、份数被电钉成 1，{@code pwr>=req} 且
         * {@code cap>=req} 才是现场料只够一发——此时 {@code bind} 给出的
         * {@code stored ÷ demand} 就是把份数钉住的那道除法式子（CPU 现场存量与每发合计需求）。
         * {@code [chexsonsaeutils-hosts]} 的
         * {@code r/busy/back/push} 指认谁在收料、谁永远忙。
         *
         * <p>{@code inv} 是打印这一行瞬间的 CPU 库存摘要：{@code bind} 里 {@code stored} 小而
         * {@code inv} 也空是网络料根本没进 CPU；{@code inv} 里躺着这个键的富余量而 {@code stored}
         * 只够一发，才是我方份数算法的闸口。
         *
         * <p><b>这一行的分隔符口径（一格一 token，子字段仍可寻址）</b>：按空白切出的每一段都必须
         * 认得出是一个已知格名（{@code lane=}/{@code locked=}/{@code worst(}/{@code minCell=}/
         * {@code inv=}…），段数与格名清单由 {@code ParallelDeclaredBatchObligationTest} 的
         * {@code per100rLineSplitsIntoExactlyTheKnownCellNames} 钉住。
         * 格内子字段之间一律用<b>不带空白的 {@code ,}</b>（与既有形状 {@code declaredReject=100/[格1,格2]}、
         * {@code 同族[…]下界} 同一把尺），于是 {@code grep ',got='} 这类跨格对账仍能按
         * {@code worst(req=..,got=..} 寻址；{@code "_"} <b>只</b>出现在格出口折掉第三方串
         * （{@code AEKey#toString} 的 {@code (with patches)}、机器给的 reason）之后——
         * 看到 {@code "_"} 就是别人家的空格，看到 {@code ","} 就是我们自己的子字段边界。
         *
         * <p>{@code obChunks/obPullExact/obPayChunks/obPaidExact} 是<b>分刀推进</b>的四格读数
         * （回补刀数与真值合计、伸手付料刀数与真值合计，100 轮累计）：一发真需 {@code >long} 时
         * 要 N 轮才凑得齐，那 N−1 轮里 {@code obPaid=0}、{@code obOwed} 不动，而
         * {@code obNetPull} 只在交割成功的那一批才前进、累加又会饱和在天花板上——只看这几格会把
         * "每轮补进一刀"读成"停滞"。真值合计用 {@link BigInteger}：读数还在前进就是在推进，
         * 刀数与真值全部归零才是真没推进。<b>纯读数</b>：停滞判据一字未改。
         *
         * <p>{@code inflightBatches=} 与 {@code oldestInflightTicks=} 是从义务停滞行搬过来的两格
         * （见 {@link #describeInflightBatchReadouts}）：读的就是作业那本现成的在飞台账，不新算一个数、
         * 不新建一本账、不参与这一行的任何判读。
         */
        void noteBatch(BigInteger pushedPatterns, long capMax, long reqMax, long gotMax,
                long pushKMax, long foldHost, long foldNull, long powerMin, long remainMax,
                ParallelBatchExtraction.Probe probe, java.util.HashMap<String, long[]> stats,
                ListCraftingInventory inventory, DeclaredBatchTerminalLedger declaredTerminals,
                long currentTick) {
            batchRounds++;
            // 本轮批量读数的现成账交给停滞探针：alloc= 必含格读的就是这一本（纯交接，判据不读）。
            lastBatchProbe = probe;
            // 诊断合计读取声明批精确发数与普通批发数；调度推进判据独立使用饱和 long，避免回绕。
            batchPushedSum = batchPushedSum.add(pushedPatterns);
            batchCapMax = Math.max(batchCapMax, capMax);
            batchReqMax = Math.max(batchReqMax, reqMax);
            batchGotMax = Math.max(batchGotMax, gotMax);
            batchPushKMax = Math.max(batchPushKMax, pushKMax);
            batchFoldHostSum = ClampedMath.addUp(batchFoldHostSum, foldHost);
            batchFoldNullSum = ClampedMath.addUp(batchFoldNullSum, foldNull);
            batchPowerMin = Math.min(batchPowerMin, powerMin);
            batchRemainMax = Math.max(batchRemainMax, remainMax);
            batchUniformOk += probe.uniformOk;
            batchUniformFallback += probe.uniformFallback;
            batchNoCandidate += probe.noCandidate;
            batchNcEmpty = ClampedMath.addUp(batchNcEmpty, probe.ncEmpty);
            batchNcNoListing = ClampedMath.addUp(batchNcNoListing, probe.ncNoListing);
            batchNcLocked = ClampedMath.addUp(batchNcLocked, probe.ncLocked);
            batchNcShort = ClampedMath.addUp(batchNcShort, probe.ncShort);
            if (probe.ncLocked > 0L && batchNcLockedKey.isEmpty()) {
                batchNcLockedOwner = probe.ncLockedOwner;
                batchNcLockedSlot = probe.ncLockedSlot;
                batchNcLockedKey = probe.ncLockedKey;
                batchNcLockedStored = probe.ncLockedStored;
                batchNcLockedUnits = probe.ncLockedUnits;
                batchNcLockedUsable = probe.ncLockedUsable;
            }
            batchTakenMismatch += probe.takenMismatch;
            batchResClamp += probe.resClamp;
            batchFamilyClamp = ClampedMath.addUp(batchFamilyClamp, probe.familyClamp);
            batchExecSum = ClampedMath.addUp(batchExecSum, probe.execSum);
            batchDeclaredPush = ClampedMath.addUp(batchDeclaredPush, probe.declaredPushes);
            batchDeclaredFirings = batchDeclaredFirings.add(probe.declaredFiringsSum);
            batchDeclaredRejected = ClampedMath.addUp(batchDeclaredRejected, probe.declaredRejected);
            // 纯读数：把这一 tick 的拒收点名格并进 100 轮的账（同格累加次数，封顶格数在渲染处收）
            for (var cell : probe.declaredRejectCells.entrySet()) {
                batchDeclaredRejectCells.merge(cell.getKey(), cell.getValue(), Long::sum);
            }
            batchDeclaredRejectDropped = ClampedMath.addUp(batchDeclaredRejectDropped,
                    probe.declaredRejectCellsDropped);
            batchObligationBatches = ClampedMath.addUp(batchObligationBatches, probe.obligationBatches);
            batchObligationFirings = ClampedMath.addUp(batchObligationFirings, probe.obligationFiringsSum);
            batchObligationRefillBatches = ClampedMath.addUp(
                    batchObligationRefillBatches, probe.obligationRefillBatches);
            batchObligationRefillUnits = ClampedMath.addUp(
                    batchObligationRefillUnits, probe.obligationRefillUnits);
            batchObligationRefillChunks = ClampedMath.addUp(
                    batchObligationRefillChunks, probe.refillChunks);
            batchObligationRefillExact =
                    batchObligationRefillExact.add(probe.refillExactUnits);
            batchObligationPaidChunks = ClampedMath.addUp(
                    batchObligationPaidChunks, probe.paidChunks);
            batchObligationPaidExact = batchObligationPaidExact.add(probe.paidExactUnits);
            if (batchRefillBlocked.isEmpty()) {
                // 每 (样板, 槽) 各一格、每 tick 每 lane 封顶四格，印不下的把 obWhy更多=N 紧跟在 obWhy=
                // 后面同一格：旧形状"每个 Probe 只留第一格"会把别的样板那一格冒充成这条 lane 卡住的那一格。
                batchRefillBlocked = probe.describeRefillBlockedCells();
            }
            for (var reason : probe.declaredNullReasons.entrySet()) {
                batchDeclaredNull.merge(reason.getKey(), reason.getValue(), Long::sum);
            }
            batchOwedMax = Math.max(batchOwedMax, probe.obligationOwedMax);
            batchLostCount = ClampedMath.addUp(batchLostCount, probe.lostCount);
            if (probe.lostFirings > batchLostFirings) {
                batchLostFirings = probe.lostFirings;
                batchLostReq = probe.lostReq;
                batchLostGot = probe.lostGot;
                batchLostPwr = probe.lostPwr;
                batchLostCap = probe.lostCap;
                batchLostCls = probe.lostCls;
                batchLostBind = probe.describeWorstBinding();
            }
            // 纯读数：100 轮里每趟取料腿各留算得出份数却最紧的那一格（份数更小的才在同一 scope 内
            // 覆盖），不跨 scope 比较，不参与任何判据。
            probe.absorbMinCellsInto(batchMinCells, batchMinCellFirings);
            for (var entry : stats.entrySet()) {
                long[] cells = batchHosts.computeIfAbsent(entry.getKey(), ignored -> new long[4]);
                long[] tick = entry.getValue();
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = ClampedMath.addUp(cells[i], tick[i]);
                }
            }
            if (batchRounds < 100L) {
                return;
            }
            long attempts = batchUniformOk + batchUniformFallback;
            AELog.info("[chexsonsaeutils-batch] per100r lane=%s pushed=%s evaluated=%d cap=%d req=%d got=%d"
                            + " pushK=%d foldHost=%d foldNull=%d pwr=%d rem=%d"
                            + " uni=%d uniAvg=%d mix=%d noCand=%d mismatch=%d resClamp=%d"
                            + " ncSplit[百窗累计]=池内不够一发%d/nolist%d/locked%d/short%d locked=[%s]"
                            + " familyClamp=%d"
                            + " declared=%d declaredK=%d%s obBatch=%d obPaid=%d obOwed=%d"
                            + " obNetBatch=%d obNetPull=%d"
                            + " obChunks=%d obPullExact=%d obPayChunks=%d obPaidExact=%d"
                            + " obWhy=%s declNull=%s%s%s%s"
                            + " lost=%d %s minCell=%s inv=%s",                    lane.laneId(), batchPushedSum, lastSweepVisited, batchCapMax, batchReqMax, batchGotMax,
                    batchPushKMax, batchFoldHostSum, batchFoldNullSum,
                    batchPowerMin == Long.MAX_VALUE ? -1L : batchPowerMin, batchRemainMax,
                    batchUniformOk,
                    batchUniformOk == 0L ? 0L : batchExecSum / batchUniformOk,
                    batchUniformFallback, batchNoCandidate, batchTakenMismatch, batchResClamp,
                    batchNcEmpty, batchNcNoListing, batchNcLocked, batchNcShort,
                    describeLockedReadout(batchNcLockedOwner, batchNcLockedSlot, batchNcLockedKey,
                            batchNcLockedStored, batchNcLockedUnits, batchNcLockedUsable),
                    batchFamilyClamp,
                    batchDeclaredPush, batchDeclaredFirings,
                    describeDeclaredRejectReadout(batchDeclaredRejected, batchDeclaredRejectCells,
                            batchDeclaredRejectDropped),
                    batchObligationBatches, batchObligationFirings, batchOwedMax,
                    batchObligationRefillBatches, batchObligationRefillUnits,
                    batchObligationRefillChunks, batchObligationRefillExact,
                    batchObligationPaidChunks, batchObligationPaidExact,
                    batchRefillBlocked.isEmpty() ? "<none>" : batchRefillBlocked,
                    describeDeclaredNullReasons(batchDeclaredNull),
                    // 这两格过去只在停滞行里有，per100r 一个字都没 ⇒ 判不出"20t 材料退避吃掉了多少轮"
                    // （本跑 evaluated=30 就是这样读不出来的）。取现成的那一格语义，不新记账。
                    describeBatchGateReadouts(lastMaterialBackoff, lastExtractNull),
                    // 在飞没回执那一档：停滞行只在"欠料且一批都没交割"那一档才打，纯"申报出去没回执、
                    // 又不欠料"那一跑过去连一行读数都不留。这里读的就是台账那两格现成读数——
                    // 只点名，不进任何判据（#209 的口径）。
                    describeInflightBatchReadouts(declaredTerminals, currentTick),
                    // 申报批的覆盖面自证读数族（台账 #225 读数 1–3 与 5）：分档笔数、两支静默口的笔数与
                    // 枚数合计、终局四档、静默收手的全场累计，外加机器回网队列那两格现读。台账那几格
                    // 读的就是同一本台账的累计快照，不新建第二本账、不进任何判据——
                    // 只让"81 笔申报 / 3 笔终局 / 在飞 0"当场对平或对不平。
                    describeDeclaredCoverageReadouts(declaredTerminals, queuePendingReadout(), lane.cluster().terminalDrops()),
                    // 份数损失配对那一格：请求份数没被满足的次数 + 丢得最狠那一次的同次提取读数。
                    batchLostCount,
                    describeWorstCell(batchLostReq, batchLostGot, batchLostPwr, batchLostCap,
                            batchLostCls, batchLostBind),
                    ParallelBatchExtraction.Probe.describeMinCells(batchMinCells),
                    describeInventory(snapshotPool(inventory)));
            AELog.info("[chexsonsaeutils-hosts] per100r lane=%s attempts=%d top=%s",
                    lane.laneId(), attempts, describeTopHosts());
            batchRounds = 0L;
            batchPushedSum = BigInteger.ZERO;
            batchCapMax = 0L;
            batchReqMax = 0L;
            batchGotMax = 0L;
            batchPushKMax = 0L;
            batchFoldHostSum = 0L;
            batchFoldNullSum = 0L;
            batchPowerMin = Long.MAX_VALUE;
            batchRemainMax = 0L;
            batchUniformOk = 0L;
            batchUniformFallback = 0L;
            batchNoCandidate = 0L;
            batchNcEmpty = 0L;
            batchNcNoListing = 0L;
            batchNcLocked = 0L;
            batchNcShort = 0L;
            batchNcLockedOwner = "?";
            batchNcLockedSlot = -1;
            batchNcLockedKey = "";
            batchNcLockedStored = 0L;
            batchNcLockedUnits = 0L;
            batchNcLockedUsable = 0L;
            batchTakenMismatch = 0L;
            batchResClamp = 0L;
            batchFamilyClamp = 0L;
            batchExecSum = 0L;
            batchDeclaredPush = 0L;
            batchDeclaredFirings = BigInteger.ZERO;
            batchDeclaredRejected = 0L;
            batchDeclaredRejectCells.clear();
            batchDeclaredRejectDropped = 0L;
            batchObligationBatches = 0L;
            batchObligationFirings = 0L;
            batchOwedMax = 0L;
            batchObligationRefillBatches = 0L;
            batchObligationRefillUnits = 0L;
            batchObligationRefillChunks = 0L;
            batchObligationRefillExact = BigInteger.ZERO;
            batchObligationPaidChunks = 0L;
            batchObligationPaidExact = BigInteger.ZERO;
            batchRefillBlocked = "";
            batchMinCells = ParallelBatchExtraction.Probe.newMinCellCells();
            batchMinCellFirings = ParallelBatchExtraction.Probe.newMinCellFirings();
            batchDeclaredNull.clear();
            batchLostCount = 0L;
            batchLostFirings = 0L;
            batchLostReq = 0L;
            batchLostGot = 0L;
            batchLostPwr = 0L;
            batchLostCap = 0L;
            batchLostCls = "";
            batchLostBind = "";
            batchHosts.clear();
        }

        /** 宿主读数：按"真正收到推送"再按 READY 数排序，取前四类，一眼看清谁在收料谁永远忙。 */
        private String describeTopHosts() {
            var rows = new java.util.ArrayList<>(batchHosts.entrySet());
            rows.sort(java.util.Comparator
                    .comparingLong((java.util.Map.Entry<String, long[]> row) -> row.getValue()[3])
                    .thenComparingLong(row -> row.getValue()[0])
                    .reversed());
            var described = new StringBuilder();
            for (int i = 0; i < Math.min(4, rows.size()); i++) {
                long[] cells = rows.get(i).getValue();
                if (i > 0) {
                    described.append(' ');
                }
                described.append(rows.get(i).getKey())
                        .append("[r=").append(cells[0])
                        .append(",busy=").append(cells[1])
                        .append(",back=").append(cells[2])
                        .append(",push=").append(cells[3]).append(']');
            }
            return described.isEmpty() ? "<none>" : described.toString();
        }

        /**
         * 拒收点名格的渲染（<b>纯读数</b>，不参与任何判据）：{@code [样板@宿主#出口=次数]}，
         * 最多四格，印不下的（本 tick 被封顶丢掉的格数 + 100 轮里合并进来的多余格）合成一格
         * {@code 更多=N}；一笔都没记过就是 {@code -}。
         *
         * <p><b>一格一 token</b>：格与格之间只用 {@code /}、格内只用 {@code =} 与 {@code #}，整串
         * 不含任何空白（含 {@code /更多=} 那一格）。下游读数按空白切 token，一个前导空格就把
         * 一格切成两格（{@code UselessBigIntegerBatchClient.RejectCells#describe} 同一条尺）。
         */
        static String describeDeclaredRejects(java.util.Map<String, Long> cells, long dropped) {
            if (cells.isEmpty()) {
                return dropped > 0L ? "[更多=" + dropped + "]" : "-";
            }
            var described = new StringBuilder("[");
            int listed = 0;
            for (var entry : cells.entrySet()) {
                if (listed >= 4) {
                    break;
                }
                if (listed > 0) {
                    described.append('/');
                }
                described.append(entry.getKey()).append('=').append(entry.getValue());
                listed++;
            }
            if (listed < cells.size() || dropped > 0L) {
                described.append("/更多=").append(cells.size() - listed + dropped);
            }
            return described.append(']').toString();
        }

        /**
         * 拒收那两格合并成一格（<b>纯读数</b>）：{@code  declaredReject=<聚合数>/<点名格>}。
         *
         * <p>为什么不许在数与格之间留空白：过去这一格印成 {@code declaredReject=%d %s}，按空白切 token
         * 的下游读数会把点名格切成两段（一格变两格）。两半一律用 {@code /} 连——与
         * {@link #describeDeclaredRejects} 内部那把 {@code /} 尺、以及
         * {@code UselessBigIntegerBatchClient.RejectCells#describe} 的 {@code /更多=} 同一个形状。
         *
         * <p>这一格进 per100r 那一行的<b>唯一</b>出口：数与格的先后、分隔符都只在这里定一次。
         */
        static String describeDeclaredRejectReadout(long rejected, java.util.Map<String, Long> cells,
                long dropped) {
            return " declaredReject=" + rejected + "/" + describeDeclaredRejects(cells, dropped);
        }

        /**
         * 申报交回 {@code null} 的分型读数渲染（纯读数）：{@code reason=N} 用 {@code /} 连接，
         * 没记过就是 {@code <none>}。
         *
         * <p><b>这一格的出口折一次空白</b>：reason 键走的是 {@code Probe#noteDeclaredRejectReason}
         * 那本账，而它记的是<b>机器给的外来串</b>——自家那六支夹子（{@code fold}/{@code cap}/
         * {@code rep-in}/{@code rep-out}/{@code power}/{@code payable}）恰好都不带空白，于是"这一格
         * 没折"在过去数不出来；一旦哪个来源交出带空白的键（{@code AEKey#toString} 带组件时是
         * {@code " (with patches)"}），旧形状就把 per100r 的一格劈成好几段。折点放在这一格的出口、
         * 不给每个来源各写一折，与 {@link #describeWorstCell}、
         * {@code ParallelBatchExtraction.Probe#cellReadout} 同一口径（各自那一格的出口折一次）。
         */
        private static String describeDeclaredNullReasons(java.util.Map<String, Long> reasons) {
            if (reasons.isEmpty()) {
                return "<none>";
            }
            var described = new StringBuilder();
            for (var entry : reasons.entrySet()) {
                if (described.length() > 0) {
                    described.append('/');
                }
                described.append(entry.getKey()).append('=').append(entry.getValue());
            }
            // 值侧是自家计数（不含空白），格自带的 "/" 与 "=" 也不含空白 ⇒ 折掉的恒是 reason 键里
            // 别人家的空格。
            return described.toString().replace(' ', '_');
        }

        /**
         * {@code worst(...)} 那一格的<b>唯一</b>出口（纯读数）：把份数掉下去那一次的全部读数收进
         * <b>一格</b>。
         *
         * <p>旧形状 {@code worst(req=%d got=%d pwr=%d cap=%d cls=%s %s)} 里那 5 个空格是 per100r
         * 按空白切 token 的断点：{@code worst(} 那一格被劈成 6-8 段，"req 与 got 同属一次提取"
         * 这个事实在切分后就不存在了。子字段一律用 {@code ,} 连（不带空白），于是
         * {@code grep ',got='} 一类跨格对账还能按 {@code worst(req=..,got=..} 寻址。
         *
         * <p>{@code cls} 是 {@code details.getClass().getSimpleName()}（自家串，不折）；
         * {@code bind} 里带 {@code AEKey#toString}（第三方，可能含空白），在<b>这一格出口</b>整串
         * 折一次 {@code "_"}——与 {@link #describeDeclaredReject}、{@code RefillBlockedCell#describe}
         * 同一个折点口径，不给每个来源各写一次。
         */
        static String describeWorstCell(long req, long got, long pwr, long cap, String cls,
                @Nullable String bind) {
            var described = new StringBuilder("worst(req=").append(req)
                    .append(",got=").append(got)
                    .append(",pwr=").append(pwr)
                    .append(",cap=").append(cap)
                    .append(",cls=").append(cls == null ? "" : cls);
            if (bind != null && !bind.isEmpty()) {
                described.append(',').append(bind);
            }
            return described.append(')').toString().replace(' ', '_');
        }

        void note(long pushedPatterns, @Nullable ParallelExecutingCraftingJob job,
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
                            // 份数走宽域精确读数：越界任务不再把饱和哨兵摆成真实份数。
                            .append(" x ").append(describeStallTaskFirings(task.getValue()));
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
                // 整行只由 describeStallLine 交出（格式串与实参只在那一处），这里只把本轮读数喂给它。
                AELog.info("%s", describeStallLine(lane.laneId(), lane.cluster().activeLaneCount(),
                        job, inventory, grid, detail.toString(), lastNoCandKey, lastNoCandStored,
                        lastNoCandQuota));
            }
        }

        /**
         * 停滞行（{@code [chexsonsaeutils-stall]}）的<b>唯一渲染口</b>：格式串与实参只在这一处，
         * 生产侧 {@code AELog.info("%s", …)} 打的就是它交出的整行——与义务停滞行
         * {@link #describeObligationStallLine} 同一口径（搬的是拼串位置，<b>不是</b>算式，
         * 数值口径一字未动）。
         *
         * <p>抽口的动机只有一条：{@code noCandWhy=}／{@code alloc=} 与 {@code quotaBlind=} 的
         * {@code src=}／{@code blocked=} 头这几格要靠<b>同一个样板身份标</b>对上同一张样板实例，
         * 而这条判据必须拿"真整行"来核；{@link #note} 那一圈要问 {@code lane.cluster()} 与
         * {@code executeCrafting} 的现场，离线树跑不起来。身份标见
         * {@link #patternIdentityTag}（AE2 的样板按 {@code getDefinition()} 覆写 {@code equals}，
         * 只看名字永远配不上号）。
         */
        String describeStallLine(Object laneId, int activeLaneCount,
                ParallelExecutingCraftingJob job, ListCraftingInventory inventory,
                @Nullable appeng.api.networking.IGrid grid, String taskDetail,
                String noCandKey, long noCandStored, long noCandQuota) {
            // 一次快照喂全部探针：三个扫描读的是同一瞬间的池子，不各自反复 SIMULATE。
            var pool = snapshotPool(inventory);
            var poolExact = snapshotPoolExact(inventory);
            var scan = collectDeficits(job, poolExact);
            // 这一行已经点名的那几枚键（alloc= 的必含格名单，同一份、同一个上限口径）
            var namedKeys = blockedNamedKeys(job);
            return String.format("[chexsonsaeutils-stall] no push for 100 ticks: lane=%s plan=%s output=%s"
                            + " tasks=%d remaining=%d waitingFor=%d lanes=%d"
                            + "%s"
                            + " backoffBlind=%s"
                            + " topUp=%d/%d topUpChunks=%d topUpExact=%d topUpKeys=%s topUpKeysCov=%s"
                            + " noCandWhy=[末次]%s 每发件数=%s stored=%d quota=%s"
                            + " inv=%s tasks=[%s]%s ecoOut=%s ecoFold=%s ringDiag=%s"
                            + " poolKeys=%s poolKeysCov=%s pool=%s",
                    laneId, job.planClass, job.finalOutput,
                    job.tasks.size(), job.remainingAmount, job.waitingFor.list.size(),
                    activeLaneCount,
                    describeSkipAttribution(lastNoProvider, lastExtractNull,
                            lastPushRejected, lastMaterialBackoff),
                    describeBackoffBlind(job, inventory, grid),
                    lastTaskTopUpBatches, lastTaskTopUpUnits,
                    lastTaskTopUpChunks, lastTaskTopUpExactUnits,
                    // 读数 6（台账 #225）：聚合那两格（topUpChunks=／topUpExact=）拆到逐枚键的流水，
                    // 外加自报覆盖面 shown/total（封顶四格，剩下的只报数不冒充全部）。纯读数。
                    describeTopUpUnitsPerKeyCells(lastTaskTopUpUnitsPerKey),
                    describeTopUpUnitsPerKeyCoverage(lastTaskTopUpUnitsPerKey),
                    // noCandWhy= 自报身份 [末次]：这一格存的是本轮<b>最后一次</b> noteNoCandidate，
                    // 与 per100r 的 ncSplit[百窗累计]=（100 轮窗、跨样板累计）不是同一窗、不是同一格——
                    // 实机把两者读成同一格的自相矛盾（"pool=7 accepts=yes 却进 noCand"）到此为止。
                    // 每发件数= 补印 IInput.getMultiplier()：串里那个 x1 是 template 量（AE2 恒 1），
                    // 不是每发件数；没有它，"7 枚 < 每发 9 枚"这一型在字面上永远说不清（纯读数，
                    // 由 noCandWhy 现成的身份标 #i<hex>#slotN 回查本轮任务表，回查不到打 "?"，不记账）。
                    noCandKey, describeNoCandPerFiring(job, noCandKey), noCandStored,
                    describeNoCandQuota(noCandQuota),
                    // inv= 段走精确读数：溢出键不再把饱和 Long.MAX 摆成池里现量（纯诊断）。
                    describeInventoryExact(poolExact), taskDetail,
                    formatDeficits(scan, grid) + formatAllocation(job, pool, scan.deficits(),
                            scan.emitted(), blockedNamedKeys(job), grid) + formatOverTakes(job),
                    poolLedger.ecoOut(),
                    // 纯读数：这一 tick 请求侧折回了几次/折掉几发（不参与任何判据）
                    poolLedger.ecoFold(),
                    job.ringSeeds.derivationReport(),
                    // 台账 #226 笔 3b：这一行已经点名的那几枚键，逐枚把池账本（ParallelPoolLedger）
                    // 的进／出按路径印出来。纯读数，不进任何判据。
                    describePoolKeyCells(namedKeys), describePoolKeyCoverage(namedKeys),
                    poolLedger.describePaths());
        }

        /** 停滞行 {@code poolKeys=} 一格最多点几枚键（纯读数封顶，不是判据）：超出只在 cov 里报数。 */
        private static final int POOL_KEY_CELL_LIMIT = 3;

        /**
         * {@code poolKeys=} 那一格（台账 #226 笔 3b）：把<b>这一行已经点名的那几枚键</b>
         * （{@link StallProbe#blockedNamedKeys} 那一份现成名单，最多 {@link #POOL_KEY_CELL_LIMIT} 枚）
         * 逐枚交出池账本的进出流水 {@code [<键>进{路径=量,…}/出{路径=量}]}。
         *
         * <p>格内两个自报子字段（都不许读成量）：{@code view=narrow} —— 这本账是窄域饱和
         * {@code long}、没有宽域出口（{@code ParallelPoolLedger#book}），达顶那一格由账本自己印
         * {@code <sat>} 标记；{@code since=reset} —— {@code reset()} 只在提交那一刻调，
         * 这一格交的是<b>自上次 reset 起累计</b>的进出量，不是本轮窗、也不是百轮窗。
         * 时机一字未动，只是把窗写进格子。
         *
         * <p>一格一 token：键名走 {@link #describeKey(AEKey)} 的折点，整串出口再折一次空白。
         */
        private String describePoolKeyCells(java.util.Collection<AEKey> namedKeys) {
            var cells = new java.util.ArrayList<String>();
            for (var key : namedKeys) {
                if (cells.size() >= POOL_KEY_CELL_LIMIT) {
                    break;
                }
                cells.add(describeKey(key) + poolLedger.describeKey(key));
            }
            var described = "view=narrow,since=reset,"
                    + (cells.isEmpty() ? "<none>" : "[" + String.join(",", cells) + "]");
            return described.replace(' ', '_');
        }

        /**
         * {@code poolKeysCov=} 那一格：{@code shown=<这一格真点出几枚键>/total=<名单里真有几枚>}
         * （与 {@code topUpKeysCov=} 同一把尺，封顶后不许把"看到的三枚"报成"一共三枚"）。
         */
        private static String describePoolKeyCoverage(java.util.Collection<AEKey> namedKeys) {
            return "shown=" + Math.min(namedKeys.size(), POOL_KEY_CELL_LIMIT)
                    + "/total=" + namedKeys.size();
        }

        /** 一行停滞读数最多点几格被退避跳过的样板（纯读数封顶，不是判据）：超出只报格数。 */
        private static final int BACKOFF_BLIND_CELL_LIMIT = 4;

        /** {@code noCandWhy} 现成串里点名样板与槽的身份段（{@code #i<hex>#slotN}）。 */
        private static final java.util.regex.Pattern NO_CAND_OWNER =
                java.util.regex.Pattern.compile("#i([0-9a-f]{1,8})(?:#s[0-9a-f?]{1,16})?#slot(\\d+)");

        /** {@code 回补受阻} 现成串里一格的头段（{@code slot#N_<折好的键原文>_got=}）。 */
        private static final java.util.regex.Pattern REFILL_NAMED_CELL =
                java.util.regex.Pattern.compile("^slot#(\\d+)_(.+?)_got=");

        /**
         * {@code alloc=} 的必含格名单（<b>纯读数，不新开第二本账</b>）：本窗 {@code blocked=}／
         * {@code 回补受阻} 点名的键。样板名单走 {@link #blockedObligationTemplates} 那唯一一份
         * （与义务停滞行的 {@code blocked=} 同一批样板）；键从现成的那本账取——真失败格读
         * {@code Probe#failingCell}，回补受阻读 {@code Probe#refillBlockedCellsFor} 交出的串，
         * 按 {@code slot#} 与折好的键原文回查该槽<b>声明候选表</b>（AE2 键按值相等，候选实例与
         * 账上实例是同一格）。回查不到就跳过那一格：宁缺点少，不许点错。
         */
        private java.util.LinkedHashSet<AEKey> blockedNamedKeys(ParallelExecutingCraftingJob job) {
            var keys = new java.util.LinkedHashSet<AEKey>();
            var probe = lastBatchProbe;
            if (job == null || probe == null) {
                return keys;
            }
            Iterable<Map.Entry<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>> ordered =
                    lastRationOrder == null ? job.tasks.entrySet() : lastRationOrder;
            for (var template : blockedObligationTemplates(job, ordered)) {
                var details = template.getKey();
                var failing = probe.failingCell(details);
                if (failing != null && failing.key() != null) {
                    keys.add(failing.key());
                }
                collectRefillNamedKeys(probe.refillBlockedCellsFor(details), details, keys);
            }
            return keys;
        }

        /**
         * 把 {@code 回补受阻} 串里点名的键换回 AEKey（纯读现成串）：一格形如
         * {@code slot#N_<键原文折成"_">_got=…}；拿折好的原文逐字比对声明候选表的
         * {@code toString} 折法（与格出口同一把尺），命中即取该候选键。{@code <none>} 与
         * 比对不上的一格跳过——这一格只服务 {@code alloc=} 的必含读数，不参与任何判据。
         */
        private static void collectRefillNamedKeys(String cells, IPatternDetails details,
                java.util.LinkedHashSet<AEKey> out) {
            if (cells == null || cells.isEmpty() || details == null) {
                return;
            }
            var slots = details.getInputs();
            if (slots == null) {
                return;
            }
            for (var cell : cells.split("/")) {
                var matcher = REFILL_NAMED_CELL.matcher(cell);
                if (!matcher.find()) {
                    continue;
                }
                int slotIndex = Integer.parseInt(matcher.group(1));
                var foldedKey = matcher.group(2);
                if (slotIndex < 0 || slotIndex >= slots.length || slots[slotIndex] == null) {
                    continue;
                }
                var candidates = slots[slotIndex].getPossibleInputs();
                if (candidates == null) {
                    continue;
                }
                for (var candidate : candidates) {
                    if (candidate == null || candidate.what() == null) {
                        continue;
                    }
                    if (String.valueOf(candidate.what()).replace(' ', '_').equals(foldedKey)) {
                        out.add(candidate.what());
                        break;
                    }
                }
            }
        }

        /**
         * {@code 每发件数=}（<b>纯读数</b>）：从 {@code noCandWhy} 现成串自带的身份标
         * {@code #i<hex>#slotN} 回查本轮任务表上的样板实例，取该槽声明的
         * {@code IInput.getMultiplier()}——串里那个 {@code x1} 是 template 量（AE2 恒 1），
         * 每发件数不印出来就永远差这一问。身份标在现成串里、实例在任务表里，两本都是现成的账；
         * 回查不到（本轮没记过、样板已出表、槽号越界）就打 {@code "?"}，不另记一份现场。
         */
        private static String describeNoCandPerFiring(ParallelExecutingCraftingJob job,
                String noCandKey) {
            if (job == null || noCandKey == null || noCandKey.isEmpty()) {
                return "?";
            }
            var matcher = NO_CAND_OWNER.matcher(noCandKey);
            if (!matcher.find()) {
                return "?";
            }
            var tag = "#i" + matcher.group(1);
            int slotIndex;
            try {
                slotIndex = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException invalid) {
                return "?";
            }
            for (var details : job.tasks.keySet()) {
                // 串里点名的是实例哈希段（{@code #i<hex>}）；跨会话摘要 {@code #s<hex>} 跟在它后面，
                // 回查只认到哈希那一格为止——用 {@code tag + "#"} 前缀把边界钉在哈希后、摘要前，
                // 不会把 {@code #i1a} 误配到 {@code #i1ab}。
                if (details == null || !patternIdentityTag(details).startsWith(tag + "#")) {
                    continue;
                }
                var slots = details.getInputs();
                if (slots == null || slotIndex < 0 || slotIndex >= slots.length
                        || slots[slotIndex] == null) {
                    return "?";
                }
                return String.valueOf(slots[slotIndex].getMultiplier());
            }
            return "?";
        }

        /**
         * 停滞行的 {@code backoffBlind=} 读数（<b>纯读数</b>：不参与任何判据，也不改变退避的进入与
         * 解除）：被缺料退避跳过的那些样板，<b>当轮真失败的那一格</b>。
         *
         * <p>补的是 {@code materialBackoff=} 那一格定不了因的缝：{@code executeCrafting} 里
         * {@code backoffUntil > currentTick} 直接 {@code continue} ⇒ 这张样板<b>压根不产生 attempts</b>，
         * 于是 {@code materialBackoff=18} 只说得出"18 次被跳过"，看不出它<b>上一轮为什么失败</b>；
         * 而它当初欠哪些键记在 {@code materialBackoffMissing} 那本现成的账里（只为 {@code postChange}
         * 精准唤醒而写，<b>从不打印</b>）。这一格把那本账落成读数："真缺料"与"规划交来的任务表
         * 不可满足"这两种在旧日志里长得一模一样的病，从此有数可比。
         *
         * <p>每格 {@code [样板头#i<身份>#slotN,欠的那一枚键,每发=…,池内=…,网络sim=…,mountsInUse=?]}：
         * <ul>
         * <li>点名哪一格：读现成的 {@code materialBackoffMissing}，按<b>样板声明的槽位序</b>过一遍，
         * 命中缺口键的那个候选就是那一格——不新开第二本账；</li>
         * <li>{@code 每发=} 走 {@code SlotAccount.unitsPerFiringExact}、{@code 池内=} 走池账唯一口径
         * {@link ParallelBatchExtraction#exactStored}：与 {@code blocked=} 那一格<b>同一道口</b>，不另摆
         * 一把尺——{@code materialBackoffMissing} 命中那一枚是候选表上的<b>声明键</b>，值对象主键的
         * 第三方键按声明键裸读恒 0，那一格就把"池里还剩 8 枚"报成"池里一枚没有"；</li>
         * <li>{@code 网络sim=} 与同一行 {@code quotaBlind=} 的 {@code net=} 走<b>同一条</b> SIMULATE
         * 通道：一次问得出至多一个 {@code long}，故只能当<b>下界</b>读；网格不在场即 {@code ?}，照常点名。
         * <b>这一腿不是"值对象主键"那一族</b>（0929 上一轮报的"网络侧读数同一型也报 0"是它的<b>假设</b>，
         * 本轮读 AE2 实现后作废）：这三格问的是 {@code MEStorage.extract(key, Long.MAX_VALUE, SIMULATE, …)}，
         * 那条通道全程不按 {@code getPrimaryKey()} 的引用分格——{@code StorageService.java:195-197} 的
         * {@code getInventory()} 交回 {@code NetworkStorage}，{@code NetworkStorage.java:173-200} 只逐台
         * 挂载存储委派 {@code inv.extract(what, …)}、自己不索引键，叶子按<b>值</b>认键
         * （{@code BasicCellInventory.java:177-183} 的 {@code storedAmounts} 是 {@code Object2LongOpenHashMap}，
         * 取数在 {@code :406 getCellItems().getLong(what)}；{@code GenericStackInv.java:181-188} 逐槽比
         * {@code currentWhat.equals(what)}；{@code CompositeStorage.java:55-57} 的分派键是注册表单例
         * {@code AEKeyType}）。那张按引用分格的 {@code KeyCounter} 主索引（{@code KeyCounter.java:48}，
         * 取数 {@code :124-131}）只在<b>我们的容器</b>（池账与产出缓冲）与 {@code getAvailableStacks(out)}／
         * {@code getCachedInventory()} 那本聚合出参上，这三格都不读它 ⇒ 值对象主键的第三方键在网络这一侧
         * 读得出量：{@code 网络sim=0} 就是网络真没有，不必再当同型缺陷提（要自证它看不见什么，读的是
         * {@code 池内=} 那一格与同一行的退避在册键，不是这一格）。</li>
         * </ul>
         *
         * <p>点名序＝执行侧推送顺位（{@code rationPatternsInExecutionOrder} 那把全序尺），不吃
         * {@code HashMap} 的遍历序（台账 #135）。封顶四格（见 {@code BACKOFF_BLIND_CELL_LIMIT}），超出的
         * 格数写成 {@code backoffBlind更多=N} 紧跟在同一格里（形状照 {@code obWhy=}／
         * {@code declaredReject=} 那一族，不粘在最后一格尾巴上）。
         *
         * <p>整格<b>零空白</b>：样板头与候选键的第三方空格（{@code AEItemKey#toString} 带组件时的
         * {@code " (with patches)"}）在这一格的出口一次折成 {@code "_"}；自家子字段边界一律用不带空白的
         * {@code ,}、格间用 {@code /}。没有样板在退避就是 {@code <none>}，不印空壳。
         */
        static String describeBackoffBlind(@Nullable ParallelExecutingCraftingJob job,
                ListCraftingInventory inventory, @Nullable appeng.api.networking.IGrid grid) {
            if (job == null || job.materialBackoffUntil.isEmpty()) {
                return "<none>";
            }
            var cells = new java.util.ArrayList<String>();
            long dropped = 0L;
            for (IPatternDetails pattern : job.rationPatternsInExecutionOrder()) {
                if (pattern == null || !job.materialBackoffUntil.containsKey(pattern)) {
                    continue; // 只点这一轮真被退避挡住的那几张
                }
                var missing = job.materialBackoffMissing.get(pattern);
                var slots = pattern.getInputs();
                if (missing == null || missing.isEmpty() || slots == null) {
                    continue; // 没记下缺口键就报不出那一格，不印空壳
                }
                for (int slotIndex = 0; slotIndex < slots.length; slotIndex++) {
                    var input = slots[slotIndex];
                    var candidates = input == null ? null : input.getPossibleInputs();
                    if (candidates == null) {
                        continue;
                    }
                    for (var candidate : candidates) {
                        if (candidate == null || candidate.what() == null
                                || !missing.contains(candidate.what())) {
                            continue; // 这一格欠的不是它
                        }
                        if (cells.size() >= BACKOFF_BLIND_CELL_LIMIT) {
                            dropped++;
                            continue;
                        }
                        AEKey key = candidate.what();
                        var perFiring = git.chexson.chexsonsaeutils.crafting.material.SlotAccount
                                .unitsPerFiringExact(input, candidate);
                        // 与 blocked= 那一格同一把尺（池账唯一口径）：欠的那一枚是候选表上的声明键，
                        // 值对象主键的第三方键按声明键裸读恒 0——这一格报的就是"池里真还剩几枚"。
                        var stored = ParallelBatchExtraction.exactStored(inventory, key);
                        var storage = grid == null || grid.getStorageService() == null
                                ? null : grid.getStorageService().getInventory();
                        var net = storage == null ? "?" : Long.toString(Math.max(0L,
                                storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE,
                                        appeng.api.networking.security.IActionSource.empty())));
                        cells.add(pattern.getDefinition() + patternIdentityTag(pattern)
                                + "#slot" + slotIndex
                                // 欠的是哪一枚键（materialBackoffMissing 命中的那一个候选）——与
                                // blocked= 的候选网格同一形状：键头紧跟槽号，子字段用 ',' 相连。
                                + "," + key
                                + ",每发=" + perFiring
                                + ",池内=" + stored
                                // 同一条 SIMULATE 嘴的"闸抬着也交 0"那一档问不出（读数 7）：如实问号。
                                + ",网络sim=" + net + ",mountsInUse=" + mountsInUseReadout());
                    }
                }
            }
            if (cells.isEmpty()) {
                return "<none>";
            }
            var described = new StringBuilder("[");
            if (dropped > 0L) {
                described.append("backoffBlind更多=").append(dropped).append('/');
            }
            described.append(String.join("/", cells)).append(']');
            // 格出口一次折净：剩下的空白恒是别人家的（样板头与候选键的 AEItemKey#toString）。
            return described.toString().replace(' ', '_');
        }

        /**
         * 旧 long 账读口的兼容外形（既有用例与真 {@code long} 合计都从这一口进）：把交进来的
         * {@code long} 值原样升宽一级，交回唯一渲染器——域内值逐字不变，这一口不持有第二份拼串。
         */
        static String formatAllocation(ParallelExecutingCraftingJob job,
                java.util.Map<AEKey, Long> pool, java.util.Map<AEKey, Long> deficits,
                java.util.Map<AEKey, Long> stillEmittable,
                @Nullable appeng.api.networking.IGrid grid) {
            return formatAllocation(job, pool, toWideAmounts(deficits), toWideAmounts(stillEmittable),
                    java.util.List.of(), grid);
        }

        /** 把一张 {@code AEKey→long} 的合计原样升宽成 {@code AEKey→BigInteger}（值零变，纯换类型）。 */
        private static java.util.Map<AEKey, BigInteger> toWideAmounts(java.util.Map<AEKey, Long> narrow) {
            var wide = new java.util.LinkedHashMap<AEKey, BigInteger>();
            for (var entry : narrow.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    wide.put(entry.getKey(), BigInteger.valueOf(entry.getValue()));
                }
            }
            return wide;
        }

        /**
         * 配给对账的<b>唯一渲染口</b>：缺口键在提交期配给里还剩多少没销、实际提走了多少、池里还有多少，
         * 以及这一键"计划说会产多少 / 报称产了多少 / 还剩多少没做完"。
         *
         * <p><b>排序吃宽域真值</b>（台账 #192）：饱和 long 是哨兵不是量——{@code >long} 的缺口全都读成
         * 同一道天花板时，前四格的先后由登记序决定，实机 19:46 那一行 4 格被四个顶格键占满、真卡死的
         * {@code digital_singularity_flux_storage_cell} 一个字都没有。宽域真值降序把欠得最狠的那格
         * 顶到头几名。
         *
         * <p><b>必含格</b>：本窗 {@code blocked=}／{@code 回补受阻} 点名的键（名单由
         * {@link StallProbe#blockedNamedKeys} 从现成的 Probe 账取，不新开第二本账）逐字走同一行
         * 渲染器补进行尾——缺口排序挤不进前四 ≠ 看不见，链条上一环不许被顶名的键挤掉。
         *
         * <p>读数分工：{@code left>0 stored=0 ann<planEmit} 是生产者没跑够（派发侧）；
         * {@code left>0 stored=0 ann==planEmit} 是<b>规划算错量</b>——计划产的比计划要吃的少一截；
         * {@code left=0 stored>0} 才是夹错人（份额之外没散量，别人却还留着料）。
         *
         * <p>非 {@code private}：{@code planNet=} 那一格的<b>格名</b>是判据的一部分（它报的是预期，
         * 不许再写成"已入网"那副交付口径），由
         * {@code ParallelDeclaredBatchNetworkDeliveryTest#expectedChunkIsPrintedAsPlannedNotAsDelivered}
         * 从这一个渲染口钉住。
         */
        static String formatAllocation(ParallelExecutingCraftingJob job,
                java.util.Map<AEKey, Long> pool, java.util.Map<AEKey, BigInteger> deficits,
                java.util.Map<AEKey, BigInteger> stillEmittable,
                java.util.Collection<AEKey> blockedNamedKeys,
                @Nullable appeng.api.networking.IGrid grid) {
            if (!job.materialAllocation.hasAllocation()) {
                return " alloc=<no-plan>";
            }
            var rows = new java.util.ArrayList<>(deficits.entrySet());
            // 宽域真值降序：Comparator.reverseOrder() 走 BigInteger.compareTo，饱和哨兵不再参与比大小。
            rows.sort(java.util.Comparator
                    .comparing((java.util.Map.Entry<AEKey, BigInteger> row) -> row.getValue(),
                            java.util.Comparator.reverseOrder()));
            var described = new StringBuilder(" alloc=[");
            var printed = new java.util.LinkedHashSet<AEKey>();
            for (int i = 0; i < Math.min(4, rows.size()); i++) {
                if (i > 0) {
                    described.append(' ');
                }
                var key = rows.get(i).getKey();
                described.append(describeAllocationRow(job, pool, stillEmittable, key));
                if (key != null) {
                    printed.add(key);
                }
            }
            // 必含格：blocked=／回补受阻 点名的键没挤进前四也要单独成行（名单登记序即打印序，不重排）。
            for (var key : blockedNamedKeys) {
                if (key == null || printed.contains(key)) {
                    continue;
                }
                if (!printed.isEmpty()) {
                    described.append(' ');
                }
                described.append(describeAllocationRow(job, pool, stillEmittable, key));
                printed.add(key);
            }
            // 行源 deficits 的盲区补格：缺口为空时上面一行都不印，"这份任务表里到底有没有一张样板
            // 产这一枚"就成了暗账——按配额单独点名，与 alloc= 行同一把尺（只说得到任务表这一层）。
            return described.append(']').append(formatQuotaBlindspots(job, pool, grid)).toString();
        }

        /**
         * {@code alloc=} 的一行读数。<b>六列（left/taken/ann/ret/toMake/planNet）交回宽域真值</b>
         * （台账 #192：&gt;long 就印真值，不再走 {@code Ae2Amounts.toLong} 饱和出口——把两本宽域账的
         * 天花板读数当量摆出去，正是"left=0 配额用尽"那一型误判的入口）。
         * {@code planEmit=} 读的是提交瞬间的计划量快照：这本账与 {@code taken/ann/ret} 同为
         * {@code WideKeyCounter}（任务 #219 条目⑥），这里印它的<b>精确原文</b>——写入侧不再在
         * {@code long} 收口，读数口也就不必有"账面真值 vs 饱和镜像"两把尺。
         * 缺口与份额的算式一字未动：这里撤掉的只是出口饱和。
         */
        private static String describeAllocationRow(ParallelExecutingCraftingJob job,
                java.util.Map<AEKey, Long> pool, java.util.Map<AEKey, BigInteger> stillEmittable,
                AEKey key) {
            // 这两本配给账内部走宽域（配给按槽、已吃按批复加，合计可越界）：诊断在 PlanAmount 上
            // 精确做差，读数直接交精确十进制原文（PlanAmount.toString 域内即 long 原文、越界即真值）。
            PlanAmount left = PlanAmount.ZERO;
            for (var row : job.materialRows()) {
                // 与封顶同口径：份额按物品本体族合计（配给可能记在带组件的变体键上）。
                PlanAmount planned = job.materialAllocation.assignedFamilyAmount(row.pattern(), key);
                if (planned.signum() <= 0L) {
                    continue;
                }
                left = left.add(job.materialAllocation.rawFamilyBalance(row.pattern(), key));
            }
            return new StringBuilder().append(key)
                    .append(" left=").append(left)
                    // 这三本流水账（taken/ann/ret）内部走宽域（跨批复加精确、不回绕），这一行是它们
                    // 唯一的对外读数口：交 WideKeyCounter.getExact 的精确十进制原文，>long 印真值——
                    // 饱和哨兵不再冒充量（旧 getLong 出口把溢出键钉在天花板上，四格同值分不出轻重）。
                    // planNet= 的口径一字未变：预期入网那一截对外的唯一读口是
                    // ParallelExecutingCraftingJob#expectedToNetwork（宽域精确、不饱和，d7e8f61
                    // 收口后打印口与用例同读这一个 accessor），下面印的是它的 exactValue。
                    .append(" taken=").append(job.accountTaken.getExact(key))
                    .append(" stored=").append(pool.getOrDefault(key, 0L))
                    .append(" planEmit=").append(job.accountPlannedEmit.getExact(key))
                    .append(" ann=").append(job.accountAnnounced.getExact(key))
                    .append(" ret=").append(job.accountReturned.getExact(key))
                    // 预期入网的那一截永远不会回池：不给这一读数，ann>ret 就会被读成"产出漏了"。
                    // 格名带 plan 前缀，是因为它出自宿主自报的计划量、不是入网回执；而产物侧
                    // 压根没有"真进了网络"的回执读数——blocked= 行里的 网络sim=/同族[…]下界 是
                    // 投入侧读数（义务回补那一格材料向网络现问的 SIMULATE 现量），产物键从不进
                    // 它那一格，说不了产物进没进网。旧注释把它写成产物的交付证据，是声称存在
                    // 却在代码中不存在的判据，照 9a3bd59 删 ann-ret-预期入网 假判据的口径改口。
                    .append(" planNet=").append(job.expectedToNetwork(key).exactValue())
                    // 交割账那一本从此不再是"零读者"（台账 #225 读数 4）：planNet= 是待交割的预期总量，
                    // 这两格并排交出同一键的<b>已交割</b>与<b>还挂着没等到终局</b>的逐键真值
                    // （{@code ParallelExecutingCraftingJob#declaredNetworkDeliveredAmount} /
                    // {@code #declaredNetworkUndelivered}，#225 那一轮审计时它生产代码<b>零读者</b>）。
                    // 这一行本身仍是<b>只印不判</b>：格名、算式、逐键口径一字未动。而 #227 K2 之后
                    // 收工第二臂读的是<b>同一处算式</b>的全键合计
                    // （{@code #declaredNetworkUndeliveredTotal}），不是第二把尺——读日志的人拿这一行
                    // {@code undelivered=} 那一格与臂的判决对得上，因为它俩减的是同一对本数。
                    // 饱和哨兵夹过的那一本带 (clamped) 标记，不许被当成一个真数去对平。
                    .append(" delivered=").append(job.declaredNetworkDeliveredAmount(key).exactValue())
                    .append(job.declaredDeliveryClamped() ? "(clamped)" : "")
                    .append(" undelivered=").append(job.declaredNetworkUndelivered(key).exactValue())
                    .append(" toMake=").append(stillEmittable.getOrDefault(key, BigInteger.ZERO))
                    // 生产者溯源：left>0 而 taken=0 时，这一格能说的是"这份<b>任务表</b>里有没有一张样板
                    // 的输出含这个键"。src= 读的是 {@code job.tasks}（{@link #describeProducers}），
                    // <b>且不按剩余发数过滤</b>——所以 {@code src=NONE ∧ toMake=0} 只说到任务表这一层：
                    // "这张任务表里没有一张样板的输出含这个键"，<b>不等于</b>"图上没有生产路线"。
                    // 要看图上有没有路线，得读规划侧 {@code GraphPlanPostProcessor#describeHoles} 的
                    // {@code producers=}（⑧ idle 那一行的同一把尺），这里说不了图的拓扑。
                    .append(" src=").append(describeProducers(job, pool, key)).toString();
        }

        /**
         * 纯读数一格：对每个<b>剩余配额 &gt; 0 而池子与网络都 0</b> 的键，印它的生产者
         * （复用 {@link #describeProducers}）或 {@code NONE}。
         *
         * <p>要分开的正是下一跑的两种病：{@code src=NONE}＝这份<b>任务表</b>里没有一张样板的输出
         * 含这一键（{@link #describeProducers} 读 {@code job.tasks}、不按剩余发数过滤，只说得到
         * 任务表这一层，<b>不等于</b>"图上没有生产路线"——要看图上有没有路线得读规划侧
         * {@code GraphPlanPostProcessor#describeHoles} 的 {@code producers=}）；
         * {@code src=某样板#剩余发数<-它的缺口输入}＝任务表里有它、却没跑（派发/依赖问题）。
         * 配额读的是 {@code planMaterialPlan − planMaterialEaten} 族口径宽域差，出口走唯一饱和
         * 读数 {@link Ae2Amounts#toLong}；网络读数一次 SIMULATE（{@code grid} 缺席时打 {@code ?}，
         * 照常点名——宁可多看一眼，不许盲区回来）。无限源键不点名：它的配额本就不夹。
         * 这一格 {@code net=} 与 {@code backoffBlind=} 的 {@code 网络sim=} 同一条通道，也<b>不是</b>
         * "值对象主键"那一族（AE2 的 {@code MEStorage.extract} 按 {@code equals}/{@code hashCode}
         * 认键）——判定与行号依据集中写在 {@link StallProbe#describeBackoffBlind} 的注释里，这里不重述。
         */
        static String formatQuotaBlindspots(ParallelExecutingCraftingJob job,
                java.util.Map<AEKey, Long> pool, @Nullable appeng.api.networking.IGrid grid) {
            if (!job.materialAllocation.hasAllocation()) {
                return "";
            }
            var lefts = new java.util.LinkedHashMap<AEKey, PlanAmount>();
            var seenFamilies = new java.util.HashSet<java.lang.Object>();
            // 外层与内层都按配给表的全序读口走（一次物化，两层共用同一份序）：lefts 的登记序决定
            // rows（只按 quota 数值稳定排序）里并列项的先后，那一档就是这一格点名的前 4 个键。
            // IdentityHashMap 的遍历序＝System.identityHashCode 的序，跨会话翻 ⇒ 点名换人
            // （台账 #135 第 2 条）。
            var rationOrder = job.materialRows();
            for (var row : rationOrder) {
                for (var amount : row.assigned()) {
                    AEKey key = amount.key();
                    if (key == null || !seenFamilies.add(key.getPrimaryKey())
                            || job.isUnboundedSupply(key)) {
                        continue;
                    }
                    PlanAmount left = PlanAmount.ZERO;
                    for (var other : rationOrder) {
                        PlanAmount planned = job.materialAllocation.assignedFamilyAmount(other.pattern(), key);
                        if (planned.signum() <= 0L) {
                            continue;
                        }
                        left = left.add(job.materialAllocation.rawFamilyBalance(other.pattern(), key));
                    }
                    if (left.signum() > 0L) {
                        lefts.put(key, left);
                    }
                }
            }
            if (lefts.isEmpty()) {
                return "";
            }
            var rows = new java.util.ArrayList<>(lefts.entrySet());
            rows.sort(java.util.Comparator
                    .comparing((java.util.Map.Entry<AEKey, PlanAmount> row) -> row.getValue(),
                            java.util.Comparator.reverseOrder()));
            var described = new StringBuilder(" quotaBlind=[");
            int printed = 0;
            for (var row : rows) {
                AEKey key = row.getKey();
                var family = key.getPrimaryKey();
                long stored = 0L;
                for (var entry : pool.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null
                            && family.equals(entry.getKey().getPrimaryKey())
                            && entry.getValue() > 0L) {
                        stored = ClampedMath.addUp(stored, entry.getValue());
                    }
                }
                boolean netKnown = grid != null && grid.getStorageService() != null
                        && grid.getStorageService().getInventory() != null;
                long net = netKnown ? Math.max(0L, grid.getStorageService().getInventory()
                        .extract(key, Long.MAX_VALUE, Actionable.SIMULATE,
                                appeng.api.networking.security.IActionSource.empty())) : 0L;
                if (stored > 0L || (netKnown && net > 0L)) {
                    continue;
                }
                if (printed > 0) {
                    described.append(' ');
                }
                described.append(key).append(" quota=").append(Ae2Amounts.toLong(row.getValue()))
                        .append(" stored=0 net=").append(netKnown ? Long.toString(net) : "?")
                        // 读数 7（台账 #225）：这一格点名的全是"池内 0 且网络也读 0"的那几枚，
                        // 而 NetworkStorage 那道递归闸抬着时同样交回一个干净的 0
                        // （NetworkStorage.java:173-176，私有字段无公开读口）——问不出，如实问号。
                        .append(" mountsInUse=").append(mountsInUseReadout())
                        .append(" src=").append(describeProducers(job, pool, key));
                if (++printed >= 4 || described.length() > 400) {
                    break;
                }
            }
            return printed == 0 ? "" : described.append(']').toString();
        }

        /**
         * 缺口键的生产者点名：任务表里谁的产出含这一键、还剩几发、在不在退避里。
         * {@code #} 是精确键，{@code ~fam#} 是同本体族变体（产的不是同一个键，勉强算来源）。
         * 只取前两个，够定性即可——本行只在停滞快照里算，但任务表可能有几千条。
         *
         * <p><b>口径更正（⑨）</b>：这一格读的是 {@code job.tasks}（<b>任务表</b>），且<b>不按剩余发数
         * 过滤</b>（{@code #} 后带的 {@code describeStallTaskFirings} 只是把发数照抄出来，不作为筛子）。
         * 所以 {@code src=NONE} 的准确含义是"<b>这份任务表里没有一张样板的输出含这个键</b>"，
         * 它<b>不等于</b>"图上没有生产路线"——图上的拓扑这一格压根看不见。{@code src=NONE ∧ toMake=0}
         * 也只说到任务表这一层。要看图上到底有没有生产这一枚的路线，得读规划侧
         * {@code GraphPlanPostProcessor#describeHoles} 的 {@code producers=}（⑧ idle 那一行的同一把尺）。
         * 旧话术把 {@code src=NONE} 说成"没有生产者/规划假闭合"，越过了任务表能证到的范围。
         */
        /**
         * 缺口键的一层依赖溯源：谁产它（剩几发、在不在退避）、它自己又要哪些键、那些键现场有多少。
         *
         * <p>要判的是"生产者明明在表里、却一次都没申报（{@code ann=0}）"这一形态：
         * 任务级图是无环的（{@code ringDiag scc2plus=0}），但<b>键级</b>可以成环——万象样板这类
         * "任意料互转"的样板会让 A→B 与 B→A 同时进计划，任务图上仍是两张独立样板，
         * 键图上却是死循环：谁都要等对方先出料，于是全员 {@code ann=0}、全员 {@code noCand}。
         * 一行里把 {@code src=生产者#剩余发数[退避]<-它的缺口输入(现场量)} 摆出来即可定性。
         */
        private static String describeProducers(ParallelExecutingCraftingJob job,
                java.util.Map<AEKey, Long> pool, AEKey key) {
            var family = key.getPrimaryKey();
            var found = new StringBuilder();
            int listed = 0;
            for (var task : job.tasks.entrySet()) {
                var details = task.getKey();
                if (details == null || details.getOutputs() == null) {
                    continue;
                }
                boolean exact = false;
                boolean relative = false;
                for (var output : details.getOutputs()) {
                    if (output == null || output.what() == null) {
                        continue;
                    }
                    if (output.what().equals(key)) {
                        exact = true;
                        break;
                    }
                    if (output.what().getPrimaryKey().equals(family)) {
                        relative = true;
                    }
                }
                if (!exact && !relative) {
                    continue;
                }
                if (found.length() > 0) {
                    found.append(',');
                }
                found.append(details.getDefinition())
                        // 生产者点名要点到<b>实例</b>：alloc= 与 quotaBlind= 的 src= 与 noCandWhy=
                        // 说的是不是一张样板，只看 definition 永远对不上（同名两张是 equals 同一个键）。
                        .append(patternIdentityTag(details))
                        .append(exact ? '#' : "~fam#")
                        .append(describeStallTaskFirings(task.getValue()));
                if (job.materialBackoffUntil.containsKey(details)) {
                    found.append("[backoff]");
                }
                found.append("<-").append(describeBlockedInputs(details, pool));
                if (++listed >= 2 || found.length() > 400) {
                    break;
                }
            }
            return found.length() == 0 ? "NONE" : found.toString();
        }

        /**
         * 这张样板的输入里，哪些键<b>在池子里</b>现场是空的／还差多少——最多点三个，够定位卡在哪一口。
         *
         * <p><b>这一格只读池子，读得出多少就说多少</b>：每个槽只看候选表<b>第一个键</b>的
         * {@code >0}。它<b>不读</b> {@code pendingPoolClaims}（别人欠账遮着的那一截）、<b>不读</b>
         * 网络、<b>不读</b>每发件数与份数——所以 {@code poolFirst>0} 只说"池里首个候选有余量"，
         * <b>绝不等于"这一格能开工"</b>：一份料被欠账遮着、或够一枚却不够一发时，这里照样印
         * {@code poolFirst>0}。旧措辞 {@code ready} 会被读成后者，实机就照着它把"输入齐备"判成
         * 了能开工（{@code src=…<-ready} 那一格说谎即此）。
         *
         * <p><b>纯读数</b>：判据方向一字未改，只改措辞。
         */
        private static String describeBlockedInputs(IPatternDetails details,
                java.util.Map<AEKey, Long> pool) {
            var described = new StringBuilder();
            int blindSlots = 0;
            for (var input : details.getInputs()) {
                if (input == null) {
                    continue;
                }
                var candidates = input.getPossibleInputs();
                if (candidates == null || candidates.length == 0) {
                    // 候选表为空不等于"就绪"：提取口根本不知道这一格要拿什么付（ncNoListing 同型）
                    blindSlots++;
                    continue;
                }
                for (var candidate : candidates) {
                    if (candidate == null || candidate.what() == null) {
                        continue;
                    }
                    long stored = pool.getOrDefault(candidate.what(), 0L);
                    if (stored > 0L) {
                        break; // 这一格池里首个候选有余量（不等于能开工，见本成员抬头）
                    }
                    if (described.length() > 0) {
                        described.append('/');
                    }
                    described.append(candidate.what()).append("=0");
                    break;
                }
                if (described.length() > 160 || described.indexOf("/") >= 2) {
                    return described.toString();
                }
            }
            if (blindSlots > 0) {
                return described.length() == 0
                        ? "no-candidates x" + blindSlots
                        : described + " +no-candidates x" + blindSlots;
            }
            return described.length() == 0 ? "poolFirst>0" : described.toString();
        }

        /**
         * 配给外的取料点名：销账超过配额的 {@code (样板, 键)}，以及压根没配给却已在销账的样板。
         *
         * <p>配额是硬上限，超了就说明有一条取料路径没走配给（环成员样板、模具槽例外、
         * 或表里根本没这张样板）。尾段 {@code taken > planEmit} 的那几十枚就是从这里漏的。
         */
        private static String formatOverTakes(ParallelExecutingCraftingJob job) {
            var rows = new StringBuilder();
            // 点名序＝配给表的全序读口（IdentityHashMap 的遍历序随样板身份哈希翻，同一份 over=
            // 行两次会话能给出相反的行序，400 字符那一刀截掉的就是不同的那几行；台账 #135 第 2 条）。
            // 已吃账的每一张都来自推送路（销账只按任务表上的键写），任务表条目恒在这份序里。
            for (var row : job.materialRows()) {
                var details = row.pattern();
                var seenFamilies = new java.util.HashSet<Object>();
                for (var amount : row.eaten()) {
                    AEKey tookKey = amount.key();
                    if (tookKey == null) {
                        continue;
                    }
                    // 与封顶同轴：ate 与 share 都按物品本体族合计。原先 ate 取逐精确键、
                    // share 取族合计，探针混轴会把同物变体报成超额（2026-09-19 复核坐实）。
                    var family = tookKey.getPrimaryKey();
                    if (!seenFamilies.add(family)) {
                        continue;
                    }
                    // 族合计走宽域，比较也在宽域上比：两侧都顶到 long 天花板时，
                    // long 相减读不出"到底超了几枚"，宽域读数才认得清（出口按饱和收口）。
                    PlanAmount ate = job.materialAllocation.eatenFamilyAmount(details, tookKey);
                    PlanAmount share = job.materialAllocation.assignedFamilyAmount(details, tookKey);
                    if (ate.compareTo(share) <= 0) {
                        continue;
                    }
                    if (rows.length() > 0) {
                        rows.append(", ");
                    }
                    rows.append(details.getDefinition()).append('#')
                            .append(details.getClass().getSimpleName())
                            .append(' ').append(tookKey).append(" ate=")
                            .append(Ae2Amounts.toLong(ate))
                            .append(" share=").append(Ae2Amounts.toLong(share));
                    if (rows.length() > 400) {
                        return " over=[" + rows.append("...]").append(']');
                    }
                }
            }
            return rows.length() == 0 ? "" : " over=[" + rows + ']';
        }

        /** 缺料扫描结果：每键缺口（宽域真值），与剩余任务自身能产出的每键量（宽域真值）。 */
        private record StallScan(java.util.Map<AEKey, BigInteger> deficits,
                java.util.Map<AEKey, BigInteger> emitted) {
        }

        /**
         * 一次性缺料扫描：每个任务每槽按首选模板估缺口（需 宽域每发件数 × 剩余发数 − 池内精确现量），
         * 同时汇总剩余任务能产出的每键量。
         *
         * <p>缺口量≈产出量 = 这批任务自平衡闭合，起步那份既不在池里也不在网络里。
         *
         * <p><b>乘法、比较与减法全在宽域真值上做，交回的读数同样是宽域真值</b>：{@code value} 那份
         * 饱和镜像越界时就是 {@code Long.MAX_VALUE} 那道哨兵，拿它当乘数造出"真需 3e19 读
         * {@code Long.MAX}"，再与同一天花板读数比大小（真池 1.8e19 也读 {@code Long.MAX}）判"够"，
         * {@code missing=[]} 空报。发数读 {@link ParallelExecutingCraftingJob.TaskProgress#exactRemaining()}、
         * 池子读 {@code poolExact}（{@link #snapshotPoolExact}）。交回的合计<b>不再</b>走饱和 long 出口
         * （台账 #192：饱和哨兵当量＝不是量——顶到同一道天花板的键在排序里分不出先后，
         * 真值最大的那一格反而被挤出读数头几名）。乘法与减法的算式一字未动，撤掉的只是出口饱和。
         * 纯读数：只喂停滞行，不参与任何判据。
         */
        private static StallScan collectDeficits(
                ParallelExecutingCraftingJob job,
                java.util.Map<AEKey, BigInteger> poolExact
        ) {
            java.util.Map<AEKey, BigInteger> deficits = new java.util.LinkedHashMap<>();
            java.util.Map<AEKey, BigInteger> emitted = new java.util.LinkedHashMap<>();
            for (var task : job.tasks.entrySet()) {
                IPatternDetails details = task.getKey();
                BigInteger remaining = task.getValue() == null
                        ? BigInteger.ZERO : task.getValue().exactRemaining().exactValue();
                if (details == null || remaining.signum() <= 0L) {
                    continue;
                }
                for (var output : details.getOutputs()) {
                    if (output != null && output.what() != null) {
                        // 禁掉裸 Long::sum：两笔 Long.MAX 直加会回绕成负数（emit= 报出负量）。
                        // 单键合计在宽域相乘、宽域相加，出口不再饱和——读数就是那一格的真枚数。
                        emitted.merge(output.what(),
                                BigInteger.valueOf(output.amount()).multiply(remaining),
                                BigInteger::add);
                    }
                }
                var inputs = details.getInputs();
                if (inputs == null) {
                    continue;
                }
                for (IPatternDetails.IInput input : inputs) {
                    if (input == null || input.getMultiplier() <= 0L) {
                        continue;
                    }
                    GenericStack[] possible = input.getPossibleInputs();
                    if (possible == null || possible.length == 0) {
                        continue;
                    }
                    // 与执行侧同一条顺位规则：按候选顺位看谁能整槽盖住（自偿还候选只需盖住一发），
                    // 全都盖不住才对第一个候选记缺口——改挑"最能盖住的那个"会把开不了工的任务判成就绪。
                    GenericStack first = null;
                    boolean fundable = false;
                    for (GenericStack candidate : possible) {
                        if (candidate == null || candidate.what() == null || candidate.amount() <= 0L) {
                            continue;
                        }
                        if (first == null) {
                            first = candidate;
                        }
                        // 需与池内现量都是宽域真值：两个天花板读数比大小会把"还差一截"判成"够"。
                        BigInteger need = SlotAccount.unitsPerFiringExact(input, candidate)
                                .multiply(SlotAccount.selfRepaying(input, candidate)
                                        ? BigInteger.ONE : remaining);
                        if (need.signum() <= 0L
                                || poolExact.getOrDefault(candidate.what(), BigInteger.ZERO)
                                        .compareTo(need) >= 0L) {
                            fundable = true;
                            break;
                        }
                    }
                    if (fundable || first == null) {
                        continue;
                    }
                    BigInteger firstNeed = SlotAccount.unitsPerFiringExact(input, first)
                            .multiply(SlotAccount.selfRepaying(input, first)
                                    ? BigInteger.ONE : remaining);
                    // 缺口合计走宽域精确相加：同一键跨任务复加不再被钉死在饱和天花板上。
                    deficits.merge(first.what(), firstNeed.subtract(
                                    poolExact.getOrDefault(first.what(), BigInteger.ZERO)),
                            BigInteger::add);
                }
            }
            return new StallScan(deficits, emitted);
        }

        /**
         * 缺料摘要，并对照网络存量——CPU 缺的键网络里却有海量存量 = 虚拟产出路由丢失
         * 实锤（产出进了普通网络而非回到 CPU 的 waitingFor）。
         *
         * <p>这一格 {@code net:} 与 {@code backoffBlind=} 的 {@code 网络sim=}、{@code quotaBlind=} 的
         * {@code net=} 同一条 SIMULATE 通道，也<b>不是</b>"值对象主键"那一族（AE2 的
         * {@code MEStorage.extract} 按 {@code equals}/{@code hashCode} 认键，不经 KeyCounter 那张
         * 按引用分格的主索引）——判定与行号依据集中写在
         * {@link StallProbe#describeBackoffBlind} 的注释里，这里不重述。
         */
        private static String formatDeficits(
                StallScan scan,
                @Nullable appeng.api.networking.IGrid grid
        ) {
            appeng.api.networking.storage.IStorageService storageService = grid == null
                    ? null
                    : grid.getStorageService();
            // 缺口与可产量都是宽域真值（collectDeficits 交回精确合计）：排序与读数吃真值，
            // >long 的缺口不再与顶格哨兵混成同一个数。
            java.util.Map<AEKey, BigInteger> deficits = scan.deficits();
            java.util.Map<AEKey, BigInteger> emitted = scan.emitted();
            if (deficits.isEmpty()) {
                return "";
            }
            StringBuilder missing = new StringBuilder(" missing=[");
            var ranked = new java.util.ArrayList<>(deficits.entrySet());
            ranked.sort(java.util.Map.Entry.<AEKey, BigInteger>comparingByValue(
                    java.util.Comparator.reverseOrder()));
            int printed = 0;
            for (var entry : ranked) {
                if (printed++ >= 10) {
                    missing.append("...(more)");
                    break;
                }
                if (printed > 1) {
                    missing.append(", ");
                }
                missing.append(entry.getKey()).append(" x ").append(entry.getValue());
                BigInteger canEmit = emitted.get(entry.getKey());
                if (canEmit != null && canEmit.signum() > 0L) {
                    missing.append(" (emit: ").append(canEmit).append(')');
                }
                if (storageService != null) {
                    long networkAvailable = Math.max(0L, storageService.getInventory()
                            .extract(entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE,
                                    appeng.api.networking.security.IActionSource.empty()));
                    if (networkAvailable > 0L) {
                        // 读数 7（台账 #225）：这一问走的是 NetworkStorage 那张嘴，递归闸抬着时它也
                        // 交 0（NetworkStorage.java:173-176）——这一档问不出，格内如实带一位问号。
                        missing.append(" (net: ").append(networkAvailable)
                                .append(" mountsInUse=").append(mountsInUseReadout()).append(')');
                    }
                }
            }
            return missing.append(']').toString();
        }

        /** 一个坏槽：槽号、最接近就绪的候选键与该键的单发需求/池内现量、逐候选描述。 */

        /**
         * 家族改道读数：{@code exclusive} 是只有一条路的槽在这一键上合计还要多少，
         * {@code unclaimed} 是谁都没配上配给的散量，{@code siblings} 是那一格其它候选
         * <b>此刻</b>在池子与网络里各有多少。
         *
         * <p>一起看才分得清：{@code exclusive>0} 而 {@code siblings} 全为 0 = 这一格按配给
         * 本该改道、却无处可退（规划借到的那份同族富余没被提进 CPU）；{@code siblings} 有量
         * 而仍卡住 = 取料侧没把配给兑现。
         */
    }
}
