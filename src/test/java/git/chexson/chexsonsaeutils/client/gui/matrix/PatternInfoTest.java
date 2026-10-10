package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定装配矩阵行切分的移植件口径：屏幕位置只由全局槽号决定，末尾行可以不满，
 * 零容量不产出任何行；参与滚动的行截断到最后一个有内容的行再加一条空行。
 */
class PatternInfoTest {

    private static final int COLUMNS = 9;
    private static final ItemStack PAPER = new ItemStack(Items.PAPER);

    @Test
    void slicesWholeInventoryIntoFixedRows() {
        PatternInfo info = new PatternInfo(30, COLUMNS);

        assertEquals(4, info.rowCount());
        assertEquals(0, info.internalRows().get(0).offset());
        assertEquals(9, info.internalRows().get(1).offset());
        assertEquals(3, info.internalRows().get(3).slots());
    }

    @Test
    void listingStopsAtTheLastContentRowPlusOneFreeRow() {
        PatternInfo info = new PatternInfo(45, COLUMNS);

        List<PatternRow> listed = info.listRows(view(45, List.of(20), null));

        assertEquals(List.of(0, 9, 18, 27), listed.stream().map(PatternRow::offset).toList());
    }

    @Test
    void emptyMachineStillListsOneFreeRowForPlacing() {
        PatternInfo info = new PatternInfo(45, COLUMNS);

        assertEquals(1, info.listRows(view(45, List.of(), null)).size());
    }

    @Test
    void fullMachineAppendsNoFreeRow() {
        PatternInfo info = new PatternInfo(45, COLUMNS);

        assertEquals(5, info.listRows(view(45, List.of(44), null)).size());
    }

    @Test
    void filteringListsOnlyRowsHoldingAMatch() {
        PatternInfo info = new PatternInfo(45, COLUMNS);

        List<PatternRow> listed = info.listRows(view(45, List.of(20), List.of(30)));

        assertEquals(1, listed.size());
        assertSame(info.internalRows().get(3), listed.get(0));
    }

    @Test
    void emptyOrDegenerateCapacityProducesNoRows() {
        assertTrue(new PatternInfo(0, COLUMNS).listRows(view(0, List.of(), null)).isEmpty());
        assertTrue(new PatternInfo(-5, COLUMNS).internalRows().isEmpty());
        assertTrue(new PatternInfo(30, 0).internalRows().isEmpty());
    }

    /** 命中位图要生效必须先有过滤标记，因此有命中时把命中槽同时填上内容。 */
    private static PatternMatrixClientView view(int totalSlots, List<Integer> filledSlots,
                                                List<Integer> matchedSlots) {
        PatternMatrixClientView view = new PatternMatrixClientView();
        view.resize(totalSlots);
        List<PatternMatrixEntry> entries = filledSlots.stream()
                .map(slot -> new PatternMatrixEntry(slot, PAPER, 0, 0))
                .toList();
        if (!entries.isEmpty()) {
            view.apply(entries);
        }
        if (matchedSlots == null) {
            return view;
        }
        long[] bits = new long[(totalSlots + 63) >>> 6];
        for (int slot : matchedSlots) {
            bits[slot >>> 6] |= 1L << (slot & 63);
        }
        view.applyMatches(bits, matchedSlots.size());
        return view;
    }
}
