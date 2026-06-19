package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import java.util.Map;

/**
 * 暴露染色递归规划期间已经确定的样板输入选择。
 *
 * formal 聚合需要消费这些单次执行输入，才能把 replacement-aware processing pattern
 * 折叠成一次处理，而不是重新把多候选输入判成非确定路径。
 */
public interface DyeablePatternSelectedInputsPlan {

    /**
     * 返回按样板 definition 分组的单次执行输入。
     *
     * key 是样板 definition。
     * value 是该样板单次执行时真实选择的输入及数量。
     */
    Map<AEItemKey, Map<AEKey, Long>> chexsonsaeutils$dyeableSelectedPatternInputs();
}
