package git.chexson.chexsonsaeutils.network.matrix;

import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端菜单侧的矩阵请求落点：界面里的格子是客户端虚拟槽，原版槽位同步覆盖不到，
 * 因此搜索与可见行真栈索取统一换算后由这里落实，并按 {@code player} 单播回包。
 * 格子点击走 AE2 自己的 {@code InventoryActionPacket}，由菜单的
 * {@code doAction} 按 extraId 分流到 {@link PatternMatrixInteraction}。
 */
public interface PatternMatrixServerTarget {

    void handleMatrixSearch(String query, ServerPlayer player);

    void handleMatrixVisibleRequest(int[] globalSlots, ServerPlayer player);
}
