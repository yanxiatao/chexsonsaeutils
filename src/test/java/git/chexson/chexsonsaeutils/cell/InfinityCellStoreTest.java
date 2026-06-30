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
