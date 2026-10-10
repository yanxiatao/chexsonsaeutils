package git.chexson.chexsonsaeutils.terminal.sync;
import java.util.List;
/** 完整逻辑帧，只有分片全部收齐后才能提交。 */
public record TerminalFrame(long revision, long base, boolean full, boolean active, boolean covered,
        List<TerminalEntry> changes, List<Integer> removed) {
    public TerminalFrame { changes = List.copyOf(changes); removed = List.copyOf(removed); }
}
