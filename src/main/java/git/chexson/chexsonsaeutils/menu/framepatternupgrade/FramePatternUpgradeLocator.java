package git.chexson.chexsonsaeutils.menu.framepatternupgrade;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

import appeng.menu.locator.MenuHostLocator;
import appeng.menu.locator.MenuLocators;

/**
 * 扩容 GUI 的菜单定位器（MenuHostLocator 实现）。
 * <p>
 * 动机：扩容 GUI 的宿主是瞬态对象（空库存，玩家自行放入物品），无法用
 * BlockEntityLocator/ItemMenuHostLocator 表达，需要自定义 locator 在服务端与
 * 客户端两侧构造宿主。本 locator 不携带任何数据（网络序列化为空实现）。
 * 注册时机：Chexsonsaeutils 主类构造器（与 {@link git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigLocator}
 * 一致）。
 */
public class FramePatternUpgradeLocator implements MenuHostLocator {

    @Override
    public <T> T locate(Player player, Class<T> hostInterface) {
        if (hostInterface == FramePatternUpgradeHost.class) {
            return hostInterface.cast(new FramePatternUpgradeHost());
        }
        return null;
    }

    public static void writeToPacket(FramePatternUpgradeLocator locator, FriendlyByteBuf buf) {
        // 无数据
    }

    public static FramePatternUpgradeLocator readFromPacket(FriendlyByteBuf buf) {
        return new FramePatternUpgradeLocator();
    }

    /**
     * 注册到 MenuLocators 网络序列化注册表（幂等：重复注册抛异常，仅调用一次）。
     */
    public static void register() {
        MenuLocators.register(
                FramePatternUpgradeLocator.class,
                FramePatternUpgradeLocator::writeToPacket,
                FramePatternUpgradeLocator::readFromPacket);
    }

    @Override
    public String toString() {
        return "FramePatternUpgradeLocator[]";
    }
}
