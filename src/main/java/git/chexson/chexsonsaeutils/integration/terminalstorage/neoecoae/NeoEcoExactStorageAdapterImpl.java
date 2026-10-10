package git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEKey;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountSource;
import git.chexson.chexsonsaeutils.terminal.storage.*;
import net.minecraft.server.MinecraftServer;
import java.util.HashMap;
import java.util.Map;
/** NeoEco 公开精确来源适配，无写入或引擎获取副作用。 */
public final class NeoEcoExactStorageAdapterImpl implements ExactStorageAdapter {
    @Override public boolean supports(MEStorage inventory) {
        return inventory instanceof ExactAmountSource
                && !(inventory instanceof git.chexson.chexsonsaeutils.api.storage.ExactCellSnapshotSource);
    }
    @Override public Map<AEKey, StorageAmount> read(MEStorage inventory, MinecraftServer server) {
        var result = new HashMap<AEKey, StorageAmount>();
        ((ExactAmountSource) inventory).neoecoae$visitExactAmounts((key, amount) -> result.put(key,
                amount.infinite() ? StorageAmount.infinite() : StorageAmount.finite(amount.value())));
        return Map.copyOf(result);
    }
}
