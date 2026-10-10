package git.chexson.chexsonsaeutils.terminal.storage;

import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEKey;
import net.minecraft.server.MinecraftServer;
import java.util.Map;

/** 一类存储的只读精确计数适配；不得探测提取或修改底层数据。 */
public interface ExactStorageAdapter {
    /** 判断当前原始库存是否属于此适配器，多个匹配属于装配错误。 */
    boolean supports(MEStorage inventory);
    /** 去重身份；共享底层记录的适配器应返回带来源命名空间的稳定身份，其余沿用发现器身份。 */
    default Object identity(StorageMount mount) { return mount.identity(); }
    /** 冻结完整正库存；调用者负责用实际挂载视图限制可见键。 */
    Map<AEKey, StorageAmount> read(MEStorage inventory, MinecraftServer server);
}
