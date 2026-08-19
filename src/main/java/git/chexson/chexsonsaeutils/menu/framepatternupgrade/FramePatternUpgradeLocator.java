package git.chexson.chexsonsaeutils.menu.framepatternupgrade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import appeng.api.parts.IPartHost;
import appeng.menu.locator.MenuHostLocator;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogicHost;

/**
 * 扩容 GUI 的菜单定位器（MenuHostLocator 实现）。
 * <p>
 * 动机：扩容 GUI 直接作用于打开它的供应器（方块实体或面板），宿主不是瞬态对象，
 * 需要携带宿主位置（BlockPos + 维度 + 可选面板方向）在服务端与客户端两侧解析宿主。
 * 面板方向（partSide）非空表示宿主是附着于线缆的
 * {@link git.chexson.chexsonsaeutils.parts.custompatternprovider.CustomPatternProviderPart}
 * （经 IPartHost 解析），为空表示宿主是方块实体。
 * 注册时机：Chexsonsaeutils 主类构造器（与 {@link git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigLocator}
 * 一致）。
 */
public class FramePatternUpgradeLocator implements MenuHostLocator {

    private final BlockPos pos;
    private final ResourceKey<Level> dimension;
    private final Direction partSide;

    public FramePatternUpgradeLocator(BlockPos pos, ResourceKey<Level> dimension, Direction partSide) {
        this.pos = pos;
        this.dimension = dimension;
        this.partSide = partSide;
    }

    @Override
    public <T> T locate(Player player, Class<T> hostInterface) {
        if (hostInterface != FramePatternUpgradeHost.class) {
            return null;
        }
        var blockEntity = player.level().getBlockEntity(this.pos);
        FramePatternProviderLogicHost holder = null;
        if (this.partSide != null && blockEntity instanceof IPartHost partHost) {
            var part = partHost.getPart(this.partSide);
            if (part instanceof FramePatternProviderLogicHost logicHost) {
                holder = logicHost;
            }
        } else if (blockEntity instanceof FramePatternProviderLogicHost logicHost) {
            holder = logicHost;
        }
        if (holder == null) {
            // 宿主缺失（方块被拆/面板被移除）：菜单无法打开，Fail Fast
            return null;
        }
        return hostInterface.cast(new FramePatternUpgradeHost(holder));
    }

    public static void writeToPacket(FramePatternUpgradeLocator locator, FriendlyByteBuf buf) {
        buf.writeBlockPos(locator.pos);
        buf.writeResourceKey(locator.dimension);
        buf.writeNullable(locator.partSide, FriendlyByteBuf::writeEnum);
    }

    public static FramePatternUpgradeLocator readFromPacket(FriendlyByteBuf buf) {
        return new FramePatternUpgradeLocator(
                buf.readBlockPos(),
                buf.readResourceKey(Registries.DIMENSION),
                buf.readNullable(b -> b.readEnum(Direction.class)));
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
        return "FramePatternUpgradeLocator[" + this.dimension + " " + this.pos
                + (this.partSide != null ? " side=" + this.partSide : "") + "]";
    }
}