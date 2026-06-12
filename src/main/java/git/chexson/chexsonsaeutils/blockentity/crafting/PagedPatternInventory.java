package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PagedPatternInventory {

    private final List<ItemStack> virtualSlots;
    private final AppEngInternalInventory activePageInventory;
    private final DirtySlotPatternRefreshScheduler refreshScheduler;
    private final int pageSize;
    private boolean synchronizingPage;
    private int activePage;

    public PagedPatternInventory(
            InternalInventoryHost host,
            DirtySlotPatternRefreshScheduler refreshScheduler,
            int totalSlots,
            int pageSize
    ) {
        this.refreshScheduler = refreshScheduler;
        this.pageSize = Math.max(1, pageSize);
        this.virtualSlots = new ArrayList<>(Math.max(this.pageSize, totalSlots));
        for (int i = 0; i < Math.max(this.pageSize, totalSlots); i++) {
            this.virtualSlots.add(ItemStack.EMPTY);
        }
        this.activePageInventory = new AppEngInternalInventory(host, this.pageSize, 1);
        loadActivePageContents();
    }

    public AppEngInternalInventory getActivePageInventory() {
        return activePageInventory;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotalSlots() {
        return virtualSlots.size();
    }

    public int getPageCount() {
        return Math.max(1, (virtualSlots.size() + pageSize - 1) / pageSize);
    }

    public int getActivePage() {
        return activePage;
    }

    public void setActivePage(int requestedPage) {
        int clampedPage = Math.max(0, Math.min(getPageCount() - 1, requestedPage));
        if (clampedPage == this.activePage) {
            return;
        }
        flushActivePageContents();
        this.activePage = clampedPage;
        loadActivePageContents();
    }

    public int toGlobalSlotIndex(int activeSlot) {
        return activePage * pageSize + activeSlot;
    }

    public ItemStack getVirtualSlot(int globalSlot) {
        if (globalSlot < 0 || globalSlot >= virtualSlots.size()) {
            return ItemStack.EMPTY;
        }
        return virtualSlots.get(globalSlot);
    }

    public void setVirtualSlot(int globalSlot, ItemStack stack) {
        if (globalSlot < 0 || globalSlot >= virtualSlots.size()) {
            return;
        }
        virtualSlots.set(globalSlot, stack.copy());
        if (globalSlot / pageSize == activePage) {
            synchronizingPage = true;
            try {
                activePageInventory.setItemDirect(globalSlot % pageSize, stack.copy());
            } finally {
                synchronizingPage = false;
            }
        }
        refreshScheduler.markDirty(globalSlot);
    }

    public void onActivePageSlotChanged(int activeSlot) {
        if (synchronizingPage || activeSlot < 0 || activeSlot >= pageSize) {
            return;
        }
        int globalSlot = toGlobalSlotIndex(activeSlot);
        if (globalSlot < 0 || globalSlot >= virtualSlots.size()) {
            return;
        }
        virtualSlots.set(globalSlot, activePageInventory.getStackInSlot(activeSlot).copy());
        refreshScheduler.markDirty(globalSlot);
    }

    public void clear() {
        for (int i = 0; i < virtualSlots.size(); i++) {
            virtualSlots.set(i, ItemStack.EMPTY);
        }
        synchronizingPage = true;
        try {
            for (int i = 0; i < activePageInventory.size(); i++) {
                activePageInventory.setItemDirect(i, ItemStack.EMPTY);
            }
        } finally {
            synchronizingPage = false;
        }
        refreshScheduler.markRangeDirty(0, virtualSlots.size());
    }

    public void clearWithoutDirtyMarksForTest() {
        for (int i = 0; i < virtualSlots.size(); i++) {
            virtualSlots.set(i, ItemStack.EMPTY);
        }
        synchronizingPage = true;
        try {
            for (int i = 0; i < activePageInventory.size(); i++) {
                activePageInventory.setItemDirect(i, ItemStack.EMPTY);
            }
        } finally {
            synchronizingPage = false;
        }
    }

    public void loadFromExternalSnapshot(Map<Integer, ItemStack> slotSnapshot) {
        clearWithoutDirtyMarksForTest();
        if (slotSnapshot == null || slotSnapshot.isEmpty()) {
            activePage = 0;
            loadActivePageContents();
            return;
        }
        for (Map.Entry<Integer, ItemStack> entry : slotSnapshot.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= virtualSlots.size()) {
                continue;
            }
            ItemStack stack = entry.getValue();
            virtualSlots.set(slot, stack == null ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
        activePage = Math.max(0, Math.min(activePage, getPageCount() - 1));
        loadActivePageContents();
    }

    public int countActivePageNonEmptySlots() {
        int count = 0;
        for (int i = 0; i < activePageInventory.size(); i++) {
            if (!activePageInventory.getStackInSlot(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public int countAllNonEmptySlots() {
        int count = 0;
        for (ItemStack stack : virtualSlots) {
            if (!stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private void flushActivePageContents() {
        for (int activeSlot = 0; activeSlot < pageSize; activeSlot++) {
            int globalSlot = toGlobalSlotIndex(activeSlot);
            if (globalSlot >= 0 && globalSlot < virtualSlots.size()) {
                virtualSlots.set(globalSlot, activePageInventory.getStackInSlot(activeSlot).copy());
            }
        }
    }

    private void loadActivePageContents() {
        synchronizingPage = true;
        try {
            for (int activeSlot = 0; activeSlot < pageSize; activeSlot++) {
                int globalSlot = toGlobalSlotIndex(activeSlot);
                ItemStack stack = globalSlot < virtualSlots.size() ? virtualSlots.get(globalSlot) : ItemStack.EMPTY;
                activePageInventory.setItemDirect(activeSlot, stack.copy());
            }
        } finally {
            synchronizingPage = false;
        }
    }
}
