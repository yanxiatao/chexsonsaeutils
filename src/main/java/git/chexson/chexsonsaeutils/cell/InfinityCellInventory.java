package git.chexson.chexsonsaeutils.cell;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.ids.AEComponents;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.core.definitions.AEItems;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;
import appeng.util.prioritylist.IPartitionList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class InfinityCellInventory implements StorageCell {
    private static final String UUID_TAG_KEY = InfinityCellItem.CELL_UUID;

    private final ItemStack stack;
    @Nullable
    private final ISaveProvider saveProvider;
    private final InfinityCellStore.CellData cellData;
    private ConfigInventory configInventory;
    private IUpgradeInventory upgrades;
    private PartitionFilter partitionFilter;
    private boolean filterDirty = true;
    private boolean stackCacheDirty;

    public InfinityCellInventory(ItemStack stack, @Nullable ISaveProvider saveProvider) {
        this.stack = stack;
        this.saveProvider = saveProvider;
        var uuid = getOrCreateUuid(stack);
        this.cellData = InfinityCellStore.global().cell(uuid);
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !canInsert(what)) {
            return 0;
        }

        var inserted = innerInsert(what, amount, mode);
        if (getUpgradeInventory().isInstalled(AEItems.VOID_CARD)) {
            return amount;
        }
        return inserted;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        var extracted = cellData.extract(what, amount, mode);
        if (extracted > 0 && mode == Actionable.MODULATE) {
            markChanged();
        }
        return extracted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        cellData.getAvailableStacks(out);
    }

    @Override
    public CellState getStatus() {
        if (cellData.typeCount() == 0) {
            return CellState.EMPTY;
        }
        return CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        return 0.5;
    }

    @Override
    public Component getDescription() {
        return stack.getHoverName();
    }

    @Override
    public void persist() {
        if (stackCacheDirty) {
            updateStackCache();
            stackCacheDirty = false;
        }
    }

    public void saveChanges() {
        persist();
        InfinityCellStore.global().saveCurrentWorld();
    }

    public void close() {
        saveChanges();
    }

    public ConfigInventory getConfigInventory() {
        if (configInventory == null) {
            configInventory = CellConfig.create(stack);
        }
        return configInventory;
    }

    public IUpgradeInventory getUpgradeInventory() {
        if (upgrades == null) {
            upgrades = UpgradeInventories.forItem(stack, 4);
        }
        return upgrades;
    }

    private long innerInsert(AEKey what, long amount, Actionable mode) {
        if (what instanceof AEItemKey itemKey) {
            var cellInv = StorageCells.getCellInventory(itemKey.toStack(), null);
            if (cellInv != null && !cellInv.canFitInsideCell()) {
                return 0;
            }
        }

        var inserted = cellData.insert(what, amount, mode);
        if (inserted > 0 && mode == Actionable.MODULATE) {
            markChanged();
        }
        return inserted;
    }

    private boolean canInsert(AEKey key) {
        return currentFilter().matches(key);
    }

    private PartitionFilter currentFilter() {
        if (filterDirty || partitionFilter == null) {
            partitionFilter = PartitionFilter.create(
                    new ArrayList<>(getConfigInventory().keySet()),
                    stack.getOrDefault(AEComponents.STORAGE_CELL_FUZZY_MODE, FuzzyMode.IGNORE_ALL),
                    getUpgradeInventory().isInstalled(AEItems.INVERTER_CARD),
                    getUpgradeInventory().isInstalled(AEItems.FUZZY_CARD)
            );
            filterDirty = false;
        }
        return partitionFilter;
    }

    private void markChanged() {
        stackCacheDirty = true;
        if (saveProvider != null) {
            saveProvider.saveChanges();
        } else {
            persist();
        }
    }

    private void updateStackCache() {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt(InfinityCellItem.CELL_CACHED_TYPES, cellData.typeCount());
        tag.putByteArray(InfinityCellItem.CELL_CACHED_TOTAL,
                cellData.totalAmount().toByteArray());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static UUID getOrCreateUuid(ItemStack stack) {
        var customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        var tag = customData.copyTag();
        if (tag.contains(UUID_TAG_KEY)) {
            return tag.getUUID(UUID_TAG_KEY);
        }
        var newUuid = UUID.randomUUID();
        tag.putUUID(UUID_TAG_KEY, newUuid);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return newUuid;
    }

    public record PartitionFilter(IPartitionList partitionList, IncludeExclude mode) {
        public static PartitionFilter create(
                List<AEKey> keys,
                FuzzyMode fuzzyMode,
                boolean inverted,
                boolean fuzzy
        ) {
            var builder = IPartitionList.builder();
            if (fuzzy) {
                builder.fuzzyMode(fuzzyMode);
            }
            builder.addAll(keys);
            return new PartitionFilter(
                    builder.build(),
                    inverted ? IncludeExclude.BLACKLIST : IncludeExclude.WHITELIST
            );
        }

        public boolean matches(AEKey key) {
            return partitionList.matchesFilter(key, mode);
        }
    }
}
