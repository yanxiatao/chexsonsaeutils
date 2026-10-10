package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;

/**
 * "份数声明 + 单发原型材料"的批量宿主（执行侧能力，不参与规划聚合）。
 *
 * <p>与 {@link HolderBatchProvider} 那条 k 倍 holder 路径的区别只有一件事：宿主不再从
 * holder 的实际量反推份数，而是照收 CPU 声明的份数——一次交互能推进几发于是只由
 * <b>宿主真实能力</b>与<b>产出侧可表示性</b>决定，不再被"k 倍料能不能一次装进
 * {@code KeyCounter[]}"夹住（{@code KeyCounter} 底层是 {@code Object2LongMap}，单键上限 long）。
 *
 * <p>代价由 CPU 侧承担：声明了 k 发、宿主只拿走一发的材料，剩下 (k-1) 份材料挂进
 * {@code BatchObligationLedger} 的义务账，分批从池子里提走并照旧走
 * {@code ParallelCraftingCpuLogic#recordTakenInputs} 的槽级口径销账（每批每键 ≤ long）。
 * 因此返回<b>实收</b>份数至关重要：宿主吃不下多少就报多少，CPU 按实收结算义务，
 * 绝不按申报量记账。
 *
 * <p>未覆写 {@link #supportsDeclaredBatch(IPatternDetails)} 的宿主永远返回 {@code false}，
 * CPU 于是继续走 k 倍 holder 路径——老宿主零改动、零行为变化。
 */
public interface DeclaredBatchHost extends ICraftingProvider {

    /** 这份样板是否按"声明份数"收料：{@code false}（默认）即 CPU 退回 k 倍 holder 路径。 */
    default boolean supportsDeclaredBatch(IPatternDetails pattern) {
        return false;
    }

    /**
     * 该样板一次声明批量的真实份数上界。<b>这条只服务真按 {@code long} 记份数的宿主</b>：
     * 必须是宿主<b>记得住产出与回流</b>的真能力，不能是"先收下再说"的乐观数。
     *
     * <p>宿主若报 {@link #declaredBatchFiringsUnbounded()} 为 {@code true}，申报侧<b>不读</b>这一格
     * （那条通道的份数本来就是 {@code BigInteger}，{@code long} 里没有一个数能当它的上界）。
     * 默认交回 {@link Long#MAX_VALUE}——按 {@code long} 记账的宿主能表达的份数本就到不了更外。
     */
    default BigInteger declaredBatchCapacity(IPatternDetails pattern) {
        return WideCeilings.LONG_ACCOUNT;
    }

    /**
     * 宿主对<b>申报份数</b>有没有数值上界：{@code true} = 没有，这条通道接的份数就是
     * {@link BigInteger}，真上限由机器在接单那一刻动态给（能量 / 分段预算 / 回网背压）。
     *
     * <p>置 {@code true} 时 CPU 申报侧跳过 {@link #declaredBatchCapacity(IPatternDetails)} 那一道
     * {@code min}，也跳过"申报 × 每发倍率 ≤ long"那一道<b>输入</b>夹子——那份夹子夹的是
     * {@code long} 计数的<b>回流容器账</b>，宿主不用 {@code long} 记份数时它就不是这一条腿的约束。
     * 产出与实收份数在 CPU 内使用宽域账；普通 long 宿主仍受自身可表示上界约束。
     */
    default boolean declaredBatchFiringsUnbounded() {
        return false;
    }

    /**
     * 收一份"单发原型材料 + 份数声明"的批量。
     *
     * @param unitPrototype     <b>一发</b>所需材料的 holder（每槽一个候选键），已由 CPU 真扣出池子
     * @param declaredExecutions CPU 请求推进的份数
     * @return 实收份数：{@code 0} 表示整批拒收且宿主零状态变化；小于申报值表示只消化一部分，
     *         CPU 按实收结算任务表与义务账
     */
    BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
            BigInteger declaredExecutions);

    /**
     * 网格退避账按<b>谁的身份</b>记这台宿主的拒收：默认就是本对象（真方块实体自己实现本接口时，
     * 身份与 {@code ParallelCpuProviderBackoff#checkProvider} 查询用的那一个天然是同一个）。
     *
     * <p>伪装壳（{@code UselessBigIntegerDeclaredHost} 那种"只转推送、不是网格 provider"的壳）
     * 必须交回<b>真宿主</b>：壳只在调用方当路由用，把退避记在壳上就等于记在一个没人查询的身份上
     * （实机读数 {@code back=0} 恒零即此形态）。
     */
    default ICraftingProvider declaredBatchBackoffOwner() {
        return this;
    }

    /**
     * 上一次 {@link #pushDeclaredBatch} 整批拒收时，宿主给回的可辨原因（<b>纯读数</b>：不参与任何
     * 判据、不改任何分支）。默认 {@code null}＝这台宿主说不出原因，读数只点得到宿主类名。
     */
    default @Nullable String lastDeclaredBatchRejectReason() {
        return null;
    }
}
