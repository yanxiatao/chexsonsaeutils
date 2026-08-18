package git.chexson.chexsonsaeutils.helpers.framepatternprovider;

import java.util.EnumSet;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

import appeng.api.stacks.AEItemKey;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;

/**
 * 框架样板供应器逻辑宿主接口。
 * <p>
 * 动机：AE2 的 {@code PatternProviderLogicHost} 的 {@code getLogic()} 返回 AE2 的
 * {@code PatternProviderLogic}，而本项目 fork 了 {@link FramePatternProviderLogic}（target 解析改为
 * 私有维度机器），返回类型不同，无法直接实现 AE2 接口，故平行定义本接口。
 * 阶段 2 继承改造（R2 评审确认）：本接口改为 extends AE2 {@link PatternProviderLogicHost}，
 * {@link #getLogic()} 协变返回 {@link FramePatternProviderLogic}（其 extends
 * PatternProviderLogic），使宿主类型与 AE2 接口兼容（阶段 3 GUI 复用铺路）。
 * 父接口的默认方法（getConfigManager/getPriority/setPriority/getGrid/isVisibleInTerminal/
 * getTerminalPatternInventory/getTerminalSortOrder/getTerminalGroup）均委托 getLogic()，
 * 子接口直接继承，不再重复声明。
 * <p>
 * 与 AE2 接口的差异：openMenu/returnToMainMenu 覆写为本项目菜单
 * （FramePatternProviderMenu，替代 AE2 默认的 PatternProviderMenu，避免继承后
 * 悄悄打开 AE2 菜单）；额外提供 {@link #getMachineItemHandler()}，供逻辑层解析
 * 私有维度机器的物品 handler（需求 8 的推送/抽取目标）。
 * <p>
 * 阶段 1 共享层泛化：本接口同时服务框架样板供应器（单 handler 模式）与定制样板
 * 供应器（多方向模式）。{@link #getTargets()} 空集 = 单 handler 模式（框架语义，
 * 逻辑层委托无参 getMachineItemHandler/getMachineEnergyHandler）；非空 = 多方向
 * 模式（新方块语义，逻辑层遍历方向取第一个可用 handler）。
 */
public interface FramePatternProviderLogicHost extends PatternProviderLogicHost {

    /**
     * @return 本框架的样板供应逻辑实例（协变返回：FramePatternProviderLogic extends
     *         PatternProviderLogic，满足父接口签名）
     */
    @Override
    FramePatternProviderLogic getLogic();

    /**
     * @return 世界中的宿主方块实体
     */
    @Override
    BlockEntity getBlockEntity();

    /**
     * @return 推送目标方向集合。空集 = 单 handler 模式（框架样板供应器语义：机器在
     *         私有维度，周围无方块，恒返回空集，逻辑层委托无参
     *         {@link #getMachineItemHandler()}）；非空 = 多方向模式（定制样板供应器
     *         语义：逻辑层遍历方向调用 {@link #getMachineItemHandler(Direction)}，
     *         取第一个可用 handler）。
     */
    @Override
    EnumSet<Direction> getTargets();

    /**
     * 宿主数据变化时通知保存。
     */
    @Override
    void saveChanges();

    /**
     * @return 私有维度机器的物品 handler。永不返回 null：客户端或机器不可达时返回空实现
     *         （0 槽，插入/抽取天然空操作），服务端返回机器本体 handler
     */
    IItemHandler getMachineItemHandler();

    /**
     * @return 已解锁样板页数（需求 5，翻页 GUI 用）。默认 1：逻辑层共享接口不强制
     *         翻页语义，宿主（框架样板供应器/定制样板供应器）按自身页数持久化覆写
     */
    default int getPages() {
        return 1;
    }

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

    /**
     * 打开本项目框架样板供应器菜单（替代 AE2 默认的 PatternProviderMenu）。
     * <p>
     * 动机：父接口默认实现打开 PatternProviderMenu.TYPE，本项目菜单为自定义
     * FramePatternProviderMenu，继承后必须覆写，避免调用方打开错误菜单。
     */
    @Override
    default void openMenu(Player player, MenuHostLocator locator) {
        MenuOpener.open(Chexsonsaeutils.FRAME_PATTERN_PROVIDER_MENU.get(), player, locator);
    }

    /**
     * 子菜单返回主菜单：打开本项目框架样板供应器菜单（替代 AE2 默认的 PatternProviderMenu）。
     */
    @Override
    default void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(Chexsonsaeutils.FRAME_PATTERN_PROVIDER_MENU.get(), player, subMenu.getLocator());
    }
}