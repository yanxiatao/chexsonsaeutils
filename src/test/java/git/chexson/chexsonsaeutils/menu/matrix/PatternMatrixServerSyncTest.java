package git.chexson.chexsonsaeutils.menu.matrix;

import appeng.api.inventories.InternalInventory;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定下发引擎的节流口径：只发变化格、按预算续扫、撤销格用空栈表达、派生版本变化能重发。
 */
class PatternMatrixServerSyncTest {

    @Test
    void firstBroadcastSendsOnlyOccupiedSlotsOnce() {
        FakeSource source = new FakeSource(10);
        source.put(1, stack());
        source.put(4, stack());
        source.put(7, stack());
        PatternMatrixServerSync sync = new PatternMatrixServerSync();

        List<PatternMatrixEntry> changes = sync.collectChanges(source, 100);

        assertEquals(Set.of(1, 4, 7), slotSet(changes));
        assertEquals(3, sync.observedSlotCount());
        assertEquals(List.of(), sync.collectChanges(source, 100));
    }

    @Test
    void scanStopsAtThePerCallBudgetAndResumesWhereItStopped() {
        FakeSource source = new FakeSource(5);
        for (int slot = 0; slot < 5; slot++) {
            source.put(slot, stack());
        }
        PatternMatrixServerSync sync = new PatternMatrixServerSync();

        assertEquals(2, sync.collectChanges(source, 2).size());
        assertEquals(2, sync.collectChanges(source, 2).size());
        assertEquals(1, sync.collectChanges(source, 2).size());
        assertEquals(List.of(), sync.collectChanges(source, 2));
    }

    @Test
    void clearingAnObservedSlotEmitsOneEmptyEntry() {
        FakeSource source = new FakeSource(6);
        source.put(4, stack());
        PatternMatrixServerSync sync = new PatternMatrixServerSync();
        sync.collectChanges(source, 100);

        source.put(4, ItemStack.EMPTY);
        List<PatternMatrixEntry> changes = sync.collectChanges(source, 100);

        assertEquals(1, changes.size());
        assertEquals(4, changes.get(0).slot());
        assertTrue(changes.get(0).displayStack().isEmpty());
        assertEquals(0, sync.observedSlotCount());
        assertEquals(List.of(), sync.collectChanges(source, 100));
    }

    @Test
    void detailVersionBumpResendsDerivedDataWithoutStackChange() {
        FakeSource source = new FakeSource(3);
        source.put(2, stack());
        PatternMatrixServerSync sync = new PatternMatrixServerSync();
        sync.collectChanges(source, 100);

        source.detailVersion++;
        List<PatternMatrixEntry> changes = sync.collectChanges(source, 100);

        assertEquals(Set.of(2), slotSet(changes));
        assertEquals(List.of(), sync.collectChanges(source, 100));
    }

    @Test
    void resetDropsTheSnapshotSoTheNextPassIsFull() {
        FakeSource source = new FakeSource(4);
        source.put(0, stack());
        source.put(3, stack());
        PatternMatrixServerSync sync = new PatternMatrixServerSync();
        sync.collectChanges(source, 100);

        sync.reset();

        assertEquals(0, sync.observedSlotCount());
        assertEquals(Set.of(0, 3), slotSet(sync.collectChanges(source, 100)));
    }

    @Test
    void broadcastSplitsChangesIntoBoundedPackets() {
        FakeSource source = new FakeSource(400);
        for (int slot = 0; slot < 300; slot++) {
            source.put(slot, stack());
        }
        PatternMatrixServerSync sync = new PatternMatrixServerSync();
        List<List<PatternMatrixEntry>> packets = new ArrayList<>();

        sync.broadcastChanges(source, packets::add);

        int total = 0;
        for (List<PatternMatrixEntry> packet : packets) {
            assertTrue(packet.size() <= PatternMatrixServerSync.ENTRIES_PER_PACKET,
                    "单包条目数必须受限，避免拼出巨型包");
            total += packet.size();
        }
        assertEquals(300, total);
        assertEquals(3, packets.size());
    }

    @Test
    void emptyStoreProducesNoTraffic() {
        FakeSource source = new FakeSource(0);
        PatternMatrixServerSync sync = new PatternMatrixServerSync();

        assertEquals(List.of(), sync.collectChanges(source, 100));
    }

    private static Set<Integer> slotSet(List<PatternMatrixEntry> entries) {
        Set<Integer> slots = new HashSet<>();
        for (PatternMatrixEntry entry : entries) {
            slots.add(entry.slot());
        }
        return slots;
    }

    private static ItemStack stack() {
        return new ItemStack(Items.PAPER);
    }

    private static final class FakeSource implements PatternMatrixSource {

        private final AppEngInternalInventory inventory;
        private long detailVersion;

        private FakeSource(int totalSlots) {
            this.inventory = new AppEngInternalInventory(new FakeHost(), Math.max(1, totalSlots), 1);
        }

        private void put(int slot, ItemStack stack) {
            inventory.setItemDirect(slot, stack);
        }

        @Override
        public InternalInventory patternMatrixInventory() {
            return inventory;
        }

        @Override
        public int patternMatrixTotalSlots() {
            return inventory.size();
        }

        @Override
        public ItemStack patternMatrixDisplayStack(int slot) {
            return inventory.getStackInSlot(slot).copy();
        }

        @Override
        public int patternMatrixStatusCode(int slot) {
            return 0;
        }

        @Override
        public int patternMatrixReasonCode(int slot) {
            return 0;
        }

        @Override
        public long patternMatrixDetailVersion() {
            return detailVersion;
        }

        @Override
        public boolean patternMatrixMayPlace(ItemStack stack) {
            return !stack.isEmpty();
        }

        @Override
        public int patternMatrixSearchMatches(String query, long[] matchBits) {
            return 0;
        }
    }

    private static final class FakeHost implements InternalInventoryHost {

        @Override
        public void saveChangedInventory(AppEngInternalInventory inv) {
        }

        @Override
        public boolean isClientSide() {
            return false;
        }
    }
}
