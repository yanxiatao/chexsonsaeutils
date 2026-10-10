package git.chexson.chexsonsaeutils.integration.terminalstorage.extendedaeplus;

import com.extendedae_plus.util.storage.InfinityDataStorage;
import net.minecraft.server.MinecraftServer;
import java.util.UUID;

/** Plus 活动库存的只读查找契约，禁止使用 getOrCreateCell 或持久化刷新。 */
@FunctionalInterface
public interface PlusCellDataLookup {
    /** 查找已有 UUID；不存在时与 Plus 原生读取语义一致，表示空库存。 */
    InfinityDataStorage find(MinecraftServer server, UUID identity);
}
