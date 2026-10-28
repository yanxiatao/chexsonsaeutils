package git.chexson.chexsonsaeutils.network.codec;

import appeng.api.config.CpuSelectionMode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.guisync.DataSynchronization;
import appeng.menu.guisync.GuiSync;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.crafting.contract.CraftingCpuAmounts;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.crafting.status.PlanSummaryBytes;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu.CraftingCpuList;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu.CraftingCpuListEntry;
import git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
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
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.network.codec",
                () -> LargeCraftingWireCases.verify(temporaryRoot)
        );
    }

    /** LargeCraftingWireCases 的独立场景夹具，按类别统一执行。 */
    public static final class LargeCraftingWireCases {
        private static final AEItemKey KEY = AEItemKey.of(Items.PAPER);
        // 45001 位，超过旧二进制 4096 字节和默认 UTF 32767 字符两种边界。
        private static final BigInteger HUGE = BigInteger.TEN.pow(45000).add(BigInteger.valueOf(123));

        void cpuExactSnapshotRoundTripsAmountsLargerThan4096Bytes() {
            var amounts = new CraftingCpuAmounts(Map.of(KEY, HUGE), Map.of(KEY, HUGE.add(BigInteger.ONE)), Map.of(KEY, HUGE.shiftLeft(1)));
            var b = buffer();
            try {
                ExactCraftingCpuPayload.STREAM_CODEC.encode(b, new ExactCraftingCpuPayload(7, 19, amounts));
                var decoded = ExactCraftingCpuPayload.STREAM_CODEC.decode(b);
                assertEquals(amounts, decoded.amounts()); assertEquals(7, decoded.containerId()); assertEquals(19, decoded.cpuSerial());
                assertEquals(0, b.readableBytes());
            } finally { b.release(); }
        }

        void patternCountsAndLargeCountListsRoundTripThroughTheRealSummaryEntryCodec() {
            var entry = new CraftingPlanSummaryEntry(KEY, 0, 0, Long.MAX_VALUE);
            var enhanced = (EnhancedCraftingPlanSummaryEntry) (Object) entry;
            enhanced.chexsonsaeutils$setPatternTimes(List.of(HUGE));
            roundTripEntry(entry, List.of(HUGE));
            var many = java.util.Collections.nCopies(100001, BigInteger.ONE);
            enhanced.chexsonsaeutils$setPatternTimes(many);
            roundTripEntry(entry, many);
        }

        void cpuSelectionListDoesNotNarrowOrTruncateItsExactRemainingAmount() {
            var entry = new CraftingCpuListEntry(19, Long.MAX_VALUE, 3, null, CpuSelectionMode.ANY,
                    new GenericStack(KEY, Long.MAX_VALUE), HUGE, 0.5f, 12345L);
            var b = buffer();
            try {
                new CraftingCpuList(List.of(entry)).writeToPacket(b);
                assertEquals(List.of(entry), new CraftingCpuList(b).cpus());
                assertEquals(0, b.readableBytes());
            } finally { b.release(); }
        }

        void exactBytesAndFollowingFieldsStayAlignedBeyondDefaultStringLength() {
            var b = buffer();
            try {
                PlanSummaryBytes.write(b, HUGE); PlanSummaryBytes.write(b, null); b.writeInt(9876);
                assertEquals(HUGE, PlanSummaryBytes.read(b)); assertNull(PlanSummaryBytes.read(b)); assertEquals(9876, b.readInt());
                assertEquals(0, b.readableBytes());
            } finally { b.release(); }
        }

        void actualGuiSyncFrameworkTransmitsLargeWaitingTextAndUpdatesWithoutLosingOtherFields() {
            var server = new Fields(); var client = new Fields();
            var sender = new DataSynchronization(server); var receiver = new DataSynchronization(client);
            server.waiting = "测试材料😀\t123456789\n".repeat(4000); server.bytes = HUGE.toString();
            sync(sender, receiver);
            assertEquals(server.waiting, client.waiting); assertEquals(server.bytes, client.bytes); assertEquals(42, client.small);
            assertFalse(sender.hasChanges());
            server.waiting = ""; server.small = 99;
            sync(sender, receiver);
            assertEquals("", client.waiting); assertEquals(server.bytes, client.bytes); assertEquals(99, client.small);
        }

        void malformedLengthsAndCountsAreRejectedBeforeAllocatingTheirClaimedSize() {
            var b = buffer();
            try {
                b.writeVarInt(Integer.MAX_VALUE);
                assertThrows(DecoderException.class, () -> ExactAmountWire.read(b));
                b.clear(); b.writeVarInt(0);
                assertThrows(DecoderException.class, () -> ExactAmountWire.read(b));
                b.clear(); b.writeByteArray(new byte[]{(byte) 0xFF});
                assertThrows(IllegalArgumentException.class, () -> ExactAmountWire.read(b));
                b.clear(); b.writeInt(Integer.MAX_VALUE);
                assertThrows(DecoderException.class, () -> new CraftingCpuList(b));
                b.clear(); b.writeVarInt(Integer.MAX_VALUE);
                assertThrows(DecoderException.class, () -> ExactAmountWire.readCount(b));
                b.clear(); b.writeVarInt(Integer.MAX_VALUE);
                assertThrows(DecoderException.class, () -> PacketTextWire.read(b));
            } finally { b.release(); }
        }

        void largeServerRuleSnapshotsDoNotHitTheOldOneMiBCharacterLimit() {
            String json = "{\"说明\":\"" + "测试".repeat(600000) + "\"}";
            var payload = new git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesPayload(31, json);
            var b = buffer();
            try {
                git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesPayload.STREAM_CODEC.encode(b, payload);
                assertEquals(payload, git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesPayload.STREAM_CODEC.decode(b));
                assertEquals(0, b.readableBytes());
            } finally { b.release(); }
        }

        void longGraphProblemsCannotBreakTheOtherwiseEmptyOptionalGraphPacket() {
            var graph = new git.chexson.chexsonsaeutils.crafting.status.PlanGraph(List.of(), List.of(), "长原因".repeat(20000));
            var b = buffer();
            try { graph.write(b); assertEquals(graph, git.chexson.chexsonsaeutils.crafting.status.PlanGraph.read(b)); assertEquals(0, b.readableBytes()); }
            finally { b.release(); }
        }

        private static void roundTripEntry(CraftingPlanSummaryEntry entry, List<BigInteger> expected) {
            var b = buffer();
            try {
                entry.write(b);
                var decoded = CraftingPlanSummaryEntry.read(b);
                assertEquals(expected, ((EnhancedCraftingPlanSummaryEntry) (Object) decoded).chexsonsaeutils$patternTimes());
                assertEquals(0, b.readableBytes());
            } finally { b.release(); }
        }
        private static void sync(DataSynchronization sender, DataSynchronization receiver) {
            var b = buffer();
            try {
                sender.writeUpdate(b); receiver.readUpdate(b, new ShortOpenHashSet());
                // AE2 写 VarInt(-1) 作为尾标记，原生 readUpdate 读 short(-1) 后保留三个尾字节。
                assertEquals(3, b.readableBytes());
                assertEquals(255, b.readUnsignedByte()); assertEquals(255, b.readUnsignedByte()); assertEquals(15, b.readUnsignedByte());
            }
            finally { b.release(); }
        }
        public static final class Fields {
            @GuiSync(1) public String waiting = "";
            @GuiSync(2) public String bytes = "";
            @GuiSync(3) public int small = 42;
        }
        private static RegistryFriendlyByteBuf buffer() {
            return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("LargeCraftingWireCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("LargeCraftingWireCases.cpuExactSnapshotRoundTripsAmountsLargerThan4096Bytes", () -> new LargeCraftingWireCases().cpuExactSnapshotRoundTripsAmountsLargerThan4096Bytes()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LargeCraftingWireCases.patternCountsAndLargeCountListsRoundTripThroughTheRealSummaryEntryCodec", () -> new LargeCraftingWireCases().patternCountsAndLargeCountListsRoundTripThroughTheRealSummaryEntryCodec()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LargeCraftingWireCases.cpuSelectionListDoesNotNarrowOrTruncateItsExactRemainingAmount", () -> new LargeCraftingWireCases().cpuSelectionListDoesNotNarrowOrTruncateItsExactRemainingAmount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LargeCraftingWireCases.exactBytesAndFollowingFieldsStayAlignedBeyondDefaultStringLength", () -> new LargeCraftingWireCases().exactBytesAndFollowingFieldsStayAlignedBeyondDefaultStringLength()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LargeCraftingWireCases.actualGuiSyncFrameworkTransmitsLargeWaitingTextAndUpdatesWithoutLosingOtherFields", () -> new LargeCraftingWireCases().actualGuiSyncFrameworkTransmitsLargeWaitingTextAndUpdatesWithoutLosingOtherFields()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LargeCraftingWireCases.malformedLengthsAndCountsAreRejectedBeforeAllocatingTheirClaimedSize", () -> new LargeCraftingWireCases().malformedLengthsAndCountsAreRejectedBeforeAllocatingTheirClaimedSize()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LargeCraftingWireCases.largeServerRuleSnapshotsDoNotHitTheOldOneMiBCharacterLimit", () -> new LargeCraftingWireCases().largeServerRuleSnapshotsDoNotHitTheOldOneMiBCharacterLimit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LargeCraftingWireCases.longGraphProblemsCannotBreakTheOtherwiseEmptyOptionalGraphPacket", () -> new LargeCraftingWireCases().longGraphProblemsCannotBreakTheOtherwiseEmptyOptionalGraphPacket())
            );
        }
    }
}
