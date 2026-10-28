package git.chexson.chexsonsaeutils.cell;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 存储与终端的行为场景；由类别测试统一调用。 */
public final class StorageScenarios {
    private StorageScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.cell",
                () -> InfinityCellStoreCases.verify(temporaryRoot)
        );
    }

    /** InfinityCellStoreCases 的独立场景夹具，按类别统一执行。 */
    public static final class InfinityCellStoreCases {
        private static final AEKey COPPER = new TestKey("copper", "ore", 0, 100);
        private static final AEKey DAMAGED_COPPER = new TestKey("copper", "ore", 60, 100);
        private static final AEKey TIN = new TestKey("tin", "ore", 0, 100);
        private static final AEKey LEAD = new TestKey("lead", "ore", 0, 100);
        private static final AEItemKey ITEM_COPPER = AEItemKey.of(Items.COPPER_INGOT);

        void exactExtractionPreservesSimulationDowngradeSnapshotsAndPersistence() throws Exception {
            var root = Files.createTempDirectory("infinity-exact-extract");
            var store = new InfinityCellStore(); var id = UUID.randomUUID(); var cell = store.cell(id);
            var huge = BigInteger.TEN.pow(100);
            cell.insertExact(ITEM_COPPER, huge.add(BigInteger.valueOf(9)), Actionable.MODULATE);
            var before = cell.readExactSnapshot(id);
            assertEquals(huge, cell.extractExact(ITEM_COPPER, huge, Actionable.SIMULATE));
            assertEquals(before.revision(), cell.readExactSnapshot(id).revision());
            assertEquals(huge.add(BigInteger.valueOf(9)), cell.totalAmount());
            assertEquals(huge, cell.extractExact(ITEM_COPPER, huge, Actionable.MODULATE));
            assertEquals(9L, cell.visibleAmount(ITEM_COPPER));
            assertEquals(BigInteger.valueOf(9), cell.totalAmount());
            assertTrue(cell.readExactSnapshot(id).revision() > before.revision());
            store.save(root); InfinityCellStore.awaitPendingWrites();
            var loaded = new InfinityCellStore(); loaded.load(root);
            assertEquals(BigInteger.valueOf(9), loaded.cell(id).extractExact(ITEM_COPPER, huge, Actionable.MODULATE));
            assertEquals(0, loaded.cell(id).typeCount());
            assertEquals(BigInteger.ZERO, loaded.cell(id).totalAmount());
            assertEquals(BigInteger.ZERO, loaded.cell(id).extractExact(ITEM_COPPER, huge, Actionable.MODULATE));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> cell.extractExact(ITEM_COPPER, BigInteger.valueOf(-1), Actionable.MODULATE));
        }

        void exactSingleInsertionPreservesSimulationTotalsAndPersistedQuantity() throws Exception {
            var root = Files.createTempDirectory("infinity-exact-insert");
            var store = new InfinityCellStore(); var id = UUID.randomUUID(); var cell = store.cell(id);
            var huge = BigInteger.TEN.pow(100).add(BigInteger.valueOf(23));
            assertEquals(huge, cell.insertExact(ITEM_COPPER, huge, Actionable.SIMULATE));
            assertEquals(0, cell.typeCount()); assertEquals(BigInteger.ZERO, cell.totalAmount());
            var initial = cell.readExactSnapshot(id);
            cell.insertExact(ITEM_COPPER, huge, Actionable.MODULATE);
            cell.insertExact(ITEM_COPPER, BigInteger.valueOf(7), Actionable.MODULATE);
            assertEquals(1, cell.typeCount());
            assertEquals(huge.add(BigInteger.valueOf(7)), cell.totalAmount());
            assertTrue(cell.readExactSnapshot(id).revision() > initial.revision());
            store.save(root); InfinityCellStore.awaitPendingWrites();
            var loaded = new InfinityCellStore(); loaded.load(root);
            assertEquals(huge.add(BigInteger.valueOf(7)), loaded.cell(id).exactAmount(ITEM_COPPER));
            assertEquals(11L, loaded.cell(id).extract(ITEM_COPPER, 11L, Actionable.MODULATE));
            assertEquals(huge.subtract(BigInteger.valueOf(4)), loaded.cell(id).totalAmount());
        }

        void fluidKeysPersistAndReloadThroughGenericCodec() throws Exception {
            var root = Files.createTempDirectory("infinity-fluid-codec");
            var store = new InfinityCellStore(); var id = UUID.randomUUID();
            var fluid = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
            var huge = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
            store.cell(id).insert(fluid, Long.MAX_VALUE, Actionable.MODULATE);
            store.cell(id).insert(fluid, Long.MAX_VALUE, Actionable.MODULATE);
            store.save(root); InfinityCellStore.awaitPendingWrites();
            var loaded = new InfinityCellStore(); loaded.load(root);
            assertEquals(huge, loaded.cell(id).readExactSnapshot(id).amount(fluid));
        }

        void oldUnknownTypeRetainsAe2MissingContentAndDoesNotPoisonKnownKeys() throws Exception {
            var root = Files.createTempDirectory("infinity-unknown-codec"); var id = UUID.randomUUID();
            var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                    net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            var keys = new net.minecraft.nbt.ListTag();
            var known = new CompoundTag(); known.putInt("id", 1);
            known.putString("type", ITEM_COPPER.getType().getId().toString());
            known.put("tag", ITEM_COPPER.toTag(registries)); keys.add(known);
            var unknown = new CompoundTag(); unknown.putInt("id", 2590); unknown.putString("type", "absentmod:chemical");
            var raw = new CompoundTag(); raw.putString("id", "absentmod:hydrogen"); unknown.put("tag", raw); keys.add(unknown);
            var entries = new net.minecraft.nbt.ListTag();
            var a = new CompoundTag(); a.putInt("keyId", 1); a.putLong("amount", 64); entries.add(a);
            var b = new CompoundTag(); b.putInt("keyId", 2590); b.putByteArray("bigAmount", BigInteger.TEN.pow(100).toByteArray()); entries.add(b);
            var fileTag = new CompoundTag(); fileTag.putInt("version", 1); fileTag.put("keys", keys); fileTag.put("entries", entries);
            var file = CellStoragePaths.getNbtFile(root, id); Files.createDirectories(file.getParent()); NbtIo.writeCompressed(fileTag, file);
            var store = new InfinityCellStore(); store.load(root); var cell = store.cell(id);
            var snapshot = cell.readExactSnapshot(id);
            assertEquals(BigInteger.valueOf(64), snapshot.amount(ITEM_COPPER));
            assertEquals(2, snapshot.amounts().size());
            var missing = snapshot.amounts().keySet().stream().filter(k -> !k.equals(ITEM_COPPER)).findFirst().orElseThrow();
            assertTrue(appeng.core.definitions.AEItems.MISSING_CONTENT.is(missing));
            assertEquals(BigInteger.TEN.pow(100), snapshot.amount(missing));
            assertEquals("absentmod:chemical", missing.toTagGeneric(registries).getString(AEKey.TYPE_FIELD));
            cell.insert(ITEM_COPPER, 1, Actionable.MODULATE); store.save(root); InfinityCellStore.awaitPendingWrites();
            var loaded = new InfinityCellStore(); loaded.load(root);
            var restored = loaded.cell(id).readExactSnapshot(id);
            var restoredMissing = restored.amounts().keySet().stream().filter(k -> !k.equals(ITEM_COPPER)).findFirst().orElseThrow();
            assertEquals(snapshot.amount(missing), restored.amount(restoredMissing));
            assertEquals("absentmod:chemical", restoredMissing.toTagGeneric(registries).getString(AEKey.TYPE_FIELD));
            assertEquals("absentmod:hydrogen", restoredMissing.toTagGeneric(registries).getString("id"));
        }

        void exactPublicSnapshotIsAtomicImmutableAndRevisionCached() {
            var store = new InfinityCellStore();
            var id = UUID.randomUUID();
            var cell = store.cell(id);
            var huge = BigInteger.TEN.pow(100);
            cell.loadAmount(store.keyDictionary().idFor(COPPER), huge);
            cell.markClean();
            var first = cell.readExactSnapshot(id);
            assertEquals(huge, first.amount(COPPER));
            assertEquals(BigInteger.ZERO, first.amount(TIN));
            assertSame(first, cell.readExactSnapshot(id));
            assertFalse(cell.isDirty());
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> first.amounts().put(TIN, BigInteger.ONE));
            cell.extract(COPPER, 64, Actionable.MODULATE);
            var second = cell.readExactSnapshot(id);
            assertEquals(huge.subtract(BigInteger.valueOf(64)), second.amount(COPPER));
            assertEquals(huge, first.amount(COPPER));
            assertNotEquals(first.revision(), second.revision());
        }

        void exactSnapshotTracksSameTotalDifferentKeysAndRemoval() {
            var store = new InfinityCellStore(); var id = UUID.randomUUID(); var cell = store.cell(id);
            cell.insert(COPPER, 64, Actionable.MODULATE);
            var first = cell.readExactSnapshot(id);
            cell.extract(COPPER, 64, Actionable.MODULATE); cell.insert(TIN, 64, Actionable.MODULATE);
            var second = cell.readExactSnapshot(id);
            assertEquals(BigInteger.ZERO, second.amount(COPPER));
            assertEquals(BigInteger.valueOf(64), second.amount(TIN));
            assertEquals(BigInteger.valueOf(64), first.amount(COPPER));
            assertNotEquals(first.revision(), second.revision());
        }

        void reusesCellDataAndGlobalKeyIdsAcrossHandles() {
            var store = new InfinityCellStore();
            var cellId = UUID.randomUUID();

            var first = store.cell(cellId);
            var second = store.cell(cellId);

            assertSame(first, second);
            assertEquals(1, store.keyDictionary().idFor(COPPER));
            assertEquals(1, store.keyDictionary().idFor(COPPER));
            assertEquals(2, store.keyDictionary().idFor(TIN));
        }

        void insertExtractUsePrimitiveAmountMapAndOverflowOnlyPastLongMax() {
            var store = new InfinityCellStore();
            var cell = store.cell(UUID.randomUUID());

            assertEquals(Long.MAX_VALUE, cell.insert(COPPER, Long.MAX_VALUE, Actionable.MODULATE));
            assertFalse(cell.hasOverflow(COPPER));
            assertEquals(Long.MAX_VALUE, cell.visibleAmount(COPPER));

            assertEquals(1, cell.insert(COPPER, 1, Actionable.MODULATE));
            assertTrue(cell.hasOverflow(COPPER));
            assertEquals(new BigInteger("9223372036854775808"), cell.exactAmount(COPPER));
            assertEquals(Long.MAX_VALUE, cell.visibleAmount(COPPER));

            assertEquals(2, cell.extract(COPPER, 2, Actionable.MODULATE));
            assertFalse(cell.hasOverflow(COPPER));
            assertEquals(Long.MAX_VALUE - 1, cell.visibleAmount(COPPER));
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE - 1), cell.totalAmount());
        }

        void totalAmountIsMaintainedAcrossLongAndOverflowChanges() {
            var store = new InfinityCellStore();
            var cell = store.cell(UUID.randomUUID());

            cell.insert(COPPER, 10, Actionable.MODULATE);
            cell.insert(TIN, Long.MAX_VALUE, Actionable.MODULATE);
            cell.insert(TIN, 4, Actionable.MODULATE);
            cell.extract(COPPER, 3, Actionable.MODULATE);
            cell.extract(TIN, 2, Actionable.MODULATE);

            assertEquals(new BigInteger("9223372036854775816"), cell.totalAmount());
        }

        /**
         * 总量的窄化判据与同类那条 long 腿共用一把尺：{@code bitLength() <= 63} 才装得进 {@code long}
         * （long 腿用 {@code Math.addExact}，越界才切实值，恰是这一口径）。
         *
         * <p>旧写法 {@code addToTotal(BigInteger)} 用 {@code < 63}，把 {@code 2^62..2^63-1} 整段白拒进
         * {@code totalBig}。这一档在本类<b>没有可达的数值后果</b>：{@code totalAmount()} 两档都交回逐位
         * 相同的读数，所以这条用例钉的是"界画在哪"（2^63-1 与 2^63 之间），不是"旧写法必红"——
         * 有可达后果的那一份在白拒带上有牙（见 {@code RingStartupLongBoundaryTest}）。
         */
        void totalNarrowsAtTheLongBoundaryNotOneNotchBelowIt() {
            var store = new InfinityCellStore();
            var cell = store.cell(UUID.randomUUID());
            var twoPow63 = new BigInteger("9223372036854775808");

            cell.loadAmount(store.keyDictionary().idFor(COPPER), BigInteger.valueOf(Long.MAX_VALUE));
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE), cell.totalAmount(),
                    "恰在 2^63-1 这一档：总量读数逐位等于 9223372036854775807，不许当越界处理");

            cell.loadAmount(store.keyDictionary().idFor(TIN), twoPow63);
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(twoPow63), cell.totalAmount(),
                    "越过 2^63 才切实值：两笔合计逐位等于 18446744073709551615");
        }

        void snapshotIsReusedUntilVersionChangesAndExposesOriginalKeys() {
            var store = new InfinityCellStore();
            var cell = store.cell(UUID.randomUUID());

            cell.insert(COPPER, 12, Actionable.MODULATE);
            var first = cell.snapshot();
            var second = cell.snapshot();

            assertSame(first, second);
            assertSame(COPPER, first.keys()[0]);

            cell.insert(TIN, 3, Actionable.MODULATE);
            var changed = cell.snapshot();

            assertNotEquals(first.version(), changed.version());
            assertEquals(2, changed.size());
        }

        void saveBoundaryPersistsBinaryNbtAndKeepsHotPathInMemory() throws Exception {
            var root = Files.createTempDirectory("infinity-cell-store");
            var store = new InfinityCellStore();
            var cellId = UUID.randomUUID();
            var cell = store.cell(cellId);

            cell.insert(ITEM_COPPER, 12, Actionable.MODULATE);
            assertFalse(Files.exists(CellStoragePaths.getNbtFile(root, cellId)));

            store.save(root);
            InfinityCellStore.awaitPendingWrites();

            var file = CellStoragePaths.getNbtFile(root, cellId);
            assertTrue(Files.exists(file));
            assertEquals(1, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getInt("version"));

            var loaded = new InfinityCellStore();
            loaded.load(root);

            assertEquals(12, loaded.cell(cellId).visibleAmount(ITEM_COPPER));
        }

        void insertWithSaveProviderDoesNotRefreshItemStackSummaryUntilPersist() {
            var stack = new ItemStack(Items.STICK);
            var saves = new CountingSaveProvider();
            var inventory = new InfinityCellInventory(stack, saves);

            inventory.insert(COPPER, 7, Actionable.MODULATE, IActionSource.empty());
            var hotPathTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();

            assertEquals(1, saves.count);
            assertFalse(hotPathTag.contains(InfinityCellItem.CELL_CACHED_TYPES));
            assertFalse(hotPathTag.contains(InfinityCellItem.CELL_CACHED_TOTAL));

            inventory.persist();
            var persistedTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();

            assertEquals(1, persistedTag.getInt(InfinityCellItem.CELL_CACHED_TYPES));
            assertEquals(BigInteger.valueOf(7),
                    new BigInteger(persistedTag.getByteArray(InfinityCellItem.CELL_CACHED_TOTAL)));
        }

        void loadOnceKeepsSameWorldMemoryAndReloadsAfterRootSwitch() throws Exception {
            var firstRoot = Files.createTempDirectory("infinity-cell-store-first");
            var secondRoot = Files.createTempDirectory("infinity-cell-store-second");
            var cellId = UUID.randomUUID();
            var first = new InfinityCellStore();
            var second = new InfinityCellStore();

            first.cell(cellId).insert(ITEM_COPPER, 12, Actionable.MODULATE);
            first.save(firstRoot);
            second.cell(cellId).insert(ITEM_COPPER, 34, Actionable.MODULATE);
            second.save(secondRoot);
            InfinityCellStore.awaitPendingWrites();

            var store = new InfinityCellStore();
            store.loadOnce(firstRoot);
            store.cell(cellId).insert(ITEM_COPPER, 5, Actionable.MODULATE);

            store.loadOnce(firstRoot);
            assertEquals(17, store.cell(cellId).visibleAmount(ITEM_COPPER));

            store.loadOnce(secondRoot);
            assertEquals(34, store.cell(cellId).visibleAmount(ITEM_COPPER));
        }

        void filterKeepsWhitelistBlacklistAndFuzzySemantics() {
            var whitelist = InfinityCellInventory.PartitionFilter.create(
                    List.of(COPPER),
                    FuzzyMode.IGNORE_ALL,
                    false,
                    true
            );
            var blacklist = InfinityCellInventory.PartitionFilter.create(
                    List.of(COPPER),
                    FuzzyMode.IGNORE_ALL,
                    true,
                    false
            );

            assertTrue(whitelist.matches(DAMAGED_COPPER));
            assertFalse(whitelist.matches(TIN));
            assertFalse(blacklist.matches(COPPER));
            assertTrue(blacklist.matches(TIN));
        }

        void availableStacksFillsKeyCounterFromVersionedSnapshot() {
            var store = new InfinityCellStore();
            var cell = store.cell(UUID.randomUUID());
            var out = new KeyCounter();

            cell.insert(COPPER, 8, Actionable.MODULATE);
            cell.insert(TIN, 5, Actionable.MODULATE);
            cell.getAvailableStacks(out);

            assertEquals(8, out.get(COPPER));
            assertEquals(5, out.get(TIN));
        }

        void absentReadsAndExtractDoNotAllocateDictionaryIds() {
            var store = new InfinityCellStore();
            var cell = store.cell(UUID.randomUUID());

            assertEquals(1, store.keyDictionary().idFor(COPPER));
            assertEquals(0, cell.extract(TIN, 1, Actionable.MODULATE));
            assertEquals(0, cell.visibleAmount(TIN));
            assertFalse(cell.hasOverflow(TIN));

            assertEquals(2, store.keyDictionary().idFor(LEAD));
        }

        private static final class CountingSaveProvider implements appeng.api.storage.cells.ISaveProvider {
            private int count;

            @Override
            public void saveChanges() {
                count++;
            }
        }

        private static final class TestKey extends AEKey {
            private final String primary;
            private final String variant;
            private final int fuzzyValue;
            private final int fuzzyMax;

            private TestKey(String primary, String variant, int fuzzyValue, int fuzzyMax) {
                this.primary = primary;
                this.variant = variant;
                this.fuzzyValue = fuzzyValue;
                this.fuzzyMax = fuzzyMax;
            }

            @Override
            public AEKeyType getType() {
                return AEKeyType.items();
            }

            @Override
            public AEKey dropSecondary() {
                return new TestKey(primary, "", 0, fuzzyMax);
            }

            @Override
            public CompoundTag toTag(HolderLookup.Provider registries) {
                var tag = new CompoundTag();
                tag.putString("primary", primary);
                tag.putString("variant", variant);
                tag.putInt("fuzzyValue", fuzzyValue);
                tag.putInt("fuzzyMax", fuzzyMax);
                return tag;
            }

            @Override
            public Object getPrimaryKey() {
                return primary;
            }

            @Override
            public int getFuzzySearchValue() {
                return fuzzyValue;
            }

            @Override
            public int getFuzzySearchMaxValue() {
                return fuzzyMax;
            }

            @Override
            public ResourceLocation getId() {
                return ResourceLocation.fromNamespaceAndPath("test", primary);
            }

            @Override
            public void writeToPacket(RegistryFriendlyByteBuf data) {
                throw new UnsupportedOperationException("test key is not packet serialized");
            }

            @Override
            protected Component computeDisplayName() {
                return Component.literal(primary + ":" + variant);
            }

            @Override
            public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
            }

            @Override
            public boolean isTagged(TagKey<?> tag) {
                return false;
            }

            @Override
            public boolean hasComponents() {
                return !variant.isEmpty();
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof TestKey key
                        && primary.equals(key.primary)
                        && variant.equals(key.variant)
                        && fuzzyValue == key.fuzzyValue
                        && fuzzyMax == key.fuzzyMax;
            }

            @Override
            public int hashCode() {
                return java.util.Objects.hash(primary, variant, fuzzyValue, fuzzyMax);
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.exactExtractionPreservesSimulationDowngradeSnapshotsAndPersistence", () -> new InfinityCellStoreCases().exactExtractionPreservesSimulationDowngradeSnapshotsAndPersistence()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.exactSingleInsertionPreservesSimulationTotalsAndPersistedQuantity", () -> new InfinityCellStoreCases().exactSingleInsertionPreservesSimulationTotalsAndPersistedQuantity()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.fluidKeysPersistAndReloadThroughGenericCodec", () -> new InfinityCellStoreCases().fluidKeysPersistAndReloadThroughGenericCodec()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.oldUnknownTypeRetainsAe2MissingContentAndDoesNotPoisonKnownKeys", () -> new InfinityCellStoreCases().oldUnknownTypeRetainsAe2MissingContentAndDoesNotPoisonKnownKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.exactPublicSnapshotIsAtomicImmutableAndRevisionCached", () -> new InfinityCellStoreCases().exactPublicSnapshotIsAtomicImmutableAndRevisionCached()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.exactSnapshotTracksSameTotalDifferentKeysAndRemoval", () -> new InfinityCellStoreCases().exactSnapshotTracksSameTotalDifferentKeysAndRemoval()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.reusesCellDataAndGlobalKeyIdsAcrossHandles", () -> new InfinityCellStoreCases().reusesCellDataAndGlobalKeyIdsAcrossHandles()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.insertExtractUsePrimitiveAmountMapAndOverflowOnlyPastLongMax", () -> new InfinityCellStoreCases().insertExtractUsePrimitiveAmountMapAndOverflowOnlyPastLongMax()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.totalAmountIsMaintainedAcrossLongAndOverflowChanges", () -> new InfinityCellStoreCases().totalAmountIsMaintainedAcrossLongAndOverflowChanges()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.totalNarrowsAtTheLongBoundaryNotOneNotchBelowIt", () -> new InfinityCellStoreCases().totalNarrowsAtTheLongBoundaryNotOneNotchBelowIt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.snapshotIsReusedUntilVersionChangesAndExposesOriginalKeys", () -> new InfinityCellStoreCases().snapshotIsReusedUntilVersionChangesAndExposesOriginalKeys()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.saveBoundaryPersistsBinaryNbtAndKeepsHotPathInMemory", () -> new InfinityCellStoreCases().saveBoundaryPersistsBinaryNbtAndKeepsHotPathInMemory()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.insertWithSaveProviderDoesNotRefreshItemStackSummaryUntilPersist", () -> new InfinityCellStoreCases().insertWithSaveProviderDoesNotRefreshItemStackSummaryUntilPersist()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.loadOnceKeepsSameWorldMemoryAndReloadsAfterRootSwitch", () -> new InfinityCellStoreCases().loadOnceKeepsSameWorldMemoryAndReloadsAfterRootSwitch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.filterKeepsWhitelistBlacklistAndFuzzySemantics", () -> new InfinityCellStoreCases().filterKeepsWhitelistBlacklistAndFuzzySemantics()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.availableStacksFillsKeyCounterFromVersionedSnapshot", () -> new InfinityCellStoreCases().availableStacksFillsKeyCounterFromVersionedSnapshot()),
                () -> org.junit.jupiter.api.Assertions.assertAll("InfinityCellStoreCases.absentReadsAndExtractDoNotAllocateDictionaryIds", () -> new InfinityCellStoreCases().absentReadsAndExtractDoNotAllocateDictionaryIds())
            );
        }
    }
}
