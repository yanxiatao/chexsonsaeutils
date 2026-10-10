package git.chexson.chexsonsaeutils.network.crafting;

import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.bigint.WidePlanLedgerCodec;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WidePlanChunkPayloadTest {
    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @Test
    void ledgerBeyondSingleUtfLimitRoundTripsThroughActualPayloadCodec() {
        var keys = List.of(Items.STONE, Items.DIRT, Items.COAL, Items.IRON_INGOT, Items.GOLD_INGOT,
                Items.PAPER, Items.DIAMOND, Items.EMERALD, Items.REDSTONE);
        BigInteger amount = BigInteger.TEN.pow(4095);
        WideKeyCounter missing = new WideKeyCounter();
        for (var item : keys) missing.add(AEItemKey.of(item), amount);
        String encoded = WidePlanLedgerCodec.encode(WideLedgerSnapshot.capture(missing, null, null), REGISTRIES);
        assertTrue(encoded.length() > 32767);
        var chunks = WidePlanChunkPayload.split(7, 1, encoded);
        WidePlanFrame frame = new WidePlanFrameImpl();
        for (int i = chunks.size() - 1; i >= 0; i--) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
            try {
                WidePlanChunkPayload.STREAM_CODEC.encode(buf, chunks.get(i));
                var decoded = WidePlanChunkPayload.STREAM_CODEC.decode(buf);
                assertEquals(chunks.get(i), decoded);
                frame.accept(decoded.revision(), decoded.index(), decoded.count(), decoded.data());
                if (i > 0) assertEquals("", frame.completed(1));
            } finally {
                buf.release();
            }
        }
        assertEquals(encoded, frame.completed(1));
        var snapshot = WidePlanLedgerCodec.decode(frame.completed(1), REGISTRIES);
        for (var item : keys) assertEquals(amount, snapshot.get(WideAmountKind.MISSING, AEItemKey.of(item)));
    }

    @Test
    void newerPlanCannotMixWithOldOrIncompleteFramesAndEmptyPlanClearsAmounts() {
        WidePlanFrame frame = new WidePlanFrameImpl();
        frame.accept(1, 0, 1, "old");
        assertEquals("old", frame.completed(1));
        frame.accept(2, 1, 2, "right");
        assertEquals("", frame.completed(1));
        assertEquals("", frame.completed(2));
        frame.accept(1, 0, 1, "old");
        frame.accept(2, 0, 2, "left");
        frame.accept(2, 0, 2, "left");
        assertEquals("leftright", frame.completed(2));
        assertEquals("", frame.completed(3));
        var empty = WidePlanChunkPayload.split(7, 3, "").getFirst();
        frame.accept(empty.revision(), empty.index(), empty.count(), empty.data());
        frame.accept(2, 1, 2, "right");
        assertEquals("", frame.completed(3));
    }

    @Test
    void malformedOrConflictingChunksFailImmediately() {
        assertThrows(IllegalArgumentException.class, () -> new WidePlanChunkPayload(7, 1, 1, 1, "x"));
        assertThrows(IllegalArgumentException.class, () -> new WidePlanChunkPayload(7, 1, 0, 0, "x"));
        assertThrows(IllegalArgumentException.class, () -> new WidePlanChunkPayload(7, 1, 0, 1,
                "x".repeat(WidePlanChunkPayload.CHUNK_LENGTH + 1)));
        WidePlanFrame frame = new WidePlanFrameImpl();
        frame.accept(1, 0, 2, "left");
        assertThrows(IllegalArgumentException.class, () -> frame.accept(1, 0, 2, "other"));
        assertThrows(IllegalArgumentException.class, () -> frame.accept(1, 1, 3, "right"));
    }
}
