package git.chexson.chexsonsaeutils.parts.automation;

import net.minecraft.server.level.ServerPlayer;

/** 菜单打开端口，由注册装配提供，避免发信器运行部件依赖具体菜单。 */
@FunctionalInterface
public interface MultiLevelEmitterMenuOpener {
    /** 为交互玩家打开指定发信器的菜单，沿用部件定位与同步流程。 */
    void openMenu(ServerPlayer player, MultiLevelEmitterRuntimePart part);
}
