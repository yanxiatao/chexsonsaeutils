package git.chexson.chexsonsaeutils.helpers.framepatternprovider;

import java.util.EnumSet;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
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
 * <p>
 * 阶段 1 共享层泛化：本接口同时服务框架样板供应器（单 handler 模式）与定制样板
 * 供应器（多方向模式）。{@link #getTargets()} 空集 = 单 handler 模式（框架语义，
 * 逻辑层委托无参 getMachineItemHandler/getMachineEnergyHandler）；非空 = 多方向
 * 模式（新方块语义，逻辑层遍历方向取第一个可用 handler）。
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
     * @return 推送目标方向集合。空集 = 单 handler 模式（框架样板供应器语义：机器在
     *         私有维度，周围无方块，恒返回空集，逻辑层委托无参
     *         {@link #getMachineItemHandler()}）；非空 = 多方向模式（定制样板供应器
     *         语义：逻辑层遍历方向调用 {@link #getMachineItemHandler(Direction)}，
     *         取第一个可用 handler）。
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

    /**
     * @return 宿主升级库存（appflux 感应卡检测用，见
     *         {@link git.chexson.chexsonsaeutils.integration.appflux.AppFluxEnergyInjectorImpl}）。
     *         框架样板供应器已有升级库存（IUpgradeableObject）
     */
    IUpgradeInventory getUpgrades();

    /**
     * @return 机器能量 handler（appflux 灌电目标）。框架语义：永不返回 null（客户端
     *         或机器不可达时返回空实现）；定制样板供应器在 BE 层实现方向逻辑
     *         （多方向模式下返回第一个可用方向的 handler，全不可用时返回 null）
     */
    IEnergyStorage getMachineEnergyHandler();

    /**
     * 按方向解析机器物品 handler（多方向模式）。
     * <p>
     * 默认实现委托无参 {@link #getMachineItemHandler()}（框架语义：单 handler，
     * 忽略方向）；定制样板供应器覆写为按方向查询相邻机器。
     *
     * @param direction 目标方向
     * @return 该方向的机器物品 handler；该方向无机器时返回 null
     */
    default IItemHandler getMachineItemHandler(Direction direction) {
        return getMachineItemHandler();
    }

    /**
     * 按方向解析机器能量 handler（多方向模式）。
     * <p>
     * 默认实现委托无参 {@link #getMachineEnergyHandler()}（框架语义：单 handler，
     * 忽略方向）；定制样板供应器覆写为按方向查询相邻机器。
     *
     * @param direction 目标方向
     * @return 该方向的机器能量 handler；该方向无机器时返回 null
     */
    default IEnergyStorage getMachineEnergyHandler(Direction direction) {
        return getMachineEnergyHandler();
    }

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