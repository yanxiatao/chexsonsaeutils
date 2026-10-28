package git.chexson.chexsonsaeutils.crafting.submit;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.core.AELog;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;
import git.chexson.chexsonsaeutils.crafting.contract.NativePartialSubmitPolicy;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationTracker;
import git.chexson.chexsonsaeutils.crafting.contract.CraftingContinuationWaitingBranch;
import git.chexson.chexsonsaeutils.crafting.contract.CraftingContinuationWaitingDetail;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ElapsedTimeTrackerAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CraftingContinuationPartialSubmit {
    private CraftingContinuationPartialSubmit() {
    }

    public static boolean isPartialSubmitRequest(ICraftingPlan plan, CraftingContinuationMode mode) {
        return plan != null
                && plan.simulation()
                && mode == CraftingContinuationMode.IGNORE_MISSING;
    }

    public static boolean supportsPartialSubmitTarget(@Nullable ICraftingCPU target) {
        return !(target instanceof NativePartialSubmitPolicy policy) || policy.supportsNativePartialSubmit();
    }

    public static ICraftingSubmitResult submitPartialJob(
            IGrid grid,
            ICraftingPlan plan,
            @Nullable ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src
    ) {
        if (!(target instanceof CraftingCPUCluster cpuCluster)) {
            return CraftingSubmitResult.NO_CPU_FOUND;
        }

        CraftingCpuLogic logic = cpuCluster.craftingLogic;
        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) logic;
        if (logic.hasJob()) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (!cpuCluster.isActive()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (cpuCluster.getAvailableStorage() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }
        if (!logicAccessor.getInventory().list.isEmpty()) {
            AELog.warn("Crafting CPU inventory is not empty yet a continuation job was submitted.");
        }

        ICraftingSubmitResult submitResult = logic.trySubmitJob(
                grid,
                createNativeSubmissionPlan(plan),
                src,
                null
        );
        if (!submitResult.successful()) {
            return submitResult;
        }
        ExecutingCraftingJob job = logicAccessor.getJob();
        if (job == null) {
            AELog.error("AE2 accepted a continuation job without creating an executing job.");
            return CraftingSubmitResult.CPU_BUSY;
        }

        KeyCounter missingInitialItems = extractAvailableInitialItems(plan, grid, logicAccessor.getInventory(), src);
        seedInitialWaitingFor(job, missingInitialItems);
        cpuCluster.markDirty();
        recordWaitingDetail(cpuCluster, plan, job, missingInitialItems);

        return submitResult;
    }

    public static KeyCounter extractAvailableInitialItems(
            ICraftingPlan plan,
            IGrid grid,
            ListCraftingInventory cpuInventory,
            IActionSource src
    ) {
        return extractAvailableInitialItems(plan, grid, cpuInventory, src, null);
    }

    /**
     * 同四参入口，可选地按物品族记录实际到账量与仍需补齐的计划原料，用于诊断及配给算法验证。
     * {@code outsideArrivals} 是未到账、允许以后补齐的外部原料，包括宽域分段提取余量与真实缺料；
     * 它不表示其它分支已经派发的产物。部分提交保留计划配额，执行时仍以池内真实材料为限，
     * 不按提交瞬间的库存永久缩减可执行份额。
     */
    public static KeyCounter extractAvailableInitialItems(
            ICraftingPlan plan,
            IGrid grid,
            ListCraftingInventory cpuInventory,
            IActionSource src,
            @Nullable FamilyArrivalLedger familyArrivals
    ) {
        return extractAvailableInitialItemsFromStorage(plan, grid.getStorageService().getInventory(),
                cpuInventory, src, familyArrivals);
    }

    /** 存储提料逻辑只依赖公开库存接口；网络装配与精确账本计算分开。 */
    static KeyCounter extractAvailableInitialItemsFromStorage(ICraftingPlan plan, appeng.api.storage.MEStorage storage,
            ListCraftingInventory cpuInventory, IActionSource src, @Nullable FamilyArrivalLedger familyArrivals) {
        WideKeyCounter requiredInitialItems = requiredInitialAmounts(plan);
        KeyCounter missingInitialItems = new KeyCounter();

        for (AEKey what : requiredInitialItems.keys()) {
            var remaining = requiredInitialItems.getExact(what);
            if (what == null || remaining.signum() <= 0) continue;
            // 精确盘可一次交完整需求；普通盘仍只交一个 long 窗口
            boolean beyondOneChunk = remaining.compareTo(WideCeilings.LONG_ACCOUNT) > 0;
            long chunk = remaining.min(WideCeilings.LONG_ACCOUNT).longValue();
            var wanted = cpuInventory instanceof git.chexson.chexsonsaeutils.bigint.WideCraftingInventory
                    ? remaining : java.math.BigInteger.valueOf(chunk);
            var extracted = git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                    storage, what, wanted, Actionable.MODULATE, src);
            var gap = remaining.subtract(extracted);
            if (extracted.signum() > 0) {
                // 0 量插入会在池子里留下 0 行（底层 KeyCounter 走 fastutil addTo，建行不加值），
                // 而 list.isEmpty() 恒假会把 cantStoreItems 与 isActive() 一起点亮：
                // 这条 lane 再也回收不掉、每 tick 空转。
                if (cpuInventory instanceof git.chexson.chexsonsaeutils.bigint.WideCraftingInventory wide)
                    wide.insertExact(what, extracted, Actionable.MODULATE);
                else cpuInventory.insert(what, extracted.longValueExact(), Actionable.MODULATE);
            }
            if (familyArrivals != null) {
                // 到账一律记在族这根轴上，全到账（下面 gap<=0）那一档也记：封顶读的是族合计
                // （ParallelPlanReservation#shareOf），少记这一笔就等于把已经躺在池子里的那一格
                // 实物夹成短交那一档的量（台账 #131）。一枚都没抽到也要立条目——"这一族真到账 0"
                // 本身就是要把份额夹到 0 的那一笔（#117 的形状）。
                familyArrivals.noteExtracted(what, extracted);
            }
            if (gap.signum() <= 0L) {
                continue;
            }
            // 两张计划账都是外部原材料需求；缺口等待玩家补料，不是其它样板的在途产出。
            // 执行期仍需原定配额，不能因为提交瞬间库存为零就永久删除该份额。
            if (familyArrivals != null) familyArrivals.noteGapOutsideArrivals(what, PlanAmount.of(gap));
            if (beyondOneChunk) {
                // 并行 CPU 把宽域余量保存在 initialItemsRemaining，按预算续取。
                AELog.info("%s", describeShortfallRow("planned initial remainder", what, extracted, gap));
                continue;
            }
            missingInitialItems.add(what, gap.longValue());
        }

        return missingInitialItems;
    }

    /**
     * 提交腿短交读数一行（纯读数）：每键<b>已抽</b>（真到账量）与<b>短交</b>（宽域缺口）同时入眼。
     *
     * <p>旧形状里这两条 {@code continue} 分支要么一个数都不印、要么只印 {@code gap} 的
     * {@code long} 截断——"配额齐、池子空"的账面无从对起。{@code 短交=} 交 {@link java.math.BigInteger}
     * 十进制原文（宽域真值）；需要交回 {@code long} 的出口（挂账的 {@code missingInitialItems}）
     * 走的仍是同一份饱和口径，本行不参与任何判据。
     */
    static String describeShortfallRow(String reason, AEKey what, long extracted, java.math.BigInteger gap) {
        return describeShortfallRow(reason, what, java.math.BigInteger.valueOf(extracted), gap);
    }

    static String describeShortfallRow(String reason, AEKey what, java.math.BigInteger extracted,
            java.math.BigInteger gap) {
        return "[chexsonsaeutils-submit] " + reason + ":"
                + " key=" + what + " 已抽=" + extracted + " 短交=" + gap;
    }

    /**
     * 提交腿的到账账，记在<b>物品本体族</b>（{@code AEKey#getPrimaryKey()}）这根轴上。
     *
     * <p><b>为什么按族</b>：封顶那一侧读的是族合计（{@code ParallelPlanReservation#shareOf} 把该族
     * 所有变体加起来），到账若逐精确键交回，"同族某个变体<b>全到账</b>（{@code gap<=0}，现行代码
     * 那一支直接 {@code continue}）"这一档就成了账面黑洞——同族另一个变体短交一次，摊派时族池里
     * 没有它那一份，于是连那一格<b>已经躺在 CPU 池子里</b>的实物也被夹成短交那一档的量
     * （台账 #131：到账按精确键记、封顶却按族花）。两本账同轴才谈得上做差。
     *
     * <p>两笔读数，<b>都是量、都不是旗</b>（台账 #133）：
     * <ul>
     * <li>{@code arrived}＝这一族<b>真抽到手</b>的合计：宽域（{@link PlanAmount}），同族几个变体
     * 各自 {@code ≤long}、合计可以 {@code >long}，账本内部精确、绝不饱和——饱和只留给交回 AE2
     * 的那一道出口；</li>
     * <li>{@code outsideArrivals}＝这一族的缺口里<b>不落在到账这条轴上</b>的那一截合计：要么挂进了
     * 在途账（{@code waitingFor}，实物由别的分支做完回池），要么是"提交这一步本来就不搬、由执行侧
     * 按批从网络续取"的那一截（{@code >long} 需求喂满一整刀）。两者说的是同一件事——这一截有到账
     * 之外的兜底，收窄它就是把合法的量永久掐死，故照样并进摊派的池子。</li>
     * </ul>
     *
     * <p><b>为什么不再是一条布尔旗</b>：{@code 75c4e67} 把豁免记在<b>族根</b>上，于是同族只要有
     * 一个成员挂着兜底，{@code narrowPlanMaterialShareByArrival} 就整族 {@code return}——与它无关的
     * 那一格 declared-shortfall 假配额照样不夹（欠夹那一型）。换成量之后 {@code pool＝到账＋自产＋
     * 那一截}、{@code allowance＝min(pool, demand)}：兜底那一员自己摊得满（等于不收窄），真超配的
     * 那一截照旧夹住。豁免落在<b>哪一截</b>由记进账本的那个数说，不由"这一族里有没有人说过话"说。
     *
     * <p>它是提交这一次抽取的<b>瞬态读数</b>，不是第二本配额账：配给表 {@code used} 腿的唯一写口
     * 仍是 {@code ParallelExecutingCraftingJob#narrowPlanMaterialShareByArrival}。
     */
    public static final class FamilyArrivalLedger {
        private final Map<Object, FamilyArrival> byFamily = new LinkedHashMap<>();

        /**
         * 这一族的到账合计记一笔：抽到几枚加几枚，{@code extracted == 0} 也<b>立条目</b>——
         * "这一族真到账 0" 本身就是要份额夹到 0 的那一笔（#117 的形状），不是一条没话说。
         */
        public void noteExtracted(@Nullable AEKey what, long extracted) {
            noteExtracted(what, java.math.BigInteger.valueOf(extracted));
        }

        /** 精确到账入口；与普通到账共用族账，不截断大数实物。 */
        public void noteExtracted(@Nullable AEKey what, java.math.BigInteger extracted) {
            if (what == null) return;
            FamilyArrival arrival = entryOf(what);
            if (extracted.signum() > 0) arrival.arrived = arrival.arrived.add(PlanAmount.of(extracted));
        }

        /**
         * 这一族的兜底合计记一笔<b>量</b>：{@code gap} 是这一员短掉、却不落在到账轴上的那一截
         * （在途会回池／本步不搬由执行侧按批续取）。只记<b>真数</b>——非正数不立兜底，
         * 免得把"没话说"读成"有一截可以花"。
         */
        public void noteGapOutsideArrivals(@Nullable AEKey what, @Nullable PlanAmount gap) {
            if (what == null || gap == null || gap.signum() <= 0L) {
                return;
            }
            FamilyArrival arrival = entryOf(what);
            arrival.outsideArrivals = arrival.outsideArrivals.add(gap);
        }

        /** 本趟抽过的族，按首次见到的序。 */
        public Collection<FamilyArrival> families() {
            return byFamily.values();
        }

        private FamilyArrival entryOf(AEKey what) {
            return byFamily.computeIfAbsent(what.getPrimaryKey(), ignored -> new FamilyArrival(what));
        }

        /** 一个物品本体族在提交这一步的到账读数。 */
        public static final class FamilyArrival {
            /** 族内任一变体当代表：族口径判定（{@code getPrimaryKey()}／无限源豁免）问谁都同一个答案。 */
            private final AEKey sampleKey;
            private PlanAmount arrived = PlanAmount.ZERO;
            private PlanAmount outsideArrivals = PlanAmount.ZERO;

            private FamilyArrival(AEKey sampleKey) {
                this.sampleKey = sampleKey;
            }

            public AEKey sampleKey() {
                return sampleKey;
            }

            public PlanAmount arrived() {
                return arrived;
            }

            /** 这一族短掉、又有到账之外兜底的那一截合计（摊派池子的第二腿）。 */
            public PlanAmount outsideArrivals() {
                return outsideArrivals;
            }
        }
    }

    /** 合并计划两张外部原料账，先恢复各自真值再相加，避免同键 used + missing 回绕。 */
    public static WideKeyCounter requiredInitialAmounts(ICraftingPlan plan) {
        var required = new WideKeyCounter();
        var wide = WideAmountPlanCarrier.resolveWideLedgers(plan);
        for (var entry : plan.usedItems()) {
            var exact = wide.get(WideAmountKind.USED, entry.getKey());
            required.add(entry.getKey(), exact == null
                    ? java.math.BigInteger.valueOf(entry.getLongValue()) : exact);
        }
        for (var entry : plan.missingItems()) {
            var exact = wide.get(WideAmountKind.MISSING, entry.getKey());
            required.add(entry.getKey(), exact == null
                    ? java.math.BigInteger.valueOf(entry.getLongValue()) : exact);
        }
        return required;
    }

    static void seedInitialWaitingFor(ExecutingCraftingJob job, KeyCounter missingInitialItems) {
        if (missingInitialItems == null || missingInitialItems.isEmpty()) {
            return;
        }

        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        for (var entry : missingInitialItems) {
            accessor.getWaitingFor().insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            addTrackedWaitingTime(accessor, entry.getLongValue(), entry.getKey());
        }
    }

    static void recordWaitingDetail(
            CraftingCPUCluster cpuCluster,
            ICraftingPlan plan,
            ExecutingCraftingJob job,
            KeyCounter missingInitialItems
    ) {
        if (!(cpuCluster.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        UUID craftId = ((ExecutingCraftingJobAccessor) job).getLink().getCraftingID();
        if (missingInitialItems == null || missingInitialItems.isEmpty()) {
            CraftingContinuationTracker.get(serverLevel).clearCompletedJob(craftId);
            return;
        }

        List<CraftingContinuationWaitingBranch> waitingBranches = new ArrayList<>();
        int order = 0;
        for (var entry : missingInitialItems) {
            waitingBranches.add(new CraftingContinuationWaitingBranch(
                    entry.getKey().getDisplayName().getString(),
                    order++,
                    Map.of(CraftingContinuationTracker.encodeKeyForSync(entry.getKey()), entry.getLongValue())
            ));
        }

        List<String> runningBranchLabels = new ArrayList<>();
        for (var entry : plan.patternTimes().entrySet()) {
            for (var output : entry.getKey().getOutputs()) {
                runningBranchLabels.add(output.what().getDisplayName().getString());
                break;
            }
        }

        CraftingContinuationTracker.get(serverLevel).trackJob(new CraftingContinuationWaitingDetail(
                craftId,
                CraftingContinuationTracker.encodeKeyForSync(plan.finalOutput().what()),
                plan.finalOutput().amount(),
                waitingBranches,
                runningBranchLabels
        ));
    }

    private static void addTrackedWaitingTime(ExecutingCraftingJobAccessor accessor, long amount, AEKey key) {
        ((ElapsedTimeTrackerAccessor) accessor.getTimeTracker()).invokeAddMaxItems(amount, key.getType());
    }

    public static ICraftingPlan createNativeSubmissionPlan(ICraftingPlan plan) {
        return new NativeJobSubmissionPlan(plan);
    }

    private record NativeJobSubmissionPlan(ICraftingPlan delegate)
            implements ICraftingPlan, RecursivePlanContract {
        private NativeJobSubmissionPlan {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public appeng.api.stacks.GenericStack finalOutput() {
            return delegate.finalOutput();
        }

        @Override
        public long bytes() {
            return delegate.bytes();
        }

        @Override
        public boolean simulation() {
            return false;
        }

        @Override
        public boolean multiplePaths() {
            return delegate.multiplePaths();
        }

        @Override
        public KeyCounter usedItems() {
            return new KeyCounter();
        }

        @Override
        public KeyCounter emittedItems() {
            return delegate.emittedItems();
        }

        @Override
        public KeyCounter missingItems() {
            return new KeyCounter();
        }

        @Override
        public Map<appeng.api.crafting.IPatternDetails, Long> patternTimes() {
            return delegate.patternTimes();
        }

        @Override
        public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
            return delegate instanceof RecursivePlanContract recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning();
        }

        @Override
        public ICraftingPlan chexsonsaeutils$getDelegate() {
            return delegate;
        }

        @Override
        public KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
            if (delegate instanceof RecursivePlanContract recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
                return recursivePlan.chexsonsaeutils$dyeableRecursiveInitialItems();
            }
            return new KeyCounter();
        }

        @Override
        public KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems() {
            if (delegate instanceof RecursivePlanContract recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
                return recursivePlan.chexsonsaeutils$dyeableRecursiveInternalItems();
            }
            return new KeyCounter();
        }

        @Override
        public long chexsonsaeutils$dyeableRecursiveFinalOutputAmount() {
            if (delegate instanceof RecursivePlanContract recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
                return recursivePlan.chexsonsaeutils$dyeableRecursiveFinalOutputAmount();
            }
            return -1L;
        }

        /**
         * 配给表那一本照同一形状转发：并行 CPU 的提交链（{@code ParallelCraftingCpuLogic
         * #trySubmitPartialJob} → {@code trySubmitJob} → {@code logSubmitBoundary} → {@code
         * adoptPlannedMaterialPlan}）读的就是这一层壳交出的计划，不转则两本账一起消失，
         * 提交期会自算第二份归属（绕开 {@code #44}：规划出口 mint、执行侧只照单取料）。
         */
        @Override
        public Map<appeng.api.crafting.IPatternDetails, WideKeyCounter>
                chexsonsaeutils$planMaterialPlan() {
            if (delegate instanceof RecursivePlanContract recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
                return recursivePlan.chexsonsaeutils$planMaterialPlan();
            }
            return Map.of();
        }

        /** 封套那一本与配给表同一侧、同一条穿壳路：少转一处，{@code hole=} 就没有东西可减。 */
        @Override
        public Map<appeng.api.crafting.IPatternDetails, WideKeyCounter>
                chexsonsaeutils$planMaterialEnvelopes() {
            if (delegate instanceof RecursivePlanContract recursivePlan
                    && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
                return recursivePlan.chexsonsaeutils$planMaterialEnvelopes();
            }
            return Map.of();
        }
    }
}
