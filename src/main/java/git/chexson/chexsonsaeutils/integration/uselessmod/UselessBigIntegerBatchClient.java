package git.chexson.chexsonsaeutils.integration.uselessmod;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.stacks.KeyCounter;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * useless_mod 的 BigInteger 批次通道客户端：{@code findTargets → capacity → admit → commit}。
 *
 * <p>它拆的是 {@code ScaledProcessingPattern(inner, k)} 折叠的那道 {@code long} 夹子：走这条通道时
 * 份数是 {@link BigInteger}，宿主不再从 holder 的实际量反推发数，一次交互能推进几发只由机器的
 * 动态接单量决定。实收份数交给 CPU 的申报账（见 {@code ParallelCraftingCpuLogic#attemptDeclaredBatch}），
 * 剩下 {@code count - 1} 份材料的扣账与交割归调用方的义务账，机器一概不管。
 *
 * <p><b>两道闸口，缺一不可</b>：
 * <ul>
 *   <li>{@link #modLoaded()} —— 没装 useless_mod 就直接不可用，连它的类都不引用；</li>
 *   <li>{@link #apiAvailable()} —— 装了还要 {@code bigint} 入口类在场（2.3.8 起才有）。
 *       老版本装了但没这通道时同样退回既有折叠路径，绝不抛 {@code NoClassDefFoundError}。</li>
 * </ul>
 * 所有真正引用 useless 类型的动作都收在 {@link UselessBigIntegerApiBridge} 里，且只能在
 * {@link #apiAvailable()} 为真之后被加载。本文件本身不 import 任何 useless 类型，
 * 所以下面这套判据（含 {@link #dispatch}）在没装 useless_mod 的离线测试里照样能跑。
 *
 * <p><b>仅服务器线程</b>：与 useless 侧 API 的线程约定一致。
 */
public final class UselessBigIntegerBatchClient {

    /** useless_mod 的 modid。 */
    public static final String USELESS_MOD_ID = "useless_mod";
    /** bigint 通道的发现入口类：在场即说明这台 useless 有 BigInteger 批次能力（2.3.8+）。 */
    private static final String BIGINT_API_CLASS =
            "com.sorrowmist.useless.api.crafting.bigint.AlloyFurnaceBigIntegerApi";
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * <b>一次性闩</b>（每 JVM 只放行一次）：{@code bigint 批次已接单} 那一行 INFO 因此<b>每 JVM 只打一条</b>。
     *
     * <p><b>不许拿它的行数当批次数</b>（台账 #225 就地标明这条误读）：2026-10-02 19:55 那一跑日志里
     * 只有 1 条接单行，被读成"只接了 1 批"——错。接了几批只看得见 {@code [chexsonsaeutils-batch]} 行的
     * {@code declared=}/{@code declaredK=}（百轮窗）与同一行的 {@code declaredBigint[累计]=}/
     * {@code inflightRegistered[累计]=}（台账累计），与这一条 INFO 的行数无关。
     */
    private static final AtomicBoolean FIRST_SUCCESS = new AtomicBoolean(false);
    /**
     * 同样是<b>一次性闩</b>（每 JVM 只打一条 WARN）：{@code 机器收了 N 发却没回报计划产出}。
     * 行数<b>不许</b>当成"没报计划的批次有几批"，理由与 {@link #FIRST_SUCCESS} 同一条。
     */
    private static final AtomicBoolean FIRST_MISSING_PLAN = new AtomicBoolean(false);

    private static volatile boolean apiProbed;
    private static volatile boolean apiPresent;

    private UselessBigIntegerBatchClient() {
    }

    // ==================== 通道形状（无 useless 类型） ====================

    /** 一台机器此刻能接几发「单发原型」：{@code 0} 即一份都收不了，{@code statusKey} 给可显示的原因。 */
    public record Capacity(@Nullable BigInteger accepted, String statusKey) {

        public boolean available() {
            return accepted != null && accepted.signum() > 0;
        }
    }

    /** 一次准入凭据：只能提交一次，提交用的必须是准备时那一份原型数组对象。 */
    public interface BatchHandle {

        /** @return 已获准的份数（恒为正） */
        @Nullable BigInteger count();

        /** @return 机器是否真的接收；{@code false} 表示没吃任何料，调用方自己回滚那 {@code count - 1} 份 */
        boolean commit();

        /**
         * 这一笔的<b>输入料所有权</b>是不是已经归了这台机器（useless 的 public 口
         * {@code AlloyFurnaceBigIntegerBatch#hasTransferredInputOwnership()} 原样透出）。
         *
         * <p>它只为一件事存在：{@link #commit()} 回 {@code false} 有<b>两种完全不同的 false</b>——
         * "拒收、一分料没吃"与"回了 false 却已经把料吃掉"。光看 {@code false} 这两型逐字同影，
         * 而回滚、回池、退避全按"没吃料"那一种写死处置，所以<em>读数</em>必须把两型分开。
         *
         * <p><b>这一对为什么算双证</b>（装机 2.3.9 的字节码顺序）：那边的背压检查
         * （{@code isOutputBacklogFull()}）排在 {@code KeyCounter.clear()} 与扣能量<b>之前</b>，
         * 所以"背压拒收"那一支走到 {@code return false} 时所有权确实还没转移；反过来
         * {@code false + true} 就是"报了 false、料却已被吃"那一型。<b>只印这一对，处置一字不改。</b>
         */
        boolean hasTransferredInputOwnership();
    }

    /**
     * 一台机器「回网队列」的深度读数（台账 #225 读数 5，<b>纯读数</b>，不参与任何判据）：
     * {@code entries}＝排在机器这边还没回网络的<b>队列条目数</b>，{@code cap}＝该机这一档的条目上限，
     * {@code saturated}＝这一格只读得出"条目数 ≥ cap"（读口的返回值已到 0，再也减不出更多）。
     *
     * <p><b>这一格问得出真值</b>（旧注释说"问不出"，那是撒谎）：装机 2.3.9 的
     * {@code MultiblockAlloyFurnaceCoreBlockEntity} 交回两个 {@code public} 读口
     * {@code getMaxAETaskCount()} 与 {@code getRemainingAETaskCount(boolean)}，后者那一支
     * （{@code craftingPattern=true}）的字节码就是
     * {@code max(0, cap - min(cap, queuedCraftingOutputs.size()))}，
     * 而 {@code cap = max(2, min(Integer.MAX_VALUE, max(1, getMaxAETaskCount()) × 8))}
     * （{@code AdvancedAlloyFurnaceAeManager#hardOutputQueueCap}，倍数常量
     * {@code OUTPUT_BACKLOG_HARD_MULTIPLIER=8}）。私有字段 {@code queuedCraftingOutputs} 本身
     * 不读、不反射，只从这两个 public 口<b>换算</b>出条目数：
     * {@code entries = cap - min(cap, remaining)}。</p>
     *
     * <p><b>换算式长在桥那一侧</b>（{@link UselessBigIntegerApiBridge}），所以机器改版、这一格
     * 只可能整体读不到（交回 {@code null} ⇒ 印 {@code ?}），不会读成别人的数。
     * {@code remaining==0} 那一档读不出"到底压了几段"，只能读成"≥cap"——这就是
     * {@code saturated} 存在的全部理由，也是它必须被印成标记而不是数字的理由。</p>
     */
    public record QueuePending(long entries, long cap, boolean saturated) {
    }

    /** 一台接 bigint 通道的多方块万象合金炉。 */
    public interface Machine {

        /** 物理机器身份（{@code 维度@x,y,z}）：同一台机器无论从哪个 provider 暴露都同值，据此不超卖。 */
        String machineIdentity();

        /** 机器是否正在动态降频（正忙）：降频只收窄批次规模，永不把容量压到 0。 */
        boolean throttled();

        /** 查容量：无副作用，可以每 tick 查。 */
        Capacity capacity(IPatternDetails pattern, KeyCounter[] unitPrototype, BigInteger requested);

        /** 申请一批准入；{@code null} 表示此刻不可接受。 */
        @Nullable BatchHandle admit(IPatternDetails pattern, KeyCounter[] unitPrototype,
                BigInteger requested, UselessBigIntegerReceipt receipt);

        /**
         * 这一台的回网队列深度读数（<b>纯读数</b>，见 {@link QueuePending}）。
         *
         * <p>问不到一律交回 {@code null}：BE 不是那一型、类没装、取 BE 的区块没加载、读口抛异常
         * 都算问不到。{@code null} 在下游只许印成 {@code ?}，<b>不许</b>折成 {@code 0}
         * ——0 是"一枚都没积压"，与"没问到"是两件事（本仓已因把问号读成 0 误判过一轮）。
         */
        @Nullable QueuePending queuePending();
    }

    /** 机器发现：离线用例注假货，实机注 {@link UselessBigIntegerApiBridge} 的真 target。 */
    public interface TargetSource {

        List<Machine> findMachines(@Nullable IGrid grid);
    }

    /**
     * 一次成功推送的结果：实收份数、机器自报的<b>计划</b>入网产出（可空＝连计划都没拿到）、接单机器身份，
     * 外加这一笔的 {@code cpuToken}（{@link UselessBigIntegerReceipt}）。
     *
     * <p>{@code expectedToNetwork} 那一份<b>不是入网证据</b>：它是 {@code admit} 那一刻机器按
     * {@code pattern.getOutputs() × 份数} 自报的计划量（见 {@link UselessBigIntegerReceipt}）。
     * 它唯一的下游用途是把"不会再有实物回 CPU 池子"的那一截从 {@code waitingFor} 里摘出去，
     * 不许当成"已交付"参与任何对平。
     *
     * <p>{@code receipt} 是这批发数<b>唯一的结案凭据载体</b>：机器在 {@code commit} 的同一层栈帧里
     * 把 {@code batchId} 记进它（桥侧 {@code onBatchAdmitted}），终局事件也从它身上回来
     * （桥侧 {@code onBatchFinished}）。CPU 侧{@code 在飞台账}就挂在这一枚上。
     */
    public record Result(BigInteger accepted, @Nullable git.chexson.chexsonsaeutils.bigint.WideKeyCounter expectedToNetwork,
            String machineIdentity, @Nullable UselessBigIntegerReceipt receipt) {
    }

    /**
     * 一次 {@code findTargets → capacity → admit → commit} 的<b>完整出口</b>：接单就给
     * {@link #result()}，没接单一律 {@code result == null}，外加 {@link #rejectDetail()}。
     *
     * <p>{@code rejectDetail} 要解决的是"四型同影"：下面那五个静默 {@code continue} 出口
     * （{@code throttled}／{@code capacity}／{@code admit-null}／{@code commit-false}，
     * 以及三个抛异常出口）过去对外只剩一个 {@code null}，实机因此只读得出
     * {@code declaredReject=99} 这一个聚合数，说不出是谁、在哪个出口拒的。
     * 其中 {@code commit-false} 那一型又按<b>输入料所有权是否已转移</b>拆成
     * {@code commit-false-noTake}／{@code commit-false-tookInput}／
     * {@code commit-false-ownershipUnknown} 三格（见 {@link #commitRejectCell}），
     * 拆的是<b>格名</b>，拒收后的处置一句未改。
     *
     * <p><b>纯读数</b>：{@code rejectDetail} 不参与任何判据、不新增拒绝分支，只随返回值带出去；
     * 形状是 {@code 出口#机器身份}，用 {@code /} 连接、封顶四格（与 {@code obWhy} 那一格同风格），
     * 装不下的格数补一格 {@code /更多=N}（不把"看到的这几格"冒充全部）。整串<b>不含空白</b>——
     * 这一串会被拼进 {@code declaredReject=} 那一格，留一个空格下游就把它切成两段。
     */
    public record DispatchOutcome(@Nullable Result result, String rejectDetail) {
    }


    // ==================== 闸口 ====================

    /** @return 装了 useless_mod 吗；mod 列表拿不到时一律按「没装」处理 */
    public static boolean modLoaded() {
        try {
            return ModList.get().isLoaded(USELESS_MOD_ID);
        } catch (Throwable ignored) {
            // 离线测试与 mod 列表未就绪：按没装处理，绝不因此拖垮 CPU tick
            return false;
        }
    }

    /** @return useless_mod 在场且 bigint 入口类也在场；结论探一次就钉住 */
    public static boolean apiAvailable() {
        if (!modLoaded()) {
            return false;
        }
        if (!apiProbed) {
            var present = false;
            try {
                Class.forName(BIGINT_API_CLASS, false, UselessBigIntegerBatchClient.class.getClassLoader());
                present = true;
            } catch (Throwable ignored) {
                // 老版本（2.3.4 及以前）没有这条通道：不可用，不是崩溃
            }
            apiPresent = present;
            apiProbed = true;
            if (!present) {
                LOGGER.info("[chexsonsaeutils-bigint] useless_mod 在场但没有 BigInteger 批次通道"
                        + "（需 2.3.8+）：合金炉继续走 ScaledProcessingPattern 折叠");
            }
        }
        return apiPresent;
    }

    /** 这份样板在不在通道里：只接 AE2 合成样板与 useless 万象样板，普通处理样板不在内。 */
    public static boolean acceptsPatternKind(@Nullable IPatternDetails pattern) {
        if (!apiAvailable() || pattern == null) {
            return false;
        }
        try {
            return UselessBigIntegerApiBridge.acceptsPatternKind(pattern);
        } catch (Throwable e) {
            LOGGER.warn("[chexsonsaeutils-bigint] 样板种类判定失败，本批退回折叠路径", e);
            return false;
        }
    }

    /**
     * 真宿主是不是这条通道的使用者：只有多方块合金炉的核心与 ME 样板组装多方块是。
     *
     * <p>单机高级合金炉只有 {@code cpuBinding=null} 那一条入口——产物只写回网络、不给 CPU 回执，
     * 接上就是把产出记成没人认领的账，所以它进不来。判定本身也自带闸口：通道不在场直接 {@code false}。
     */
    public static boolean isBigIntegerProvider(@Nullable Object host) {
        if (!apiAvailable()) {
            return false;
        }
        try {
            return UselessBigIntegerApiBridge.isBigIntegerProvider(host);
        } catch (Throwable e) {
            LOGGER.warn("[chexsonsaeutils-bigint] provider 判定失败，本批退回折叠路径", e);
            return false;
        }
    }

    /** 现查一次网格里的 bigint 机器；通道不可用时返回空表。 */
    public static List<Machine> findMachines(@Nullable IGrid grid) {
        if (!apiAvailable() || grid == null) {
            return List.of();
        }
        try {
            var machines = UselessBigIntegerApiBridge.findMachines(grid);
            return machines == null ? List.of() : machines;
        } catch (Throwable e) {
            LOGGER.warn("[chexsonsaeutils-bigint] 发现 bigint 目标失败，本 tick 退回折叠路径", e);
            return List.of();
        }
    }

    // ==================== 推送 ====================

    /**
     * 走一遍 {@code findTargets → capacity → admit → commit}，返回实收份数与机器自报的
     * <b>计划入网</b>产出（预期账，不是入网回执）。
     *
     * <p>那一本 {@code expectedToNetwork} 出自 {@code admit} 那一刻机器按配方算出的<b>入队计划量</b>
     * （机器侧 {@code AlloyFurnaceBigIntegerCrafting} 交回 {@code pending.bigIntegerOutputs}），
     * 里面没有一枚"已插进存储"的证据；它的用途只是把不会再回 CPU 池子的那一截从实物预期里摘出去。
     *
     * <p>四种不可用的形态一律返回 {@code null}，且<b>都不留幽灵账</b>：
     * 网格里没有 target、容量不可用、{@code admit} 返回 {@code null}、{@code commit} 返回
     * {@code false}——机器一次料都没吃，CPU 手上的原型料原样还在，调用方按「整批拒收」退回原路径。
     *
     * @param grid          CPU 自己的网格（仅服务器线程）
     * @param pattern       已解开的样板（不能是倍率包装壳）
     * @param unitPrototype <b>单次推送</b>的原型材料，不是整批的 count 倍
     * @param requested     期望份数，可超 {@code long}
     * @param source        机器发现来源
     */
    static @Nullable Result dispatch(@Nullable IGrid grid, @Nullable IPatternDetails pattern,
            @Nullable KeyCounter[] unitPrototype, @Nullable BigInteger requested, TargetSource source) {
        return dispatchOutcome(grid, pattern, unitPrototype, requested, source).result();
    }

    /**
     * {@link #dispatch} 的完整出口：额外的 {@code rejectDetail} 只服务读数，判据一律看 {@code result}。
     *
     * <p>{@code public} 的理由与 {@link TargetSource} 同一条：这条腿的<b>机器发现</b>本来就是注入口
     * （实机注 {@code UselessBigIntegerApiBridge} 的真 target，离线注假货），万象炉申报批的
     * {@code 在飞 → 终局} 那一条判据要能在离线用例里真走一遍
     * {@code findTargets → capacity → admit → commit → receipt}，不许只拿池子腿替代。
     */
    public static DispatchOutcome dispatchOutcome(@Nullable IGrid grid, @Nullable IPatternDetails pattern,
            @Nullable KeyCounter[] unitPrototype, @Nullable BigInteger requested, TargetSource source) {
        var rejected = new RejectCells();
        if (pattern == null || unitPrototype == null || unitPrototype.length == 0
                || requested == null || requested.signum() <= 0 || source == null) {
            rejected.note("bad-args", "-");
            return new DispatchOutcome(null, rejected.describe());
        }
        List<Machine> machines;
        try {
            machines = source.findMachines(grid);
        } catch (Throwable e) {
            LOGGER.warn("[chexsonsaeutils-bigint] 目标枚举失败，本批退回折叠路径", e);
            rejected.note("find-threw", "-");
            return new DispatchOutcome(null, rejected.describe());
        }
        if (machines == null || machines.isEmpty()) {
            rejected.note("no-machine", "-");
            return new DispatchOutcome(null, rejected.describe());
        }
        // 交给机器的是原型的一份<b>副本</b>：commit 成功时机器会逐槽清空它（= 材料所有权转移）。
        // 直接把 CPU 的 holder 递过去，调用方随后的 recordTakenInputs / addDeclaredReserves
        // 就读到空槽 —— 份数申报了、料却一分没销账，正是要避免的幽灵形态。
        var handedOver = copyPrototype(unitPrototype);
        for (var machine : machines) {
            if (machine == null) {
                rejected.note("null-machine", "-");
                continue;
            }
            if (machine.throttled()) {
                // 机器正忙（服务端每 tick 时间预算被打满）：这不是「机器就这样」，非紧急批延后发。
                // 降频永不把容量压到 0，所以停手由调用方决定，这里只是换下一台/退回折叠路径。
                rejected.note("throttled", machine.machineIdentity());
                continue;
            }
            Capacity capacity;
            try {
                capacity = machine.capacity(pattern, handedOver, requested);
            } catch (Throwable e) {
                LOGGER.warn("[chexsonsaeutils-bigint] 容量查询失败，换下一台机器", e);
                rejected.note("capacity-threw", machine.machineIdentity());
                continue;
            }
            if (capacity == null || !capacity.available()) {
                rejected.note("capacity", machine.machineIdentity());
                continue;
            }
            var receipt = new UselessBigIntegerReceipt(machine.machineIdentity());
            BatchHandle batch;
            try {
                batch = machine.admit(pattern, handedOver, capacity.accepted(), receipt);
            } catch (Throwable e) {
                LOGGER.warn("[chexsonsaeutils-bigint] 准入申请失败，换下一台机器", e);
                rejected.note("admit-threw", machine.machineIdentity());
                continue;
            }
            if (batch == null) {
                rejected.note("admit-null", machine.machineIdentity());
                continue;
            }
            boolean committed;
            try {
                committed = batch.commit();
            } catch (Throwable e) {
                LOGGER.warn("[chexsonsaeutils-bigint] 提交异常，按未接收处理（料未转移，不留账）", e);
                rejected.note("commit-threw", machine.machineIdentity());
                continue;
            }
            if (!committed) {
                // 机器没吃任何料：承诺的那 (count - 1) 份原样在 CPU 手上，交给调用方回滚重试
                // （这一句处置一字未动）。这里只把"为什么 false"说清楚：拒收出口按<b>所有权是否已
                // 转移</b>分成两格，两型不再共用一个 commit-false。
                rejected.note(commitRejectCell(batch), machine.machineIdentity());
                continue;
            }
            var acceptedCount = batch.count();
            var accepted = acceptedCount == null || acceptedCount.signum() <= 0
                    ? capacity.accepted() : acceptedCount;
            // 拿回来的是<b>计划</b>入网量：它的用途只有一个——把不会再回 CPU 池子的那一截从实物预期里
            // 摘出去。它不是入网证据，所以也不许拿它说"这批已经交付了"。
            var plannedToNetwork = receipt.plannedAsWideCounter();
            if (plannedToNetwork == null && FIRST_MISSING_PLAN.compareAndSet(false, true)) {
                LOGGER.warn("[chexsonsaeutils-bigint] 机器收了 {} 发却没回报计划产出（machine={}）："
                        + "这一批只能整批挂 waitingFor 等实物，而那批实物永远不会回 CPU——"
                        + "挂账从此结不掉，回执通道对不上账", accepted, machine.machineIdentity());
            }
            if (FIRST_SUCCESS.compareAndSet(false, true)) {
                LOGGER.info("[chexsonsaeutils-bigint] bigint 批次已接单 machine={} 实收={} 申报={} "
                                + "计划入网产出键数={}",
                        machine.machineIdentity(), accepted, requested,
                        plannedToNetwork == null ? 0 : plannedToNetwork.size());
            }
            return new DispatchOutcome(
                    new Result(accepted, plannedToNetwork, machine.machineIdentity(), receipt), "");
        }
        return new DispatchOutcome(null, rejected.describe());
    }

    /**
     * {@code commit()} 回了 {@code false} 的那一格叫什么（<b>纯读数</b>，一格一 token）：
     * 按 public 的 {@link BatchHandle#hasTransferredInputOwnership()} 把这一型 false 拆成两档，
     * <b>两档不共用一个名字、也不互相顶替</b>：
     * <ul>
     *   <li>{@code commit-false-noTake}：拒收<b>且一分料没吃</b>（背压拒收就是这一型——那边的
     *       背压检查排在清空原型与扣能量之前）；</li>
     *   <li>{@code commit-false-tookInput}：报了 false，可<b>料已经被吃掉了</b>——这一型如果出现在
     *       实机上，就是"调用方按没吃料去回滚、料却回不来"的那一型，正是过去只有一句
     *       {@code commit-false} 时永远说不出来的那一格；</li>
     *   <li>{@code commit-false-ownershipUnknown}：连所有权这一问都抛异常（问不到）。
     *       这一档<b>不许</b>并进上面任何一档——把"没问到"记成"没吃料"就是拿猜顶读数。</li>
     * </ul>
     *
     * <p>这一处不改任何处置：调用方那一句 {@code continue}（回滚、回池、退避）与拆档之前逐字相同。
     */
    private static String commitRejectCell(BatchHandle batch) {
        boolean tookInput;
        try {
            tookInput = batch.hasTransferredInputOwnership();
        } catch (Throwable thrown) {
            return "commit-false-ownershipUnknown";
        }
        return tookInput ? "commit-false-tookInput" : "commit-false-noTake";
    }

    /** {@link DispatchOutcome#rejectDetail()} 的封顶格集：只攒读数，不参与任何判据。 */
    private static final class RejectCells {

        /** 一次 dispatch 留得下的出口格数（与 {@code obWhy} 那格同风格的封顶，不是判据）。 */
        private static final int REJECT_DETAIL_CELL_LIMIT = 4;

        private final StringBuilder described = new StringBuilder();
        private int kept;
        private long dropped;

        private void note(String exit, String machineIdentity) {
            if (kept >= REJECT_DETAIL_CELL_LIMIT) {
                dropped++;
                return;
            }
            if (described.length() > 0) {
                described.append('/');
            }
            described.append(exit).append('#').append(machineIdentity);
            kept++;
        }

        private String describe() {
            return dropped > 0L
                    ? described.append("/更多=").append(dropped).toString()
                    : described.toString();
        }
    }


    /** 把 CPU 的原型 holder 复制成一份可以交给机器的数组（{@code KeyCounter} 逐槽克隆）。 */
    static KeyCounter[] copyPrototype(KeyCounter[] prototype) {
        var copy = new KeyCounter[prototype.length];
        for (int index = 0; index < prototype.length; index++) {
            var slot = prototype[index];
            var cloned = new KeyCounter();
            if (slot != null) {
                for (var entry : slot) {
                    if (entry.getKey() != null && entry.getLongValue() > 0L) {
                        cloned.add(entry.getKey(), entry.getLongValue());
                    }
                }
            }
            copy[index] = cloned;
        }
        return copy;
    }
}
