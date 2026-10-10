package git.chexson.chexsonsaeutils.client.integration.neoecoae;

import git.chexson.chexsonsaeutils.terminal.sync.TerminalInventoryView;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** 自有精确帧到 NeoEco 公开客户端缓存的投影契约。 */
public interface NeoEcoTerminalProjection {
    /** 首帧前保留 NeoEco 状态，随后按帧和缓存修订更新，关菜单时丢弃投影实例。 */
    void refresh(AbstractContainerMenu menu, TerminalInventoryView view);
}
