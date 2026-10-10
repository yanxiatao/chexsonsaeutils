package git.chexson.chexsonsaeutils.terminal.sync;
import java.util.*;
/** 分片结束后一次替换不可变视图，不暴露暂存条目。 */
public final class TerminalInventoryViewImpl implements TerminalInventoryView {
    private Map<Integer, TerminalEntry> entries = Map.of();
    private long revision;
    private boolean active, covered;
    @Override public boolean apply(TerminalFrame frame) {
        if (frame.revision() <= revision || (!frame.full() && frame.base() != revision)) return false;
        var updated = frame.full() ? new HashMap<Integer, TerminalEntry>() : new HashMap<>(entries);
        for (var id : frame.removed()) updated.remove(id);
        var ids = new HashSet<Integer>();
        for (var entry : frame.changes()) {
            if (!ids.add(entry.id())) throw new IllegalArgumentException("同帧重复条目编号");
            updated.put(entry.id(), entry);
        }
        if (updated.size() > TerminalFrameCodec.MAX_ENTRIES) throw new IllegalArgumentException("条目预算超限");
        var keys = new HashSet<appeng.api.stacks.AEKey>();
        for (var entry : updated.values()) if (!keys.add(entry.key()))
            throw new IllegalArgumentException("同一库存键出现多个编号");
        entries = Map.copyOf(updated); revision = frame.revision(); active = frame.active(); covered = frame.covered();
        return true;
    }
    @Override public Collection<TerminalEntry> entries() { return entries.values(); }
    @Override public long revision() { return revision; }
    @Override public boolean active() { return active; }
    @Override public boolean covered() { return covered; }
}
