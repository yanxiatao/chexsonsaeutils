package git.chexson.chexsonsaeutils.client.gui.matrix;

import appeng.util.inv.AppEngInternalInventory;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import net.minecraft.world.item.ItemStack;

/**
 * 装配矩阵的一行：ExtendedAE {@code GuiAssemblerMatrix.PatternRow} 的移植件。
 *
 * <p>与原版差异只有两处：去掉了多方块的 {@code id} 维度（一台机器只有一个样板库存），
 * 以及行内容不在开包时全量镜像，而是进入可见窗口前从本地视图回填 ——
 * 全量样板最多 16384 格，整份配方下发会撑爆连接。</p>
 */
public final class PatternRow {

    private final AppEngInternalInventory inventory;
    private final int offset;
    private final int slots;

    public PatternRow(int offset, int slots) {
        this.offset = offset;
        this.slots = slots;
        this.inventory = new AppEngInternalInventory(slots);
    }

    /** 按全局槽号写入本行，减去行首偏移得到行内下标，与移植件一致。 */
    public void setItemByInvSlot(int slot, ItemStack stack) {
        this.inventory.setItemDirect(slot - offset, stack);
    }

    /** 进入可见窗口前回填本行：有样板本体用本体，否则用服务端下发的产出物摘要。 */
    public void syncFrom(PatternMatrixClientView view) {
        for (int col = 0; col < slots; col++) {
            this.inventory.setItemDirect(col, view.cellStackAt(offset + col));
        }
    }

    /** 整行是否命中搜索：本行只要有一格命中就保留，与移植件的行过滤口径一致。 */
    public boolean matchesSearch(PatternMatrixClientView view) {
        for (int col = 0; col < slots; col++) {
            if (view.isMatched(offset + col)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasContent(PatternMatrixClientView view) {
        for (int col = 0; col < slots; col++) {
            if (view.hasDisplayStack(offset + col)) {
                return true;
            }
        }
        return false;
    }

    public AppEngInternalInventory inventory() {
        return inventory;
    }

    public int offset() {
        return offset;
    }

    public int slots() {
        return slots;
    }

    public int globalSlot(int col) {
        return offset + col;
    }
}
