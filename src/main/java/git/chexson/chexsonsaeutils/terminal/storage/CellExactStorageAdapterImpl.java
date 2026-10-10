package git.chexson.chexsonsaeutils.terminal.storage;

import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.api.storage.ExactCellSnapshotSource;
import net.minecraft.server.MinecraftServer;
import java.util.HashMap;
import java.util.Map;

/** 公共单元接口适配，既服务自家无限盘，也允许第三方实现同一接口。 */
public final class CellExactStorageAdapterImpl implements ExactStorageAdapter {
    private final java.util.WeakHashMap<MEStorage, Cached> cache = new java.util.WeakHashMap<>();
    @Override public boolean supports(MEStorage inventory) { return inventory instanceof ExactCellSnapshotSource; }
    @Override public Map<AEKey, StorageAmount> read(MEStorage inventory, MinecraftServer server) {
        var snapshot = ((ExactCellSnapshotSource) inventory).readExactSnapshot();
        var existing = cache.get(inventory);
        if (existing != null && existing.snapshot.identity().equals(snapshot.identity())
                && existing.snapshot.revision() == snapshot.revision()) return existing.amounts;
        var result = new HashMap<AEKey, StorageAmount>();
        snapshot.amounts()
                .forEach((key, value) -> result.put(key, StorageAmount.finite(value)));
        var frozen = Map.copyOf(result);
        cache.put(inventory, new Cached(snapshot, frozen));
        return frozen;
    }
    private record Cached(git.chexson.chexsonsaeutils.api.storage.ExactCellSnapshot snapshot,
            Map<AEKey, StorageAmount> amounts) {}
}
