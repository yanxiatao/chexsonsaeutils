package git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEKey;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountCollector;
import git.chexson.chexsonsaeutils.terminal.storage.NetworkExactInventory;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
/** NeoEco 公开采集器在真实挂载处累加 BigInteger，保留多个 long 盘的溢出和。 */
public final class NeoEcoNetworkExactInventoryImpl implements NetworkExactInventory {
    @Override public Map<AEKey, StorageAmount> read(IGrid grid) {
        return readFrom(grid.getStorageService().getInventory());
    }
    /** 直接使用真实采集入口，允许集成验证无需构造整个服务器网格。 */
    public Map<AEKey, StorageAmount> readFrom(MEStorage inventory) {
        ExactAmountCollector.begin();
        try {
            var narrow = inventory.getAvailableStacks();
            var wide = ExactAmountCollector.finish();
            var result = new HashMap<AEKey, StorageAmount>();
            for (var entry : narrow) {
                var exact = wide.get(entry.getKey());
                if (exact != null) result.put(entry.getKey(), exact.infinite()
                        ? StorageAmount.infinite() : StorageAmount.finite(exact.value()));
                else {
                    if (entry.getLongValue() < 0) throw new IllegalStateException("NeoEco 实际挂载采集发生回绕");
                    result.put(entry.getKey(), StorageAmount.finite(BigInteger.valueOf(entry.getLongValue())));
                }
            }
            return Map.copyOf(result);
        } catch (RuntimeException | Error failure) {
            ExactAmountCollector.abort();
            throw failure;
        }
    }
    @Override public boolean observesExactAmounts(MEStorage visible) {
        if (visible instanceof cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountSource) return true;
        // 由实际采集器判定包装库存是否已提供精确值，避免新版解包后再次加上溢出差额。
        var listed = visible.getAvailableStacks();
        ExactAmountCollector.begin();
        try {
            ExactAmountCollector.observe(visible, listed);
            var collected = ExactAmountCollector.finish();
            for (var entry : listed) {
                if (entry.getLongValue() != 0 && !collected.containsKey(entry.getKey())) return false;
            }
            return !collected.isEmpty();
        } catch (RuntimeException | Error failure) {
            ExactAmountCollector.abort();
            throw failure;
        }
    }
    @Override public boolean covers(IGridNode node) {
        return node.getOwner() instanceof cn.dancingsnow.neoecoae.blocks.entity.storage.ECODriveBlockEntity
                || node.getOwner() instanceof cn.dancingsnow.neoecoae.blocks.entity.storage.ECOStorageSystemBlockEntity;
    }
}
