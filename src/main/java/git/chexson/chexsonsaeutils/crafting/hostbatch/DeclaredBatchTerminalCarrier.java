package git.chexson.chexsonsaeutils.crafting.hostbatch;

import org.jetbrains.annotations.Nullable;

/**
 * 「这一台宿主申报批之后还会给回<b>批次终局事件</b>」这一条能力的显式形状。
 *
 * <p>capability 的判法按<b>接口/回调是否实现</b>，不按读数、不按超时：只有把该笔的
 * {@code cpuToken}（{@link DeclaredBatchReceipt}）交回来的宿主，CPU 侧才允许挂
 * {@code 在飞 / 欠交付} 那本台账；交不回 token 的宿主（既有全部 {@code DeclaredBatchHost}
 * 实现者、以及 {@code DeclaredBatchReceipt#batchId()} 一直为零的老通道）逐字退回
 * 接入本通道之前的口径——<b>不索要、不拦出表、不新增闸门</b>。
 *
 * <p>本仓唯一的生产实现者是 {@code UselessBigIntegerDeclaredHost}（万象炉 bigint 通道壳）：
 * 它的终局事件来自 useless 侧 {@code AlloyFurnaceBigIntegerCpuAdapter#onBatchFinished}，
 * 由 {@code UselessBigIntegerApiBridge} 沿 集成回执的终局接收口 打回来。
 *
 * <p><b>它同时是"这一批能不能把产出划成直投网络"的前提（#227 K1）</b>：交不回 token、或 token 上
 * 没有 {@code batchId} ⇒ 这一笔没有终局凭据可等 ⇒ CPU 侧一律按"没有直投网络那一截"划账，
 * 整批产物全额挂 {@code waitingFor} 等实物。划了账却没人交割的那一型（实机 2026-10-02 19:55
 * {@code push=81}／{@code declared=113} 而 {@code inflightBatches=0}）就此不再可能出现。
 */
public interface DeclaredBatchTerminalCarrier {

    /**
     * 上一次 {@code pushDeclaredBatch} <b>真收下</b>那一批的 {@code cpuToken}；
     * 整批拒收、或这条通道压根没有终局事件来源时交回 {@code null}。
     *
     * <p>只在推送成功之后被读一次：读到了就该批次而言台账挂得上，读不到就本轮一分账都不挂。
     */
    @Nullable DeclaredBatchReceipt lastAcceptedBatchReceipt();
}
