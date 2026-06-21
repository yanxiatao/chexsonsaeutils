package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.KeyCounter;

/**
 * 染色样板递归计划标记。
 *
 * 标记需要把最终输出暂存在 CPU 库存里，以便后续同一作业的递归样板继续消耗。
 */
public interface DyeablePatternRecursivePlan {

    /**
     * 返回当前计划是否使用了染色样板递归 ring replacement。
     */
    boolean chexsonsaeutils$usesDyeableRecursivePlanning();

    /**
     * 返回底层委托的 CraftingPlan 对象，用于兼容期望 CraftingPlan 类型的代码。
     */
    ICraftingPlan chexsonsaeutils$getDelegate();

    /**
     * 返回递归 ring replacement 额外要求从网络取出的初始物品。
     */
    default KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
        return new KeyCounter();
    }

    /**
     * 返回递归作业内部保留的催化物。
     *
     * 这些物品在同一作业内可作为中间产物回流到 CPU 库存，不能只按最终输出判断。
     */
    default KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems() {
        return new KeyCounter();
    }

    /**
     * 返回应交给 requester 的最终输出数量。
     */
    default long chexsonsaeutils$dyeableRecursiveFinalOutputAmount() {
        return -1L;
    }
}
