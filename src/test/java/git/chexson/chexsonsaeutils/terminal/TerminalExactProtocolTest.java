package git.chexson.chexsonsaeutils.terminal;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import git.chexson.chexsonsaeutils.terminal.sync.*;
import git.chexson.chexsonsaeutils.terminal.display.TerminalAmountFormatterImpl;
import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.Items;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class TerminalExactProtocolTest {
    @Test void fluidFrameAndCrossTypeAmountSortKeepExactUnitRatios() {
        var registry = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var fluid = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
        var item = AEItemKey.of(Items.COPPER_INGOT); var huge = BigInteger.TEN.pow(100);
        var fluidEntry = new TerminalEntry(0, fluid, StorageAmount.finite(huge.multiply(BigInteger.valueOf(fluid.getAmountPerUnit())).add(BigInteger.ONE)), false, null);
        var itemEntry = new TerminalEntry(1, item, StorageAmount.finite(huge), false, null);
        var frame = new TerminalFrame(1, 0, true, true, true, List.of(fluidEntry, itemEntry), List.of());
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        try { TerminalFrameCodec.write(buf, frame); assertEquals(frame, TerminalFrameCodec.read(buf)); }
        finally { buf.release(); }
        var nativeFluid = new appeng.menu.me.common.GridInventoryEntry(0, fluid, Long.MAX_VALUE, 0, false);
        var nativeItem = new appeng.menu.me.common.GridInventoryEntry(1, item, Long.MAX_VALUE, 0, false);
        assertTrue(git.chexson.chexsonsaeutils.terminal.display.TerminalAmountComparison.compare(
                fluidEntry, nativeFluid, itemEntry, nativeItem) > 0);
        var formatter = new TerminalAmountFormatterImpl();
        assertEquals("1", formatter.compact(fluid, StorageAmount.finite(BigInteger.valueOf(fluid.getAmountPerUnit()))));
        assertEquals("0.001 " + fluid.getUnitSymbol(), formatter.full(fluid, StorageAmount.finite(BigInteger.ONE)));
        assertTrue(formatter.full(fluid, fluidEntry.amount()).contains(".001"));
    }
    @Test void exactAmountsRoundTripAcrossLongBoundary() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (var value : List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.ONE),
                    BigInteger.valueOf(Long.MAX_VALUE), BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), BigInteger.TEN.pow(100))) {
                TerminalFrameCodec.writeAmount(buf, StorageAmount.finite(value));
                assertEquals(value, TerminalFrameCodec.readAmount(buf).value());
            }
            TerminalFrameCodec.writeAmount(buf, StorageAmount.infinite()); assertTrue(TerminalFrameCodec.readAmount(buf).unbounded());
        } finally { buf.release(); }
    }
    @Test void rejectsNegativeAndNonCanonicalQuantities() {
        assertThrows(IllegalArgumentException.class, () -> StorageAmount.finite(BigInteger.ONE.negate()));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try { buf.writeByte(1); buf.writeByteArray(new byte[]{0, 1});
            assertThrows(IllegalArgumentException.class, () -> TerminalFrameCodec.readAmount(buf)); }
        finally { buf.release(); }
    }
    @Test void chunksCommitOnlyWhenCompleteAndRejectMissingChunk() {
        var assembler = new TerminalFrameAssembler();
        assertNull(assembler.accept(1, 0, 2, new byte[]{1, 2}));
        assertArrayEquals(new byte[]{1, 2, 3}, assembler.accept(1, 1, 2, new byte[]{3}));
        assertThrows(IllegalStateException.class, () -> assembler.accept(2, 1, 2, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(2, 0, 1, new byte[TerminalFrameCodec.CHUNK_BYTES + 1]));
    }
    @Test void appliesExactIncrementAndRemovesStoredButRetainsCraftable() {
        var view = new TerminalInventoryViewImpl(); var key = AEItemKey.of(Items.COPPER_INGOT);
        var huge = BigInteger.TEN.pow(100);
        assertTrue(view.apply(new TerminalFrame(1, 0, true, true, true,
                List.of(new TerminalEntry(3, key, StorageAmount.finite(huge), true, null)), List.of())));
        assertTrue(view.apply(new TerminalFrame(2, 1, false, true, true,
                List.of(new TerminalEntry(3, key, StorageAmount.finite(huge.subtract(BigInteger.valueOf(64))), true, null)), List.of())));
        assertEquals(huge.subtract(BigInteger.valueOf(64)), view.entries().iterator().next().amount().value());
        assertFalse(view.apply(new TerminalFrame(4, 3, false, true, true, List.of(), List.of(3))));
        assertEquals(1, view.entries().size());
        assertTrue(view.apply(new TerminalFrame(3, 2, false, true, true,
                List.of(new TerminalEntry(3, key, StorageAmount.ZERO, true, null)), List.of())));
        assertTrue(view.entries().iterator().next().craftable());
        assertTrue(view.apply(new TerminalFrame(4, 3, false, false, false, List.of(), List.of(3))));
        assertTrue(view.entries().isEmpty()); assertFalse(view.active());
    }
    @Test void sortingAndFormattingNeverNarrowAdjacentHugeIntegers() {
        var huge = BigInteger.TEN.pow(100); var formatter = new TerminalAmountFormatterImpl();
        assertTrue(StorageAmount.finite(huge).compareTo(StorageAmount.finite(huge.add(BigInteger.ONE))) < 0);
        assertEquals("1.00e100", formatter.compact(StorageAmount.finite(huge)));
        assertEquals("9,223,372,036,854,775,808", formatter.full(StorageAmount.finite(BigInteger.ONE.shiftLeft(63))));
    }
    @Test void fullFrameWithKeysAndLargeQuantityRoundTripsAcrossChunks() {
        var registry = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        var value = BigInteger.ONE.shiftLeft(200_000).add(BigInteger.valueOf(64));
        var frame = new TerminalFrame(1, 0, true, true, false, List.of(
                new TerminalEntry(0, AEItemKey.of(Items.COPPER_INGOT), StorageAmount.finite(value), false, Long.MAX_VALUE),
                new TerminalEntry(1, AEItemKey.of(Items.GOLD_INGOT), StorageAmount.ZERO, true, null)), List.of());
        try {
            TerminalFrameCodec.write(buf, frame);
            var assembler = new TerminalFrameAssembler();
            int count = (buf.readableBytes() + TerminalFrameCodec.CHUNK_BYTES - 1) / TerminalFrameCodec.CHUNK_BYTES;
            assertTrue(count > 1);
            byte[] complete = null;
            for (int index = 0; index < count; index++) {
                var data = new byte[Math.min(buf.readableBytes(), TerminalFrameCodec.CHUNK_BYTES)]; buf.readBytes(data);
                complete = assembler.accept(1, index, count, data);
                if (index + 1 < count) assertNull(complete);
            }
            var decoded = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(complete), registry);
            try { assertEquals(frame, TerminalFrameCodec.read(decoded)); } finally { decoded.release(); }
        } finally { buf.release(); }
    }
    @Test void inconsistentChunkCountAndDuplicateKeyLeaveCommittedStateUntouched() {
        var assembler = new TerminalFrameAssembler();
        assembler.accept(1, 0, 3, new byte[]{1});
        assertThrows(IllegalStateException.class, () -> assembler.accept(1, 1, 2, new byte[]{2}));
        var view = new TerminalInventoryViewImpl(); var key = AEItemKey.of(Items.COPPER_INGOT);
        var entry = new TerminalEntry(0, key, StorageAmount.finite(BigInteger.ONE), false, null);
        assertTrue(view.apply(new TerminalFrame(1, 0, true, true, true, List.of(entry), List.of())));
        assertThrows(IllegalArgumentException.class, () -> view.apply(new TerminalFrame(2, 1, false, true, true,
                List.of(new TerminalEntry(1, key, StorageAmount.finite(BigInteger.TWO), false, null)), List.of())));
        assertEquals(List.of(entry), List.copyOf(view.entries())); assertEquals(1, view.revision());
    }

}
