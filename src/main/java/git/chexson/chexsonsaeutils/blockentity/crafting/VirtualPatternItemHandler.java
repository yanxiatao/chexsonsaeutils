package git.chexson.chexsonsaeutils.blockentity.crafting;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

public final class VirtualPatternItemHandler implements IItemHandlerModifiable {

    private final VirtualPatternInventoryContainer container;

    public VirtualPatternItemHandler(VirtualPatternInventoryContainer container) {
        this.container = container;
    }

    @Override
    public int getSlots() {
        return container.getContainerSize();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return container.getItem(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !container.canPlaceItem(slot, stack)) {
            return stack;
        }
        ItemStack existing = container.getItem(slot);
        if (!existing.isEmpty()) {
            return stack;
        }
        ItemStack inserted = stack.copyWithCount(1);
        if (!simulate) {
            container.setItem(slot, inserted);
            container.setChanged();
        }
        if (stack.getCount() <= 1) {
            return ItemStack.EMPTY;
        }
        ItemStack remainder = stack.copy();
        remainder.shrink(1);
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack existing = container.getItem(slot);
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack extracted = existing.copyWithCount(Math.min(amount, existing.getCount()));
        if (!simulate) {
            if (amount >= existing.getCount()) {
                container.setItem(slot, ItemStack.EMPTY);
            } else {
                ItemStack remaining = existing.copy();
                remaining.shrink(amount);
                container.setItem(slot, remaining);
            }
            container.setChanged();
        }
        return extracted;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 1;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return container.canPlaceItem(slot, stack);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        container.setItem(slot, stack);
        container.setChanged();
    }
}
