package git.chexson.chexsonsaeutils.blockentity.crafting;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public final class DirtySlotPatternRefreshScheduler {

    private final BitSet dirtySlots;
    private boolean providerRefreshPending;

    public DirtySlotPatternRefreshScheduler(int totalSlots) {
        this.dirtySlots = new BitSet(Math.max(1, totalSlots));
    }

    public void markDirty(int globalSlot) {
        if (globalSlot < 0) {
            return;
        }
        dirtySlots.set(globalSlot);
        providerRefreshPending = true;
    }

    public void markRangeDirty(int startInclusive, int endExclusive) {
        if (endExclusive <= startInclusive) {
            return;
        }
        dirtySlots.set(Math.max(0, startInclusive), Math.max(0, endExclusive));
        providerRefreshPending = true;
    }

    public List<Integer> drainDirtySlots() {
        return drainDirtySlots(Integer.MAX_VALUE);
    }

    public List<Integer> drainDirtySlots(int maxSlots) {
        List<Integer> drained = new ArrayList<>();
        int limit = Math.max(0, maxSlots);
        for (int slot = dirtySlots.nextSetBit(0);
             slot >= 0 && drained.size() < limit;
             slot = dirtySlots.nextSetBit(slot + 1)) {
            drained.add(slot);
            dirtySlots.clear(slot);
        }
        if (limit == Integer.MAX_VALUE) {
            dirtySlots.clear();
        }
        providerRefreshPending = !dirtySlots.isEmpty();
        return drained;
    }

    public boolean hasPendingWork() {
        return providerRefreshPending || !dirtySlots.isEmpty();
    }

    public int dirtyCount() {
        return dirtySlots.cardinality();
    }

    public void clear() {
        dirtySlots.clear();
        providerRefreshPending = false;
    }
}
