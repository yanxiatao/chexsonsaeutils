package git.chexson.chexsonsaeutils.blockentity.crafting;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class VirtualPatternInventoryContainer implements Container {

    private final AbstractHighCapacityCraftingHostBlockEntity host;

    public VirtualPatternInventoryContainer(AbstractHighCapacityCraftingHostBlockEntity host) {
        this.host = host;
    }

    @Override
    public int getContainerSize() {
        return host.getTotalPatternSlots();
    }

    @Override
    public boolean isEmpty() {
        return host.getTotalNonEmptyPatternSlots() == 0;
    }

    @Override
    public ItemStack getItem(int slot) {
        return host.getPatternAt(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack current = host.getPatternAt(slot);
        if (current.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack extracted = current.copyWithCount(Math.min(amount, current.getCount()));
        if (amount >= current.getCount()) {
            host.setPatternAt(slot, ItemStack.EMPTY);
        } else {
            ItemStack remaining = current.copy();
            remaining.shrink(amount);
            host.setPatternAt(slot, remaining);
        }
        return extracted;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = host.getPatternAt(slot);
        if (!current.isEmpty()) {
            host.setPatternAt(slot, ItemStack.EMPTY);
        }
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        host.setPatternAt(slot, stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void setChanged() {
        host.saveChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return host.isSupportedEncodedPattern(stack);
    }

    @Override
    public void clearContent() {
        host.clearPatternsForAutomation();
    }
}
