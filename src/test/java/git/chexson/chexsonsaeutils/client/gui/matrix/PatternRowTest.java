package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStack;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSearch;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定行镜像的取数与过滤口径：本体优先、摘要兜底，整行只要有一格命中就保留。
 */
class PatternRowTest {

    private static final int COLUMNS = 9;
    private static final ItemStack PAPER = new ItemStack(Items.PAPER);
    private static final ItemStack STONE = new ItemStack(Items.STONE);

    @Test
    void writesByGlobalSlotAndReadsBackByRowColumn() {
        PatternRow row = new PatternRow(18, COLUMNS);

        row.setItemByInvSlot(20, PAPER);

        assertTrue(ItemStack.isSameItemSameComponents(PAPER, row.inventory().getStackInSlot(2)));
        assertEquals(20, row.globalSlot(2));
    }

    @Test
    void syncPrefersTheRealStackAndFallsBackToTheDisplaySummary() {
        PatternMatrixClientView view = new PatternMatrixClientView();
        view.resize(30);
        view.apply(List.of(new PatternMatrixEntry(18, PAPER, 0, 0), new PatternMatrixEntry(19, STONE, 0, 0)));
        view.applyRealStacks(List.of(new PatternMatrixRealStack(18, STONE)));
        PatternRow row = new PatternRow(18, COLUMNS);

        row.syncFrom(view);

        assertTrue(ItemStack.isSameItemSameComponents(STONE, row.inventory().getStackInSlot(0)));
        assertTrue(ItemStack.isSameItemSameComponents(STONE, row.inventory().getStackInSlot(1)));
        assertTrue(row.inventory().getStackInSlot(8).isEmpty());
    }

    @Test
    void aSingleHitKeepsTheWholeRow() {
        PatternMatrixClientView view = viewWithMatches(30, 20);
        PatternRow matchedRow = new PatternRow(18, COLUMNS);
        PatternRow cleanRow = new PatternRow(0, COLUMNS);

        assertTrue(matchedRow.matchesSearch(view));
        assertFalse(cleanRow.matchesSearch(view));
    }

    @Test
    void contentFlagFollowsTheSyncedCells() {
        PatternMatrixClientView view = new PatternMatrixClientView();
        view.resize(30);
        view.apply(List.of(new PatternMatrixEntry(26, PAPER, 0, 0)));

        assertTrue(new PatternRow(18, COLUMNS).hasContent(view));
        assertFalse(new PatternRow(0, COLUMNS).hasContent(view));
    }

    private static PatternMatrixClientView viewWithMatches(int totalSlots, int... matchedSlots) {
        PatternMatrixClientView view = new PatternMatrixClientView();
        view.resize(totalSlots);
        long[] bits = new long[PatternMatrixSearch.bitsetSize(totalSlots)];
        for (int slot : matchedSlots) {
            bits[slot >>> 6] |= 1L << (slot & 63);
        }
        view.applyMatches(bits, matchedSlots.length);
        return view;
    }
}
