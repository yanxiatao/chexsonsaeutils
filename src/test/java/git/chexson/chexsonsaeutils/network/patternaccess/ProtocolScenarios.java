package git.chexson.chexsonsaeutils.network.patternaccess;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.core.network.clientbound.PatternAccessTerminalPacket;
import appeng.util.inv.AppEngInternalInventory;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import static org.junit.jupiter.api.Assertions.*;

/** 网络协议的行为场景；由类别测试统一调用。 */
public final class ProtocolScenarios {
    private ProtocolScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.network.patternaccess",
                () -> PatternAccessPacketBatchCases.verify(temporaryRoot)
        );
    }

    /** PatternAccessPacketBatchCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternAccessPacketBatchCases {
        private static final long INVENTORY = 4321L;
        private static final PatternContainerGroup GROUP = new PatternContainerGroup(null, Component.literal("测试极限装配机"), List.of());
        private final PatternAccessPacketBatch batches = new PatternAccessPacketBatchImpl();

        void reproducesThe4451SlotEncodingFailureBeforeSplitting() {
            var original = PatternAccessTerminalPacket.fullUpdate(INVENTORY, 5000, 77L, GROUP, slots(4451));
            var buffer = buffer();
            try { assertThrows(EncoderException.class, () -> PatternAccessTerminalPacket.STREAM_CODEC.encode(buffer, original)); }
            finally { buffer.release(); }
        }

        void allFullUpdateSlotsSurviveRealCodecRoundTripAndOnlyTheFirstPacketResetsInventory() {
            for (int size : new int[]{0, 1, 128, 129, 4096, 4451, 8192}) {
                var original = PatternAccessTerminalPacket.fullUpdate(INVENTORY, size + 7, 77L, GROUP, slots(size));
                var split = batches.split(original);
                assertEquals(Math.max(1, (size + 127) / 128), split.size());
                AppEngInternalInventory client = null;
                int receivedSlots = 0;
                for (int index = 0; index < split.size(); index++) {
                    var packet = roundTrip(split.get(index));
                    assertEquals(INVENTORY, packet.inventoryId());
                    assertTrue(packet.slots().size() <= 128);
                    assertEquals(index == 0, packet.fullUpdate());
                    if (packet.fullUpdate()) {
                        assertEquals(original.inventorySize(), packet.inventorySize());
                        assertEquals(original.sortBy(), packet.sortBy());
                        assertEquals(GROUP, packet.group());
                        client = new AppEngInternalInventory(packet.inventorySize());
                    }
                    assertNotNull(client);
                    for (var entry : packet.slots().int2ObjectEntrySet()) client.setItemDirect(entry.getIntKey(), entry.getValue());
                    receivedSlots += packet.slots().size();
                }
                assertEquals(size, receivedSlots);
                for (var entry : original.slots().int2ObjectEntrySet())
                    assertTrue(ItemStack.matches(entry.getValue(), client.getStackInSlot(entry.getIntKey())), "槽位 " + entry.getIntKey());
                assertTrue(client.getStackInSlot(size + 6).isEmpty());
            }
        }

        void incrementalUpdatesKeepEmptyStacksAndGlobalSlotNumbers() {
            var client = new AppEngInternalInventory(10000);
            var changes = new Int2ObjectLinkedOpenHashMap<ItemStack>();
            for (int i = 0; i < 4451; i++) {
                int slot = 2000 + i;
                client.setItemDirect(slot, new ItemStack(Items.STICK));
                changes.put(slot, i % 2 == 0 ? ItemStack.EMPTY : stack(i));
            }
            var packets = batches.split(PatternAccessTerminalPacket.incrementalUpdate(INVENTORY, changes));
            for (var part : packets) {
                var packet = roundTrip(part);
                assertFalse(packet.fullUpdate());
                assertEquals(INVENTORY, packet.inventoryId());
                for (var entry : packet.slots().int2ObjectEntrySet()) client.setItemDirect(entry.getIntKey(), entry.getValue());
            }
            for (var entry : changes.int2ObjectEntrySet()) assertTrue(ItemStack.matches(entry.getValue(), client.getStackInSlot(entry.getIntKey())));
        }

        void queuedBatchesAreNotChangedByLaterServerSlotMutations() {
            var slots = slots(129);
            var packet = PatternAccessTerminalPacket.fullUpdate(INVENTORY, 200, 77L, GROUP, slots);
            var split = batches.split(packet);
            slots.get(0).setCount(5);
            slots.get(128).set(DataComponents.DYED_COLOR, new DyedItemColor(9999, false));
            assertEquals(1, split.getFirst().slots().get(0).getCount());
            assertEquals(128, split.getLast().slots().get(128).get(DataComponents.DYED_COLOR).rgb());
        }

        void smallAndEmptyUpdatesPreserveTheirOriginalPacket() {
            var empty = PatternAccessTerminalPacket.fullUpdate(INVENTORY, 5000, 77L, GROUP, slots(0));
            assertSame(empty, batches.split(empty).getFirst());
            var small = PatternAccessTerminalPacket.incrementalUpdate(INVENTORY, slots(128));
            assertSame(small, batches.split(small).getFirst());
        }

        private static Int2ObjectMap<ItemStack> slots(int count) {
            var result = new Int2ObjectLinkedOpenHashMap<ItemStack>();
            for (int i = 0; i < count; i++) result.put(i, stack(i));
            return result;
        }
        private static ItemStack stack(int index) {
            var stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(index, false));
            return stack;
        }
        private static PatternAccessTerminalPacket roundTrip(PatternAccessTerminalPacket value) {
            var buffer = buffer();
            try {
                PatternAccessTerminalPacket.STREAM_CODEC.encode(buffer, value);
                var decoded = PatternAccessTerminalPacket.STREAM_CODEC.decode(buffer);
                assertEquals(0, buffer.readableBytes());
                return decoded;
            } finally { buffer.release(); }
        }
        private static RegistryFriendlyByteBuf buffer() {
            return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternAccessPacketBatchCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternAccessPacketBatchCases.reproducesThe4451SlotEncodingFailureBeforeSplitting", () -> new PatternAccessPacketBatchCases().reproducesThe4451SlotEncodingFailureBeforeSplitting()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternAccessPacketBatchCases.allFullUpdateSlotsSurviveRealCodecRoundTripAndOnlyTheFirstPacketResetsInventory", () -> new PatternAccessPacketBatchCases().allFullUpdateSlotsSurviveRealCodecRoundTripAndOnlyTheFirstPacketResetsInventory()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternAccessPacketBatchCases.incrementalUpdatesKeepEmptyStacksAndGlobalSlotNumbers", () -> new PatternAccessPacketBatchCases().incrementalUpdatesKeepEmptyStacksAndGlobalSlotNumbers()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternAccessPacketBatchCases.queuedBatchesAreNotChangedByLaterServerSlotMutations", () -> new PatternAccessPacketBatchCases().queuedBatchesAreNotChangedByLaterServerSlotMutations()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternAccessPacketBatchCases.smallAndEmptyUpdatesPreserveTheirOriginalPacket", () -> new PatternAccessPacketBatchCases().smallAndEmptyUpdatesPreserveTheirOriginalPacket())
            );
        }
    }
}
