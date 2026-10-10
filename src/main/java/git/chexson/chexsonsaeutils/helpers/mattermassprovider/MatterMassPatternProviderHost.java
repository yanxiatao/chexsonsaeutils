package git.chexson.chexsonsaeutils.helpers.mattermassprovider;

import java.util.EnumSet;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogicHost;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 物质团供应器宿主接口。
 * <p>
 * 继承 {@link CustomPatternProviderLogicHost} 复用翻页/扩容契约（扩容菜单的
 * pageHolder 类型要求），机器侧差异：无任何对外输入输出——机器 handler 恒空、
 * 推送目标恒空集；菜单覆写为物质团供应器菜单；额外暴露返回模式与放置者
 * UUID（玩家模式交付用）。
 */
public interface MatterMassPatternProviderHost extends CustomPatternProviderLogicHost {

    @Override
    MatterMassPatternProviderLogic getLogic();

    /** @return 产物返回目标模式（NBT 持久化，菜单 @GuiSync 同步） */
    ReturnMode getReturnMode();

    /** @return 放置者玩家 UUID（放置时绑定，玩家模式交付目标）；未绑定时 null */
    @Nullable
    UUID getOwnerUuid();

    /** 机器侧覆写：无任何对外推送目标（物质团供应器不向邻机推送原料）。 */
    @Override
    default EnumSet<Direction> getTargets() {
        return EnumSet.noneOf(Direction.class);
    }

    /** 机器侧覆写：无机器物品 handler（恒空实现，满足接口契约）。 */
    @Override
    default IItemHandler getMachineItemHandler() {
        return EMPTY_ITEM_HANDLER;
    }

    /** 机器侧覆写：无机器能量 handler。 */
    @Override
    @Nullable
    default IEnergyStorage getMachineEnergyHandler() {
        return null;
    }

    @Override
    default void openMenu(Player player, MenuHostLocator locator) {
        MenuOpener.open(ChexsonsaeutilsContent.MATTER_MASS_PATTERN_PROVIDER_MENU.get(), player, locator);
    }

    @Override
    default void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(ChexsonsaeutilsContent.MATTER_MASS_PATTERN_PROVIDER_MENU.get(), player,
                subMenu.getLocator());
    }
}
