package git.chexson.chexsonsaeutils.network.crafting;

import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.bigint.WidePlanLedgerCodec;
import git.chexson.chexsonsaeutils.crafting.request.CraftingAmountExpressionImpl;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.*;

/** 网络协议的行为场景；由类别测试统一调用。 */
public final class ProtocolScenarios {
    private ProtocolScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.network.crafting",
                () -> WideCraftRequestPayloadCases.verify(temporaryRoot),
                () -> WidePlanChunkPayloadCases.verify(temporaryRoot)
        );
    }

    /** WideCraftRequestPayloadCases 的独立场景夹具，按类别统一执行。 */
    public static final class WideCraftRequestPayloadCases {
        void expressionsRemainExactAcrossOrderAndRestoreProtocols() {
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
        void fluidUnitsAreConvertedOnceBeforeTransmission() {
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
        void rejectsInvalidAmountsRatherThanWrappingToLong() {
            assertThrows(IllegalArgumentException.class, () -> new WideCraftRequestPayload(1, BigInteger.ZERO, false, false, false));
            assertThrows(IllegalArgumentException.class, () -> new WideCraftRequestPayload(1, BigInteger.ONE.negate(), false, false, false));
            assertThrows(IllegalArgumentException.class, () -> new WideCraftRequestPayload(1, BigInteger.TEN.pow(4096), false, false, false));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WideCraftRequestPayloadCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftRequestPayloadCases.expressionsRemainExactAcrossOrderAndRestoreProtocols", () -> new WideCraftRequestPayloadCases().expressionsRemainExactAcrossOrderAndRestoreProtocols()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftRequestPayloadCases.fluidUnitsAreConvertedOnceBeforeTransmission", () -> new WideCraftRequestPayloadCases().fluidUnitsAreConvertedOnceBeforeTransmission()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WideCraftRequestPayloadCases.rejectsInvalidAmountsRatherThanWrappingToLong", () -> new WideCraftRequestPayloadCases().rejectsInvalidAmountsRatherThanWrappingToLong())
            );
        }
    }

    /** WidePlanChunkPayloadCases 的独立场景夹具，按类别统一执行。 */
    public static final class WidePlanChunkPayloadCases {
        private static final RegistryAccess REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("WidePlanChunkPayloadCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePlanChunkPayloadCases.ledgerBeyondSingleUtfLimitRoundTripsThroughActualPayloadCodec", () -> new WidePlanChunkPayloadCases().ledgerBeyondSingleUtfLimitRoundTripsThroughActualPayloadCodec()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePlanChunkPayloadCases.newerPlanCannotMixWithOldOrIncompleteFramesAndEmptyPlanClearsAmounts", () -> new WidePlanChunkPayloadCases().newerPlanCannotMixWithOldOrIncompleteFramesAndEmptyPlanClearsAmounts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("WidePlanChunkPayloadCases.malformedOrConflictingChunksFailImmediately", () -> new WidePlanChunkPayloadCases().malformedOrConflictingChunksFailImmediately())
            );
        }
    }
}
