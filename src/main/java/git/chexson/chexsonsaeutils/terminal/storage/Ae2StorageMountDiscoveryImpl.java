package git.chexson.chexsonsaeutils.terminal.storage;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.IGridNode;
import git.chexson.chexsonsaeutils.api.storage.ExactCellSnapshotSource;
import java.util.ArrayList;
import java.util.List;

/** AE2 存储舱和驱动器发现；同时读取原始盘与网络挂载视图。 */
public final class Ae2StorageMountDiscoveryImpl implements StorageMountDiscovery {
    @Override public boolean supports(IGridNode node) { return node.getOwner() instanceof IChestOrDrive; }
    @Override public List<StorageMount> discover(IGridNode node) {
        var host = (IChestOrDrive) node.getOwner();
        if (!node.isOnline() || !host.isPowered()) return List.of();
        var result = new ArrayList<StorageMount>();
        for (int slot = 0; slot < host.getCellCount(); slot++) {
            var original = host.getOriginalCellInventory(slot);
            var visible = host.getCellInventory(slot);
            if (original == null || visible == null) continue;
            Object identity = original instanceof ExactCellSnapshotSource exact
                    ? exact.readExactSnapshot().identity() : new SlotIdentity(host, slot);
            result.add(new StorageMount(identity, original, visible));
        }
        return List.copyOf(result);
    }
    private record SlotIdentity(IChestOrDrive host, int slot) {}
}
