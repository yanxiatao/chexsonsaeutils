package git.chexson.chexsonsaeutils.network.matrix;

import java.util.List;

/**
 * 客户端菜单侧的矩阵落点：网络层按 containerId 校验后把服务端下发的数据
 * 写进菜单持有的客户端视图。
 */
public interface PatternMatrixTarget {

    boolean matchesContainerId(int containerId);

    /** 写入变化格子，并按服务端权威总槽数扩缩本地视图。 */
    void applyMatrixEntries(int totalSlots, List<PatternMatrixEntry> entries);

    /** 写入一次搜索的命中位图；{@code matchBits} 为空数组表示取消过滤。 */
    void applyMatrixMatches(long[] matchBits, int matchCount);

    /** 写入可见行的样板本体，只用于悬停提示。 */
    void applyMatrixRealStacks(List<PatternMatrixRealStack> realStacks);

    /** 视图本身，界面据此渲染格子。 */
    PatternMatrixClientView matrixView();
}
