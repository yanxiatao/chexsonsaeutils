package git.chexson.chexsonsaeutils.integration.terminalstorage.extendedaeplus;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.extendedae_plus.api.storage.InfinityBigIntegerCellInventory;
import git.chexson.chexsonsaeutils.terminal.storage.ExactStorageAdapter;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import git.chexson.chexsonsaeutils.terminal.storage.StorageMount;
import net.minecraft.server.MinecraftServer;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Plus 1.6.3 活动双表适配；冻结逐键真值，不按总量或存储结构修订缓存数量。 */
public final class PlusExactStorageAdapterImpl implements ExactStorageAdapter {
    private final PlusCellDataLookup lookup;
    public PlusExactStorageAdapterImpl() { this(new PlusCellDataLookupImpl()); }
    /** 注入同一只读查找行为，允许真实 SDK 验证不构造 MinecraftServer。 */
    public PlusExactStorageAdapterImpl(PlusCellDataLookup lookup) { this.lookup = Objects.requireNonNull(lookup); }
    @Override public boolean supports(MEStorage inventory) { return inventory instanceof InfinityBigIntegerCellInventory; }
    @Override public Object identity(StorageMount mount) {
        var uuid = ((InfinityBigIntegerCellInventory) mount.original()).getUUID();
        return uuid == null ? mount.identity() : new CellIdentity(uuid);
    }
    private record CellIdentity(java.util.UUID uuid) {}
    @Override public Map<AEKey, StorageAmount> read(MEStorage inventory, MinecraftServer server) {
        var identity = ((InfinityBigIntegerCellInventory) inventory).getUUID();
        if (identity == null) return Map.of();
        var data = lookup.find(server, identity);
        if (data == null) return Map.of();
        var result = new HashMap<AEKey, StorageAmount>(data.size());
        for (var entry : data.longAmounts.object2LongEntrySet()) {
            long amount = entry.getLongValue();
            if (amount <= 0) throw new IllegalStateException("Plus long 库存含非正数量");
            result.put(entry.getKey(), StorageAmount.finite(BigInteger.valueOf(amount)));
        }
        for (var entry : data.bigAmounts.entrySet()) {
            var amount = Objects.requireNonNull(entry.getValue(), "Plus 大数库存缺少数量");
            if (amount.signum() <= 0 || amount.bitLength() <= 63)
                throw new IllegalStateException("Plus 大数库存未满足宽域约束");
            if (result.putIfAbsent(entry.getKey(), StorageAmount.finite(amount)) != null)
                throw new IllegalStateException("Plus 两个数量表重复包含同一键");
        }
        return Map.copyOf(result);
    }
}
