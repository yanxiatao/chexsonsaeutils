package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;

import java.util.Map;

/**
 * 递归计划执行契约。
 *
 * <p>方法名保留历史兼容前缀，但契约适用于所有样板，颜色不参与规划语义。
 * 执行侧需要暂存最终输出，使同一作业中的递归样板可以继续消费它。
 */
public interface RecursivePlanContract {

    /** 返回当前计划是否使用递归规划执行契约。 */
    boolean chexsonsaeutils$usesDyeableRecursivePlanning();

    /** 返回底层委托计划，用于兼容期望原始计划类型的代码。 */
    ICraftingPlan chexsonsaeutils$getDelegate();

    /** 返回递归计划额外要求从网络提取的初始物品。 */
    default KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
        return new KeyCounter();
    }

    /**
     * 返回规划定版的逐张样板配给：这张样板在这张计划里，每个键一共要吃多少份。
     *
     * <p>模糊槽允许规划混取，但"哪个键几份"是计划里的数——执行侧照这份数取料，
     * 份额之外一概不再消耗。空表表示这份归属没随计划下发（旧计划/别的算法出口），
     * 执行侧退到结构占料判据。
     *
     * <p>账本是 {@link WideKeyCounter}：份数＝每发量 × <b>宽域</b>发数，同一键还要跨槽跨候选
     * 复加，合计没有上界。这份表是执行侧配给账（{@code planMaterialPlan}）的<b>唯一上游</b>，
     * 它在计划里就顶到 {@code Long.MAX_VALUE} 的话，流到执行侧与逐批精确的已吃账做差就成了
     * 少算的那一截 ⇒ 还能推的批次被夹成 0 发。饱和一律留给判据那一道出口
     * （{@code ParallelPlanReservation#remainingQuota}）。
     *
     * <p>这一本<b>含</b> {@link #chexsonsaeutils$planMaterialEnvelopes()} 那截封套（许可上界），
     * 配额夹读的就是这一整本；凡"这一族还缺多少料"的读数才需要先剔掉封套。
     */
    default Map<IPatternDetails, WideKeyCounter> chexsonsaeutils$planMaterialPlan() {
        return Map.of();
    }

    /**
     * 返回 {@link #chexsonsaeutils$planMaterialPlan()} 里<b>封套那一截</b>（同一形状：
     * 样板 → 键 → 份数），出自守恒闸门的同一趟账。
     *
     * <p>封套是 {@code MaterialClosureGate#billReachKeySlot} 给模糊槽首位候选记的<b>许可上界</b>
     * （"这一格若向这一族伸手，至多吃掉整格那一份"），不是计划真从这一族取走的量。所以这本账
     * 只有一个用途：让"这一族还缺多少料"的读数在减法上先剔掉它——执行侧 {@code hole=} 因此只报
     * 排水实记，不再把整格上界报成规模相关的假洞（假洞还会按大小排在真洞前面，把真洞挤出前 8）。
     *
     * <p><b>配额夹（{@code ParallelPlanReservation#quotaExact} / {@code remainingQuota} 与
     * {@code ParallelCraftingCpuLogic#capByRemainingShare}）读的是含封套的整本配给表，不是这一本</b>：
     * 封套存在的全部理由就是"运行期现场有量而规划宇宙为 0 时允许它吃满这一格"，
     * 摘掉它就把能跑的表夹死了。
     *
     * <p>与配给表同一侧：只活在内存里、<b>不进 NBT</b>；账本同样是 {@link WideKeyCounter}，
     * 不许降级成 {@code Map<AEKey, Long>}（这本账是被减数，截短就等于把配到的那一截抹掉）。
     * 缺省空表 = 这份计划没带封套（旧出口 / 别的算法），执行侧的 hole 分子因此不剔任何东西。
     */
    default Map<IPatternDetails, WideKeyCounter> chexsonsaeutils$planMaterialEnvelopes() {
        return Map.of();
    }

    /**
     * 返回递归作业内部保留的催化物。
     *
     * <p>这些物品可在同一作业内作为中间产物回流到 CPU 库存，不能仅按最终输出判断。
     */
    default KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems() {
        return new KeyCounter();
    }

    /** 返回应交给 requester 的最终输出数量。 */
    default long chexsonsaeutils$dyeableRecursiveFinalOutputAmount() {
        return -1L;
    }
}
