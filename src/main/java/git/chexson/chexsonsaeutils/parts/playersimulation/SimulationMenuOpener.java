package git.chexson.chexsonsaeutils.parts.playersimulation;

import net.minecraft.server.level.ServerPlayer;

/** 在装配层注入菜单打开行为，部件不引用菜单实现。 */
@FunctionalInterface
public interface SimulationMenuOpener {
    /** 为正在访问部件的真实玩家打开面板菜单。 */
    void open(ServerPlayer player, PlayerSimulationPart part);
}
