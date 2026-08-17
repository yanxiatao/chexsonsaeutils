package git.chexson.chexsonsaeutils.menu.framepatternconfig;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.menu.locator.MenuHostLocator;
import appeng.menu.locator.MenuLocators;

/**
 * 框架样板配置 GUI 的菜单定位器（MenuHostLocator 实现）。
 * <p>
 * 动机：配置 GUI 的宿主是瞬态对象（携带选中的处理样板副本），无法用
 * BlockEntityLocator/ItemMenuHostLocator 表达，需要自定义 locator 在
 * 服务端与客户端两侧构造宿主。网络序列化通过
 * {@link MenuLocators#register} 注册（类名 + 读写器），物品用
 * RegistryFriendlyByteBuf 读写（菜单打开包的实际 buffer 类型）。
 * 注册时机：Chexsonsaeutils 主类构造器。
 */
public class FramePatternConfigLocator implements MenuHostLocator {

    private final ItemStack inputPattern;

    public FramePatternConfigLocator(ItemStack inputPattern) {
        this.inputPattern = inputPattern;
    }

    @Override
    public <T> T locate(Player player, Class<T> hostInterface) {
        if (hostInterface == FramePatternConfigHost.class) {
            return hostInterface.cast(new FramePatternConfigHost(this.inputPattern));
        }
        return null;
    }

    public static void writeToPacket(FramePatternConfigLocator locator, FriendlyByteBuf buf) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode((RegistryFriendlyByteBuf) buf, locator.inputPattern);
    }

    public static FramePatternConfigLocator readFromPacket(FriendlyByteBuf buf) {
        return new FramePatternConfigLocator(ItemStack.OPTIONAL_STREAM_CODEC.decode((RegistryFriendlyByteBuf) buf));
    }

    /**
     * 注册到 MenuLocators 网络序列化注册表（幂等：重复注册抛异常，仅调用一次）。
     */
    public static void register() {
        MenuLocators.register(
                FramePatternConfigLocator.class,
                FramePatternConfigLocator::writeToPacket,
                FramePatternConfigLocator::readFromPacket);
    }

    @Override
    public String toString() {
        return "FramePatternConfigLocator[" + this.inputPattern + "]";
    }
}