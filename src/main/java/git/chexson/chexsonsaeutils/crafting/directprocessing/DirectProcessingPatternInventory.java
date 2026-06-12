package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.inventories.InternalInventory;
import appeng.util.inv.InternalInventoryHost;
import git.chexson.chexsonsaeutils.blockentity.crafting.DirtySlotPatternRefreshScheduler;
import git.chexson.chexsonsaeutils.blockentity.crafting.PagedPatternInventory;
import net.minecraft.world.item.ItemStack;

public final class DirectProcessingPatternInventory {

    private final PagedPatternInventory delegate;

    public DirectProcessingPatternInventory(
            InternalInventoryHost host,
            DirtySlotPatternRefreshScheduler refreshScheduler,
            int totalSlots,
            int pageSize
    ) {
        this.delegate = new PagedPatternInventory(host, refreshScheduler, totalSlots, pageSize);
    }

    public InternalInventory getActivePageInventory() {
        return delegate.getActivePageInventory();
    }

    public int getPageSize() {
        return delegate.getPageSize();
    }

    public int getTotalSlots() {
        return delegate.getTotalSlots();
    }

    public int getPageCount() {
        return delegate.getPageCount();
    }

    public int getActivePage() {
        return delegate.getActivePage();
    }

    public void setActivePage(int pageIndex) {
        delegate.setActivePage(pageIndex);
    }

    public int toGlobalSlotIndex(int activeSlot) {
        return delegate.toGlobalSlotIndex(activeSlot);
    }

    public ItemStack getVirtualSlot(int globalSlot) {
        return delegate.getVirtualSlot(globalSlot);
    }

    public void setVirtualSlot(int globalSlot, ItemStack stack) {
        delegate.setVirtualSlot(globalSlot, stack == null ? ItemStack.EMPTY : stack.copyWithCount(1));
    }

    public void onActivePageSlotChanged(int activeSlot) {
        delegate.onActivePageSlotChanged(activeSlot);
    }
}
