package git.chexson.chexsonsaeutils.integration.buildinggadgets2;

import appeng.core.definitions.AEItems;
import net.minecraft.world.item.ItemStack;

/**
 * ponytail: BG2 API not available as compile dependency in 1.20.1.
 * Full bridge with BG2Data/GadgetNBT/MiscHelpers requires BG2 at runtime.
 */
public final class BuildingGadgets2TemplatePatternBridge {

    private BuildingGadgets2TemplatePatternBridge() {
    }

    public static boolean isAe2PatternTarget(ItemStack stack) {
        return stack.is(AEItems.BLANK_PATTERN.asItem()) || stack.is(AEItems.PROCESSING_PATTERN.asItem());
    }
}
