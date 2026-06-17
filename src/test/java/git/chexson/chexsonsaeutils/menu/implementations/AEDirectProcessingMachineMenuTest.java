package git.chexson.chexsonsaeutils.menu.implementations;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AEDirectProcessingMachineMenuTest {

    @Test
    void showsPatternSlotEmptyTooltipForEmptyDisplayedStack() {
        assertTrue(AEDirectProcessingMachineMenu.shouldShowPatternSlotEmptyTooltip(ItemStack.EMPTY));
    }

    @Test
    void hidesPatternSlotEmptyTooltipForOccupiedDisplayedStack() {
        assertFalse(AEDirectProcessingMachineMenu.shouldShowPatternSlotEmptyTooltip(
                new ItemStack(Items.CRAFTING_TABLE)
        ));
    }
}
