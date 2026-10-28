package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 已冻结的全量帧直接复用，避免逐帧复制数千条精确数量；发生增量时重新冻结新表。 */
public final class ExactMenuFrameImpl<V> implements ExactMenuFrame<V> {
    @Override
    public Map<AEKey, V> apply(Map<AEKey, V> previous, Map<AEKey, V> updates, Set<AEKey> removed, boolean full) {
        // 自家客户端的全量投影使用 Map.copyOf；再次 copyOf 同一不可变表不会复制条目。
        if (full && removed.isEmpty()) return Map.copyOf(updates);
        var next = new HashMap<AEKey, V>(full ? Map.of() : previous);
        removed.forEach(next::remove);
        next.putAll(updates);
        return Map.copyOf(next);
    }
}
