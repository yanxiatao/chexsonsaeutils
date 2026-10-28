package git.chexson.chexsonsaeutils.terminal;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountCollector;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.*;
import git.chexson.chexsonsaeutils.terminal.display.TerminalAmountFormatterImpl;
import git.chexson.chexsonsaeutils.terminal.display.TerminalTooltipLinesImpl;
import git.chexson.chexsonsaeutils.terminal.storage.*;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import git.chexson.chexsonsaeutils.terminal.sync.*;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import static org.junit.jupiter.api.Assertions.*;

/** 存储与终端的行为场景；由类别测试统一调用。 */
public final class StorageScenarios {
    private StorageScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.terminal",
                () -> NeoEcoInfinityCellDisplayCases.verify(temporaryRoot),
                () -> TerminalExactProtocolCases.verify(temporaryRoot),
                () -> TerminalStorageAggregationCases.verify(temporaryRoot),
                () -> TerminalTooltipLinesCases.verify(temporaryRoot)
        );
    }

    /** NeoEcoInfinityCellDisplayCases 的独立场景夹具，按类别统一执行。 */
    public static final class NeoEcoInfinityCellDisplayCases {
        void nativeCollectorReadsExactItemsAndFluidsAfterSaturatedDecrement() {
            var cell = new NeoEcoInfinityCellInventory(new ItemStack(Items.STICK), null);
            var item = AEItemKey.of(Items.COPPER_INGOT);
            var fluid = AEFluidKey.of(Fluids.WATER);
            for (var key : List.of(item, fluid)) {
                cell.insert(key, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
                cell.insert(key, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
            }
            cell.extract(fluid, 64, Actionable.MODULATE, IActionSource.empty());
            ExactAmountCollector.begin();
            try {
                ExactAmountCollector.observe(cell, cell.getAvailableStacks());
                var exact = ExactAmountCollector.finish();
                var twice = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
                assertEquals(twice, exact.get(item).value());
                assertEquals(twice.subtract(BigInteger.valueOf(64)), exact.get(fluid).value());
                assertFalse(exact.get(item).infinite());
                assertEquals(Long.MAX_VALUE, cell.getAvailableStacks().get(fluid));
                var neo = new NeoEcoExactStorageAdapterImpl();
                assertFalse(neo.supports(cell));
                var network = new NeoEcoNetworkExactInventoryImpl();
                assertTrue(network.observesExactAmounts(cell));
                var baseline = network.readFrom(new appeng.api.storage.MEStorage() {
                    @Override public net.minecraft.network.chat.Component getDescription() { return cell.getDescription(); }
                    @Override public void getAvailableStacks(appeng.api.stacks.KeyCounter out) {
                        var contribution = cell.getAvailableStacks();
                        ExactAmountCollector.observe(cell, contribution);
                        out.addAll(contribution);
                    }
                });
                var reader = new TerminalInventoryReaderImpl(List.of(), List.of(new CellExactStorageAdapterImpl(), neo), network);
                assertEquals(baseline, reader.reconcile(baseline, List.of(new StorageMount(cell, cell, cell)), null));
                assertEquals(twice, reader.aggregate(List.of(new StorageMount(cell, cell, cell)), null).get(item).value());
            } finally {
                ExactAmountCollector.abort();
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("NeoEcoInfinityCellDisplayCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("NeoEcoInfinityCellDisplayCases.nativeCollectorReadsExactItemsAndFluidsAfterSaturatedDecrement", () -> new NeoEcoInfinityCellDisplayCases().nativeCollectorReadsExactItemsAndFluidsAfterSaturatedDecrement())
            );
        }
    }

    /** TerminalExactProtocolCases 的独立场景夹具，按类别统一执行。 */
    public static final class TerminalExactProtocolCases {
        void fluidFrameAndCrossTypeAmountSortKeepExactUnitRatios() {
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
        void exactAmountsRoundTripAcrossLongBoundary() {
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
        void rejectsNegativeAndNonCanonicalQuantities() {
            assertThrows(IllegalArgumentException.class, () -> StorageAmount.finite(BigInteger.ONE.negate()));
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            try { buf.writeByte(1); buf.writeByteArray(new byte[]{0, 1});
                assertThrows(IllegalArgumentException.class, () -> TerminalFrameCodec.readAmount(buf)); }
            finally { buf.release(); }
        }
        void chunksCommitOnlyWhenCompleteAndRejectMissingChunk() {
            var assembler = new TerminalFrameAssembler();
            assertNull(assembler.accept(1, 0, 2, new byte[]{1, 2}));
            assertArrayEquals(new byte[]{1, 2, 3}, assembler.accept(1, 1, 2, new byte[]{3}));
            assertThrows(IllegalStateException.class, () -> assembler.accept(2, 1, 2, new byte[]{1}));
            assertThrows(IllegalArgumentException.class, () -> assembler.accept(2, 0, 1, new byte[TerminalFrameCodec.CHUNK_BYTES + 1]));
        }
        void appliesExactIncrementAndRemovesStoredButRetainsCraftable() {
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
        void sortingAndFormattingNeverNarrowAdjacentHugeIntegers() {
            var huge = BigInteger.TEN.pow(100); var formatter = new TerminalAmountFormatterImpl();
            assertTrue(StorageAmount.finite(huge).compareTo(StorageAmount.finite(huge.add(BigInteger.ONE))) < 0);
            assertEquals("1.00e100", formatter.compact(StorageAmount.finite(huge)));
            assertEquals("9,223,372,036,854,775,808", formatter.full(StorageAmount.finite(BigInteger.ONE.shiftLeft(63))));
        }
        void fullFrameWithKeysAndLargeQuantityRoundTripsAcrossChunks() {
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
        void inconsistentChunkCountAndDuplicateKeyLeaveCommittedStateUntouched() {
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("TerminalExactProtocolCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalExactProtocolCases.fluidFrameAndCrossTypeAmountSortKeepExactUnitRatios", () -> new TerminalExactProtocolCases().fluidFrameAndCrossTypeAmountSortKeepExactUnitRatios()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalExactProtocolCases.exactAmountsRoundTripAcrossLongBoundary", () -> new TerminalExactProtocolCases().exactAmountsRoundTripAcrossLongBoundary()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalExactProtocolCases.rejectsNegativeAndNonCanonicalQuantities", () -> new TerminalExactProtocolCases().rejectsNegativeAndNonCanonicalQuantities()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalExactProtocolCases.chunksCommitOnlyWhenCompleteAndRejectMissingChunk", () -> new TerminalExactProtocolCases().chunksCommitOnlyWhenCompleteAndRejectMissingChunk()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalExactProtocolCases.appliesExactIncrementAndRemovesStoredButRetainsCraftable", () -> new TerminalExactProtocolCases().appliesExactIncrementAndRemovesStoredButRetainsCraftable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalExactProtocolCases.sortingAndFormattingNeverNarrowAdjacentHugeIntegers", () -> new TerminalExactProtocolCases().sortingAndFormattingNeverNarrowAdjacentHugeIntegers()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalExactProtocolCases.fullFrameWithKeysAndLargeQuantityRoundTripsAcrossChunks", () -> new TerminalExactProtocolCases().fullFrameWithKeysAndLargeQuantityRoundTripsAcrossChunks()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalExactProtocolCases.inconsistentChunkCountAndDuplicateKeyLeaveCommittedStateUntouched", () -> new TerminalExactProtocolCases().inconsistentChunkCountAndDuplicateKeyLeaveCommittedStateUntouched())
            );
        }
    }

    /** TerminalStorageAggregationCases 的独立场景夹具，按类别统一执行。 */
    public static final class TerminalStorageAggregationCases {
        private static final AEKey COPPER = AEItemKey.of(Items.COPPER_INGOT);
        private static final AEKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
        private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
        private final TerminalInventoryReaderImpl reader = new TerminalInventoryReaderImpl(
                List.of(), List.of(new CellExactStorageAdapterImpl()));

        private InfinityCellInventory cell() {
            var cell = new InfinityCellInventory(new ItemStack(Items.STICK), null);
            cell.insert(COPPER, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
            cell.insert(COPPER, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
            cell.insert(GOLD, 7, Actionable.MODULATE, IActionSource.empty());
            return cell;
        }
        void realCellsSumBeyondLongAndExposeSaturatedDecrement() {
            var a = cell(); var b = cell();
            var mounts = List.of(new StorageMount(a, a, a), new StorageMount(b, b, b));
            assertEquals(MAX.multiply(BigInteger.valueOf(4)), reader.aggregate(mounts, null).get(COPPER).value());
            var saved = a.readExactSnapshot();
            a.extract(COPPER, 64, Actionable.MODULATE, IActionSource.empty());
            assertEquals(Long.MAX_VALUE, a.getAvailableStacks().get(COPPER));
            assertEquals(MAX.multiply(BigInteger.valueOf(4)).subtract(BigInteger.valueOf(64)),
                    reader.aggregate(mounts, null).get(COPPER).value());
            assertEquals(MAX.multiply(BigInteger.TWO), saved.amount(COPPER));
        }
        void adapterReusesUnchangedRevisionAndInvalidatesAfterExactChange() {
            var cell = cell(); var adapter = new CellExactStorageAdapterImpl();
            var first = adapter.read(cell, null);
            assertSame(first, adapter.read(cell, null));
            cell.extract(COPPER, 64, Actionable.MODULATE, IActionSource.empty());
            var changed = adapter.read(cell, null);
            assertNotSame(first, changed);
            assertEquals(first.get(COPPER).value().subtract(BigInteger.valueOf(64)), changed.get(COPPER).value());
        }
        void wrapperFiltersKeysAndDuplicateDiscoveryDoesNotCountTwice() {
            var cell = cell(); var visible = visible(Map.of(COPPER, Long.MAX_VALUE));
            var mount = new StorageMount(cell, cell, visible);
            var amounts = reader.aggregate(List.of(mount, mount), null);
            assertEquals(MAX.multiply(BigInteger.TWO), amounts.get(COPPER).value());
            assertFalse(amounts.containsKey(GOLD));
            assertEquals(7, cell.getAvailableStacks().get(GOLD));
        }
        void quantityTransformingWrapperAndAdapterConflictRejectInsteadOfLying() {
            var cell = cell(); var bad = new StorageMount(cell, cell, visible(Map.of(COPPER, 5L)));
            assertThrows(IllegalStateException.class, () -> reader.aggregate(List.of(bad), null));
            var conflicting = new TerminalInventoryReaderImpl(List.of(),
                    List.of(new CellExactStorageAdapterImpl(), new CellExactStorageAdapterImpl()));
            assertThrows(IllegalStateException.class, () -> conflicting.aggregate(List.of(new StorageMount(cell, cell, cell)), null));
        }
        void reconcileUsesUnsaturatedPerMountBaselineAndKeepsOtherSources() {
            var cell = cell(); var mount = new StorageMount(cell, cell, cell);
            var domain = BigInteger.TEN.pow(100);
            var baseline = Map.of(COPPER, StorageAmount.finite(domain.add(MAX).add(BigInteger.valueOf(64))),
                    GOLD, StorageAmount.finite(BigInteger.valueOf(7)));
            var result = reader.reconcile(baseline, List.of(mount), null);
            assertEquals(domain.add(MAX.multiply(BigInteger.TWO)).add(BigInteger.valueOf(64)), result.get(COPPER).value());
            assertEquals(domain.add(MAX).add(BigInteger.valueOf(64)), baseline.get(COPPER).value());
            assertEquals(StorageAmount.infinite(), reader.reconcile(Map.of(COPPER, StorageAmount.infinite(),
                    GOLD, StorageAmount.finite(BigInteger.valueOf(7))), List.of(mount), null).get(COPPER));
        }
        void unknownInventoryDoesNotBecomeConfirmedLongQuantity() {
            var unknown = visible(Map.of(COPPER, Long.MAX_VALUE));
            assertTrue(reader.aggregate(List.of(new StorageMount(unknown, unknown, unknown)), null).isEmpty());
        }
        void locatedUnknownSourceOnlyMarksItsOwnKeys() {
            var cell = cell();
            var known = reader.aggregate(List.of(new StorageMount(cell, cell, cell)), null);
            var cached = new KeyCounter();
            cached.add(COPPER, Long.MAX_VALUE);
            cached.add(GOLD, 10);
            var snapshot = reader.confirmReadings(known, known, cached, java.util.Set.of(GOLD), true,
                    java.util.Set.of(COPPER));
            assertNull(snapshot.unverified().get(COPPER));
            assertEquals(MAX.multiply(BigInteger.TWO), snapshot.amounts().get(COPPER).value());
            assertEquals(10L, snapshot.unverified().get(GOLD));
            assertEquals(BigInteger.valueOf(7), snapshot.amounts().get(GOLD).value());
            assertTrue(snapshot.craftables().contains(COPPER));
            assertFalse(snapshot.covered());
        }
        void saturatedNetworkLongCannotProveUnknownSameKeyIsAbsent() {
            var cell = cell();
            var known = reader.aggregate(List.of(new StorageMount(cell, cell, cell)), null);
            var snapshot = reader.confirmReadings(known, known, cell.getAvailableStacks(), java.util.Set.of(),
                    false, java.util.Set.of());
            assertEquals(Long.MAX_VALUE, snapshot.unverified().get(COPPER));
            assertEquals(MAX.multiply(BigInteger.TWO), snapshot.amounts().get(COPPER).value());
        }
        void disappearedUnknownKeyAndCraftableZeroDoNotRemainUnverified() {
            var empty = reader.confirmReadings(Map.of(), Map.of(), new KeyCounter(), java.util.Set.of(),
                    false, java.util.Set.of(COPPER));
            assertTrue(empty.amounts().isEmpty());
            assertTrue(empty.unverified().isEmpty());
            assertTrue(empty.craftables().contains(COPPER));
        }
        private static MEStorage visible(Map<AEKey, Long> counts) {
            return new MEStorage() {
                @Override public Component getDescription() { return Component.literal("只读可见库存"); }
                @Override public void getAvailableStacks(KeyCounter out) { counts.forEach(out::add); }
                @Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
                    throw new AssertionError("显示不得插入物品");
                }
                @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                    throw new AssertionError("显示不得提取物品");
                }
            };
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases.realCellsSumBeyondLongAndExposeSaturatedDecrement", () -> new TerminalStorageAggregationCases().realCellsSumBeyondLongAndExposeSaturatedDecrement()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases.adapterReusesUnchangedRevisionAndInvalidatesAfterExactChange", () -> new TerminalStorageAggregationCases().adapterReusesUnchangedRevisionAndInvalidatesAfterExactChange()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases.wrapperFiltersKeysAndDuplicateDiscoveryDoesNotCountTwice", () -> new TerminalStorageAggregationCases().wrapperFiltersKeysAndDuplicateDiscoveryDoesNotCountTwice()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases.quantityTransformingWrapperAndAdapterConflictRejectInsteadOfLying", () -> new TerminalStorageAggregationCases().quantityTransformingWrapperAndAdapterConflictRejectInsteadOfLying()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases.reconcileUsesUnsaturatedPerMountBaselineAndKeepsOtherSources", () -> new TerminalStorageAggregationCases().reconcileUsesUnsaturatedPerMountBaselineAndKeepsOtherSources()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases.unknownInventoryDoesNotBecomeConfirmedLongQuantity", () -> new TerminalStorageAggregationCases().unknownInventoryDoesNotBecomeConfirmedLongQuantity()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases.locatedUnknownSourceOnlyMarksItsOwnKeys", () -> new TerminalStorageAggregationCases().locatedUnknownSourceOnlyMarksItsOwnKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases.saturatedNetworkLongCannotProveUnknownSameKeyIsAbsent", () -> new TerminalStorageAggregationCases().saturatedNetworkLongCannotProveUnknownSameKeyIsAbsent()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalStorageAggregationCases.disappearedUnknownKeyAndCraftableZeroDoNotRemainUnverified", () -> new TerminalStorageAggregationCases().disappearedUnknownKeyAndCraftableZeroDoNotRemainUnverified())
            );
        }
    }

    /** TerminalTooltipLinesCases 的独立场景夹具，按类别统一执行。 */
    public static final class TerminalTooltipLinesCases {
        void completeHugeAmountRemainsReadableAcrossManyLinesWithoutScientificNotation() {
            var formatter = new TerminalAmountFormatterImpl();
            var value = BigInteger.TEN.pow(4096).add(BigInteger.valueOf(123));
            var full = "存储数量: " + formatter.full(StorageAmount.finite(value));
            var lines = new TerminalTooltipLinesImpl().wrap(full, 120, s -> s.length() * 6);
            assertTrue(lines.size() > 100);
            assertEquals(full, String.join("", lines));
            assertTrue(lines.stream().allMatch(s -> s.length() * 6 <= 120));
            assertEquals(value.toString(), String.join("", lines).substring("存储数量: ".length()).replace(",", ""));
            assertThrows(UnsupportedOperationException.class, () -> lines.clear());
        }
        void fluidFractionsUnitsAndUnicodeRemainIntactInWrappedTooltip() {
            var key = AEFluidKey.of(Fluids.WATER);
            var value = BigInteger.TEN.pow(100).add(BigInteger.ONE);
            var text = new TerminalAmountFormatterImpl().full(key, StorageAmount.finite(value)) + " 🧪";
            var lines = new TerminalTooltipLinesImpl().wrap(text, 17, s -> s.codePointCount(0, s.length()));
            assertEquals(text, String.join("", lines));
            assertTrue(text.contains(".001"));
            assertTrue(lines.stream().allMatch(s -> !Character.isHighSurrogate(s.charAt(s.length() - 1))));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("TerminalTooltipLinesCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalTooltipLinesCases.completeHugeAmountRemainsReadableAcrossManyLinesWithoutScientificNotation", () -> new TerminalTooltipLinesCases().completeHugeAmountRemainsReadableAcrossManyLinesWithoutScientificNotation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalTooltipLinesCases.fluidFractionsUnitsAndUnicodeRemainIntactInWrappedTooltip", () -> new TerminalTooltipLinesCases().fluidFractionsUnitsAndUnicodeRemainIntactInWrappedTooltip())
            );
        }
    }
}
