package git.chexson.chexsonsaeutils.menu.matrix;

import appeng.helpers.InventoryAction;
import net.minecraft.world.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 锁定装配矩阵格子的点击换算，逐条对齐 ExtendedAE 的装配矩阵：
 * 左键取放、右键拆分/放置、shift 快速移动、创造模式复制。
 */
class PatternMatrixInteractionClickTest {

    @Test
    void pickupMapsLeftToPickupOrSetDownAndRightToSplit() {
        assertEquals(InventoryAction.PICKUP_OR_SET_DOWN,
                PatternMatrixInteraction.clickAction(ClickType.PICKUP, false, false));
        assertEquals(InventoryAction.SPLIT_OR_PLACE_SINGLE,
                PatternMatrixInteraction.clickAction(ClickType.PICKUP, true, false));
    }

    @Test
    void quickMoveMapsLeftToShiftClickAndRightToPickupSingle() {
        assertEquals(InventoryAction.SHIFT_CLICK,
                PatternMatrixInteraction.clickAction(ClickType.QUICK_MOVE, false, false));
        assertEquals(InventoryAction.PICKUP_SINGLE,
                PatternMatrixInteraction.clickAction(ClickType.QUICK_MOVE, true, false));
    }

    @Test
    void cloneOnlyDuplicatesInCreative() {
        assertEquals(InventoryAction.CREATIVE_DUPLICATE,
                PatternMatrixInteraction.clickAction(ClickType.CLONE, false, true));
        assertNull(PatternMatrixInteraction.clickAction(ClickType.CLONE, false, false));
    }

    @Test
    void unsupportedClickTypesProduceNoAction() {
        assertNull(PatternMatrixInteraction.clickAction(ClickType.THROW, false, true));
        assertNull(PatternMatrixInteraction.clickAction(ClickType.SWAP, false, true));
        assertNull(PatternMatrixInteraction.clickAction(ClickType.PICKUP_ALL, false, true));
    }
}
