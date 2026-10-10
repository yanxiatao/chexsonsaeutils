package git.chexson.chexsonsaeutils.integration.terminalstorage.extendedaeplus;

import com.extendedae_plus.ExtendedAEPlus;
import com.extendedae_plus.util.storage.InfinityDataStorage;
import net.minecraft.server.MinecraftServer;
import java.util.Objects;
import java.util.UUID;

/** 读取 Plus 已初始化的活动存储，不触发 SavedData 的 computeIfAbsent。 */
public final class PlusCellDataLookupImpl implements PlusCellDataLookup {
    @Override public InfinityDataStorage find(MinecraftServer server, UUID identity) {
        Objects.requireNonNull(server, "精确库存读取需要活动服务器");
        return Objects.requireNonNull(ExtendedAEPlus.currentStorageManager(), "Plus 活动存储未初始化").getCell(identity);
    }
}
