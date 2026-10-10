package git.chexson.chexsonsaeutils.cell;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.networking.security.IActionSource;
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
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InfinityCellStoreTest {

    private static final AEKey COPPER = new TestKey("copper", "ore", 0, 100);
    private static final AEKey DAMAGED_COPPER = new TestKey("copper", "ore", 60, 100);
    private static final AEKey TIN = new TestKey("tin", "ore", 0, 100);
    private static final AEKey LEAD = new TestKey("lead", "ore", 0, 100);
    private static final AEItemKey ITEM_COPPER = AEItemKey.of(Items.COPPER_INGOT);

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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
    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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
}
