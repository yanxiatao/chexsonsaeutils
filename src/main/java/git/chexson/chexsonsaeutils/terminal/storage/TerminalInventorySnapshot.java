package git.chexson.chexsonsaeutils.terminal.storage;

import appeng.api.stacks.AEKey;
import java.util.Map;
import java.util.Set;

/** 规范化库存快照；covered 只证明已发现节点，AE2 未公开的全局来源仍无法枚举。 */
public record TerminalInventorySnapshot(Map<AEKey, StorageAmount> amounts,
        Map<AEKey, Long> unverified, Set<AEKey> craftables, boolean covered) {
    public TerminalInventorySnapshot {
        amounts = Map.copyOf(amounts);
        unverified = Map.copyOf(unverified);
        craftables = Set.copyOf(craftables);
    }
}
