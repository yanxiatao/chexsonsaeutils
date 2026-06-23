package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.crafting.IPatternDetails;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class DecodedPatternEntryCache {

    public record Entry(ItemStack snapshot, @Nullable IPatternDetails patternDetails) {
    }

    private final Entry[] entries;

    public DecodedPatternEntryCache(int totalSlots) {
        this.entries = new Entry[Math.max(1, totalSlots)];
    }

    @Nullable
    public Entry get(int globalSlot) {
        if (globalSlot < 0 || globalSlot >= entries.length) {
            return null;
        }
        return entries[globalSlot];
    }

    public boolean matches(int globalSlot, ItemStack stack) {
        Entry entry = get(globalSlot);
        if (entry == null) {
            return false;
        }
        return ItemStack.isSameItemSameTags(entry.snapshot(), stack)
                && entry.snapshot().getCount() == stack.getCount();
    }

    public void put(int globalSlot, ItemStack snapshot, @Nullable IPatternDetails patternDetails) {
        if (globalSlot < 0 || globalSlot >= entries.length) {
            return;
        }
        entries[globalSlot] = new Entry(snapshot.copy(), patternDetails);
    }

    public void invalidate(int globalSlot) {
        if (globalSlot < 0 || globalSlot >= entries.length) {
            return;
        }
        entries[globalSlot] = null;
    }

    public void clear() {
        for (int i = 0; i < entries.length; i++) {
            entries[i] = null;
        }
    }
}
