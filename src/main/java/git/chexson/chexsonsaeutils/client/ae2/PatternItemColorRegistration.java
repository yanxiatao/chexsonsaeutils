package git.chexson.chexsonsaeutils.client.ae2;

import appeng.core.definitions.AEItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * 编码样板颜色注册。
 *
 * 只负责把染色 component 映射到 item tint。
 */
public final class PatternItemColorRegistration {

    private PatternItemColorRegistration() {
    }

    public static void register(RegisterColorHandlersEvent.Item event) {
        event.register(PatternItemColorRegistration::getColor, AEItems.CRAFTING_PATTERN, AEItems.PROCESSING_PATTERN,
                AEItems.STONECUTTING_PATTERN, AEItems.SMITHING_TABLE_PATTERN);
    }

    private static int getColor(ItemStack stack, int tintIndex) {
        if (tintIndex != 1) {
            return -1;
        }
        var color = stack.get(DataComponents.DYED_COLOR);
        if (color == null) {
            return -1;
        }
        return FastColor.ARGB32.opaque(color.rgb());
    }
}
