package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import git.chexson.chexsonsaeutils.crafting.plan.PatternRosterReadout;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCounterNbt;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveTaskOrdering;
import git.chexson.chexsonsaeutils.crafting.recursive.RingSeedLedger;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedgerImpl;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ParallelExecutingCraftingJob {

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_LINK = "link";
    private static final String NBT_PLAYER_ID = "playerId";
    private static final String NBT_FINAL_OUTPUT = "finalOutput";
    private static final String NBT_WAITING_FOR = "waitingFor";
    private static final String NBT_TIME_TRACKER = "timeTracker";
    private static final String NBT_REMAINING_AMOUNT = "remainingAmount";
    private static final String NBT_TASKS = "tasks";
    private static final String NBT_SUSPENDED = "suspended";
    private static final String NBT_DYEABLE_RECURSIVE_PLAN = "dyeableRecursivePlan";
    private static final String NBT_DYEABLE_RECURSIVE_FINAL_OUTPUT_AMOUNT = "dyeableRecursiveFinalOutputAmount";
    private static final String NBT_DYEABLE_RECURSIVE_INTERNAL_ITEMS = "dyeableRecursiveInternalItems";
    private static final String NBT_CRAFTING_PROGRESS = "#craftingProgress";
    /**
     * 任务条目上的<b>宽域份数</b>键：只在发数真越出 {@code long} 时挂上（补码字节，与
     * {@code InfinityCellStore} 的 {@code bigAmount} 同一写法）。缺该键即按老
     * {@link #NBT_CRAFTING_PROGRESS} 的 {@code long} 值读——那是自然缺省，不是迁移壳。
     */
    static final String NBT_CRAFTING_PROGRESS_WIDE = "#craftingProgressWide";

    final CraftingLink link;
    /**
     * 产出缓冲：<b>声明类型仍是 AE2 基类</b>{@link ListCraftingInventory}，装的是
     * {@link WideCraftingInventory}（主表 + 溢出侧表）。
     *
     * <p>刻意不改声明类型：本文件之外所有读它的地方（{@code getWaitingFor}、
     * {@code getAllWaitingFor}、{@code getWaitingStacks}、
     * {@code insertInternal} 的回池夹取与状态栏 {@code getAllItems}、退网与状态栏）
     * 对溢出键拿到的仍是饱和 {@code Long.MAX_VALUE}——与无限盘对网络报数的同一条口径
     * （{@code getAllItems} 在 #72 第二刀后改经 {@link WideCraftingInventory#amount} 读精确值，
     * 但交去 AE2 菜单前逐键饱和，出口读数一字不变）。精确值只经 {@link #waitingForExact(AEKey)}
     * 与 {@link WideCraftingInventory#amount} 这类宽域读口出去。
     */
    final ListCraftingInventory waitingFor;
    /** 提交计划的外部原材料剩余提取量，随作业保存，禁止从任务缺料反推额外需求。 */
    final WideCraftingInventory initialItemsRemaining = new WideCraftingInventory(ignored -> {});
    /** 新接单或重载后核对一次真实网络无限供给，修复旧计划漏记的来源身份。 */
    boolean unboundedSupplyRefreshPending = true;
    /** 分段提取轮转位置，避免大数量单键占完每轮预算，使其他原料永远抽不到。 */
    int initialItemsCursor;
    /** 无法解码的任务保留原始条目并暂停，避免删除进度或使世界加载崩溃。 */
    private final ListTag unresolvedTasks = new ListTag();

    /** 无法解码的存档任务尚未恢复时，禁止手动解除暂停造成提前收工。 */
    boolean hasUnresolvedTasks() {
        return !unresolvedTasks.isEmpty();
    }
    /**
     * 任务表：{@link LinkedHashMap}，它的插入序就是三条顺位共用的<b>底序</b>。
     *
     * <p>这本表不能是 {@link HashMap}：执行侧推送（{@code ParallelCraftingCpuLogic#tickCraftingLogic}）、
     * 族到账摊派（{@link #rationPatternsInExecutionOrder}）、提交边界配平读数（{@code #describePlanBalance}）
     * 都把它原样交给同一把尺 {@code RecursiveTaskOrdering}（那把尺只把环成员提前，<b>不动</b>并列项）。
     * 底序若是哈希序，并列项的先后就由 {@code AEItemKey#hashCode}（取自 {@code Item} 的身份哈希）
     * 决定——<b>同一份计划两个 JVM 会话可以给出相反的扣料先后</b>（实测同一夹具两次跑出
     * {@code [stone, anvil]} 与 {@code [anvil, stone]} 两份序）。
     *
     * <p><b>这一份序从哪来（实测链，不是设想）</b>：新提交的作业，插入序来自构造器里
     * {@code plan.patternTimes().keySet()} 的遍历序（本文件 {@code for (IPatternDetails pattern :
     * taskKeys)} 那一趟，{@code taskKeys} 是 {@link LinkedHashSet}，只照抄喂进来那份的序）。
     * 喂进来的那份由 {@code Ae2PlanConverter} 交出，而它现在交的是
     * {@link git.chexson.chexsonsaeutils.bigint.WideCraftCounter#orderedLongView()}
     * ——<b>规划侧份数账的注册序</b>：一张样板第一次被记进 {@code crafts} 的那一瞬间定下的槽号，
     * 由 {@code PlanJournal.craftsMerge}（求解逐站点入账）保证、撤销后重新入账沿用原槽号。
     * 所以底序＝"求解先给哪张样板记账"，这一步本身不看样板实例的哈希。
     *
     * <p><b>但这句话只在转换器以下成立</b>（台账 #140 的改口）：求解的首触序来自编译期的 routes，
     * 而 routes 吃的那份提供者清单出自 AE2 的 {@code NetworkCraftingProviders:237}——一只
     * {@code HashSet}，{@code :241-247} 只按提供者的机器优先级稳定排序，<b>并列那一档留的就是
     * 哈希遍历序</b>（{@code PatternInfo} 的哈希混入 {@code definition.hashCode()}，再往下是
     * {@code Item} 的身份哈希）。那一档现在由我们侧的两个收集点
     * （{@code GraphCompiler.Compilation#craftingFor} 读的提供者清单与 {@code #resolveNodeKey} 读的
     * 模糊代用清单，两者都走 {@code ProviderPatternOrder}）按<b>稳定键</b>定死——那把键早就不止
     * "definition 的注册表 id 字符串"一项，还含输出 / 逐候选的退料键 / 候选输入 / 倍率 / 组件读数 /
     * 实现类名（台账 #142/#143）。
     *
     * <p><b>"稳"覆盖哪条路、不覆盖哪条路（台账 #143 的改口，不许写成已可控）</b>：上面那句只保证
     * <b>graph 路</b>（自有并行 CPU 的主规划路）那份清单序与它下游的注册序跨会话一致。
     * <b>AE2 的树路不在这条承诺里</b>：{@code CraftingTreeNode} 自己读 {@code getCraftingFor}
     * （{@code CraftingTreeNode.java:86/:126}）与 {@code getFuzzyCraftable}（{@code :98}），
     * 一个都不经上面那两个读取点；它交回的 {@code plan.patternTimes()} 出自
     * {@code CraftingSimulationState.java:56} 那只 {@code HashMap}，因而是哈希遍历序。
     * 任务表按这本份数账初始化，因此 <b>凡走树路交出的计划，
     * 这本任务表的底序仍然随会话翻</b>（AE2 原生树回落腿按口径原样保留、不为它做特殊处理；
     * 详见 {@code ProviderPatternOrder} 类抬头"管到哪一条路"）。
     * 注册序自己造不出源头没有的次序。
     *
     * <p><b>续传作业</b>：任务表按上面的序逐条写进 {@code NBT_TASKS} 的 {@link ListTag}（保序），
     * 读回也按列表下标 {@code put} ⇒ 重启后的推送顺位＝提交那一刻的顺位。表条目上解出来的
     * {@code IPatternDetails} 是重新 decode 的<b>新实例</b>（身份哈希与提交时那批不同），这在这一改
     * 之后不再要紧——顺位只认插入序。归属那一本（{@link #materialAllocation} 与封套）按既有口径
     * <b>不落 NBT</b>：续传作业根本没有配给表，{@link #narrowPlanMaterialShareByArrival} 直接早退，
     * 于是"重启后新算的归属与旧作业表序不一致"这一型在续传路上不存在（没有第二份归属可错配）。
     */
    final Map<IPatternDetails, TaskProgress> tasks = new LinkedHashMap<>();
    final ParallelElapsedTimeTracker timeTracker;
    /**
     * 缺料退避（瞬态，不落 NBT）：样板 → 可重试 tick。提取失败的样板
     * 短期跳过——大批量任务表里缺料任务的重复失败提取（多槽 × 大库存
     * 逐槽扫模板）会吃光 20ms tick 预算，排在后面的富料任务永远轮不到
     * 派发（发配饿死）。TTL 内材料到达至多延迟 1 秒重试。
     */
    final Map<IPatternDetails, Long> materialBackoffUntil = new HashMap<>();
    /** 退避样板的缺口键索引：库存该键变动时即刻唤醒（postChange 驱动）。 */
    final Map<IPatternDetails, Set<AEKey>> materialBackoffMissing = new HashMap<>();
    /** 只覆盖当前退避任务；不保存进 NBT，作业恢复后从实际缺料重新登记。 */
    final MaterialWakeIndex materialWakeIndex = new MaterialWakeIndexImpl();
    GenericStack finalOutput;
    long remainingAmount;
    /** 提交时的权威根订单与其剩余交付量，包括恰好 Long.MAX_VALUE 的有限订单。 */
    @Nullable PlanAmount exactFinalOutputAmount;
    @Nullable PlanAmount exactRemainingAmount;

    /** 只按真实接收数量扣除宽域余额，防止达到 long 代表值后提前结束。 */
    void recordFinalOutputAccepted(long accepted) {
        if (accepted < 0) throw new IllegalArgumentException("交付量不得为负");
        recordFinalOutputAccepted(PlanAmount.of(accepted));
    }

    void recordFinalOutputAccepted(PlanAmount accepted) {
        if (exactRemainingAmount != null) {
            exactRemainingAmount = exactRemainingAmount.subtract(accepted).max(PlanAmount.ZERO);
            remainingAmount = git.chexson.chexsonsaeutils.bigint.Ae2Amounts.toLong(exactRemainingAmount);
        } else remainingAmount = PlanAmount.of(remainingAmount).subtract(accepted).max(PlanAmount.ZERO).fastValue();
    }
    @Nullable
    Integer playerId;
    boolean suspended;
    /** 已接单批次的材料债务清算完成前，取消和成功收尾都不能释放库存。 */
    boolean cancellationRequested;
    boolean completionRequested;
    /** 递归终产物进入 CPU 不代表 requester 已收货；此余额只按真实收货递减。 */
    @Nullable PlanAmount requesterDeliveryRemaining;

    boolean hasUnsettledBatchObligations() {
        if (batchObligations.hasAnyPending()) return true;
        for (int i = 0; i < unresolvedTasks.size(); i++) {
            if (BatchObligationLedger.readEntry(unresolvedTasks.getCompound(i)).signum() > 0) return true;
        }
        return false;
    }
    boolean dyeableRecursivePlan;
    long dyeableRecursiveFinalOutputAmount = -1L;
    /** 提交时的计划实例类名（瞬态）：续传作业固定报 resumed，一眼区分两条来源。 */
    String planClass = "resumed";
    /**
     * 流水账（瞬态，续传后从零起算）：从池里提走的料 / 报称会产出的量 / 真回到池里的量，
     * 另有一本提交瞬间整张任务表的计划产量快照。原生推送与 NeoEco 快路径都记前两笔账，
     * 停滞日志按缺口键把这几行拉出来对账：报称多、回池少即产出漏了。
     *
     * <h3>前三本（{@link #accountTaken} / {@link #accountAnnounced} / {@link #accountReturned}）
     * 是跨批复加的账 ⇒ 换 {@link WideKeyCounter}</h3>
     * 单笔 {@code ≤ long}，但<b>合计可以越出 {@code long}</b>：{@code accountAnnounced} 与
     * {@link #waitingFor} 吃的是<b>同一条申报流</b>（{@code ParallelCraftingCpuLogic#reserveOne}
     * 一处同时写这两本，见该方法注释），产出缓冲能记 {@code > long} 是上一刀已经坐实的事实，
     * 那这条流上的报称账同样能越界。老 {@link KeyCounter} 是裸加，越界即<b>回绕</b>：
     * 三批各 4e18 枚在 {@code long} 域算出<b>负数</b>读数，"报称多、回池少"这条判据直接被读反。
     * 现在逐键走 {@link WideKeyCounter}（主表 {@code long} + 溢出侧表 {@code BigInteger}，
     * 与 {@link #outputsExpectedToNetwork} 同一份算法本体）⇒ <b>内部精确、绝不回绕</b>。
     *
     * <h4>出口饱和：只有一处口径</h4>
     * 这三本账的对外消费方只有停滞日志（{@code ParallelCraftingCpuLogic#formatAllocation} 的
     * {@code taken=/ann=/ret=} 三项），它只能吃 {@code long}：一律经
     * {@link WideKeyCounter#getLong(AEKey)} 读<b>主表窄值</b>——溢出键在主表恒是
     * {@link Long#MAX_VALUE} 哨兵 ⇒ 读数钉在 {@code long} 天花板、不回绕成负，
     * 与 {@code getAllItems} 上一刀同一条"内部精确、出口饱和"口径。
     *
     * <h4>无存档面</h4>
     * 这三本<b>纯运行态</b>：{@link #writeToNBT(HolderLookup.Provider)} 只写 link / finalOutput /
     * waitingFor / timeTracker / tasks（含任务条目上的义务）/ 预期入网账 / remainingAmount /
     * playerId / suspended / 可染色递归那几项，续传构造器也不读这三本流水账与下面那张
     * {@link #accountPlannedEmit} 快照——续传后从零起算。
     * 因此本刀<b>不需要</b>覆盖键那套 NBT 风格（没有落盘，就没有要兼容的存档形状）。
     *
     * <p>{@link #accountPlannedEmit} 是<b>提交瞬间的计划量快照</b>，不是跨批复加的流水；任务 #219
     * 条目⑥只换它的账体与写入侧数域（{@code KeyCounter → WideKeyCounter}），快照语义一字未动。
     * 曾与它并列的那本（提交瞬间 {@code plan.usedItems()} 的计划吃料量）<b>只写不读</b>——停滞行印的
     * 是 {@code taken=/planEmit=/ann=/ret=/planNet=}，没有一格问它；那条"planned − 剩余需求"的算式
     * 在本仓已经成立不了，故整本删净，连同它唯一的写口（{@code logSubmitBoundary} 里抄快照的那一趟）。
     * 留一本没人读的账，就是下一次"这本判决到底信哪本"的入口（台账 #132）。
     */
    final WideKeyCounter accountTaken = new WideKeyCounter();
    final WideKeyCounter accountAnnounced = new WideKeyCounter();
    final WideKeyCounter accountReturned = new WideKeyCounter();
    /**
     * 提交瞬间整张任务表的计划产量——<b>与上面三本同一形状的宽域账</b>（任务 #219 条目⑥）。
     *
     * <p>老账体是 AE2 的 {@code KeyCounter}：每格只到 {@code long}，写入侧（
     * {@code ParallelCraftingCpuLogic#describePlanBalance}）在 {@code saturatingMultiply} 上收口，
     * 于是 {@code >long} 的计划自产在这一格里<b>恒读 {@code Long.MAX_VALUE}</b>。它不是读数问题：
     * 唯一消费点 {@link #narrowPlanMaterialShareByArrival} 拿它当公用池的一条<b>腿</b>
     * （{@code pool＝族到账＋有到账之外兜底那一截＋计划自产}），饱和是<b>向上夹</b>，
     * 真值越大这一腿反而被记得越小 ⇒ 摊派把还能推的那一截份额夹小、尾段那一格带着配额开不了工
     * （与实机 {@code left>0 ∧ ret=0} 那一族同形）。同一本账顶格哨兵不许再冒充量。
     *
     * <p>仍是<b>提交瞬间的快照</b>（不是跨批复加的流水），仍不进 NBT、续传后从零起算。
     */
    final WideKeyCounter accountPlannedEmit = new WideKeyCounter();
    /** 配给、封套与消费的唯一材料归属账；随任务保存并绑定到恢复后的样板身份。 */
    final MaterialAllocation materialAllocation = new MaterialAllocationImpl();

    /**
     * 规划侧认下的<b>无限源</b>键（随计划出门，见 {@link WideAmountPlanCarrier#resolveUnboundedKeys}）。
     *
     * <p>这些键在 {@code long} 域里没有数值上界：配额封顶、占料锁对它一律不成立，缺口也不走
     * "从网络提取"那一步——取料点（{@code ParallelBatchExtraction#prepareUniform} 的
     * {@code unboundedSource} 那一支）就地认量，一份料都不经池子。
     *
     * <p>随作业保存，恢复后仍按原有无限源语义取料。
     *
     * <p>提交及重载时重新核对实际来源能力与提取权限，补录漏记来源并撤销失效身份。
     * 集合会被逐 tick 重建的 {@code ParallelPlanReservation} 读到，故用并发集合。
     */
    final Set<AEKey> unboundedSupplyKeys;

    /**
     * 补录一枚已核对来源能力和权限的无限源键。
     *
     * <p>逐 tick 重建的 {@code ParallelPlanReservation} 与 {@code ParallelExecutingCraftingJob
     * #isUnboundedSupply} 都读这份集合，所以认中之后配额封顶、占料锁与初始提料一律对它放行。
     *
     * <p>这里<b>不</b>顺手清 {@code waitingFor}：那一本账同时挂着 {@code emittedItems} 与在途
     * 申报的<b>真实</b>数量，按键族整体摘掉会把该回来的实物一起拒收（{@code insertInternal}
     * 那道闸门）。无限来源的初始材料等待在源头就不进账——见 {@code ParallelCraftingCpuLogic
     * #seedInitialInputs}。
     */
    void noteUnboundedSupply(@Nullable AEKey key) {
        if (key != null) {
            unboundedSupplyKeys.add(key);
        }
    }

    /** 这一精确键是否具有已核对的无限供给来源。 */
    boolean isUnboundedSupply(@Nullable AEKey key) {
        return unboundedSupplyContains(unboundedSupplyKeys, key);
    }

    /** 只匹配来源明确提供的精确键，不豁免同物品的其他数据变体。 */
    static boolean unboundedSupplyContains(Set<AEKey> keys, @Nullable AEKey key) {
        return key != null && keys.contains(key);
    }

    /** 计划带下来的那份是不可变集合：换成可增的并发集合，供 {@link #noteUnboundedSupply} 补录。 */
    private static Set<AEKey> mutableKeys(Set<AEKey> carried) {
        var keys = java.util.concurrent.ConcurrentHashMap.<AEKey>newKeySet();
        keys.addAll(carried);
        return keys;
    }

    /**
     * 份数声明形态的义务账：宿主按声明份数收了料，剩下 (k-1) 份材料的提取欠在这里。
     *
     * <p>随任务条目落 NBT（{@code #batchObligationWide}），续传后继续分批交割；
     * 没走声明形态的作业这张表恒空，读写都不产生任何键。
     */
    final BatchObligationLedger batchObligations = new BatchObligationLedger();

    /**
     * 万象炉 bigint 通道申报批的<b>在飞 / 欠交付</b>台账：结案凭据只有该笔的<b>终局事件</b>
     * （{@code UselessBigIntegerApiBridge#onBatchFinished} 沿 {@code cpuToken} 打回来）。
     *
     * <p>它回答的是上一刀答不了的那一问：机器报满（{@code accepted}＝申报值）而货没到。
     * 这里<b>不</b>记承诺量、<b>不</b>记池子/网络现量差值——那两把尺是 {@code b99d7ee} 被回退的死因。
     *
     * <p><b>不落 NBT</b>（口径与风险逐字写在
     * {@code git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger} 的类注释：
     * {@code batchId} 是 useless 侧内存对象 {@code PendingCraftingOutput.cpuContext} 的字段，重载走的是
     * 不带 context 的那枚构造器 ⇒ 重启后旧 batchId 的终局<b>永不</b>到达，落了就是造一批注定等不到
     * 凭据的记录）。因此：① 重载后 {@code inflightBatches=} 从 0 起算；② 跨重启窗口被取消的那一批
     * 不会被记成欠交付、那一批发数<b>也不会</b>被退回待办。两条都只影响读数与事后归因，
     * 不影响收工判据——判据读的是发数账与材料欠账那两本照旧落 NBT 的账。
     *
     * <p><b>这本台账不交出表判据</b>（#209）：它只把该笔的终局事件落成<b>回退</b>——负向终局沿
     * {@link #rollBackDeclaredBatchFirings} 把那一笔的发数退回这一条的待办，出表判据仍是既有那两格
     * （发数跑完 + 材料欠账清了），全仓只在 {@code ParallelCraftingCpuLogic#taskMayLeaveTable} 一处。
     * 没接这条通道的作业这本台账恒空 ⇒ 行为逐字不变。
     */
    final DeclaredBatchTerminalLedger declaredTerminals = new DeclaredBatchTerminalLedgerImpl();

    /**
     * 产出侧的<b>预期入网</b>账：宿主把这批产物不经 CPU 池子直接交出去时，产物不再回
     * {@link #waitingFor} 等实物，改记这一笔——没有这笔账，接上大数批次通道就是纯丢账。
     *
     * <p><b>这本账记的是预期，不是交付</b>：落账的那几枚出自宿主按配方自报的计划量
     * （集成侧计划量换算口），一条都不是"插进存储成功"的回执。
     * 它只回答"这一截不会再有实物回池子"，<b>不</b>回答"网络里有没有这份产物"——而<b>代码里也没有
     * 任何能答这一问的读数</b>。{@code blocked=} 那一格里拼的 {@code 网络sim=/同族[…]下界}
     * （{@code ParallelBatchExtraction#describeRefillNetworkReadout}）是<b>投入侧</b>读数：义务回补时
     * 那一格<b>材料</b>向网络现问的 SIMULATE 现量，产物键从不进它那一格。旧写法把它当产物交付的
     * 证据口径，说的是一个写下来却在代码中不存在的判据（照 {@code fe80030} 在
     * {@code ParallelCraftingCpuLogic#reserveOne} 那一处同一句的改口口径）。
     *
     * <p>算法本体复用 {@link WideKeyCounter}（主表 {@code long} + 溢出侧表 {@code BigInteger}）：
     * 每批 ≤ {@code long} 落账（机器的切段粒度），跨批复加由它提升到宽域 ⇒ 合计精确、
     * 绝不被 {@code long} 截断。本类不实现第二套宽域算术（与 {@link TaskProgress} 同一口径）。
     *
     * <p>它与 {@link #waitingFor} 是<b>同一笔申报产出的一次划分</b>：分流只走
     * {@code ParallelCraftingCpuLogic.reserveOne} 那一个出口——预期入网多少就少挂多少实物，
     * 两边合计恒等于申报量 ⇒ "实物进容器"与"预期入网"在结构上不可能重复计。
     *
     * <p><b>划账的前提（#227 K1）</b>：这一笔必须挂得上在飞台账（宿主是 {@code DeclaredBatchTerminalCarrier}、
     * 交回了该笔的 {@code cpuToken}、token 上有 {@code batchId}）才允许往这本账里划；挂不上就整批
     * 全额挂 {@code waitingFor} 等实物。这本账从此只装"有人能替它作证"的枚数——{@code #227 K2} 之后
     * 收工第二臂直接减它，账与凭据必须同进同退。
     *
     * <p><b>#227 K2 起它是收工第二臂的分子</b>：{@link #declaredNetworkUndeliveredTotal}
     * 减交割账，差为 0 才算"直投网络那一截交割干净"。负向终局那一笔划走的枚数由
     * {@link #revokeDeclaredBatchPlannedNetworkOutputs} 随回滚一起从这本账退掉（不退就差值永不归零）。
     *
     * <p><b>落盘只做事后归因，读回来即退</b>（{@value #NBT_OUTPUTS_EXPECTED_TO_NETWORK}，条目级
     * {@value #NBT_ENTRY_AMOUNT_WIDE}）：能交回这一截枚数的凭据是那笔的在飞台账与它的
     * {@code batchId}（内存态 {@code UUID}，按台账口径<b>不落 NBT</b>），重启后终局永不来 ⇒
     * 这一截既转不成已交割、也不许继续拦收工 ⇒ {@code readFromNBT} 把它读回来就整本走
     * {@link #revokeDeclaredBatchPlannedNetworkOutputs} 退掉（{@link #dropRestoredExpectedToNetwork}）。
     * 守的是 {@code #227 K1} 那条不变量：这本账只装"挂在飞台账上的那一笔"，续传后台账是空的，
     * 它在判据里就必须同样是空的。常态零预期入网 ⇒ 一个键都不挂。
     */
    final WideKeyCounter outputsExpectedToNetwork = new WideKeyCounter();

    /**
     * 记一笔"这批产物按宿主的自报计划要入网"：宿主报多少枚就记多少枚，累计走
     * {@link WideKeyCounter#add(AEKey, long)} 的溢出提升 ⇒ 跨批复加精确、不截断。
     */
    void addExpectedToNetwork(@Nullable AEKey what, long batchAmount) {
        if (what == null || batchAmount <= 0L) {
            return;
        }
        outputsExpectedToNetwork.add(what, batchAmount);
    }

    /** 单批计划产出可超 long；此口只接精确量。 */
    void addExpectedToNetwork(@Nullable AEKey what, BigInteger batchAmount) {
        if (what != null && batchAmount.signum() > 0) {
            outputsExpectedToNetwork.add(what, batchAmount);
        }
    }

    /**
     * 该键累计<b>预期</b>入网的<b>精确</b>读数：宽域口径，不参与任何 {@code long} 域判据，也不是交付证据。
     *
     * <p><b>这本账对外的唯一读口</b>：停滞日志 {@code planNet=} 那一格（
     * {@code ParallelCraftingCpuLogic.StallProbe#formatAllocation}）与用例读的都是这一个出口——
     * 一本账两把读尺迟早漂，打印口不再绕过这里直接问 {@link #outputsExpectedToNetwork}。
     */
    PlanAmount expectedToNetwork(@Nullable AEKey what) {
        return what == null ? PlanAmount.ZERO : outputsExpectedToNetwork.getAmount(what);
    }

    /**
     * 申报批的<b>交割账</b>（已交割量，逐键宽域）：只有该批终局 {@code SUCCESS} 到齐才写进来——
     * {@code SUCCESS} 发射条件本身带数量含义（2.3.9 javap：{@code notifyCpuBatchCompleted} 只在
     * {@code PendingCraftingOutput.isEmpty()} 时发，而 {@code isEmpty} 由
     * {@code attributeDeliveredOutputs} 按真插进 ME 网络的量逐批 {@code consume} 扣减决定），所以这一格
     * 记的是"这批 N 枚已入网"，与 {@link #outputsExpectedToNetwork}（待交割）<b>对平</b>。
     *
     * <p><b>它不是第四本流水账</b>：{@code ann}/{@code ret}/{@code planNet} 三本流水仍只做读数；这本是
     * {@code planNet}（待交割）的<b>交割侧</b>——同一批 min(自报,申报) 的枚数，申报时进 {@code planNet}
     * 当预期，终局 {@code SUCCESS} 到齐时进这本当已交割。
     *
     * <p><b>这本账是收工判据第二臂的减数（#227 K2 起）</b>：那条臂
     * （{@code ParallelCraftingCpuLogic#declaredNetworkDeliveriesSettled}）读
     * {@code planNet − 这本} 那道差（{@link #declaredNetworkUndeliveredTotal}，逐键走
     * {@link #declaredNetworkUndelivered} 同一道算式），差为 0 才算这一截交割干净。
     *
     * <p><b>话术沿革（两回才改对，逐字留着）</b>：最早这里写着"收工判据第二臂只问 {@code planNet}
     * 与这本的差（还欠几枚没交割），全仓就这一处'够不够'"——台账 #225 逐条数过，那时<b>与代码不符</b>：
     * 臂读的是 {@code DeclaredBatchTerminalLedger#hasUndeliveredDeclaredOutput()} 那把<b>只扫在飞表</b>
     * 的布尔尺，一个字都不减这本账，而 {@code declaredNetworkUndelivered} 那道差<b>生产代码零读者</b>。
     * #227 K2 把臂换成读真差值，这句才第一次与代码一致；那把布尔尺降级成 {@code pendingDelivery=}
     * 那一格的纯读数（{@code hasUndeliveredDeclaredOutput} 仍被用例读着，实现一字未动）。
     * {@code alloc=} 那一行的 {@code delivered=}／{@code undelivered=} 两格照旧只印不判——
     * 它们与臂读的是<b>同一处算式</b>，不是第二把尺。
     *
     * <p>纯运行态、<b>不落 NBT</b>：续传后 {@code SUCCESS} 凭据永不来（{@code batchId} 是内存态
     * {@code UUID}），这本账读不到就是零已交割。而分子那一头（{@link #outputsExpectedToNetwork}）
     * 也在装载时被整本退掉（{@link #dropRestoredExpectedToNetwork}，同一本账、同一个退账出口）
     * ⇒ <b>续传作业的差值恒 0</b>：第二臂既不会被落盘的预期永久拦住（{@code #209} 那一型），
     * 也不比换尺子之前多放行一格——重启前后逐字同效。跨重启真正常还的那一档是"本场内划了账
     * 却没等到终局"，那一档照旧拦收工（{@code ParallelDeclaredBatchDeliveryCredentialTest}
     * 的 {@code planNetRecordedWithoutAnyInflightEntryKeepsTheJobOpen}），凭据回来才解。
     */
    final WideKeyCounter declaredNetworkDelivered = new WideKeyCounter();
    /** 交割账里有没有哪一笔的加数被 {@code Long.MAX_VALUE} 饱和哨兵夹过（纯标记，读数带 {@code clamped}）。 */
    private volatile boolean declaredDeliveryClamped;

    /**
     * 记一笔申报批的交割（{@code SUCCESS} 到齐）：把这一批当初划成待交割的每键枚数转成已交割。
     *
     * <p>写口唯一，即 {@code DeclaredBatchTerminalLedger} 的 {@code Delivery} 出口；不在这里对平、
     * 不在这里判收工——它只结交割账。收工判据第二臂拿这本减 {@code planNet} 那本（
     * {@link #declaredNetworkUndeliveredTotal}），<b>只在 {@code SUCCESS} 的枚数上</b>认交割；
     * 负向终局那一笔<b>不进这一本</b>，它走 {@link #revokeDeclaredBatchPlannedNetworkOutputs}
     * 把预期退掉——"没人交割"与"已交割"是两件事，不许拿退账冒充后者。
     */
    void creditDeclaredBatchDelivered(Map<AEKey, BigInteger> pendingDelivery, boolean clamped) {
        if (pendingDelivery == null || pendingDelivery.isEmpty()) {
            return;
        }
        for (var entry : pendingDelivery.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue().signum() > 0L) {
                declaredNetworkDelivered.add(entry.getKey(), entry.getValue());
                timeTracker.decrementItems(PlanAmount.of(entry.getValue()), entry.getKey().getType());
                if (!dyeableRecursivePlan && isFinalOutputKey(entry.getKey())) {
                    recordFinalOutputAccepted(PlanAmount.of(entry.getValue()));
                }
            }
        }
        if (clamped) {
            declaredDeliveryClamped = true;
        }
    }

    /**
     * 负向终局（{@code REJECTED}／{@code CANCELLED}）那一笔的<b>退账</b>：把这一笔当初划进
     * {@code planNet}（{@link #outputsExpectedToNetwork}）的那几枚原样退掉。
     *
     * <p><b>为什么必须退（#227 K2 的硬前置）</b>：{@code DeclaredBatchTerminalLedger#onTerminal} 的
     * 负向分支把该笔的 {@code pendingDelivery} 整笔丢弃、不转已交割，而收工第二臂读的是
     * {@code planNet − 已交割}——不退这一截，那道差就<b>永远非零</b>，一笔 {@code CANCELLED}
     * 就把整张作业永久钉在"未收工"上（{@code #209}／{@code 4816673} 那一型被回退过的病）。
     * 该笔的发数已随同一条回滚出口退回待办、材料欠账原样挂着，划走的预期跟着退回，账才对得平。
     *
     * <p>用的就是 {@code planNet} 那本现成的宽域账：<b>不</b>新建第二本账、<b>不</b>加第二把尺，
     * 也<b>不</b>把退掉的那一截记成已交割。逐键夹在 {@code [0, 该键现量]}——多退／重退都不许
     * 把预期账读成负数（与 {@link #declaredNetworkUndelivered} 同一道"不许把欠量读成负"的口径）。
     *
     * <p>回退落空那一档（条目已出表 ⇒ 发数无处可退）照样退账：这一笔的凭据已经用掉了。
     */
    void revokeDeclaredBatchPlannedNetworkOutputs(@Nullable Map<AEKey, BigInteger> planned) {
        if (planned == null || planned.isEmpty()) {
            return;
        }
        for (var entry : planned.entrySet()) {
            AEKey key = entry.getKey();
            BigInteger amount = entry.getValue();
            if (key == null || amount == null || amount.signum() <= 0L) {
                continue;
            }
            PlanAmount revoked = outputsExpectedToNetwork.getAmount(key)
                    .min(PlanAmount.of(amount));
            if (revoked.signum() <= 0L) {
                continue;
            }
            if (revoked.isFast()) {
                outputsExpectedToNetwork.subtract(key, revoked.fastValue());
            } else {
                outputsExpectedToNetwork.add(key, revoked.wideValue().negate());
            }
        }
    }

    /** 某键<b>已交割</b>的宽域真值（交割账对 {@code planNet} 的交割侧读数，与 {@code planNet} 对平用）。 */
    PlanAmount declaredNetworkDeliveredAmount(@Nullable AEKey what) {
        return what == null ? PlanAmount.ZERO : declaredNetworkDelivered.getAmount(what);
    }

    /**
     * 这一键<b>还欠几枚没交割</b>：{@code planNet}（待交割）减交割账（已交割）；负数夹到 0
     * （宿主少报/多交割都不许把欠量读成负）。
     *
     * <p><b>它#227 K2 起就是收工判据第二臂的算式本体</b>（改前那一段写着"它不是收工判据、第二臂读的
     * 是在飞表那一格布尔"，那是因为那时臂真的不减这本账）：全键合计交 {@link
     * #declaredNetworkUndeliveredTotal}，那一格才是臂读的那一口；本方法逐键口径与它<b>同一处算式</b>，
     * 另有一个读者是 {@code alloc=} 那一行的 {@code undelivered=} 读数（纯读数，一格一枚键）。
     */
    PlanAmount declaredNetworkUndelivered(@Nullable AEKey what) {
        if (what == null) {
            return PlanAmount.ZERO;
        }
        PlanAmount owed = outputsExpectedToNetwork.getAmount(what)
                .subtract(declaredNetworkDelivered.getAmount(what));
        return owed.signum() <= 0 ? PlanAmount.ZERO : owed;
    }

    /**
     * 全键合计的"还欠几枚没交割"——收工判据第二臂唯一读的那一口（全仓就这一处"够不够"）。
     *
     * <p>分子取自 {@link #outputsExpectedToNetwork} 的在册键全集：交割账的每一枚都由某笔申报批
     * 当初划进 {@code planNet} 的那一份转来（{@link #creditDeclaredBatchDelivered} 唯一的写入口是
     * 台账的 {@code Delivery} 出口，交的就是那份枚数），⇒ 交割账不可能有 {@code planNet} 之外的键，
     * 这里无须并第二张键集。相加走 {@link #declaredNetworkUndelivered} 那道<b>同一处</b>算式，
     * 不在这里再摆一把尺：任何"先折成 {@code long} 再减"的写法都会把 {@code >long} 那一档读成负数
     * 或读成天花板哨兵。
     */
    PlanAmount declaredNetworkUndeliveredTotal() {
        PlanAmount total = PlanAmount.ZERO;
        for (AEKey key : outputsExpectedToNetwork.keys()) {
            total = total.add(declaredNetworkUndelivered(key));
        }
        return total;
    }

    /** 交割账有没有被饱和哨兵夹过（纯读数标记，停滞行据此带 {@code clamped}）。 */
    boolean declaredDeliveryClamped() {
        return declaredDeliveryClamped;
    }

    /**
     * 终点产物的<b>已交付</b>流水（逐键宽域）：只在<b>实物到账</b>那一条口上写，
     * 一条判完成用的读口（{@code ParallelCraftingCpuLogic#isDyeableRecursiveJobComplete}）。
     *
     * <p>唯一写口是既有的那条出口：{@code ParallelCraftingCpuLogic#insertInternal} 里认成终点产物
     * 那一支——实物交到 CPU 入账口那一刻（与 {@code accountReturned} 同一瞬间，但那本账连内部件一起记，
     * 不能拿来判交付）。
     *
     * <p>{@code ParallelCraftingCpuLogic#reserveOne} 分流口上宿主自报"不经池子直接交出去"的那一截
     * <b>不记在这里</b>（{@code 22bbc22} 把它记成交付是一条虚报腿，本刀摘掉）：那份枚数出自宿主按配方
     * 算出的计划量，通道里没有一枚"插进存储成功"的回执（口径见
     * {@code DeclaredBatchPlannedOutputs} 与 {@code DeclaredBatchReceipt} 的类注释，与本仓 #116
     * 定过的那条同向）。它只把 {@link #waitingFor} 少挂同样多、并记进 {@link #outputsExpectedToNetwork}
     * 那本预期账。因此<b>宿主直写网络那一型（useless bigint）在这条判据下永不自动收工</b>，只能走
     * {@code isDyeableRecursiveJobComplete} 那条"任务表自己清空"的老路——这是有意取舍：宁可这一型多等
     * 一步，也不让自报量把作业判成交齐。
     *
     * <p>账本换 {@link WideKeyCounter}：单笔 ≤ {@code long}（AE2 交付通道粒度），跨批复加没有上界，
     * 老 {@link KeyCounter} 的裸加会在越界那一刻<b>回绕</b>，把交付判据读反（{@link #accountReturned}
     * 同一刀的理由）。饱和一律留给出口，判据本身读 {@link PlanAmount} 真值。
     *
     * <p>纯运行态、<b>不落 NBT</b>（与三本流水账同一侧）：续传作业读不到就是零交付 ⇒ 交付判据不成立，
     * 逐字退回改前那条"任务表自己清空才收工"的路，不会把重启后的作业提前关掉。
     */
    final WideKeyCounter finalOutputDelivered = new WideKeyCounter();

    /**
     * 记一笔终点产物交付：非本作业终点产物那一族、零量与负量一律不进这本账。
     *
     * <p>匹配口径与 {@code insertInternal} 分派那一句用的是<b>同一条</b> {@code AEKey#matches}，
     * 不在这里另起一把判据——两把尺对"是不是终点产物"给出不同答案，交付账就会漏记或多记。
     */
    void addFinalOutputDelivered(@Nullable AEKey what, long amount) {
        if (amount <= 0L || !isFinalOutputKey(what)) {
            return;
        }
        finalOutputDelivered.add(finalOutput.what(), amount);
    }

    void addFinalOutputDelivered(@Nullable AEKey what, BigInteger amount) {
        if (amount.signum() > 0 && isFinalOutputKey(what)) {
            finalOutputDelivered.add(finalOutput.what(), amount);
        }
    }

    /**
     * 这一枚键是不是本作业的<b>终点键</b>：记交付账（{@link #addFinalOutputDelivered}）、建表那一趟认
     * 终点产量、以及交付臂认"谁的欠账算这一单的欠账"（{@link #producesFinalOutput}）问的都是这<b>一句</b>
     * {@code AEKey#matches}。同一个"是不是终点产物"不许有两把尺——两把尺给出不同答案，
     * 交付账就漏记或多记、收工判据就跟着错。
     */
    boolean isFinalOutputKey(@Nullable AEKey what) {
        return what != null && finalOutput != null && finalOutput.what() != null
                && what.matches(finalOutput);
    }

    /**
     * 这张样板<b>自己声明的产出</b>里有没有终点键（每发量 {@code > 0} 的那一格）。
     *
     * <p>它只服务一句问话：还挂在义务账上的那几张样板<b>产不产这一单要的终点件</b>
     * （{@code ParallelCraftingCpuLogic#finalOutputObligationsSettled}，FIX-F）。
     */
    boolean producesFinalOutput(@Nullable IPatternDetails details) {
        if (details == null || finalOutput == null || finalOutput.what() == null) {
            return false;
        }
        var outputs = details.getOutputs();
        if (outputs == null) {
            return false;
        }
        for (var output : outputs) {
            if (output != null && output.amount() > 0L && isFinalOutputKey(output.what())) {
                return true;
            }
        }
        return false;
    }

    /** 终点产物累计<b>已交付</b>的宽域真值：没有终点产物就是 0。 */
    PlanAmount finalOutputDeliveredAmount() {
        return finalOutput == null || finalOutput.what() == null
                ? PlanAmount.ZERO
                : finalOutputDelivered.getAmount(finalOutput.what());
    }

    /**
     * 提交时的终点毛产量：各样板发数乘产量，加上 emittedItems。
     * 它可能包含配方取整余量和环内复用，不能用来反推根订单。
     * 保留这份快照用于迁移旧 MAX 订单的 requester 余额：旧毛产量减旧余额才是已实际交付量。
     */
    @Nullable
    private PlanAmount finalOutputPlannedProduction;

    /**
     * 收尾和完成判断均使用权威根订单。没有宽域根量的旧存档以原 long 订单为准，
     * 包括恰好 Long.MAX_VALUE 的有限订单；真正越出 long 的订单必须携带精确根量。
     */
    @Nullable
    PlanAmount finalOutputOrderAmount() {
        if (exactFinalOutputAmount != null) return exactFinalOutputAmount;
        long readout = dyeableRecursiveFinalOutputAmount > 0L
                ? dyeableRecursiveFinalOutputAmount
                : finalOutput == null ? 0L : finalOutput.amount();
        if (readout <= 0L) {
            return null;
        }
        return PlanAmount.of(readout);
    }

    /**
     * {@link #waitingFor} 上该键的<b>精确</b>读数（宽域）：主表 {@code long} 加溢出侧表那一截。
     *
     * <p><b>实话（改这段注释之前的话术）</b>：这里原写"唯一一条能让累计越出 {@code long} 的实物预期
     * 被读全的口子"。两句都不对——① 它在<b>主代码里零调用点</b>，只有用例读它
     * （{@code ParallelWaitingForWideInventoryTest} 钉跨批挂账的精确合计、
     * {@code ParallelRecursiveFinalOutputDeliveryTest} 钉分流后还剩几枚挂实物预期）；
     * ② 宽域读全产出缓冲的还有 {@link ParallelCraftingCpuLogic#getAllItems} 那一处（状态栏聚合），
     * 它不经过本方法。留着这一个口子的理由是"用例要一个与产线同一条读法的读数"，不是"产线只有这条路"。
     *
     * <p>基类身份的那些调用点（{@code getWaitingFor} 一类）拿到的仍是饱和 {@code long} 读数——
     * 那是给 AE2 与外部 mod 看的口径，本刀一字未动。
     */
    PlanAmount waitingForExact(@Nullable AEKey what) {
        return PlanAmount.of(WideCraftingInventory.amount(waitingFor, what));
    }

    /**
     * 作业上的<b>预期入网账</b>列表键：只在真记过预期入网时挂上，常态零预期即整个列表不写。
     * 条目风格与 {@link #NBT_CRAFTING_PROGRESS_WIDE} 同一条规则（缺宽域条目键即"这笔装得进
     * {@code long}"），不新建通用宽域编解码框架。
     *
     * <p>落盘的字面量沿用改前那串 {@code outputsDeliveredToNetwork}：这本账的语义从"已交付"改成
     * "预期"是话术与命名修正，份数算法一字未动，换列表键等于把在途作业的这本账整本丢掉。
     */
    static final String NBT_OUTPUTS_EXPECTED_TO_NETWORK = "outputsDeliveredToNetwork";
    /** 预期入网条目上的老 {@code long} 数量键：与 AE2 {@code ListCraftingInventory} 同一个字段名。 */
    private static final String NBT_ENTRY_AMOUNT = "#";
    /** 预期入网条目上的<b>宽域数量</b>键：BigInteger 补码字节，只在累计越出 {@code long} 时挂上。 */
    static final String NBT_ENTRY_AMOUNT_WIDE = "#deliveredWide";

    /** 按样板取回它自己那条任务进度：声明形态的推送只认任务表里挂着的发数。 */
    @Nullable
    TaskProgress taskProgress(IPatternDetails details) {
        return details == null ? null : tasks.get(details);
    }

    /**
     * 万象炉申报批的<b>负向终局</b>落在这里：那一笔的发数<b>退回这一条的待办</b>。
     *
     * <p>走的是既有的那一个<b>宽域</b>入口 {@link TaskProgress#addFirings}——不复用
     * {@link TaskProgress#value} 那份饱和读数做加法：越界任务上 {@code value} 是
     * {@code Long.MAX_VALUE} 哨兵，拿哨兵加就等于把 1e19 发加成别的数（同一句口径见
     * {@link TaskProgress} 类注释）。这里<b>不新建第二本账</b>：退回的发数与当初扣掉的那一笔
     * 是同一张 {@link TaskProgress}，扣与退同在一个对象上、同一把尺。
     *
     * <p><b>材料欠账一字不动</b>：欠账一清就同时撤掉它对池子的遮罩（{@code pendingPoolClaims}
     * 读的就是任务表上挂着欠账的样板），"同一份存量许诺两次"逐字复发；这一批<b>重新申报</b>那一刻
     * 由既有的 {@code BatchObligationLedger#incur} 把新的欠账<b>重挂</b>回去（口径见
     * {@code DeclaredBatchTerminalLedger} 类注释"三种终局各结什么"）。也<b>不</b>退料——
     * 机器已经把料吃掉了，通道里没有"该退哪一格料"这一说。
     *
     * @return 落到还挂在任务表上的那一条 ⇒ {@code true}；条目已出表（"永不终局"那一档先放行、
     *         终局事后才到）⇒ {@code false}，<b>不</b>把条目塞回任务表——复活别人已经判完的活
     *         比拦表更坏，那一截只在停滞行 {@code undelivered=} 里点名
     */
    boolean rollBackDeclaredBatchFirings(@Nullable IPatternDetails details,
            @Nullable BigInteger firings) {
        if (details == null || firings == null || firings.signum() <= 0L) {
            return false;
        }
        TaskProgress progress = tasks.get(details);
        if (progress == null) {
            return false;
        }
        progress.addFirings(PlanAmount.of(firings));
        return true;
    }

    /**
     * 记一笔"这张样板按配给取走了这些料"：只记非原样退回的槽。
     *
     * <p>累加走 {@link WideKeyCounter#add(AEKey, long)}（主表 {@code long} + 溢出侧表
     * {@code BigInteger}）：单笔 {@code ≤ long}（一次推送的实提量），跨批复加可越界 ⇒ 账本自动
     * 提升到宽域，<b>内部精确、绝不停在</b> {@link Long#MAX_VALUE}。这本账是"还能推几发"的减数，
     * 顶在天花板就会把还能推的批次算成已用尽；饱和只允许发生在判据的对外读数上
     * （{@code ParallelPlanReservation#remainingQuota} 那一道出口）。
     */
    void addPlanMaterialTaken(IPatternDetails details, AEKey key, long amount) {
        materialAllocation.recordConsumption(details, key, amount);
    }

    /**
     * 同一笔销账的<b>宽域</b>出口：一笔<b>真值</b>就能越出 {@code long}（一发某格真需
     * 1.8e19 枚，见 {@code ParallelBatchExtraction#payInChunks}）时，按 {@code long} 那笔读数
     * 收费就是把这一发吃走的料少记一整刀，下一次还能按满额取。
     *
     * <p>仍走同一张 {@code planMaterialEaten} 表、同一个 {@link WideKeyCounter#add(AEKey,
     * BigInteger)}：不建第二本配额账。
     */
    void addPlanMaterialTaken(IPatternDetails details, AEKey key, BigInteger amount) {
        materialAllocation.recordConsumption(details, key, amount);
    }

    /**
     * 配给表的 <b>{@code used} 腿唯一收窄口</b>：把"该族账面供给"从<b>仿真提取峰值</b>换成
     * <b>提交腿真到账量的族合计 + 有到账之外兜底的那一截 + 计划自产</b>，再在<b>同族的消费者之间
     * 摊派</b>这一份——{@code 份额 ← min(原份额, 摊到那一截)}。
     *
     * <h3>为什么需要这一刀</h3>
     * 上游 {@code MaterialClosureGate} 那本<b>供给原账</b>（{@code validate} 一趟建好，
     * 全仓只在这一个地方数一次腿，任务 #219 条目①）里 {@code used} 那一腿取
     * {@code GraphPlanningState#used}（仿真提取峰值，来自 {@code requiredExtract}）；而提交腿
     * （{@code CraftingContinuationPartialSubmit#extractAvailableInitialItems}）真抽 {@code MODULATE}
     * 短交时，{@code gap} 只决定挂不挂 {@code waitingFor}，不阻断提交 ⇒ lane 带着
     * "配额齐、池子空"起跑，执行侧每 tick 空撞（实机 708 次 {@code nolist}）。配给自锁不成立
     * （表本就是"逐张消费样板 × 每键绝对份数"），账面依据必须改从<b>真搬进池子的量</b>来。
     *
     * <h3>为什么到账量必须<b>摊派</b>而不是逐张各发一份</h3>
     * 调用方交回的是<b>族口径的一份到账</b>（提交腿按 {@code getPrimaryKey()} 聚合，与封顶同一根轴），
     * 而表里同一族可以有 N 个消费者（不同样板、不同槽）。老写法把 {@code 到账 + 自产}
     * 当<b>每张各自的</b>封顶，
     * N 位就各放行一整份 ⇒ 账面留下 {@code (N−1)×到账} 的假份额，正是上面那一刀要消灭的形状的
     * 反面版本（少算 → 多算）。现在这一份是<b>公用池</b>：按顺位逐张扣，扣完即 0。真正要守的一边
     * 不是"族合计放行份额恒 {@code ≤ 池子三腿}"这条字面不变式，而是<b>还能取的量</b>那一侧
     * （放行份额减去该张在配给表上已吃的量＝执行侧读到的剩余配额）恒 {@code ≤ 池子三腿}：
     * 池子只扣<b>未吃需求</b>——把已吃的量也记进本轮池子的扣减，等于让早先已付过钱的实物再占一遍
     * 本轮到账，后面本可摊到份额的一张当场被截短（台账 #126 的少放行那一型）。
     *
     * <h3>口径（定死）</h3>
     * <ul>
     * <li>已到账量只从真抽那一步拿，而且交回的就是<b>族合计</b>（{@code CraftingContinuationPartialSubmit
     * #FamilyArrivalLedger} 按 {@code getPrimaryKey()} 聚合：每一个真抽过的键都把 {@code extracted}
     * 记进去，<b>含全到账那一档</b>）——到账与封顶必须同一根轴，否则同族一次短交就能把另一档
     * <b>已经躺在池子里</b>的实物夹成 0（台账 #131）；</li>
     * <li>池子第三腿＝{@code outsideArrivals}＝这一族<b>短掉却不落在到账轴上</b>的那一截合计（挂进
     * 在途账／"提交这一步本来就不搬、由执行侧按批续取"），同样是<b>量</b>（台账 #133）：
     * {@code pool＝族到账＋族自产＋那一截}、{@code allowance＝min(pool, demand)}——有兜底的那一员
     * 自己摊得满（对它等于不收窄，{@code >long} 的 lane 不被夹成一刀的量），而同族那一格
     * declared-shortfall 的<b>假配额</b>没有兜底、池子给它不出钱，照旧夹住。
     * 老写法在这里是一面族根的布尔旗＋{@code return}：同族只要有一个人挂着兜底就<b>整族不夹</b>，
     * 与它无关的那一格假配额跟着漏放（欠夹那一型）——旗子换成量之后那一支整块删净，
     * 本方法只有一条路，不并存"看走哪支"；</li>
     * <li>计划自产读现成的那本 {@link #accountPlannedEmit}（提交瞬间任务表产量快照，
     * 宽域真值——条目⑥之前它是 {@code KeyCounter}，{@code >long} 的自产恒读天花板），
     * 与停滞行 {@code planEmit=} 同一把尺，不另写第二本；</li>
     * <li>族口径与封顶同轴（{@code getPrimaryKey()} 合计，配给可能记在带组件的变体键上）；</li>
     * <li><b>摊派顺位＝执行侧的推送顺位</b>（{@link #rationPatternsInExecutionOrder}），
     * 不用 {@code planMaterialPlan}（{@link IdentityHashMap}）的遍历序当顺位；</li>
     * <li><b>摊派只扣未吃需求，而这一时刻未吃需求＝配给表份额</b>：{@link #materialAllocation}
     * 的唯一写口是执行侧销账（{@code ParallelCraftingCpuLogic#recordTakenInputs} → {@link
     * #addPlanMaterialTaken}），而本方法全仓唯一调用点在 {@code trySubmitPartialJob} 交回到账量
     * 那一刻——作业是 {@code trySubmitJob} 刚 {@code new} 出来的（job 非空先吃 {@code CPU_BUSY}；
     * 两个构造器都不从计划或 NBT 恢复这本账，续传作业根本没有这张表）⇒ 调用时 {@code eaten}
     * 恒空。逐张 floor 到已吃量（老 {@code .max(floor)} 与变体层 {@code variantFloor}）在生产
     * 路径上恒取 0，却在形状上把已吃量再记一遍本轮池子的扣减，正是上面"还能取的量"要防的那一型
     * ——整块按死路删除（台账 #126，用户口径：没用的路径删净，不留一半）；</li>
     * <li>无限源键整个不参与：它的配额本就不夹（{@code ParallelPlanReservation} 同一口径），
     * 也就没有可摊的上界；</li>
     * <li>摊派全程走 {@link PlanAmount} 宽域，本方法<b>不饱和</b>；饱和只在交回 AE2 的那一道出口
     * （{@code ParallelPlanReservation#remainingQuota}）。只降不升：摊不到就停在原值，
     * 因此同一族被逐笔到账多次调用也不会把谁抬高。</li>
     * </ul>
     *
     * <p>收窄后份额为 0 ⇒ 取料点按<b>既有</b>路径走（同族别的候选／照实停在缺口）——本方法只改
     * 账面数值，不新增拒绝分支、闸门或早退；任务表份数、计划产出、订单量一字不动。
     * 表的其他写口维持既有形状（{@code adoptPlannedMaterialPlan} 整本采用、mint 趟自算），
     * <b>事后收窄只此一处</b>。
     */
    void narrowPlanMaterialShareByArrival(@Nullable AEKey key, @Nullable PlanAmount arrived,
            @Nullable PlanAmount outsideArrivals) {
        if (key == null || isUnboundedSupply(key)) {
            return;
        }
        materialAllocation.narrowArrival(executionPatternOrder(), key, arrived, outsideArrivals,
                plannedEmitOfFamily(key.getPrimaryKey()));
    }

    /** 执行任务序是材料摊派、提交排水与诊断共用的底序。 */
    private java.util.List<IPatternDetails> executionPatternOrder() {
        return RecursiveTaskOrdering.orderedEntries(tasks, orderingInternalItems()).stream()
                .map(Map.Entry::getKey).toList();
    }

    /** 材料诊断快照；键序保留，金额不可变，不暴露可写账本。 */
    java.util.List<MaterialAllocation.Row> materialRows() {
        return materialAllocation.rows(executionPatternOrder());
    }

    /** 执行身份在前，配给、封套及消费独有身份按登记顺位补尾。 */
    java.util.List<IPatternDetails> rationPatternsInExecutionOrder() {
        return materialRows().stream().map(MaterialAllocation.Row::pattern).toList();
    }

    /**
     * 排序用的环内部件表：契约带的与任务表现算的取并集（同键取更大的起步量）。
     *
     * <p><b>这一把就是全仓唯一的顺位尺</b>：执行侧推送顺位（{@code ParallelCraftingCpuLogic
     * #tickCraftingLogic} 与 {@code #describePlanBalance}）与摊派顺位（{@link
     * #rationPatternsInExecutionOrder}）都读这一个方法——前者经 {@code
     * ParallelCraftingCpuLogic#ringInternalItemsForOrdering} 委托过来，不再各写一份算式。
     * 两份实现必然漂移，漂的那天"摊派顺位"就不再是当初 mint 配给那趟排水的顺位。
     * 包级可见只为给"两处交出同一性"一条直接判据用例入口。
     */
    KeyCounter orderingInternalItems() {
        var derived = ringSeeds.seedAmounts();
        if (!dyeableRecursivePlan || dyeableRecursiveInternalItems.isEmpty()) {
            return derived;
        }
        var merged = new KeyCounter();
        merged.addAll(dyeableRecursiveInternalItems);
        for (var entry : derived) {
            merged.set(entry.getKey(),
                    Math.max(merged.get(entry.getKey()), entry.getLongValue()));
        }
        return merged;
    }

    /**
     * 计划自产的族口径读数：现成的提交瞬间快照 {@link #accountPlannedEmit}，按
     * {@code getPrimaryKey()} 逐笔<b>宽域精确</b>相加（族口径一字未动）。
     *
     * <p>老读口逐笔 {@code entry.getLongValue()}：账体是 AE2 {@code KeyCounter} 时那就是账面原值，
     * 而账面原值在 {@code >long} 的键上就是天花板哨兵（写入侧饱和）。账体换 {@link WideKeyCounter}
     * 之后这里读 {@code getAmount} 的精确值，同一枚键不再有"账上是真值、读出来是哨兵"两把尺。
     */
    private PlanAmount plannedEmitOfFamily(Object family) {
        PlanAmount emit = PlanAmount.ZERO;
        for (AEKey key : accountPlannedEmit.keys()) {
            if (key == null || !family.equals(key.getPrimaryKey())) {
                continue;
            }
            PlanAmount amount = accountPlannedEmit.getAmount(key);
            if (amount.signum() > 0L) {
                emit = emit.add(amount);
            }
        }
        return emit;
    }

    /** 环种账本：只按本作业未完成的任务表派生，交两样——排序用的起步量，与取料点留底用的<b>分量口径</b>
     *  （{@code seedDemandsByFamily()}：族下每枚过资格的<b>精确种键</b>各自的分量成员集合 +
     *  每张成员一发吃掉<b>那一枚</b>几枚 + 一发还回<b>那一枚</b>几枚；同族几个键各记一份，
     *  取料点按族把它们复加、按那两笔的大小决定留底取哪一截。册子每次读都现派生，
     *  "在不在册"那一格读的就是本 tick 的筛图（{@code outstandingFirings <= 0} 的整张出图）。
     *  账本内部那份发数交的是 {@link RingSeedLedger#outstandingFirings}：
     *  {@link TaskProgress#exactRemaining()} 的<b>宽域真值</b> + {@code batchObligations} 上
     *  <b>还没交割的义务发数</b>。两个理由各打一处：
     *  ①留底那两笔复加（{@code owed} 与 {@code handedBack}）两侧都要乘发数，吃 {@code value}
     *  那份饱和镜像时 {@code > long} 的订单会把两侧同时顶到 {@code Long.MAX_VALUE} ⇒
     *  那截"到底还差几枚"就读不出来了，取料点按这两笔定留底哪一截也就没有可信读数；②申报那一刻
     *  {@code consumeFirings} 已把这 {@code k} 发从剩余里扣干，
     *  剩下那 {@code k-1} 发的材料仍由同一张样板从池子里取——只看剩余发数会把这一张整张筛出 SCC 图外，
     *  它既不进旁人的 {@code owed}（旁人夹得不够），也不进 {@code members}（它自己失去豁免、
     *  回来付欠账时被同族留底夹死）。留底那笔复加的发数由 {@code ParallelPlanReservation}
     *  现读同一句 {@link RingSeedLedger#outstandingFirings}，两侧同一条尺。 */
    final RingSeedLedger<ParallelExecutingCraftingJob.TaskProgress> ringSeeds =
            new RingSeedLedger<>(tasks, progress -> progress.exactRemaining(),
                    batchObligations::pendingFiringsWide);
    final KeyCounter dyeableRecursiveInternalItems = new KeyCounter();

    @FunctionalInterface
    interface CraftingDifferenceListener {
        void onCraftingDifference(appeng.api.stacks.AEKey what);
    }

    ParallelExecutingCraftingJob(
            ICraftingPlan plan,
            CraftingDifferenceListener postCraftingDifference,
            CraftingLink link,
            @Nullable Integer playerId
    ) {
        this.finalOutput = plan.finalOutput();
        this.remainingAmount = this.finalOutput == null ? 0L : this.finalOutput.amount();
        var rootAmount = this.finalOutput == null ? PlanAmount.ZERO : WideAmountPlanCarrier.resolveFinalOutput(plan);
        if (rootAmount.signum() > 0) {
            this.exactFinalOutputAmount = rootAmount;
            this.exactRemainingAmount = rootAmount;
        }
        this.planClass = plan.getClass().getSimpleName();
        this.unboundedSupplyKeys = mutableKeys(WideAmountPlanCarrier.resolveUnboundedKeys(plan));
        this.waitingFor = new WideCraftingInventory(postCraftingDifference::onCraftingDifference);
        this.timeTracker = new ParallelElapsedTimeTracker();
        // 环催化物的起步份数由规划期多算一发解决，随 usedItems 一起提进库存；
        // 任务表现算的账本（ringSeeds）本身不动库存：它把环成员排到队首，并交出分量口径
        // 让取料点上的环种留底扣住"环自己还没跑完的那一截"（见 ParallelPlanReservation#ringSeedReserves）。
        RecursivePlanContract seedContract = plan instanceof RecursivePlanContract recursivePlan
                ? recursivePlan : null;
        this.dyeableRecursivePlan = seedContract != null
                && seedContract.chexsonsaeutils$usesDyeableRecursivePlanning();
        if (this.dyeableRecursivePlan) {
            this.dyeableRecursiveFinalOutputAmount =
                    seedContract.chexsonsaeutils$dyeableRecursiveFinalOutputAmount();
            this.dyeableRecursiveInternalItems.addAll(seedContract.chexsonsaeutils$dyeableRecursiveInternalItems());
        }

        // 保存完整毛产量供旧余额迁移，含不经任务表的 emittedItems；它不参与根订单判定。
        PlanAmount terminalProduction = null;
        for (var entry : plan.emittedItems()) {
            waitingFor.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            BigInteger exactEmitted = WideAmountPlanCarrier.resolveWideLedgers(plan)
                    .get(git.chexson.chexsonsaeutils.bigint.WideAmountKind.EMIT, entry.getKey());
            PlanAmount emittedAmount = exactEmitted == null ? PlanAmount.of(entry.getLongValue()) : PlanAmount.of(exactEmitted);
            timeTracker.addMaxItems(emittedAmount, entry.getKey().getType());
            if (entry.getLongValue() > 0L && isFinalOutputKey(entry.getKey())) {
                terminalProduction = (terminalProduction == null ? PlanAmount.ZERO : terminalProduction)
                        .add(emittedAmount);
            }
        }
        // 份数一律取规划侧随计划出门的宽域真值：patternTimes() 是 AE2 那份会被饱和成
        // Long.MAX_VALUE 的代表值字典，快照里有这张样板就用快照的真值，没有才说明
        // 代表值即真值（认不到载体时快照为空，整张表退回老口径）。
        //
        // 键集取"自有宽域份数账 ∪ AE2 代表值字典"，不能只问其中一份：
        // WideCraftSnapshot.capture 按设计只记发数越出 long 的样板（常态直接返回空快照），
        // 单拿它的键集建表会把所有塞得进 long 的样板整批丢掉，等于作业少跑一批任务；
        // 反过来只问 patternTimes() 则让 AE2 那份代表值字典决定"哪些样板该有名字"，
        // 宽域账里独有的一截（AE2 字典没记上的越界样板）就永远进不了表。
        // 并集两条都不丢，且表里每个键的份数优先取宽域真值。
        // 名单宇宙与每枚份数的派生收在 PatternRosterReadout.universeAndTimes 一处（⑩）：
        // 键集取"自有宽域份数账 ∪ AE2 代表值字典"，份数优先宽域真值、代表值兜底——建表照这份数排
        // 任务，读数（stage=build）照同一份数点名，绝不在别处再算一份"看起来一样的数"。
        var timesByPattern = PatternRosterReadout.universeAndTimes(plan);
        // 计划名单（B-1）＋规划出口对拍（⑩）：0 发那张照样进名单带 DROP(zeroFirings)，只列进表的
        // 等于没答。建表这一档补 stage=build，与规划出口的 stage=exit 同一把尺一比，少掉的那几张当场点名。
        // terminalProduction 在上面 emittedItems 那一趟已收下"直接出门"那一截，这里只把任务表各张产出累上去。
        for (var rosterEntry : timesByPattern.entrySet()) {
            IPatternDetails pattern = rosterEntry.getKey();
            PlanAmount executions = rosterEntry.getValue();
            if (executions.signum() <= 0) {
                continue;   // 0 发那张建表整张丢掉；名单里由 PatternRosterReadout 点名 DROP
            }
            tasks.computeIfAbsent(pattern, ignored -> new TaskProgress()).addFirings(executions);
            for (var output : pattern.getOutputs()) {
                // GenericStack 已使用回交接口的原始单位；流体不能再乘一次每单位数量。
                // 进度跟踪器对计划量与实际回交量使用同一条单位归一化公式。
                var amount = PlanAmount.of(output.amount()).multiply(executions);
                timeTracker.addMaxItems(amount, output.what().getType());
                // 毛产量与根订单分别记账，配方取整和环内复用不能扩大收货请求。
                if (output.amount() > 0L && isFinalOutputKey(output.what())) {
                    terminalProduction = (terminalProduction == null
                            ? PlanAmount.ZERO : terminalProduction)
                            .add(PlanAmount.of(output.amount()).multiply(executions));
                }
            }
        }
        this.finalOutputPlannedProduction = terminalProduction;
        // 一行自报覆盖面（组装口唯一）：shown=打印出来的张数（截断后 ≤ cap），total=表里真有几张，
        // zeroFirings=被建表整张丢掉的张数。<b>这行只进 debug.log，不进 latest.log</b>（作者读的现场
        // 文件是 PrismLauncher 实例的 debug.log，DEBUG 档在那儿开着）；建表每作业一行，latest.log 里
        // 读不到它，不是没打。
        LOGGER.debug("Graph planning pattern roster: {}",
                PatternRosterReadout.formatLine("build", timesByPattern));
        this.link = link;
        this.playerId = playerId;
        this.suspended = false;
    }

    ParallelExecutingCraftingJob(
            CompoundTag data,
            HolderLookup.Provider registries,
            CraftingDifferenceListener postCraftingDifference,
            ParallelCraftingLaneState lane
    ) {
        this.link = new CraftingLink(data.getCompound(NBT_LINK), lane.linkCpu());
        this.finalOutput = GenericStack.readTag(registries, data.getCompound(NBT_FINAL_OUTPUT));
        this.remainingAmount = data.getLong(NBT_REMAINING_AMOUNT);
        if (data.contains("exactFinalOutputAmount", Tag.TAG_BYTE_ARRAY)) {
            this.exactFinalOutputAmount = PlanAmount.of(new BigInteger(data.getByteArray("exactFinalOutputAmount")));
            this.exactRemainingAmount = PlanAmount.of(new BigInteger(data.getByteArray("exactRemainingAmount")));
            if (exactFinalOutputAmount.signum() <= 0 || exactRemainingAmount.signum() < 0
                    || exactRemainingAmount.compareTo(exactFinalOutputAmount) > 0)
                throw new IllegalArgumentException("宽域根订单存档数量无效");
            this.remainingAmount = git.chexson.chexsonsaeutils.bigint.Ae2Amounts.toLong(exactRemainingAmount);
            if (data.contains("exactFinalOutputDelivered", Tag.TAG_BYTE_ARRAY)) {
                var delivered = new BigInteger(data.getByteArray("exactFinalOutputDelivered"));
                if (delivered.signum() < 0) throw new IllegalArgumentException("宽域根订单交付量无效");
                this.finalOutputDelivered.add(this.finalOutput.what(), delivered);
            }
        }
        this.unboundedSupplyKeys = mutableKeys(Set.of());
        for (var key : RecursiveCounterNbt.read(data.getList("unboundedSupplyKeys", Tag.TAG_COMPOUND), registries)) {
            unboundedSupplyKeys.add(key.getKey());
        }
        this.initialItemsCursor = data.getInt("initialItemsCursor");
        if (data.contains("finalOutputDelivered", Tag.TAG_BYTE_ARRAY)) {
            finalOutputDelivered.setExact(finalOutput.what(), new BigInteger(data.getByteArray("finalOutputDelivered")));
        }
        if (data.contains("finalOutputPlannedProduction", Tag.TAG_BYTE_ARRAY)) {
            finalOutputPlannedProduction = PlanAmount.of(new BigInteger(data.getByteArray("finalOutputPlannedProduction")));
        }
        this.waitingFor = new WideCraftingInventory(postCraftingDifference::onCraftingDifference);
        this.waitingFor.readFromNBT(data.getList(NBT_WAITING_FOR, Tag.TAG_COMPOUND), registries);
        this.initialItemsRemaining.readFromNBT(
                data.getList("initialItemsRemaining", Tag.TAG_COMPOUND), registries);
        this.timeTracker = new ParallelElapsedTimeTracker(data.getCompound(NBT_TIME_TRACKER));
        this.playerId = data.contains(NBT_PLAYER_ID, Tag.TAG_INT) ? data.getInt(NBT_PLAYER_ID) : null;
        this.dyeableRecursivePlan = data.getBoolean(NBT_DYEABLE_RECURSIVE_PLAN);
        this.dyeableRecursiveFinalOutputAmount = data.getLong(NBT_DYEABLE_RECURSIVE_FINAL_OUTPUT_AMOUNT);
        this.dyeableRecursiveInternalItems.addAll(RecursiveCounterNbt.read(
                data.getList(NBT_DYEABLE_RECURSIVE_INTERNAL_ITEMS, Tag.TAG_COMPOUND),
                registries
        ));
        boolean legacyRootAmount = exactFinalOutputAmount == null;
        if (legacyRootAmount) {
            // 没有宽域根量的旧计划，其 long 订单量就是真值，MAX 也不是未知量哨兵。
            exactFinalOutputAmount = finalOutputOrderAmount();
            if (exactFinalOutputAmount != null) {
                exactRemainingAmount = PlanAmount.of(Math.max(0L, remainingAmount)).min(exactFinalOutputAmount);
                remainingAmount = git.chexson.chexsonsaeutils.bigint.Ae2Amounts.toLong(exactRemainingAmount);
            }
        }

        ListTag tasksTag = data.getList(NBT_TASKS, Tag.TAG_COMPOUND);
        for (int index = 0; index < tasksTag.size(); index++) {
            CompoundTag item = tasksTag.getCompound(index);
            AEItemKey pattern = AEItemKey.fromTag(registries, item);
            IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, lane.cluster().level());
            if (details == null) {
                LOGGER.error("并行 CPU 恢复失败：无法解码任务样板 {}，暂停任务并保留原始存档条目", pattern);
                unresolvedTasks.add(item.copy());
                continue;
            }
            this.tasks.put(details, readTaskProgress(item));
            materialAllocation.readPatternFromNBT(details, item.getCompound("materialAllocation"), registries);
            // 已声明出去的材料交割义务随对应任务恢复。
            this.batchObligations.incur(details, BatchObligationLedger.readEntry(item));
        }

        this.suspended = data.getBoolean(NBT_SUSPENDED) || !unresolvedTasks.isEmpty();
        this.cancellationRequested = data.getBoolean("cancellationRequested");
        this.completionRequested = data.getBoolean("completionRequested");
        if (data.contains("requesterDeliveryRemaining", Tag.TAG_BYTE_ARRAY)) {
            this.requesterDeliveryRemaining = PlanAmount.of(
                    new BigInteger(data.getByteArray("requesterDeliveryRemaining")));
            if (requesterDeliveryRemaining.signum() < 0) {
                throw new IllegalArgumentException("递归收货余额不得为负");
            }
            if (legacyRootAmount && PlanAmount.of(Long.MAX_VALUE).equals(exactFinalOutputAmount)
                    && finalOutputPlannedProduction != null) {
                // 旧 MAX 订单错误地用毛产量初始化余额。保留其已真实交给 requester 的数量，
                // 只修正尚欠量；上面补出的权威根量随 NBT 保存，使此迁移只执行一次。
                PlanAmount delivered = finalOutputPlannedProduction.subtract(requesterDeliveryRemaining)
                        .max(PlanAmount.ZERO);
                requesterDeliveryRemaining = exactFinalOutputAmount.subtract(delivered).max(PlanAmount.ZERO);
            }
        }
        // 预期入网的产出账跟着作业回来，只为把它<b>原样退回</b>（#227 K2 的第二处退账，与负向终局
        // 走同一个 revoke 出口）：交回这一截枚数的凭据——那笔的在飞台账与它的 {@code batchId}——按
        // 既有口径<b>不落 NBT</b>（{@code batchId} 是 useless 侧现生成的内存态 UUID，重载后终局永不来），
        // 所以这一截既转不成已交割、也不许继续站在收工第二臂的分子上：留着它就等于让一笔"永远等不到
        // 凭据"的预期把整张作业永久钉在"未收工"上，正是 {@code #209}／{@code 4816673} 那一型。
        // 与 K1 那条不变量对齐：{@code planNet} 只装"挂在飞台账上的那一笔"，续传后台账是空的，
        // 这本账在判据里就必须同样是空的。落盘那一步照旧保留（存档里留着这份历史枚数供事后归因，
        // 也保住 {@code #225} 读数 4 那三格并排的口径），读回来即退。
        readExpectedNetworkOutputs(data, registries, this.outputsExpectedToNetwork);
        dropRestoredExpectedToNetwork();
    }

    /**
     * 续传时把落盘回来的"直投网络"预期整本退掉——与负向终局同一本账、同一个
     * {@link #revokeDeclaredBatchPlannedNetworkOutputs} 出口（不建第二本、不加第二把尺）。
     *
     * <p>理由逐字写在调用点：凭据不落盘 ⇒ 这一截永远结不掉 ⇒ 不许拦收工（{@code #209} 那一型）。
     * 本场内"划了账却没等到终局"那一档照旧拦着收工，由
     * {@code ParallelDeclaredBatchDeliveryCredentialTest#planNetRecordedWithoutAnyInflightEntryKeepsTheJobOpen}
     * 钉住；续传后收工照旧放行，由
     * {@code ParallelRecursiveFinalOutputDeliveryTest#tickClosingAsksTheCriterionWhenNoPhysicalDeliveryEverArrives}
     * 那条走 NBT 往返的夹具钉住。
     */
    private void dropRestoredExpectedToNetwork() {
        if (outputsExpectedToNetwork.isEmpty()) {
            return;
        }
        var restored = new java.util.LinkedHashMap<AEKey, BigInteger>();
        for (AEKey key : outputsExpectedToNetwork.keys()) {
            restored.put(key, outputsExpectedToNetwork.getAmount(key).exactValue());
        }
        revokeDeclaredBatchPlannedNetworkOutputs(restored);
    }

    CompoundTag writeToNBT(HolderLookup.Provider registries) {
        CompoundTag data = new CompoundTag();
        CompoundTag linkData = new CompoundTag();
        link.writeToNBT(linkData);
        data.put(NBT_LINK, linkData);
        if (finalOutput != null) {
            data.put(NBT_FINAL_OUTPUT, GenericStack.writeTag(registries, finalOutput));
        }
        data.put(NBT_WAITING_FOR, waitingFor.writeToNBT(registries));
        data.put("initialItemsRemaining", initialItemsRemaining.writeToNBT(registries));
        data.put(NBT_TIME_TRACKER, timeTracker.writeToNBT());

        ListTag taskList = new ListTag();
        for (var entry : tasks.entrySet()) {
            CompoundTag item = entry.getKey().getDefinition().toTag(registries);
            writeTaskProgress(item, entry.getValue());
            item.put("materialAllocation", materialAllocation.writePatternToNBT(entry.getKey(), registries));
            BatchObligationLedger.writeEntry(item, batchObligations.pendingFirings(entry.getKey()));
            taskList.add(item);
        }
        taskList.addAll(unresolvedTasks.copy());
        data.put(NBT_TASKS, taskList);
        data.putInt("initialItemsCursor", initialItemsCursor);
        var unboundedKeys = new KeyCounter();
        for (var key : unboundedSupplyKeys) unboundedKeys.add(key, 1L);
        data.put("unboundedSupplyKeys", RecursiveCounterNbt.write(unboundedKeys, registries));
        if (finalOutput != null) {
            data.putByteArray("finalOutputDelivered", finalOutputDeliveredAmount().exactValue().toByteArray());
        }
        if (finalOutputPlannedProduction != null) {
            data.putByteArray("finalOutputPlannedProduction", finalOutputPlannedProduction.exactValue().toByteArray());
        }
        // 预期入网账落盘，且落<b>作业级</b>而非任务条目级：宿主自报的是"哪些键的产物直接
        // 交出去、不再回池子"的<b>枚数</b>，不是发数，而且报的是样板声明的<b>模板键</b>——动态产物的
        // 真实键要到 onBatchOutputs 才认得出来（见 AlloyFurnaceBigIntegerCpuAdapter#onBatchOutputs），
        // 硬摊到样板头上会把不存在的键记到别人名下。它是流水账，与 account* 同侧。
        writeExpectedNetworkOutputs(data, registries, outputsExpectedToNetwork);
        data.putLong(NBT_REMAINING_AMOUNT, remainingAmount);
        if (exactFinalOutputAmount != null) {
            data.putByteArray("exactFinalOutputAmount", exactFinalOutputAmount.exactValue().toByteArray());
            data.putByteArray("exactRemainingAmount", exactRemainingAmount.exactValue().toByteArray());
            data.putByteArray("exactFinalOutputDelivered", finalOutputDeliveredAmount().exactValue().toByteArray());
        }
        if (playerId != null) {
            data.putInt(NBT_PLAYER_ID, playerId);
        }
        data.putBoolean(NBT_SUSPENDED, suspended);
        data.putBoolean("cancellationRequested", cancellationRequested);
        data.putBoolean("completionRequested", completionRequested);
        if (requesterDeliveryRemaining != null) {
            data.putByteArray("requesterDeliveryRemaining", requesterDeliveryRemaining.exactValue().toByteArray());
        }
        data.putBoolean(NBT_DYEABLE_RECURSIVE_PLAN, dyeableRecursivePlan);
        data.putLong(NBT_DYEABLE_RECURSIVE_FINAL_OUTPUT_AMOUNT, dyeableRecursiveFinalOutputAmount);
        data.put(
                NBT_DYEABLE_RECURSIVE_INTERNAL_ITEMS,
                RecursiveCounterNbt.write(dyeableRecursiveInternalItems, registries)
        );
        return data;
    }

    static void addMaxItems(ParallelElapsedTimeTracker tracker, long itemDiff, @Nullable AEKeyType keyType) {
        if (itemDiff <= 0L || keyType == null) {
            return;
        }
        tracker.addMaxItems(itemDiff, keyType);
    }

    static void decrementItems(ParallelElapsedTimeTracker tracker, long itemDiff, @Nullable AEKeyType keyType) {
        if (itemDiff <= 0L || keyType == null) {
            return;
        }
        tracker.decrementItems(itemDiff, keyType);
    }

    /**
     * 把一条任务的份数写进它自己的条目 tag：常态只写老 {@code long} 键（越界时才多一个宽域键），
     * 老键位始终存饱和 {@code long} 读数，读回规则见 {@link #readTaskProgress(CompoundTag)}。
     */
    static void writeTaskProgress(CompoundTag item, TaskProgress progress) {
        item.putLong(NBT_CRAFTING_PROGRESS, progress.value);
        BigInteger wide = progress.wideExact();
        if (wide != null) {
            item.putByteArray(NBT_CRAFTING_PROGRESS_WIDE, wide.toByteArray());
        }
    }

    /**
     * 从条目 tag 读回一条任务的份数：带宽域键就按补码还原精确值，否则按老 {@code long} 值读。
     *
     * <p>不写存档迁移壳（本仓库口径：破坏性变更不留兼容层）——缺宽域键就是"这份数装得进
     * {@code long}"，与新建表时的形态完全一致。
     */
    static TaskProgress readTaskProgress(CompoundTag item) {
        TaskProgress progress = new TaskProgress();
        progress.value = item.getLong(NBT_CRAFTING_PROGRESS);
        if (item.contains(NBT_CRAFTING_PROGRESS_WIDE, Tag.TAG_BYTE_ARRAY)) {
            progress.wide = new BigInteger(item.getByteArray(NBT_CRAFTING_PROGRESS_WIDE));
        }
        return progress;
    }

    /**
     * 把预期入网账写进作业 tag：逐键一条条目，老 {@value #NBT_ENTRY_AMOUNT} 键存饱和
     * {@code long} 读数、越界才多挂 {@value #NBT_ENTRY_AMOUNT_WIDE}（BigInteger 补码字节）。
     *
     * <p>编码规则与 {@link #writeTaskProgress(CompoundTag, TaskProgress)} 逐字同一条，不复用它的
     * 键名位——这份账的条目是 {@code (AEKey, 枚数)}，不是一条任务的发数。常态零预期 ⇒ 一个键都不挂。
     */
    static void writeExpectedNetworkOutputs(CompoundTag data, HolderLookup.Provider registries,
            WideKeyCounter expected) {
        if (data == null || registries == null || expected == null || expected.isEmpty()) {
            return;
        }
        ListTag entries = new ListTag();
        for (AEKey key : expected.keys()) {
            if (key == null) {
                continue;
            }
            BigInteger exact = expected.getExact(key);
            if (exact == null || exact.signum() <= 0L) {
                continue;
            }
            boolean insideLong = exact.bitLength() <= 63;
            CompoundTag entry = key.toTagGeneric(registries);
            entry.putLong(NBT_ENTRY_AMOUNT, insideLong ? exact.longValueExact() : Long.MAX_VALUE);
            if (!insideLong) {
                entry.putByteArray(NBT_ENTRY_AMOUNT_WIDE, exact.toByteArray());
            }
            entries.add(entry);
        }
        if (!entries.isEmpty()) {
            data.put(NBT_OUTPUTS_EXPECTED_TO_NETWORK, entries);
        }
    }

    /**
     * 从作业 tag 读回预期入网账：条目带宽域键按补码还原精确值，否则按老 {@code long} 值读；
     * 缺整个列表就是"这作业没有预期入网产出"——自然缺省，不是迁移壳。
     */
    static void readExpectedNetworkOutputs(CompoundTag data, HolderLookup.Provider registries,
            WideKeyCounter into) {
        if (data == null || registries == null || into == null
                || !data.contains(NBT_OUTPUTS_EXPECTED_TO_NETWORK, Tag.TAG_LIST)) {
            return;
        }
        ListTag entries = data.getList(NBT_OUTPUTS_EXPECTED_TO_NETWORK, Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            AEKey key = AEKey.fromTagGeneric(registries, entry);
            if (key == null) {
                continue;
            }
            BigInteger exact = entry.contains(NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)
                    ? new BigInteger(entry.getByteArray(NBT_ENTRY_AMOUNT_WIDE))
                    : BigInteger.valueOf(entry.getLong(NBT_ENTRY_AMOUNT));
            if (exact.signum() > 0L) {
                into.setExact(key, exact);
            }
        }
    }

    /**
     * 一张样板的剩余发数：常态走 {@code long} 零分配，只有真越界才进 {@link #wide} 侧值。
     *
     * <p>范式照 {@code WideCraftCounter}（主表 {@code long} + 溢出侧表 + 饱和哨兵），
     * 算术本体复用 {@link PlanAmount}，本类不实现第二套宽域算术。
     *
     * <p><b>{@link #value} 是饱和读数，不是账本</b>：越界时它是代表值 {@code Long.MAX_VALUE}，
     * 只准给排序视图、展示与那些只能吃 {@code long} 的旧调用点用。扣减走
     * {@link #consumeFirings(long)}、判完成走 {@link #isFinished()}——拿饱和读数判完成，
     * 就是让哨兵被逐批扣到 0 时把"还剩 1e19 发"当成收工。
     */
    static final class TaskProgress implements RecursiveTaskOrdering.ParallelTaskProgressView {
        /** 饱和 {@code long} 读数；{@link #wide} 为 {@code null} 时它就是精确值。 */
        long value;
        /** 精确发数越出 {@code long} 时的真值；{@code null} 表示 {@link #value} 即精确值。 */
        @Nullable
        private BigInteger wide;

        /** 宽域累加发数（提交期建表，同样板多站点相加）：越界自动升侧值，绝不饱和、绝不回绕。 */
        void addFirings(PlanAmount delta) {
            if (delta == null || delta.signum() == 0) {
                return;
            }
            writeExact(wide == null
                    ? PlanAmount.of(value).add(delta)
                    : PlanAmount.of(wide).add(delta));
        }

        /** 精确扣减一批实收发数；扣回 {@code long} 域即撤侧值，{@link #value} 恢复精确。 */
        void consumeFirings(long batchExecutions) {
            if (batchExecutions == 0L) {
                return;
            }
            writeExact(exactRemaining().subtract(PlanAmount.of(batchExecutions)));
        }

        /** BigInteger 宿主实收份数直接扣减，禁止经过 long 投影。 */
        void consumeFirings(BigInteger batchExecutions) {
            writeExact(exactRemaining().subtract(PlanAmount.of(batchExecutions)));
        }

        /** 精确剩余发数：判完成、算批上限的读口。 */
        PlanAmount exactRemaining() {
            return wide != null ? PlanAmount.of(wide) : PlanAmount.of(value);
        }

        /** 任务是否真跑完：只看精确值，绝不用饱和读数判。 */
        boolean isFinished() {
            return exactRemaining().signum() <= 0;
        }

        /** 写回精确值并按需升降侧值（与 {@code WideCraftCounter.promote} 同一条规则）。 */
        private void writeExact(PlanAmount exact) {
            if (exact.isFast()) {
                this.wide = null;
                this.value = exact.fastValue();
                return;
            }
            BigInteger wideExact = exact.exactValue();
            if (wideExact.bitLength() <= 63) {
                this.wide = null;
                this.value = wideExact.longValueExact();
                return;
            }
            this.wide = wideExact;
            this.value = wideExact.signum() >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }

        /** 侧值本体（能塞 {@code long} 时返回 {@code null}）：NBT 编码只认它。 */
        @Nullable
        BigInteger wideExact() {
            return this.wide;
        }

        /** {@inheritDoc} 排序视图：给饱和 {@code long} 读数——只要比大小，不参与判完成。 */
        @Override
        public long chexsonsaeutils$dyeableRecursiveTaskProgressValue() {
            return value;
        }
    }
}
