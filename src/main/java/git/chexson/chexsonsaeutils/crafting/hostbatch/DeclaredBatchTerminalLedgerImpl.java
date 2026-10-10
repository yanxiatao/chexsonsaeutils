package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.core.AELog;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 经万象炉 bigint 通道申报的批次的<b>在飞 / 欠交付</b>台账：结案与回退的凭据<b>只有该笔的终局事件</b>。
 *
 * <p><b>为什么换凭据</b>：这条通道里机器报回的每一个数（{@code acceptedCount}、
 * {@code plannedOutputs}、{@code onBatchOutputs} 那份清单）都是 {@code admit} 那一刻按
 * {@code pattern.getOutputs() × 份数} 现算的<b>自报计划</b>，没有一枚是"产物进了存储"的回执
 * （{@code DeclaredBatchReceipt} 的类注释与 {@code notifyCpuBatchCompleted} 的字节码同一条口径）。
 * 所以"报满而货不到"那一型在这条通道里<b>读不出来</b>：能读出来的只有<b>这一笔到底收没收场</b>。
 * 本台账因此<b>不</b>看承诺量（{@code expectedToNetwork}/{@code planNet}/{@code ann}），
 * 也<b>不</b>看池子或网络的现量差值——那两把尺都是上一刀（{@code b99d7ee}，已回退）的死因。
 *
 * <p><b>#227 K2 的定位（别把这一句读成"差值那把尺不许用"）</b>：不看的仍是<b>本台账</b>——差值账
 * 从头到尾都在宿主作业那两本现成的宽域账上（{@code ParallelExecutingCraftingJob#outputsExpectedToNetwork}
 * 减 {@code #declaredNetworkDelivered}），收工第二臂读的也是那一处，与 {@code b99d7ee} 拿"池子/网络的
 * 现量差值"当尺的死因不是同一把尺。本台账只负责把终局事件落成<b>交割</b>（{@code SUCCESS}）或
 * <b>回退＋退账</b>（负向），自己既不减账也不建账。
 *
 * <p><b>本台账没有任何出表判据</b>（#209 收口的那一条，逐字写下来）：出表判据仍是既有那两格
 * ——发数跑完、材料欠账清了——全仓只有 {@code ParallelCraftingCpuLogic#taskMayLeaveTable} 一处，
 * 它<b>一个字都不读</b>本台账。本台账只做两件事：① 把<b>终局事件</b>落成<b>回退</b>（见下），
 * ② 交出 {@code inflightBatches=}／{@code oldestInflightTicks=}／{@code undelivered=} 三格纯读数。
 *
 * <p><b>为什么判据不许落在本台账的任何一格数上</b>：负向终局唯一能让一张样板"永远出不了表"的写法
 * 就是拿"欠交付的发数"拦表（{@code 992eae8} 的形状）。拦表要能解开，只有"这一批重新申报出去"，
 * 而重新申报要料——<b>料永远不来</b>那一档（现场缺料、配给已尽）就把一张样板钉在表上永不放行，
 * 而钉住它的那一格里发数早已归零，没有任何一条腿还能把它自己救出来。这一型是
 * 12:54 那一刀被回退的病（{@code 永不出表／作业永不收工}），也是本仓显式选定"不索要、不拦表、只点名"
 * 的理由：凭据只用来<b>决定这一笔要不要退回待办</b>，从来不用来决定<b>这张样板能不能收工</b>。
 *
 * <p><b>三种终局各结什么</b>（口径与既有"接受后才 commit 游标、拒绝／能耗缩批先回注且不前移"同一条）：
 * <ul>
 *   <li>{@link Outcome#SUCCESS} ⇒ 那批发数<b>结掉</b>：在飞记录撤下，不再被点名，什么都不回退；</li>
 *   <li>{@link Outcome#REJECTED} / {@link Outcome#CANCELLED} ⇒ 负向终局即<b>回退</b>：那一笔的发数
 *       沿 {@link Rollback} 交回的那一个<b>既有</b>入口退回该样板的<b>待办</b>（
 *       {@code ParallelExecutingCraftingJob.TaskProgress#addFirings}，宽域精确，本类不新建第二本账）；
 *       这一批的<b>材料欠账原样挂着不销</b>——欠账一清就同时撤掉它对池子的遮罩，
 *       "同一份存量许诺两次"逐字复发，而这一批重新申报那一刻由既有的
 *       {@code BatchObligationLedger#incur} 把新的欠账<b>重挂</b>回去。
 *       这里<b>不</b>整张归零、<b>不</b>顺手退料：机器已经把料吃掉了，我们只知道"这批没交出来"，
 *       不知道"该退哪一格料"。</li>
 * </ul>
 *
 * <p><b>永不终局那一型的判决口径</b>（这一条是被显式选定并写下来的，不是"看起来会自洽"）：
 * <b>不索要、不拦表，只点名</b>。挂了却等不到终局的批次留在 {@code inflight} 里，只进
 * {@link #inflightBatchCount()} / {@link #describeOldestInflight(long)} 两格读数。理由是被装 jar
 * 逐字证过的，不是猜的：{@code useless_mod-1.21.1-2.3.9.jar} 里
 * {@code AdvancedAlloyFurnaceAeManager} 只有两个终局出口——
 * {@code flushQueuedCraftingOutputs} 发 {@code SUCCESS}、{@code cancelAllTasks} 发
 * {@code CANCELLED}；而 {@code AlloyFurnaceBigIntegerBatchResult.REJECTED} 在全 jar 的字节码里
 * 除了枚举自身的声明外<b>没有任何引用点</b>（{@code grep -rl REJECTED *.class} 只命中枚举类）。
 * 也就是："这一笔永远没有终局"是这条通道的<b>正常形态</b>之一（料被存进 NBT、机器被拆、区块卸载
 * 都在这一档）。任何"等终局才放行出表"的判据都会在这一档把整张作业钉死——那正是上一刀
 * {@code 永不出表／作业永不收工} 的病，比原病更坏，不许复发。
 *
 * <p><b>回退落空的那一笔怎么办</b>（唯一还留着的形状，也只留读数）：条目已经按上面那一档出表之后
 * 终局才到，退回来的发数无处可落——本类<b>不</b>把条目塞回任务表（那是复活别人已经判完的活，
 * 比拦表更坏），只在 {@link #describeUndelivered()} 里把它的 {@code batchId} 与发数点出来，
 * {@code 已回退} 那一截比累计少的那一截就是它。
 *
 * <p><b>续传口径（落不落 NBT 只有一条，写死在这里）：在飞与欠交付都不落 NBT。</b>
 * 凭据是 {@code batchId}，而 {@code batchId} 是 useless 侧 {@code buildCpuContext} 里
 * {@code UUID.randomUUID()} 现生成的、随 {@code PendingCraftingOutput.cpuContext} 只存在于内存
 * （{@code cpuContext}/{@code cpuAdapterId} 是那个内部类的<b>构造参数</b>；存档重载走的是
 * {@code PendingCraftingOutput(long, accumulator)} 那一枚构造器，两个字段一律 {@code null}）。
 * 所以<b>重启后旧 batchId 的终局永远不会来</b>：落 NBT 只会造出一批"注定等不到凭据"的在飞记录。
 * 风险照这一条口径如实写清：① 跨重启窗口被机器取消的那一批<b>不会</b>被记成欠交付、那一批发数
 * <b>不会</b>被退回待办（我们不发明读数，也不发明回退）；② 重载后 {@code inflightBatches=} 从 0
 * 起算，重启前那几笔在飞从此无名。
 *
 * <p><b>#227 K2 之后这一句仍然成立，但靠的是新加的一步（不是巧合）</b>：改前这里写着
 * "这两条只影响读数与事后归因，<b>不</b>影响收工判据"。收工第二臂今天读的是 {@code planNet − 已交割}
 * （{@code ParallelExecutingCraftingJob#declaredNetworkUndeliveredTotal}），而分子那本账
 * <b>照旧落 NBT</b>、这本台账<b>不落</b> ⇒ 若原样读回来，续传的作业会被一笔永远等不到凭据的预期
 * 永久钉在"未收工"上（正是 {@code #209} 那一型）。所以装载时把它整本退回同一个退账出口
 * （{@code ParallelExecutingCraftingJob#dropRestoredExpectedToNetwork}）：这一句"不影响收工判据"
 * 由此从"碰巧成立"变成"被代码保证"。本场内划了账却没等到终局那一档照旧拦收工，凭据回来才解。
 *
 * <p>本类<b>不 import 任何 useless 类型</b>（{@code UUID/BigInteger/IPatternDetails} 之外没有别的）：
 * {@code useless_mod} 在 build 里是 {@code compileOnly}（{@code build.gradle} 的
 * {@code reference-sources/useless_mod-1.21.1-2.3.8.jar}），测试类路径上根本没有它，
 * 加载不了 {@code UselessBigIntegerApiBridge}。回调体在这一侧收口的东西因此必须能在离线用例里被
 * 直接打到：{@code UselessBigIntegerApiBridge#onBatchFinished} 的<b>全部</b>实质动作就是
 * {@code receipt.deliverTerminal(ctx.batchId(), 映射后的 Outcome)} 这一句，而那一句落进
 * {@link #onTerminal(UUID, Outcome)}。终局→ Outcome 的映射表在桥侧，三档常量穷举，
 * useless 加了新结局就编译不过。
 *
 * <p><b>覆盖面自证读数（台账 #225 读数 1–3，纯读数，一个字都不进判据）</b>：实机 2026-10-02 19:55
 * 那一跑同时读出三条互斥的数——宿主行 {@code push=81}、批量行 {@code declared=113}、
 * 全场只有 3 条 {@code 批次已终局(SUCCESS)}，而 {@code inflightBatches=0 undelivered=NONE
 * pendingDelivery=NONE}。那两把数出自<b>两个不同的 100 轮窗</b>，谁也证不了谁；而
 * {@link #noteAdmitted} 的两支静默 {@code return}（{@code receipt==null} 与 {@code batchId==null}）
 * 与 集成回执的终局接收口 的静默收手<b>一笔都不留</b>，于是"申报了 81 笔、
 * 在飞 0 笔"这一档今天既没有计数器也没有行可打。本类因此加一组<b>只数不判</b>的累计读数
 * （{@link #noteDeclaredHostShape} 与 {@link #coverageCounters()}）：分档笔数、两支静默口的笔数与
 * 枚数合计、终局三档与"账上没有这笔"的那一档，全部走同一本台账、同一行交出，
 * 让"81 笔申报 / 3 笔终局 / 在飞 0"当场对平或对不平。
 * {@code taskMayLeaveTable}／{@code isDyeableRecursiveJobComplete}／
 * {@code declaredNetworkDeliveriesSettled}／{@link #hasUndeliveredDeclaredOutput()} 这四条臂
 * <b>一个都不读</b>这些格——它们只被打印。
 */
public final class DeclaredBatchTerminalLedgerImpl implements DeclaredBatchTerminalLedger {

    /** 一笔在飞记录：发数与样板身份在申报那一刻就定死，终局只负责判"结掉（交割）还是退回待办"。 */
    private record Inflight(IPatternDetails pattern, BigInteger firings, long admittedTick,
            @Nullable Rollback rollback, @Nullable Map<AEKey, BigInteger> pendingDelivery,
            boolean clamped, @Nullable Delivery delivery) {
    }

    /**
     * 一张样板被负向终局判掉的合计（<b>纯读数</b>，不参与任何判据）：累计发数、其中真退回待办的发数、
     * 点名用的那几个 batchId。
     */
    private static final class Undelivered {
        /** 累计欠交付发数：被负向终局判掉的发数合计（只增——它是历史，不是待办）。 */
        private volatile BigInteger firings = BigInteger.ZERO;
        /** 其中真退回待办的那一截（回退落空的那些不在这里）。 */
        private volatile BigInteger rolledBack = BigInteger.ZERO;
        /** 点名用的 batchId 集：并发安全集合，读侧（{@code describeUndelivered}）不持锁。 */
        private final Set<UUID> batchIds = ConcurrentHashMap.<UUID>newKeySet();
    }

    private final Map<UUID, Inflight> inflight = new ConcurrentHashMap<>();
    private final Map<IPatternDetails, Undelivered> undelivered = new ConcurrentHashMap<>();

    // ==================== 覆盖面自证读数（台账 #225 读数 1–3）：只数、只印，不参与任何判据 ====================

    /** 申报宿主里"能挂在飞台账那一型"（{@code DeclaredBatchTerminalCarrier} 在场）的累计笔数。 */
    private final AtomicLong declaredBigintHosts = new AtomicLong();
    /** 别的申报宿主（交不回 cpuToken 那几型）的累计笔数：它们<b>天生</b>挂不上台账，不是被夹掉的。 */
    private final AtomicLong declaredOtherHosts = new AtomicLong();
    /** 真挂进 {@link #inflight} 的累计笔数（与上面两档并排，才对得平）。 */
    private final AtomicLong inflightRegistered = new AtomicLong();
    /** 静默口一：{@code receipt == null} 直接 return 的累计笔数。 */
    private final AtomicLong silentNoReceipt = new AtomicLong();
    /** 静默口二：{@code receipt != null} 但 {@code batchId == null} 直接 return 的累计笔数。 */
    private final AtomicLong silentNoBatchId = new AtomicLong();
    /** 静默口一涉及的<b>枚数合计</b>（这一批已被 {@code reserveExpectedWaiting} 摘走的待交割枚数，宽域真值）。 */
    private BigInteger silentNoReceiptUnits = BigInteger.ZERO;
    /** 静默口二的枚数合计（同上口径）。 */
    private BigInteger silentNoBatchIdUnits = BigInteger.ZERO;
    /** 静默口一最新一枚样板（点名用；身份标由调用方那把现成的尺交，台账不自造第二把 id）。 */
    private @Nullable IPatternDetails lastNoReceiptPattern;
    /** 静默口二最新一枚样板。 */
    private @Nullable IPatternDetails lastNoBatchIdPattern;
    /** 终局三档各自累计笔数。 */
    private final AtomicLong terminalSuccess = new AtomicLong();
    private final AtomicLong terminalCancelled = new AtomicLong();
    private final AtomicLong terminalOther = new AtomicLong();
    /** 终局来了、{@link #inflight} 里却<b>没有这一笔</b>的累计笔数（改前这一支一个计数都不留）。 */
    private final AtomicLong terminalUnmatched = new AtomicLong();

    /** 取覆盖面快照（纯读数；笔数取自 {@link AtomicLong}，枚数与样板身份在 {@code synchronized} 里取）。  */
    @Override
    public CoverageCounters coverageCounters() {
        synchronized (this) {
            return new CoverageCounters(
                    declaredBigintHosts.get(), declaredOtherHosts.get(), inflightRegistered.get(),
                    silentNoReceipt.get(), silentNoBatchId.get(),
                    silentNoReceiptUnits, silentNoBatchIdUnits,
                    lastNoReceiptPattern, lastNoBatchIdPattern,
                    terminalSuccess.get(), terminalCancelled.get(), terminalOther.get(),
                    terminalUnmatched.get());
        }
    }

    /**
     * 申报批按<b>宿主型别</b>分档累计（纯读数）：{@code carrier == true} 那一型把该笔的
     * {@code cpuToken} 交回来了、可能挂在飞台账；{@code false} 那一型天生挂不上。
     *
     * <p>由 CPU 侧的申报腿在 {@code host instanceof DeclaredBatchTerminalCarrier} 那一句<b>旁边</b>调一次，
     * 与 {@link #noteAdmitted} 的入口配对；这里<b>不</b>分支、<b>不</b>拦任何东西，只数一笔。
     */
    @Override
    public void noteDeclaredHostShape(boolean terminalCarrier) {
        if (terminalCarrier) {
            declaredBigintHosts.incrementAndGet();
        } else {
            declaredOtherHosts.incrementAndGet();
        }
    }

    /**
     * 两支静默口各记一笔（纯读数）＋ 一行常规可读 WARN（{@code > 0} 才走到这一句，故 WARN 与"发生了
     * 几笔"同一条口）。
     *
     * <p>覆盖面不靠这一条 WARN 自证：{@code inflightRegistered[累计]=}/{@code noReceipt[累计]=}/
     * {@code noBatchId[累计]=} 三格挂在<b>每 100 轮必打</b>的 {@code [chexsonsaeutils-batch] per100r}
     * 行里（{@code ParallelCraftingCpuLogic#describeDeclaredCoverageReadouts}），恒 0 也照印 ⇒
     * "没打这一行"与"打了全 0"分得开。这一条 WARN 只保证<b>每一笔当场留名</b>。
     *
     * <p>AELog 的格式串走 {@code String.format}，整行先拼好再走 {@code warn("%s", 行)}——
     * 样板头那类第三方串里万一有一个 {@code %}，直进格式串会把这一行炸掉。
     */
    private void noteSilentUnregistered(boolean noReceipt, @Nullable IPatternDetails pattern,
            @Nullable Map<AEKey, BigInteger> pendingDelivery) {
        BigInteger units = BigInteger.ZERO;
        int pendingKeys = 0;
        if (pendingDelivery != null) {
            for (var entry : pendingDelivery.entrySet()) {
                if (entry.getValue() != null && entry.getValue().signum() > 0L) {
                    units = units.add(entry.getValue());
                    pendingKeys++;
                }
            }
        }
        long count;
        BigInteger totalUnits;
        String identity = "<null>";
        synchronized (this) {
            if (noReceipt) {
                count = silentNoReceipt.incrementAndGet();
                silentNoReceiptUnits = silentNoReceiptUnits.add(units);
                totalUnits = silentNoReceiptUnits;
                lastNoReceiptPattern = pattern;
            } else {
                count = silentNoBatchId.incrementAndGet();
                silentNoBatchIdUnits = silentNoBatchIdUnits.add(units);
                totalUnits = silentNoBatchIdUnits;
                lastNoBatchIdPattern = pattern;
            }
            if (pattern != null) {
                identity = String.valueOf(pattern.getDefinition()).replace(' ', '_');
            }
        }
        AELog.warn("%s", "[chexsonsaeutils-declared] 申报批静默未挂账 臂="
                + (noReceipt ? "noReceipt" : "noBatchId")
                + " 累计笔数=" + count
                + " 本笔枚数=" + units
                + " 枚数合计=" + totalUnits
                + " 待交割键数=" + pendingKeys
                + " 样板=" + identity);
    }


    /**
     * 挂上一笔在飞记录：由 {@code attemptDeclaredBatch} 在宿主收下这批（{@code accepted > 0}）之后调，
     * 并把本台账绑回该笔的 {@code cpuToken}（{@link DeclaredBatchReceipt}），终局从那条口回来；
     * 同时把负向终局要用的<b>回退出口</b>一起挂上——没有出口就没有回退，只剩点名。
     *
     * <p>{@code receipt} 为 {@code null} 或没带 {@code batchId}（宿主没有终局事件来源）⇒ <b>一笔都不挂</b>：
     * capability 退回按"回调/接口是否实现"判，不猜、不超时、不设闸门。
     */
    /**
     * 挂一笔<b>没有"宿主直投网络"产出</b>的在飞记录（既有各条申报宿主走这一枚：产物全走实物回池，
     * 没有待交割那一截，{@code planNet} 那本账一格都不记 ⇒ 收工判据的第二臂自然不被这一笔挡住）。
     * 委托到带交割账的完整入口，待交割交空表、交割出口交 {@code null}——语义与接入交割凭据之前
     * <b>逐字一致</b>。
     */
    @Override
    public void noteAdmitted(@Nullable DeclaredBatchReceipt receipt, IPatternDetails pattern,
            BigInteger firings, long admittedTick, @Nullable Rollback rollback) {
        noteAdmitted(receipt, pattern, firings, admittedTick, rollback,
                Map.of(), false, null);
    }

    @Override
    public void noteAdmitted(@Nullable DeclaredBatchReceipt receipt, IPatternDetails pattern,
            BigInteger firings, long admittedTick, @Nullable Rollback rollback,
            @Nullable Map<AEKey, BigInteger> pendingDelivery, boolean clamped,
            @Nullable Delivery delivery) {
        // 下面三支 return 与改前那一句 `receipt == null || pattern == null || firings == null
        // || firings.signum() <= 0L` 加 `batchId == null` <b>逐字同效</b>（同样一笔都不挂、同样什么都不改），
        // 拆开的理由只有一个：给两支静默口各自一个计数（台账 #225 读数 1）。判据一条都没新增。
        if (receipt == null) {
            noteSilentUnregistered(true, pattern, pendingDelivery);
            return;
        }
        if (pattern == null || firings == null || firings.signum() <= 0L) {
            return;
        }
        UUID batchId = receipt.batchId();
        if (batchId == null) {
            noteSilentUnregistered(false, pattern, pendingDelivery);
            return;
        }
        Map<AEKey, BigInteger> pending = pendingDelivery == null || pendingDelivery.isEmpty()
                ? Map.of() : new ConcurrentHashMap<>(pendingDelivery);
        inflight.put(batchId, new Inflight(pattern, firings, admittedTick, rollback,
                pending, clamped, delivery));
        inflightRegistered.incrementAndGet();
        receipt.bindTerminal(this::onTerminal, batchId);
    }

    /**
     * 这一台宿主里还有<b>没有交割的申报产出</b>（挂了待交割、终局却没到）。
     *
     * <p><b>#227 K2 起它是纯读数</b>：收工第二臂改读 {@code planNet − 已交割} 那道差
     * （{@code ParallelCraftingCpuLogic#declaredNetworkDeliveriesSettled}），不再问这一格。
     * 改前它写的是"{@code isDyeableRecursiveJobComplete} 第二臂读的就是这一格"——那句在改前是真的，
     * 而那一把尺<b>只扫 {@code inflight} 表的尺寸</b>：账上记满了直投预期、表里却没有那一笔
     * （登记没成／条目已出表后终局才到／回执侧静默收手），它恒真 ⇒ "报满零实物"照样被判成收工，
     * 这正是换尺子的理由。本方法的实现一字未动，仍被 {@code pendingDelivery=} 那一格读数与用例读着。
     *
     * <p>与 {@code #213} 边界不冲突：这一格（连同换成差值之后的第二臂）只拦<b>收工</b>（作业级），
     * 一个字都不进 {@code taskMayLeaveTable}（样板级出表）——永不终局那一型照样出表、只被点名，
     * 只是整张作业停在"未收工"等交割。
     */
    @Override
    public boolean hasUndeliveredDeclaredOutput() {
        for (Inflight entry : inflight.values()) {
            if (entry.pendingDelivery() != null && !entry.pendingDelivery().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 交割账的<b>待交割读数</b>（纯读数，一格一 token，不参与判据）：还挂着没等到终局的申报批一共欠
     * 网络几枚产物，其中有没有哪一笔的加数被 {@code Long.MAX_VALUE} 饱和哨兵夹过。
     *
     * <p>形状：{@code <总欠量>枚} 或 {@code <总欠量>枚(clamped)}——{@code (clamped)} 那一截就是
     * "这一格里有饱和哨兵冒充量"的标记：去掉它 {@code >long} 的加数会被读成一个真数（本仓铁律禁止）。
     * 恒 {@code NONE} 表示没有申报批直投网络的产出挂着（接 bigint 通道之前逐字同效）。
     */
    @Override
    public String describePendingDelivery() {
        BigInteger total = BigInteger.ZERO;
        boolean clamped = false;
        boolean any = false;
        for (Inflight entry : inflight.values()) {
            Map<AEKey, BigInteger> pending = entry.pendingDelivery();
            if (pending == null || pending.isEmpty()) {
                continue;
            }
            any = true;
            for (BigInteger amount : pending.values()) {
                if (amount != null) {
                    total = total.add(amount);
                }
            }
            if (entry.clamped()) {
                clamped = true;
            }
        }
        if (!any) {
            return "NONE";
        }
        return total + "枚" + (clamped ? "(clamped)" : "");
    }

    /**
     * 该笔的终局到了：这一处是<b>唯一</b>能让发数"结掉"或"退回待办"的入口，除它以外没有任何写入方。
     *
     * <p>不在 {@code inflight} 里的那一笔（重启前挂的、或不是本作业申报的）在此<b>无声结束</b>：
     * 没有凭据可挂，就不许改任何账。
     */
    @Override
    public void onTerminal(UUID batchId, Outcome outcome) {
        if (batchId == null || outcome == null) {
            return;
        }
        Inflight settled = inflight.remove(batchId);
        if (settled == null) {
            // 终局来了、账上却没有这一笔：改前这一支一个计数都不留（台账 #225 读数 3）。
            // 只数一笔、只印一行，别的什么都不改——没有凭据可挂，就不许动任何账（口径照旧）。
            terminalUnmatched.incrementAndGet();
            // 整行先拼好再走 warn("%s", 行)：AELog 的格式串是 String.format，第三方串里的 % 会把这一行炸掉。
            AELog.warn("%s", "[chexsonsaeutils-declared] 终局来了但账上没有这笔 batch="
                    + shortBatchId(batchId) + " 结局=" + outcome
                    + " 累计笔数=" + terminalUnmatched.get()
                    + "：在飞表里没有这一笔（重启前的 batchId、或不是本作业申报的那一笔）");
            return;
        }
        if (outcome == Outcome.SUCCESS) {
            terminalSuccess.incrementAndGet();
        } else if (outcome == Outcome.CANCELLED) {
            terminalCancelled.incrementAndGet();
        } else {
            terminalOther.incrementAndGet();
        }
        if (outcome == Outcome.SUCCESS) {
            // 结掉＝交割：这一批不再在飞，也不再欠交付。凭据来源是 SUCCESS 发射条件本身带数量含义
            // （见 {@link Delivery}），所以这里把当初划成待交割的那几枚转成<b>已交割</b>，记进宿主
            // 作业既有那本交割账——台账自己不建账。发数账与材料欠账一概不动（SUCCESS 不回退）。
            Map<AEKey, BigInteger> pending = settled.pendingDelivery();
            Delivery delivery = settled.delivery();
            if (delivery != null && pending != null && !pending.isEmpty()) {
                delivery.deliverBatch(pending, settled.clamped());
            }
            return;
        }
        // 负向终局＝回退：发数退回待办由宿主作业上既有那一个入口落，本类不在这里动任何账本，
        // 更不拿这一格去拦出表（拦表的回路即 #209 的病，理由逐字写在类注释）。
        Rollback rollback = settled.rollback();
        boolean rolledBack = rollback != null
                && rollback.rollBackFirings(settled.pattern(), settled.firings());
        synchronized (this) {
            Undelivered owed = undelivered.computeIfAbsent(settled.pattern(),
                    ignored -> new Undelivered());
            owed.firings = owed.firings.add(settled.firings());
            if (rolledBack) {
                owed.rolledBack = owed.rolledBack.add(settled.firings());
            }
            owed.batchIds.add(batchId);
        }
    }

    /** 在飞笔数（纯读数）：挂了还没等到任何终局的申报批有几笔。 */
    @Override
    public int inflightBatchCount() {
        return inflight.size();
    }

    /**
     * 最老一笔在飞的 tick 龄 + 它的 batchId 前缀（纯读数，一格一 token：
     * {@code 4213@1f2e3d4c}）。没有任何终局回执的那一笔就从这一格露出来。
     */
    @Override
    public String describeOldestInflight(long currentTick) {
        Inflight oldest = null;
        UUID oldestId = null;
        for (Map.Entry<UUID, Inflight> entry : inflight.entrySet()) {
            if (oldest == null || entry.getValue().admittedTick() < oldest.admittedTick()) {
                oldest = entry.getValue();
                oldestId = entry.getKey();
            }
        }
        if (oldest == null) {
            return "0@none";
        }
        long age = currentTick >= oldest.admittedTick()
                ? currentTick - oldest.admittedTick() : 0L;
        return age + "@" + shortBatchId(oldestId);
    }

    /**
     * 欠交付点名（纯读数，一格一 token）：
     * {@code 样板#累计欠交付N枚/已回退M枚@batch[短id,短id]}，多张样板之间用 {@code ,}。
     *
     * <p>{@code M} 比 {@code N} 少的那一截就是"条目已出表、退回来无处可落"的那一笔——它只在这里
     * 露出来，不进任何判据（口径见类注释"回退落空的那一笔怎么办"）。
     *
     * <p>话术上它与停滞行的 {@code owed=}（<b>欠料</b>：申报出去还没从池子交割的材料）不是一回事：
     * 这一格说的是<b>欠交付</b>——机器已经吃了料、终局却把它判成没交出来。
     */
    @Override
    public String describeUndelivered() {
        if (undelivered.isEmpty()) {
            return "NONE";
        }
        var described = new StringBuilder();
        for (Map.Entry<IPatternDetails, Undelivered> entry : undelivered.entrySet()) {
            if (described.length() > 0) {
                described.append(',');
            }
            var ids = new StringBuilder();
            for (UUID batchId : entry.getValue().batchIds) {
                if (ids.length() > 0) {
                    ids.append('/');
                }
                ids.append(shortBatchId(batchId));
            }
            var owed = entry.getValue();
            described.append(String.valueOf(entry.getKey().getDefinition()).replace(' ', '_'))
                    .append('#').append(owed.firings).append("枚/已回退").append(owed.rolledBack)
                    .append("枚@batch[").append(ids).append(']');
        }
        return described.toString();
    }

    private static String shortBatchId(@Nullable UUID batchId) {
        return batchId == null ? "none" : batchId.toString().substring(0, 8);
    }
}
