package git.chexson.chexsonsaeutils.crafting.status;

import java.util.List;

/** 单个预览产物对应的精确样板发数，避免 AE2 代表值饱和后显示错误。 */
public interface EnhancedCraftingPlanSummaryEntry {

    /** 每张产出该键的样板独立保留次数；显示层排序，不能混算成物品产量。 */
    List<java.math.BigInteger> chexsonsaeutils$patternTimes();

    /** 写入不可变精确次数列表，随原概要条目同步。 */
    void chexsonsaeutils$setPatternTimes(List<java.math.BigInteger> patternTimes);
}
