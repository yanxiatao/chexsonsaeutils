package git.chexson.chexsonsaeutils.network.custompatternencoder;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.List;

/** 编码菜单的网络行为契约；载荷不依赖具体 GUI 或菜单实现。 */
public interface CustomPatternEncoderSyncTarget {
    /** 应用服务端提供的稀疏输入、槽位映射、抽取槽及超堆叠开关。 */
    void updateFromServer(List<GenericStack> inputs, int[] mapping, int[] extractSlots, boolean overflowStacks);
    /** 服务端接受玩家为一个样板槽指定的精确键。 */
    void update(AEKey key, int slot);
}
