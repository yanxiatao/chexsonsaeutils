package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.KeyCounter;

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
