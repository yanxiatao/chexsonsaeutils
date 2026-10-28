package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEKey;
import java.util.Map;
import java.util.Set;

/** 合成监控的数量表投影；全量帧与增量帧必须保留不同的删除语义。 */
public interface ExactMenuFrame<V> {
    /** 全量帧替换旧表，增量帧先删除再更新；返回不可变表，防止后续渲染污染同步数据。 */
    Map<AEKey, V> apply(Map<AEKey, V> previous, Map<AEKey, V> updates, Set<AEKey> removed, boolean full);
}
