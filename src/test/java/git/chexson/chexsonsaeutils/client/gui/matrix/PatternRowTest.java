package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定行镜像的取数与过滤口径：本体优先、摘要兜底，整行只要有一格命中就保留。
 */
class PatternRowTest {

    private static final int COLUMNS = 9;
    private static final ItemStack PAPER = new ItemStack(Items.PAPER);
    private static final ItemStack STONE = new ItemStack(Items.STONE);

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

}
