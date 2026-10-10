package git.chexson.chexsonsaeutils.menu.matrix;

import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.IntConsumer;

/**
 * 样板槽位的唯一权威存储：全量槽位放在同一份库存里，界面窗口只是它的偏移。
 *
 * <p>因此 AE2 本体的样板管理终端与本机 GUI 读到的始终是同一份数据，
 * 不存在“只有当前页对终端可见”的视野缺口。</p>
 */
public final class PatternSlotStore {

    private final AppEngInternalInventory inventory;
    private final DirtySlotPatternRefreshScheduler refreshScheduler;
    private final IntConsumer persistSlot;
    /** 批量写入期间抑制逐槽回写：整表清空/镜像快照时由调用方按整体口径落盘。 */
    private boolean suppressPersist;

    public PatternSlotStore(
            InternalInventoryHost host,
            DirtySlotPatternRefreshScheduler refreshScheduler,
            int totalSlots,
            IntConsumer persistSlot
    ) {
        this.refreshScheduler = refreshScheduler;
        this.persistSlot = persistSlot;
        this.inventory = new AppEngInternalInventory(host, Math.max(1, totalSlots), 1);
    }

    public AppEngInternalInventory inventory() {
        return inventory;
    }

    public int getTotalSlots() {
        return inventory.size();
    }

    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= getTotalSlots()) {
            return ItemStack.EMPTY;
        }
        return inventory.getStackInSlot(slot);
    }

    /** 程序化写入：与界面写入共用同一条落盘与脏槽链路。 */
    public void setStackInSlot(int slot, ItemStack stack) {
        if (slot < 0 || slot >= getTotalSlots()) {
            return;
        }
        inventory.setItemDirect(slot, normalize(stack));
    }

    /** 由 {@link InternalInventoryHost#onChangeInventory} 转发过来的写入事件。 */
    public void onSlotWritten(int slot) {
        if (slot < 0 || slot >= getTotalSlots()) {
            return;
        }
        if (!suppressPersist) {
            persistSlot.accept(slot);
        }
        refreshScheduler.markDirty(slot);
    }

    public void clearAll() {
        writeSnapshot(Map.of());
    }

    public void loadFromExternalSnapshot(Map<Integer, ItemStack> slotSnapshot) {
        writeSnapshot(slotSnapshot);
    }

    public int countNonEmptySlots() {
        int count = 0;
        for (int slot = 0; slot < getTotalSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** 整表替换：一次写入覆盖全部槽位，脏槽整段标记，回写交给调用方。 */
    private void writeSnapshot(Map<Integer, ItemStack> slotSnapshot) {
        suppressPersist = true;
        try {
            for (int slot = 0; slot < getTotalSlots(); slot++) {
                ItemStack next = normalize(slotSnapshot == null ? null : slotSnapshot.get(slot));
                if (next.isEmpty() && inventory.getStackInSlot(slot).isEmpty()) {
                    continue;
                }
                setStackInSlot(slot, next);
            }
        } finally {
            suppressPersist = false;
        }
        refreshScheduler.markRangeDirty(0, getTotalSlots());
    }

    /** 样板槽语义是一张样板，数量恒为 1。 */
    private static ItemStack normalize(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
    }
}
