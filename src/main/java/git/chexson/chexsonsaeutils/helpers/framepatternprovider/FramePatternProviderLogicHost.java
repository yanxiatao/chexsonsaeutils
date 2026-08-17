package git.chexson.chexsonsaeutils.helpers.framepatternprovider;

import java.util.EnumSet;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.util.IConfigManager;
import appeng.api.util.IConfigurableObject;
import appeng.helpers.IPriorityHost;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;

/**
 * 框架样板供应器逻辑宿主接口。
 * <p>
 * 动机：AE2 的 {@code PatternProviderLogicHost} 的 {@code getLogic()} 返回 AE2 的
 * {@code PatternProviderLogic}，而本项目 fork 了 {@link FramePatternProviderLogic}（target 解析改为
 * 私有维度机器），返回类型不同，无法直接实现 AE2 接口，故平行定义本接口。
 * 方法集参照 AE2 19.2.17 的 PatternProviderLogicHost，默认方法委托 {@link FramePatternProviderLogic}。
 * <p>
 * 与 AE2 接口的差异：不提供 openMenu/returnToMainMenu 默认方法（本项目菜单为自定义
 * FramePatternProviderMenu，由方块负责打开）；额外提供 {@link #getMachineItemHandler()}，
 * 供逻辑层解析私有维度机器的物品 handler（需求 8 的推送/抽取目标）。
 */
public interface FramePatternProviderLogicHost extends IConfigurableObject, IPriorityHost, PatternContainer {

    /**
     * @return 本框架的样板供应逻辑实例
     */
    FramePatternProviderLogic getLogic();

    /**
     * @return 世界中的宿主方块实体
     */
    BlockEntity getBlockEntity();

    /**
     * @return 推送目标方向集合。需求 8：输入输出隔离——机器在私有维度，周围无方块，
     *         恒返回空集（不向周围方块发/收）。
     */
    EnumSet<Direction> getTargets();

    /**
     * 宿主数据变化时通知保存。
     */
    void saveChanges();

    /**
     * @return 私有维度机器的物品 handler。永不返回 null：客户端或机器不可达时返回空实现
     *         （0 槽，插入/抽取天然空操作），服务端返回机器本体 handler
     */
    IItemHandler getMachineItemHandler();

    @Override
    default IConfigManager getConfigManager() {
        return getLogic().getConfigManager();
    }

    @Override
    default int getPriority() {
        return getLogic().getPriority();
    }

    @Override
    default void setPriority(int newValue) {
        getLogic().setPriority(newValue);
    }

    @Override
    default @Nullable IGrid getGrid() {
        return getLogic().getGrid();
    }

    /**
     * @return 终端（样板访问终端）中显示的图标
     */
    AEItemKey getTerminalIcon();

    @Override
    default boolean isVisibleInTerminal() {
        return getLogic().getConfigManager().getSetting(Settings.PATTERN_ACCESS_TERMINAL) == YesNo.YES;
    }

    @Override
    default InternalInventory getTerminalPatternInventory() {
        return getLogic().getPatternInv();
    }

    @Override
    default long getTerminalSortOrder() {
        return getLogic().getSortValue();
    }

    default PatternContainerGroup getTerminalGroup() {
        return getLogic().getTerminalGroup();
    }

    /**
     * 子菜单返回主菜单：打开本项目框架样板供应器菜单（替代 AE2 默认的 PatternProviderMenu）。
     */
    @Override
    default void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(Chexsonsaeutils.FRAME_PATTERN_PROVIDER_MENU.get(), player, subMenu.getLocator());
    }
}