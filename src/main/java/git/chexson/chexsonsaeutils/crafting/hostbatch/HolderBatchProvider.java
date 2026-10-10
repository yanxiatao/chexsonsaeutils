package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

/** 能接收多份真实 holder 的批处理供应器；CPU 据此一次提取和推送整批材料。 */
public interface HolderBatchProvider extends ICraftingProvider {
    /** 当前样板单次可接收的份数；宿主须按真实数量和工时边界报告，拒收不得消耗材料。 */
    default long maxPushExecutions(IPatternDetails pattern) { return Long.MAX_VALUE; }
}
