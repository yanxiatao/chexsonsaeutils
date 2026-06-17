package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.ids.AEComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 样板染色辅助逻辑。
 *
 * 只处理颜色元数据读取，不改动样板定义本体。
 */
public final class PatternColorHelper {

    private PatternColorHelper() {
    }

    public static int getPatternColor(@Nullable IPatternDetails patternDetails) {
        if (!(patternDetails instanceof IPatternDetailsColorAccessor colorAccessor)) {
            return -1;
        }
        return colorAccessor.chexsonsaeutils$getColor();
    }

    public static int getPatternColor(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        return DyedItemColor.getOrDefault(stack, -1);
    }

    public static boolean hasOnlyColorData(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.has(DataComponents.DYED_COLOR)
                && !stack.has(DataComponents.CUSTOM_NAME)
                && !stack.has(DataComponents.CUSTOM_DATA)
                && !stack.has(AEComponents.ENCODED_CRAFTING_PATTERN)
                && !stack.has(AEComponents.ENCODED_PROCESSING_PATTERN)
                && !stack.has(AEComponents.ENCODED_STONECUTTING_PATTERN)
                && !stack.has(AEComponents.ENCODED_SMITHING_TABLE_PATTERN);
    }

    public static List<IPatternDetails> orderPatternsByColor(
            @Nullable Collection<? extends IPatternDetails> patterns,
            int preferredColor
    ) {
        if (patterns == null || patterns.isEmpty() || preferredColor == -1) {
            return patterns == null ? List.of() : patterns.stream().map(details -> (IPatternDetails) details).toList();
        }
        return patterns.stream()
                .map(details -> (IPatternDetails) details)
                .sorted(Comparator.comparingInt(details -> getPatternColor(details) == preferredColor ? 0 : 1))
                .toList();
    }
}
