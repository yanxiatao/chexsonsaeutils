package git.chexson.chexsonsaeutils.network.crafting;

import java.util.HashMap;
import java.util.Map;

/** 按计划版本组装分片；菜单独占实例，换版本立即丢弃上一帧。 */
public final class WidePlanFrameImpl implements WidePlanFrame {
    private long revision = -1;
    private int count;
    private final Map<Integer, String> parts = new HashMap<>();
    private String completed;

    @Override
    public void accept(long revision, int index, int count, String data) {
        WidePlanChunkPayload.validate(revision, index, count, data);
        if (revision < this.revision) return;
        if (revision > this.revision) {
            this.revision = revision;
            this.count = count;
            parts.clear();
            completed = null;
        }
        if (count != this.count) throw new IllegalArgumentException("同一计划的分片总数不一致");
        String previous = parts.putIfAbsent(index, data);
        if (previous != null && !previous.equals(data))
            throw new IllegalArgumentException("同一计划的重复分片内容不一致");
        if (completed == null && parts.size() == count) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < count; i++) joined.append(parts.get(i));
            completed = joined.toString();
        }
    }

    @Override
    public String completed(long revision) {
        return revision == this.revision && completed != null ? completed : "";
    }
}
