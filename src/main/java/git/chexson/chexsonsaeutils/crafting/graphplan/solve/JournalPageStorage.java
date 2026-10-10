package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.nio.file.Path;

/** 低内存模式的页栈契约；保存已满的历史页并按逆序恢复到同一驻留页。 */
interface JournalPageStorage extends AutoCloseable {
    /** 惰性创建请求独占的临时存储；I/O 失败记录日志并使本次求解失败。 */
    static JournalPageStorage open() { return new JournalPageStorageImpl(); }
    /** 保存一页的主列及稀有列，不保留数量旧值的堆引用。 */
    default void push(int[] kinds, int[] keyIds, Object[] refs, long[] deltas,
            double[] doubles, int doubleCount, Object[] values, int valueCount) {
        pushRange(kinds, keyIds, refs, deltas, doubles, values,
                new Slice(0, kinds.length, 0, doubleCount, 0, valueCount));
    }
    /** 直接保存旧数组中的一段，迁移大历史时不复制整个日志或构造第二份历史。 */
    void pushRange(int[] kinds, int[] keyIds, Object[] refs, long[] deltas,
            double[] doubles, Object[] values, Slice slice);
    /** 主列、double 列、对象列各自的起点与长度；稀有列不能按主列下标切分。 */
    record Slice(int rowStart, int rowCount, int doubleStart, int doubleCount, int valueStart, int valueCount) {}
    /** 恢复最近保存的页；返回稀有列水位，新分支允许覆盖弹出的尾页。 */
    Tops pop(int[] kinds, int[] keyIds, Object[] refs, long[] deltas, double[] doubles, Object[] values);
    /** 当前磁盘历史页数，供驻留上界验收使用。 */
    int pageCount();
    /** 临时页文件路径，用于生命周期检查。 */
    Path path();
    /** 关闭并删除临时文件，释放图身份字典。 */
    @Override void close();
    /** 主页恢复后，double/对象稀有列已使用的长度。 */
    record Tops(int doubles, int values) {}
}
