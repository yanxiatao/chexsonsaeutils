package git.chexson.chexsonsaeutils.blockentity.directprocessing;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

public final class DirectProcessingItemHandler implements IItemHandlerModifiable {

    private final AEDirectProcessingMachineBlockEntity host;

    public DirectProcessingItemHandler(AEDirectProcessingMachineBlockEntity host) {
        this.host = host;
    }

    @Override
    public int getSlots() {
        return 1 + host.getTotalPatternSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot == 0) {
            return host.getMachineBindingStack();
        }
        return host.getPatternAt(slot - 1);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !isItemValid(slot, stack)) {
            return stack;
        }
        if (!getStackInSlot(slot).isEmpty()) {
            return stack;
        }
        ItemStack inserted = stack.copyWithCount(1);
        if (!simulate) {
            setStackInSlot(slot, inserted);
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
        ItemStack existing = getStackInSlot(slot);
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack extracted = existing.copyWithCount(1);
        if (!simulate) {
            setStackInSlot(slot, ItemStack.EMPTY);
        }
        return extracted;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 1;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (slot == 0) {
            return host.isSupportedBindingMachine(stack);
        }
        return host.isProcessingPattern(stack);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (slot == 0) {
            host.setMachineBindingStack(stack);
        } else {
            host.setPatternAt(slot - 1, stack);
        }
        host.saveChanges();
    }
}
