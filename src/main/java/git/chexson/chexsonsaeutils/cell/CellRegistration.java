package git.chexson.chexsonsaeutils.cell;

import appeng.api.client.StorageCellModels;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public final class CellRegistration {
    public static final String CELL_ITEM_ID = "infinity_cell";

    public static void bootstrap(Supplier<InfinityCellItem> cellItem,
            java.util.function.BiFunction<ItemStack, ISaveProvider, ? extends StorageCell> inventoryFactory) {
        StorageCells.addCellHandler(new InfinityCellHandler(inventoryFactory));

        var item = cellItem.get();
        StorageCellModels.registerModel(item,
                ResourceLocation.parse("chexsonsaeutils:block/drive/infinity_cell"));

        Upgrades.add(AEItems.FUZZY_CARD, item, 1, "storage_cells");
        Upgrades.add(AEItems.INVERTER_CARD, item, 1, "storage_cells");
        Upgrades.add(AEItems.VOID_CARD, item, 1, "storage_cells");
    }

    private CellRegistration() {
    }

    private static class InfinityCellHandler implements ICellHandler {
        /** 装配层选择可选模组适配；单元核心只依赖 AE2 存储契约。 */
        private final java.util.function.BiFunction<ItemStack, ISaveProvider, ? extends StorageCell> factory;

        private InfinityCellHandler(java.util.function.BiFunction<ItemStack, ISaveProvider, ? extends StorageCell> factory) {
            this.factory = java.util.Objects.requireNonNull(factory);
        }
        @Override
        public boolean isCell(ItemStack is) {
            return is.getItem() instanceof InfinityCellItem;
        }

        @Override
        @Nullable
        public StorageCell getCellInventory(ItemStack is, @Nullable ISaveProvider host) {
            if (!isCell(is)) return null;
            return factory.apply(is, host);
        }
    }
}
