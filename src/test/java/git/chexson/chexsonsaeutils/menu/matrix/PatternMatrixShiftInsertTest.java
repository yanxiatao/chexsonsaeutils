package git.chexson.chexsonsaeutils.menu.matrix;

import appeng.api.inventories.InternalInventory;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定背包 shift 点击的服务端写入口径：矩阵格子是客户端虚拟槽，
 * shift 只能由菜单在 {@code transferStackToMenu} 里直接写全量库存，
 * 因此这里钉住首个空槽优先、每格一份、非法样板拒绝与满仓不动。
 */
class PatternMatrixShiftInsertTest {

    @Test
    void wholeStackSpreadsOnePatternPerSlotFromTheFirstFreeSlot() {
        FakeSource source = new FakeSource(10);

        int inserted = PatternMatrixInteraction.shiftInsertIntoMatrix(source, stack(Items.PAPER, 3));

        assertEquals(3, inserted);
        assertEquals(3, countOccupied(source));
        assertEquals(Items.PAPER, itemAt(source, 0));
        assertEquals(Items.PAPER, itemAt(source, 2));
        assertEquals(1, source.inventory.getStackInSlot(0).getCount());
    }

    @Test
    void occupiedSlotsAreSkipped() {
        FakeSource source = new FakeSource(4);
        source.put(0, stack(Items.STONE, 1));

        int inserted = PatternMatrixInteraction.shiftInsertIntoMatrix(source, stack(Items.PAPER, 2));

        assertEquals(2, inserted);
        assertEquals(Items.STONE, itemAt(source, 0));
        assertEquals(Items.PAPER, itemAt(source, 1));
        assertEquals(Items.PAPER, itemAt(source, 2));
        assertTrue(source.inventory.getStackInSlot(3).isEmpty());
    }

    @Test
    void rejectedStackInsertsNothing() {
        FakeSource source = new FakeSource(4);
        source.accepts = false;

        assertEquals(0, PatternMatrixInteraction.shiftInsertIntoMatrix(source, stack(Items.PAPER, 2)));
        assertEquals(0, countOccupied(source));
    }

    @Test
    void fullMatrixTakesNothing() {
        FakeSource source = new FakeSource(2);
        source.put(0, stack(Items.STONE, 1));
        source.put(1, stack(Items.STONE, 1));

        assertEquals(0, PatternMatrixInteraction.shiftInsertIntoMatrix(source, stack(Items.PAPER, 2)));
        assertEquals(2, countOccupied(source));
    }

    private static Item itemAt(FakeSource source, int slot) {
        return source.inventory.getStackInSlot(slot).getItem();
    }

    private static int countOccupied(FakeSource source) {
        int used = 0;
        for (int slot = 0; slot < source.inventory.size(); slot++) {
            if (!source.inventory.getStackInSlot(slot).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    private static final class FakeSource implements PatternMatrixSource {

        private final AppEngInternalInventory inventory;
        private boolean accepts = true;

        private FakeSource(int totalSlots) {
            this.inventory = new AppEngInternalInventory(new FakeHost(), totalSlots, 1);
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
            return 0L;
        }

        @Override
        public boolean patternMatrixMayPlace(ItemStack stack) {
            return accepts && !stack.isEmpty();
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
