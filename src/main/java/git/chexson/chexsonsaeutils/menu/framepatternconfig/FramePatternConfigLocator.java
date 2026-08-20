package git.chexson.chexsonsaeutils.menu.framepatternconfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import appeng.menu.locator.MenuHostLocator;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;

/**
 * 框架样板编码 GUI 的菜单定位器（MenuHostLocator 实现）。
 * <p>
 * 动机：编码 GUI 直接编辑供应器样板槽中的原样板（原地转换，无样板副本），
 * 宿主需要定位到打开它的供应器：携带供应器位置（BlockPos + 维度）与
 * 样板槽序号（patternSlotIndex）。网络序列化通过
 * {@link MenuLocators#register} 注册（类名 + 读写器），位置用
 * FriendlyByteBuf 的 writeBlockPos/writeResourceKey 读写（与
 * {@link git.chexson.chexsonsaeutils.menu.framepatternupgrade.FramePatternUpgradeLocator}
 * 一致）。注册时机：Chexsonsaeutils 主类构造器。
 */
public class FramePatternConfigLocator implements MenuHostLocator {

    private final BlockPos pos;
    private final ResourceKey<Level> dimension;
    private final int patternSlotIndex;

    /**
     * @param pos             供应器方块位置
     * @param dimension       供应器所在维度
     * @param patternSlotIndex 供应器样板槽序号（patternInventory 内索引）
     */
    public FramePatternConfigLocator(BlockPos pos, ResourceKey<Level> dimension, int patternSlotIndex) {
        this.pos = pos;
        this.dimension = dimension;
        this.patternSlotIndex = patternSlotIndex;
    }

    @Override
    public <T> T locate(Player player, Class<T> hostInterface) {
        if (hostInterface != FramePatternConfigHost.class) {
            return null;
        }
        // 宿主缺失（方块被拆）时由 Host 内部延迟定位处理（getProvider 返回 null，
        // 菜单逻辑 Fail Fast），locate 本身只负责构造宿主
        return hostInterface.cast(new FramePatternConfigHost(this.pos, this.dimension, this.patternSlotIndex));
    }

    public static void writeToPacket(FramePatternConfigLocator locator, FriendlyByteBuf buf) {
        buf.writeBlockPos(locator.pos);
        buf.writeResourceKey(locator.dimension);
        buf.writeInt(locator.patternSlotIndex);
    }

    public static FramePatternConfigLocator readFromPacket(FriendlyByteBuf buf) {
        return new FramePatternConfigLocator(
                buf.readBlockPos(),
                buf.readResourceKey(Registries.DIMENSION),
                buf.readInt());
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
        return "FramePatternConfigLocator[" + this.dimension + " " + this.pos
                + " slot=" + this.patternSlotIndex + "]";
    }
}