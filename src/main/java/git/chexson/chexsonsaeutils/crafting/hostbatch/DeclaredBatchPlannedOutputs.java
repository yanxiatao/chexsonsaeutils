package git.chexson.chexsonsaeutils.crafting.hostbatch;

import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import org.jetbrains.annotations.Nullable;

/**
 * 声明批量（{@link DeclaredBatchHost}）的可选<b>计划</b>回执口：宿主把这批产物<b>不经过 CPU 池子</b>
 * 直接交出去（写回 ME 网络、或进它自己的输出池）时，用它把宿主<b>自报的计划枚数</b>交回 CPU。
 *
 * <p>CPU 侧唯一消费者是
 * {@code ParallelCraftingCpuLogic#reserveExpectedWaiting(job, expectedOutputs, containers, plannedToNetwork)}
 * 的第四个参数：申报 E 枚、宿主自报要入网 P 枚 ⇒ 记 {@code min(P,E)} 枚进<b>预期入网</b>账、余下
 * {@code E - min(P,E)} 枚挂 {@code waitingFor} 等实物。合计恒等于申报量——预期入网那一截不会再
 * 被当成"永不回来的实物挂账"，也不会与实物重复计一遍。
 *
 * <p><b>交进第四个参数之前还有一道前提（#227 K1）</b>：这一批必须<b>挂得上在飞台账</b>——宿主是
 * {@code DeclaredBatchTerminalCarrier}、交回了该笔的 {@code cpuToken}、且 token 上有 {@code batchId}。
 * 挂不上却照划，那几枚就成了"实物没人等、在飞看不见、planNet 记满、出表照旧"的死账（预期入网那一截
 * 只由该笔终局 {@code SUCCESS} 转成已交割，没有凭据就永远转不了）。判定不成立时 CPU 侧把这一份
 * 交回的计划量整份按 {@code null} 处理 ⇒ 全额挂 {@code waitingFor} 等实物。
 *
 * <p><b>这一口交回的是预期，不是交付证据</b>：宿主报的 {@code P} 出自它自己按配方算出的计划量，
 * 里面没有一枚是"插进存储成功了"的回执（useless 的 bigint 通道连后来那份"实际产出"都是同一个
 * 计划数——见 {@code UselessBigIntegerReceipt} 的类注释）。所以这本账只许用来把"不会再回池子"的那
 * 一截从实物预期里摘出去，<b>不许当"已交付"参与任何对平</b>；少交付那一截露在哪儿，见
 * {@code UselessBigIntegerReceipt#selfReportDelta}。
 *
 * <p>取走即清空：一次 {@code pushDeclaredBatch} 只对应一次取走。留着上一批的计划数，下一批就会
 * 把它算到自己头上。
 *
 * <p>没实现本接口的宿主（含既有全部声明宿主）一律按 {@code null} 处理：产物全额挂
 * {@code waitingFor} 等实物，与接入本通道之前逐字同效。
 */
public interface DeclaredBatchPlannedOutputs {

    /**
     * 取走并清空上一次声明批量推送里宿主<b>自报计划入网</b>的产出枚数（逐键精确 BigInteger）。
     *
     * @return 计划入网产出；{@code null} 表示这批产物仍按实物回 CPU 池子
     */
    @Nullable WideKeyCounter takePlannedNetworkOutputs();
}
