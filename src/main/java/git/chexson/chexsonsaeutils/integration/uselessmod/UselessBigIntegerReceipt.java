package git.chexson.chexsonsaeutils.integration.uselessmod;

import appeng.api.stacks.AEKey;
import appeng.api.networking.IGrid;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputClaim;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchOutputReceiver;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalCallback;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 bigint 推送的一次性<b>计划</b>回执槽：由 {@code UselessBigIntegerBatchClient} 造出来、当作
 * {@code AlloyFurnaceBigIntegerCpuBinding} 的 {@code cpuToken} 交给机器，机器在 {@code commit}
 * 成功的同一层栈帧里把「本批<b>计划</b>产出」原样交回来（见 useless 侧
 * {@code AdvancedAlloyFurnaceAeManager#commitCraftingBatch} 的 {@code notifyCpuBatchAdmitted}）。
 *
 * <p><b>这一格里没有一枚是"已经进网络"的证据</b>：{@code context.plannedOutputs()} 是
 * {@code AlloyFurnaceBigIntegerCrafting#plannedOutputs} 按 {@code pattern.getOutputs() × 份数}
 * 现算的声明量；机器后来发给 {@code onBatchOutputs} 的那份"实际产出"也是入队时算好的同一个数
 * （{@code AdvancedAlloyFurnaceAeManager#notifyCpuBatchCompleted} 直接取
 * {@code pending.bigIntegerOutputs}，注释自己写着"不去累加 long 分段"）。产物到底进没进 ME 存储、
 * 被 ECO 动态输出池认领走多少，这条通道一个字都不报。所以本槽只记<b>预期</b>。
 *
 * <p>为什么要在推送这一刻就要到：CPU 的申报产出一律经
 * {@code reserveExpectedWaiting(job, outputs, containers, plannedToNetwork)} 的第四参记「预期入网」，
 * 那笔账必须和推送同一 tick 结清。等不到这一格就只能整批挂 {@code waitingFor} 等实物，而那批实物
 * 永远不会回 CPU——正是要堵的「永不结算的空挂账」。<b>它堵的是空挂账，不是交付对平</b>：
 * 拿它当"已交付"参与对平，就是把预期当账，少交付从此看不见。
 *
 * <p>本类刻意只认 AE2 的 {@link AEKey} 与 {@link BigInteger}：不 import 任何 useless 类型，
 * 所以没装 useless_mod 时它也照样能加载，{@link #selfReportDelta} 那把尺也照样能在离线用例里问。
 *
 * <p>2.3.9 的 {@code claimOutputs} 是另一个真实交付入口：本槽把它转给 CPU 的宽域接收器，
 * 返回实际转移量供机器扣队列。该量独立于上述计划清单，不从 {@code onBatchOutputs} 补记库存。
 */
public final class UselessBigIntegerReceipt implements DeclaredBatchOutputClaim {

    /** 仅在真实交付口使用；终局后释放对 CPU 与网络的引用。 */
    @Nullable
    private DeclaredBatchOutputReceiver outputReceiver;

    @Override
    public void bindOutputReceiver(DeclaredBatchOutputReceiver receiver) {
        this.outputReceiver = java.util.Objects.requireNonNull(receiver);
    }

    /** 认领机器队列中的实物；与计划清单、自报清单完全独立。 */
    Map<AEKey, BigInteger> claimOutputs(java.util.UUID sourceBatchId, IGrid sourceGrid,
            Map<AEKey, BigInteger> available) {
        var receiver = outputReceiver;
        if (receiver == null || sourceGrid == null || !sourceBatchId.equals(batchId)) {
            return Map.of();
        }
        var claimed = new LinkedHashMap<AEKey, BigInteger>();
        available.forEach((what, amount) -> {
            if (amount.signum() > 0) {
                var accepted = receiver.receive(sourceGrid, what, amount);
                if (accepted.signum() < 0 || accepted.compareTo(amount) > 0) {
                    throw new IllegalStateException("CPU 实物认领量超出机器可交付数量");
                }
                if (accepted.signum() > 0) {
                    claimed.put(what, accepted);
                }
            }
        });
        return claimed;
    }

    private final String machineIdentity;
    private final Map<AEKey, BigInteger> plannedOutputs = new LinkedHashMap<>();
    /**
     * 这一笔批次的 {@code batchId}：由 {@code onBatchAdmitted} 在推送同一层栈帧里记进来
     * （useless 侧 {@code buildCpuContext} 现生成的 {@code UUID.randomUUID()}，从 admit 到终局
     * 一直是同一个对象）。它是 {@link DeclaredBatchTerminalLedger} 认回"自己那一笔申报"的<b>唯一</b>标识。
     */
    @Nullable
    private volatile java.util.UUID batchId;
    /** CPU 侧挂上来的终局台账（{@code null}＝这一笔没人认领：非申报腿推送、或重启后 token 已丢）。 */
    @Nullable
    private volatile DeclaredBatchTerminalCallback terminalCallback;
    /**
     * 全场 JVM 口径的两支静默收手累计笔数（台账 #225 读数 3，纯读数、不参与任何判据）：
     * 见 {@link #deliverTerminal(java.util.UUID, DeclaredBatchTerminalLedger.Outcome)} 的注释。
     */
    private static final java.util.concurrent.atomic.AtomicLong TERMINAL_DROPPED_NO_LEDGER =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TERMINAL_DROPPED_ID_MISMATCH =
            new java.util.concurrent.atomic.AtomicLong();

    public UselessBigIntegerReceipt(String machineIdentity) {
        this.machineIdentity = machineIdentity == null ? "" : machineIdentity;
    }

    /**
     * 机器在 {@code admit} 那一刻报回的 {@code batchId}（只记第一次，之后不许被别的批次改写）。
     *
     * <p>生产侧唯一的调用点是 {@code UselessBigIntegerApiBridge#onBatchAdmitted}（与推送同栈帧）；
     * {@code public} 只为让离线用例能用假 ctx 打同一条口，不改变任何语义。
     */
    public void noteBatchId(@Nullable java.util.UUID batchId) {
        if (batchId != null && this.batchId == null) {
            this.batchId = batchId;
        }
    }

    /** 本批的 {@code batchId}；机器没报回来时 {@code null}（调用方据此<b>不索要</b>任何终局）。 */
    @Override
    public @Nullable java.util.UUID batchId() {
        return batchId;
    }

    /** 把这枚 token 与 CPU 侧的在飞台账绑起来：终局事件就沿这一条口回来。 */
    @Override
    public void bindTerminal(DeclaredBatchTerminalCallback callback, java.util.UUID attachedBatchId) {
        if (callback != null && attachedBatchId != null && attachedBatchId.equals(this.batchId)) {
            this.terminalCallback = callback;
        }
    }

    /**
     * 该笔的终局到了：{@code UselessBigIntegerApiBridge#onBatchFinished} 的回调体<b>全部</b>实质动作
     * 就是打这一句（除它以外桥那边只剩一行 {@code CANCELLED} 的既有 WARN）。
     *
     * <p>只认与本机 {@link #batchId()} 同一笔的终局：对不上号（重启后 token 易主、或这压根不是
     * 申报腿的那一笔）就一个字都不改。没挂台账同样直接收手——<b>不索要、不猜、不兜底</b>。
     *
     * <p>这两支静默收手各留<b>一个累计计数</b>（台账 #225 读数 3 的"终局来了但账上没有这笔"）：
     * 本台账是逐 lane/逐作业建的，而 {@code terminalLedger == null} 那一支压根到不了任何一本台账，
     * 所以这两格只能是<b>全场 JVM 口径</b>（静态累计，打印处必须带 {@code [全场JVM]} 自报覆盖面，
     * 不许被读成"这一条 lane 的笔数"）。仍然<b>只数不判</b>：没有任何判据读它们。
     */
    public void deliverTerminal(@Nullable java.util.UUID terminalBatchId,
            @Nullable DeclaredBatchTerminalLedger.Outcome outcome) {
        if (outcome != null && terminalBatchId != null && terminalBatchId.equals(batchId)) {
            outputReceiver = null;
        }
        var callback = terminalCallback;
        if (callback == null) {
            TERMINAL_DROPPED_NO_LEDGER.incrementAndGet();
            return;
        }
        if (outcome == null) {
            return;
        }
        if (terminalBatchId == null || !terminalBatchId.equals(batchId)) {
            TERMINAL_DROPPED_ID_MISMATCH.incrementAndGet();
            return;
        }
        callback.onTerminal(terminalBatchId, outcome);
    }

    /**
     * 全场（JVM 静态口径）累计：终局回来了、这枚 token 上<b>没有挂任何台账</b>就静默收手的笔数。
     *
     * <p>{@code DeclaredBatchTerminalLedger#noteAdmitted} 那两支静默 {@code return}（没 receipt／没
     * {@code batchId}）走的正是这一条口——那一笔没挂上台账，后来的终局就在这里无声消失。
     */
    public static long terminalDroppedNoLedger() {
        return TERMINAL_DROPPED_NO_LEDGER.get();
    }

    /** 全场累计：终局回来了、{@code batchId} 与本机这一笔<b>对不上号</b>就静默收手的笔数。 */
    public static long terminalDroppedIdMismatch() {
        return TERMINAL_DROPPED_ID_MISMATCH.get();
    }

    /** 机器回报的一条计划产出（整批合计，份数可以超 {@code long}）。 */
    void addPlannedOutput(AEKey what, BigInteger amount) {
        if (what == null || amount == null || amount.signum() <= 0) {
            return;
        }
        plannedOutputs.merge(what, amount, BigInteger::add);
    }

    String machineIdentity() {
        return machineIdentity;
    }

    /** @return 计划产出的整批合计（所有键相加）；没收到回执时为零 */
    BigInteger plannedTotal() {
        var total = BigInteger.ZERO;
        for (var amount : plannedOutputs.values()) {
            total = total.add(amount);
        }
        return total;
    }

    /** 机器自报的整批计划产出；保留宽域真值，不以分段交付上限截断整批。 */
    @Nullable git.chexson.chexsonsaeutils.bigint.WideKeyCounter plannedAsWideCounter() {
        if (plannedOutputs.isEmpty()) {
            return null;
        }
        var result = new git.chexson.chexsonsaeutils.bigint.WideKeyCounter();
        plannedOutputs.forEach(result::add);
        return result;
    }

    /** 精确回执不经过 long 投影，因此没有截断。 */
    @Override
    public boolean plannedOutputsClamped() {
        return false;
    }

    /**
     * 自报清单相对<b>样板声明</b>的逐键差：{@code selfReported - planned}，正负都留、只剔掉为零的键。
     *
     * <p>这把尺与改前 {@code UselessBigIntegerApiBridge#subtract} 逐字同式（负差本来就在表里，
     * 登记里"只留正差"那条不成立），搬进本类的理由有两个：① 它是唯一能对出"键形差/自报量差"的口，
     * 得能在没装 useless 的离线用例里被问；② 它旁边的话术要说对——见 {@link #describeSelfReportDelta}。
     *
     * <p><b>对平判据不许超出这两侧能看到的范围</b>：两侧都是入队前按配方算出的自报量，产物到底进没进
     * ME 存储、被 ECO 动态输出池认领走多少，这里对不出来；而机器那份清单为空时
     * {@code onBatchOutputs} 根本不会被调用（useless 侧 {@code notifyCpuBatchCompleted} 的
     * {@code !outputs.isEmpty()} 前置），连这一格都不会印。
     *
     * @param planned      样板声明的产出（即记进 CPU 预期账的那一截）
     * @param selfReported 机器后来报回的入队清单
     */
    static Map<AEKey, BigInteger> selfReportDelta(Map<AEKey, BigInteger> planned,
            Map<AEKey, BigInteger> selfReported) {
        var delta = new LinkedHashMap<AEKey, BigInteger>();
        if (planned != null) {
            for (var entry : planned.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    delta.put(entry.getKey(), entry.getValue().negate());
                }
            }
        }
        if (selfReported != null) {
            for (var entry : selfReported.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                delta.merge(entry.getKey(), entry.getValue(), BigInteger::add);
            }
        }
        delta.values().removeIf(amount -> amount.signum() == 0);
        return delta;
    }

    /**
     * {@link #selfReportDelta} 的读数说法：把正负两截分开点名，欠多少枚直说。
     *
     * <p>纯读数，不参与任何判据、不补记产出。
     */
    static String describeSelfReportDelta(Map<AEKey, BigInteger> delta) {
        if (delta == null || delta.isEmpty()) {
            return "自报清单与样板声明逐键一致(注：两侧都是自报量，本行不证明任何一枚进过 ME 存储)";
        }
        var described = new StringBuilder();
        for (var entry : delta.entrySet()) {
            if (described.length() > 0) {
                described.append(' ');
            }
            described.append(entry.getValue().signum() < 0L ? "欠" : "多")
                    .append(entry.getValue().abs())
                    .append('枚').append(entry.getKey());
        }
        return described.append("(注：两侧都是自报量，欠的那一截不许再从预期账里扣)").toString();
    }

    @Override
    public String toString() {
        return "UselessBigIntegerReceipt{" + machineIdentity + ", planned=" + plannedOutputs.size() + "}";
    }
}
