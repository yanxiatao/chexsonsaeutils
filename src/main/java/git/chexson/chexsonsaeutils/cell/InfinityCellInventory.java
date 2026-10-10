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
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class InfinityCellInventory implements StorageCell {
    /** 套娃检查决策缓存上限；满即整体清空（决策是 AEKey 的纯函数，重建只损失命中率）。 */
    private static final int FIT_CACHE_LIMIT = 4096;
    private static final Object2BooleanOpenHashMap<AEKey> FIT_CACHE = new Object2BooleanOpenHashMap<>();

    private final ItemStack stack;
    private final UUID cellUuid;
    @Nullable
    private final ISaveProvider saveProvider;
    private final InfinityCellStore.CellData cellData;
    private final boolean hasVoidUpgrade;
    private final boolean hasInverterCard;
    private final boolean hasFuzzyCard;
    private ConfigInventory configInventory;
    private PartitionFilter partitionFilter;
    private boolean filterDirty = true;
    private boolean stackCacheDirty;
    private int lastCachedTypes = -1;
    @Nullable
    private BigInteger lastCachedTotal;

    public InfinityCellInventory(ItemStack stack, @Nullable ISaveProvider saveProvider) {
        this.stack = stack;
        this.saveProvider = saveProvider;
        this.cellUuid = getOrCreateUuid(stack);
        this.cellData = InfinityCellStore.global().cell(cellUuid);
        var upgrades = buildUpgradeInventory();
        this.hasVoidUpgrade = upgrades.isInstalled(AEItems.VOID_CARD);
        this.hasInverterCard = upgrades.isInstalled(AEItems.INVERTER_CARD);
        this.hasFuzzyCard = upgrades.isInstalled(AEItems.FUZZY_CARD);
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !canInsert(what)) {
            return 0;
        }

        var inserted = innerInsert(what, amount, mode);
        if (hasVoidUpgrade) {
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

    /** 内容物不进物品组件，非空单元存入其他单元会绕开 AE2 的嵌套防护，故仅允许空单元。 */
    @Override
    public boolean canFitInsideCell() {
        return cellData.isEmpty();
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

    public ConfigInventory getConfigInventory() {
        if (configInventory == null) {
            configInventory = CellConfig.create(stack);
        }
        return configInventory;
    }

    private IUpgradeInventory buildUpgradeInventory() {
        return UpgradeInventories.forItem(stack, 4);
    }

    private long innerInsert(AEKey what, long amount, Actionable mode) {
        if (what instanceof AEItemKey itemKey && !fitsInsideCells(itemKey)) {
            return 0;
        }

        var inserted = cellData.insert(what, amount, mode);
        if (inserted > 0 && mode == Actionable.MODULATE) {
            markChanged();
        }
        return inserted;
    }

    /**
     * 防止非空存储单元被递归存入。决策是 AEKey 的纯函数（key 含组件，
     * 标准单元内容物变更必然换 key），因此可缓存；缓存省掉每笔插入的
     * ItemStack 分配与全部单元 handler 遍历。
     * <p>
     * 本 mod 的无限单元内容物不在组件里（同 key 内容可变），其决策不进缓存。
     */
    private static boolean fitsInsideCells(AEItemKey itemKey) {
        if (itemKey.getItem() instanceof InfinityCellItem) {
            return resolveFit(itemKey);
        }
        synchronized (FIT_CACHE) {
            if (FIT_CACHE.containsKey(itemKey)) {
                return FIT_CACHE.getBoolean(itemKey);
            }
        }
        var fits = resolveFit(itemKey);
        synchronized (FIT_CACHE) {
            if (FIT_CACHE.size() >= FIT_CACHE_LIMIT) {
                FIT_CACHE.clear();
            }
            FIT_CACHE.put(itemKey, fits);
        }
        return fits;
    }

    private static boolean resolveFit(AEItemKey itemKey) {
        var cellInv = StorageCells.getCellInventory(itemKey.toStack(), null);
        return cellInv == null || cellInv.canFitInsideCell();
    }

    private boolean canInsert(AEKey key) {
        return currentFilter().matches(key);
    }

    private PartitionFilter currentFilter() {
        if (filterDirty || partitionFilter == null) {
            partitionFilter = PartitionFilter.create(
                    new ArrayList<>(getConfigInventory().keySet()),
                    stack.getOrDefault(AEComponents.STORAGE_CELL_FUZZY_MODE, FuzzyMode.IGNORE_ALL),
                    hasInverterCard,
                    hasFuzzyCard
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

    /** 即时刷新物品组件中的统计缓存（tooltip 数据源），数值未变则跳过写入。 */
    private void updateStackCache() {
        var types = cellData.typeCount();
        var total = cellData.totalAmount();
        if (types == lastCachedTypes && total.equals(lastCachedTotal)) {
            return;
        }
        var tag = new CompoundTag();
        tag.putUUID(InfinityCellItem.CELL_UUID, cellUuid);
        tag.putInt(InfinityCellItem.CELL_CACHED_TYPES, types);
        tag.putByteArray(InfinityCellItem.CELL_CACHED_TOTAL, total.toByteArray());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        lastCachedTypes = types;
        lastCachedTotal = total;
    }

    private static UUID getOrCreateUuid(ItemStack stack) {
        var customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        var tag = customData.copyTag();
        if (tag.contains(InfinityCellItem.CELL_UUID)) {
            return tag.getUUID(InfinityCellItem.CELL_UUID);
        }
        var newUuid = UUID.randomUUID();
        tag.putUUID(InfinityCellItem.CELL_UUID, newUuid);
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
