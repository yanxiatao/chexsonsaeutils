package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import org.jetbrains.annotations.Nullable;

/** 一次 holder 推送多发的能力，保留动态上限与折叠失败的明确语义。 */
public interface HolderBatchCapability {
    /** 查询当前样板、当前机器状态下可推送的份数上限。 */
    long maxPushExecutions(IPatternDetails pattern);

    /** 准备多发推送样板；原生入口重读动态上限，折叠入口只折叠，null 表示拒绝且材料应保留。 */
    @Nullable IPatternDetails preparePushPattern(IPatternDetails pattern, long executions);

    /** 标识是否为折叠入口，供现有 foldHost/foldNull 诊断计数使用。 */
    boolean foldsPattern();
}
