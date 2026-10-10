package git.chexson.chexsonsaeutils.network.crafting;

import git.chexson.chexsonsaeutils.crafting.request.CraftingAmountExpressionImpl;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WideCraftRequestPayloadTest {
    @Test void expressionsRemainExactAcrossOrderAndRestoreProtocols() {
        var expression = new CraftingAmountExpressionImpl();
        for (var input : new String[] {"1E19", "1E20", "1e19", "1e20", "1e100-1"}) {
            var amount = expression.parse(input, 1).orElseThrow();
            for (boolean mode : new boolean[] {false, true}) {
                var request = new WideCraftRequestPayload(37, amount, true, true, mode);
                var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    WideCraftRequestPayload.STREAM_CODEC.encode(buf, request);
                    assertEquals(request, WideCraftRequestPayload.STREAM_CODEC.decode(buf));
                    assertEquals(0, buf.readableBytes());
                    buf.clear();
                    var restore = new CraftingAmountRestorePayload(38, amount, true);
                    CraftingAmountRestorePayload.STREAM_CODEC.encode(buf, restore);
                    assertEquals(restore, CraftingAmountRestorePayload.STREAM_CODEC.decode(buf));
                    assertEquals(0, buf.readableBytes());
                } finally { buf.release(); }
            }
        }
    }
    @Test void fluidUnitsAreConvertedOnceBeforeTransmission() {
        var expression = new CraftingAmountExpressionImpl();
        var amount = expression.parse("1e19", 1000).orElseThrow();
        var request = new WideCraftRequestPayload(9, amount, false, false, false);
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            WideCraftRequestPayload.STREAM_CODEC.encode(buf, request);
            var received = WideCraftRequestPayload.STREAM_CODEC.decode(buf);
            assertEquals(BigInteger.TEN.pow(22), received.amount());
            assertEquals("10000000000000000000", expression.format(received.amount(), 1000));
        } finally { buf.release(); }
    }
    @Test void rejectsInvalidAmountsRatherThanWrappingToLong() {
        assertThrows(IllegalArgumentException.class, () -> new WideCraftRequestPayload(1, BigInteger.ZERO, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new WideCraftRequestPayload(1, BigInteger.ONE.negate(), false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new WideCraftRequestPayload(1, BigInteger.TEN.pow(4096), false, false, false));
    }
}
