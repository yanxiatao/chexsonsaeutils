package git.chexson.chexsonsaeutils.menu.custompatternconfig;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogicHost;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import static org.junit.jupiter.api.Assertions.*;

public final class ConfigLocatorScenarios {
    private ConfigLocatorScenarios() {}

    public static void verify(Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("定制样板编码定位",
                ConfigLocatorScenarios::blockAndPanelLocatorsRoundTrip,
                ConfigLocatorScenarios::eachSideResolvesItsOwnLivePanel);
    }

    private static void blockAndPanelLocatorsRoundTrip() {
        var pos = new BlockPos(17, 63, -28);
        for (Direction side : new Direction[]{null, Direction.NORTH, Direction.EAST}) {
            var original = new CustomPatternConfigLocator(pos, Level.NETHER, 108, side);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                CustomPatternConfigLocator.writeToPacket(original, buffer);
                var restored = CustomPatternConfigLocator.readFromPacket(buffer);
                assertEquals(original.toString(), restored.toString());
                assertEquals(0, buffer.readableBytes());
                var host = restored.locate(null, CustomPatternConfigHost.class);
                assertEquals(pos, host.getPos());
                assertEquals(108, host.getPatternSlotIndex());
                assertEquals(side, host.getPartSide());
            } finally {
                buffer.release();
            }
        }
    }

    private static void eachSideResolvesItsOwnLivePanel() {
        var parts = new EnumMap<Direction, IPart>(Direction.class);
        var north = providerPart();
        var east = providerPart();
        parts.put(Direction.NORTH, north);
        parts.put(Direction.EAST, east);
        var cable = (IPartHost) Proxy.newProxyInstance(IPartHost.class.getClassLoader(),
                new Class<?>[]{IPartHost.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPart")) return parts.get((Direction) args[0]);
                    throw new AssertionError("Unexpected cable call: " + method.getName());
                });
        assertSame(north, CustomPatternConfigHost.resolvePartProvider(cable, Direction.NORTH));
        assertSame(east, CustomPatternConfigHost.resolvePartProvider(cable, Direction.EAST));
        assertNull(CustomPatternConfigHost.resolvePartProvider(cable, Direction.SOUTH));
        parts.remove(Direction.NORTH);
        assertNull(CustomPatternConfigHost.resolvePartProvider(cable, Direction.NORTH), "拆掉面板但保留线缆后不得复用旧面板引用");
        assertSame(east, CustomPatternConfigHost.resolvePartProvider(cable, Direction.EAST));
    }

    private static IPart providerPart() {
        return (IPart) Proxy.newProxyInstance(IPart.class.getClassLoader(),
                new Class<?>[]{IPart.class, CustomPatternProviderLogicHost.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) return "provider panel";
                    throw new AssertionError("Unexpected panel call: " + method.getName());
                });
    }
}
