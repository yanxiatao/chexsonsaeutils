package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import java.util.Set;

/** 单作业的缺料反向索引，只缩小唤醒候选范围，不代替输入槽合法性与真实取料判断。 */
interface MaterialWakeIndex {
    /** 重新登记任务全部候选键；替换旧登记，避免重新退避留下重复引用。 */
    void register(IPatternDetails pattern, Set<AEKey> keys);
    /** 任务唤醒或退避到期时删除全部反向引用。 */
    void remove(IPatternDetails pattern);
    /** 返回到达键可能唤醒的任务快照；原生精确样板按精确键，其他样板按物品族筛选。 */
    Set<IPatternDetails> candidates(AEKey arriving);
}
