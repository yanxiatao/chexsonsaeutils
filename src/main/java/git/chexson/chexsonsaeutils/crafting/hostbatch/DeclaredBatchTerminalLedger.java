package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** 声明批次终局台账契约；记录在飞、交割及负向回退，读数不参与任务出表判据。 */
public interface DeclaredBatchTerminalLedger {
    /** 该笔申报批的通用终局；集成侧将宿主枚举穷举映射到本契约。 */
    public enum Outcome {
        /** 宿主已交割这一批，允许将同次登记的待交割量记为已交割。 */
        SUCCESS,
        /** 宿主拒绝这一批，撤回直投预期并尝试恢复仍在表内的发数。 */
        REJECTED,
        /** 宿主取消这一批，撤回直投预期并尝试恢复仍在表内的发数。 */
        CANCELLED
    }

    /**
     * 负向终局要落的那一<b>回退</b>出口：由挂账那一侧（CPU 的申报腿）在 {@link #noteAdmitted} 时带进来。
     *
     * <p>本类因此一个字都不碰发数账与材料欠账——回退落到<b>宿主作业上既有的那一个入口</b>
     * （{@code TaskProgress#addFirings}），欠账留不留也由那张既有账自己的口径决定。台账里不建第二本账，
     * 也就不可能出现"两本账对不上"那一型。
     *
     * <p><b>#227 K2 带进来的那一半职责（同一次调用、同一个出口）</b>：实现方还要把<b>这一笔当初划进
     * {@code planNet} 的直投预期</b>一起退掉。台账的负向分支把该笔 {@code pendingDelivery} 整笔丢弃、
     * 不转已交割，而收工第二臂读的是 {@code planNet − 已交割}——不退那一段，一笔 {@code CANCELLED}
     * 就让差值永远非零、作业永久停在"未收工"（{@code #209} 那一型）。退账落在作业既有那本
     * {@code outputsExpectedToNetwork} 上（不建第二本、不加第二把尺），也<b>不</b>记成已交割。
     */
    public interface Rollback {

        /**
         * 把这一笔的发数退回该样板的待办，并把这一笔当初划进 {@code planNet} 的直投预期一起退账
         * （见上面接口注释里 {@code #227 K2} 那一条）。
         *
         * @param pattern 这一笔申报的样板
         * @param firings 这一笔已被实收、却没交出货的发数（恒 &gt; 0）
         * @return 退回落到还挂在任务表上的那一条 ⇒ {@code true}；条目已出表、无处可退 ⇒
         *         {@code false}（这一笔只被 {@link #describeUndelivered()} 点名，
         *         <b>不</b>被复活、<b>不</b>被拦表；退账那一半与这一问无关，照做）
         */
        boolean rollBackFirings(IPatternDetails pattern, BigInteger firings);
    }

    /**
     * 正向终局（{@link Outcome#SUCCESS}）要落的那一<b>交割</b>出口：由挂账那一侧（CPU 的申报腿）在
     * {@link #noteAdmitted} 时带进来。
     *
     * <p><b>凭据来源（装机的 2.3.9 jar javap 逐字复核过）</b>：{@code AdvancedAlloyFurnaceAeManager}
     * 里 {@code notifyCpuBatchCompleted} 只在 {@code PendingCraftingOutput.isEmpty()} 成立时才发，
     * 而 {@code isEmpty} 读的是 {@code ledger}（{@code CraftingAeAmountAccumulator}），{@code ledger}
     * 由 {@code attributeDeliveredOutputs} 按每笔真插进 ME 网络的量走 {@code consume} 逐批扣减——
     * 扣到空才发 {@code SUCCESS}。也就是说<b>{@code SUCCESS} 终局本身就带"这批计划量已真写进网络"
     * 的数量含义</b>，不是纯事件。本出口就是把这一批当初划成待交割的那几枚，转成<b>已交割</b>记进
     * 现成的交割账（宿主作业既有那本），不在台账里另建账。
     */
    public interface Delivery {

        /**
         * 这一批的待交割枚数转成已交割：按 AEKey 交宽域真值（与 {@code planNet} 同一份 min(自报, 申报)）。
         *
         * @param pendingDelivery 这一批当初划成待交割的每键枚数（恒非空、每键 &gt; 0）
         * @param clamped         加数里有没有被夹到 {@code Long.MAX_VALUE} 那道饱和哨兵（纯标记）
         */
        void deliverBatch(Map<AEKey, BigInteger> pendingDelivery, boolean clamped);
    }

    /** 原台账累计计数的只读快照：宿主分档、缺凭据枚数与终局各档均取同一本账，
     * 仅用于覆盖面诊断，不作为任务出表或交付判据。 */
    public record CoverageCounters(
            long declaredBigint, long declaredOther, long inflightRegistered,
            long silentNoReceipt, long silentNoBatchId,
            BigInteger silentNoReceiptUnits, BigInteger silentNoBatchIdUnits,
            @Nullable IPatternDetails silentNoReceiptPattern,
            @Nullable IPatternDetails silentNoBatchIdPattern,
            long terminalSuccess, long terminalCancelled, long terminalOther, long terminalUnmatched) {
    }

    /** 按终局凭据能力登记宿主型别，仅累计诊断笔数。 */
    void noteDeclaredHostShape(boolean terminalCarrier);
    /** 挂一笔无网络直投预期的声明批；先登记记录，再向回执绑定终局入口。 */
    void noteAdmitted(@Nullable DeclaredBatchReceipt receipt, IPatternDetails pattern,
            BigInteger firings, long admittedTick, @Nullable Rollback rollback);
    /** 挂一笔声明批及同次 min 分流形成的待交割量，终局仅调用对应交割或回退出口。 */
    void noteAdmitted(@Nullable DeclaredBatchReceipt receipt, IPatternDetails pattern,
            BigInteger firings, long admittedTick, @Nullable Rollback rollback,
            @Nullable Map<AEKey, BigInteger> pendingDelivery, boolean clamped, @Nullable Delivery delivery);
    /** 结算指定批次；原子撤下记录以阻止重复交割，回调不持台账锁。 */
    void onTerminal(UUID batchId, Outcome outcome);
    /** 当前仍有未结算网络直投预期；仅供观察，不作任务出表判据。 */
    boolean hasUndeliveredDeclaredOutput();
    /** 当前在飞笔数，只读。 */
    int inflightBatchCount();
    /** 最老在飞记录的 tick 龄与批次短标识，只读。 */
    String describeOldestInflight(long currentTick);
    /** 待交割枚数及饱和标记，只读。 */
    String describePendingDelivery();
    /** 负向终局累计欠交付及已回退份数，只读。 */
    String describeUndelivered();
    /** 宿主分档、缺凭据与终局累计覆盖面的稳定快照。 */
    CoverageCounters coverageCounters();
}
