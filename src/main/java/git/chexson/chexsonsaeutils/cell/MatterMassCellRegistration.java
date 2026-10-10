package git.chexson.chexsonsaeutils.cell;

import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 物质团只读单元的注册入口（仿 {@link CellRegistration}）。
 * <p>
 * 受物质团供应器特性门控：门控关闭时不调用 {@link #bootstrap}，
 * IO 端口/驱动器不再把物质团识别为存储单元（物品退化为普通只读容器）。
 */
public final class MatterMassCellRegistration {

    public static void bootstrap(Supplier<MatterMassItem> massItem) {
        StorageCells.addCellHandler(new MatterMassCellHandler());
    }

    private MatterMassCellRegistration() {
    }

    private static class MatterMassCellHandler implements ICellHandler {
        @Override
        public boolean isCell(ItemStack is) {
            return is.getItem() instanceof MatterMassItem;
        }

        @Override
        @Nullable
        public StorageCell getCellInventory(ItemStack is, @Nullable ISaveProvider host) {
            if (!isCell(is)) {
                return null;
            }
            return new MatterMassCellInventory(is, host);
        }
    }
}
