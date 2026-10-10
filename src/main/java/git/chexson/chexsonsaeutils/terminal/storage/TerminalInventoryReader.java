package git.chexson.chexsonsaeutils.terminal.storage;

import appeng.api.networking.IGrid;
import net.minecraft.server.MinecraftServer;

/** 网格展示数据读取契约；只在服务端主线程读取活动库存。 */
public interface TerminalInventoryReader {
    /** 读取当前节点挂载库存及可合成键，不对 long 网络总量做减法补偿。 */
    TerminalInventorySnapshot read(IGrid grid, MinecraftServer server);
}
