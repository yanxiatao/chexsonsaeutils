package git.chexson.chexsonsaeutils.menu.matrix;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定客户端视图的增量口径：只有真正改变内容的格子才递增 changeCount，
 * 内容一变就作废按需索取的样板本体，命中位图与过滤开关同生同灭。
 */
class PatternMatrixClientViewTest {

    private static final ItemStack PAPER = new ItemStack(Items.PAPER);
    private static final ItemStack STONE = new ItemStack(Items.STONE);

    @Test
    void resizeAllocatesCapacityAndCountsExistingSlots() {
        PatternMatrixClientView view = new PatternMatrixClientView();

        view.resize(100);
        assertEquals(100, view.getTotalSlots());
        assertEquals(0, view.getNonEmptySlots());

        view.apply(List.of(entry(3, PAPER, 1, 2)));
        assertEquals(1, view.getNonEmptySlots());
        assertTrue(view.hasDisplayStack(3));
        assertEquals(1, view.statusCodeAt(3));
        assertEquals(2, view.reasonCodeAt(3));
    }

    @Test
    void shrinkingResizeDropsTailingSlotsAndKeepsTheRest() {
        PatternMatrixClientView view = view(10, 2, 7);
        int changesBeforeResize = view.changeCount();

        view.resize(5);

        assertEquals(5, view.getTotalSlots());
        assertTrue(view.hasDisplayStack(2));
        assertFalse(view.hasDisplayStack(7));
        assertEquals(1, view.getNonEmptySlots());
        assertTrue(view.changeCount() > changesBeforeResize);
    }

    @Test
    void identicalResyncDoesNotTouchChangeCount() {
        PatternMatrixClientView view = view(10, 4);
        int changes = view.changeCount();

        view.apply(List.of(entry(4, PAPER, 0, 0)));
        view.apply(List.of(entry(9, ItemStack.EMPTY, 0, 0)));

        assertEquals(changes, view.changeCount());
        assertEquals(1, view.getNonEmptySlots());
    }

    @Test
    void contentChangeInvalidatesTheOnDemandRealStack() {
        PatternMatrixClientView view = view(10, 4);
        view.applyRealStacks(List.of(new PatternMatrixRealStack(4, PAPER.copy())));
        assertTrue(view.hasRealStack(4));

        view.apply(List.of(entry(4, STONE, 0, 0)));

        assertFalse(view.hasRealStack(4));
        assertTrue(ItemStack.isSameItemSameComponents(STONE, view.displayStackAt(4)));
    }

    @Test
    void realStacksAreOnlyAcceptedForSlotsTheViewAlreadyShows() {
        PatternMatrixClientView view = view(10, 4);

        view.applyRealStacks(List.of(new PatternMatrixRealStack(5, PAPER)));

        assertFalse(view.hasRealStack(5));
        assertTrue(view.realStackAt(5).isEmpty());
    }

    @Test
    void clearingASlotUpdatesTheUsedCount() {
        PatternMatrixClientView view = view(10, 2, 7);

        view.apply(List.of(entry(7, ItemStack.EMPTY, 0, 0)));

        assertEquals(1, view.getNonEmptySlots());
        assertFalse(view.hasDisplayStack(7));
    }

    @Test
    void matchBitmapDrivesTheFilterFlag() {
        PatternMatrixClientView view = view(100, 3);

        assertFalse(view.filterActive());

        long[] bits = new long[PatternMatrixSearch.bitsetSize(100)];
        bits[3 >>> 6] |= 1L << (3 & 63);
        int changesBefore = view.changeCount();
        view.applyMatches(bits, 1);

        assertTrue(view.filterActive());
        assertEquals(1, view.matchCount());
        assertTrue(view.isMatched(3));
        assertTrue(view.changeCount() > changesBefore);

        view.applyMatches(bits, 1);
        assertEquals(changesBefore + 1, view.changeCount());

        view.applyMatches(new long[0], 0);
        assertFalse(view.filterActive());
    }

    @Test
    void outOfRangeReadsAreEmptyRatherThanFailing() {
        PatternMatrixClientView view = view(4, 1);

        assertTrue(view.displayStackAt(99).isEmpty());
        assertTrue(view.realStackAt(-1).isEmpty());
        assertEquals(0, view.statusCodeAt(99));
        assertFalse(view.isMatched(-1));
    }

    private static PatternMatrixClientView view(int totalSlots, int... filledSlots) {
        PatternMatrixClientView view = new PatternMatrixClientView();
        view.resize(totalSlots);
        List<PatternMatrixEntry> entries = new ArrayList<>();
        for (int slot : filledSlots) {
            entries.add(entry(slot, PAPER, 0, 0));
        }
        view.apply(entries);
        return view;
    }

    private static PatternMatrixEntry entry(int slot, ItemStack displayStack, int statusCode, int reasonCode) {
        return new PatternMatrixEntry(slot, displayStack, statusCode, reasonCode);
    }
}
