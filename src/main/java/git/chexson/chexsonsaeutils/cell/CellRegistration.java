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

    public static void bootstrap(Supplier<InfinityCellItem> cellItem) {
        StorageCells.addCellHandler(new InfinityCellHandler());

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
        @Override
        public boolean isCell(ItemStack is) {
            return is.getItem() instanceof InfinityCellItem;
        }

        @Override
        @Nullable
        public StorageCell getCellInventory(ItemStack is, @Nullable ISaveProvider host) {
            if (!isCell(is)) return null;
            return new InfinityCellInventory(is, host);
        }
    }
}
