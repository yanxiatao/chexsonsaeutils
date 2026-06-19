package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.ids.AEComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
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
    private static final String LEGACY_DISPLAY_TAG = "display";
    private static final String LEGACY_COLOR_TAG = "color";

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
        int componentColor = DyedItemColor.getOrDefault(stack, -1);
        if (componentColor != -1) {
            return normalizeOpaqueColor(componentColor);
        }
        return getLegacyDisplayColor(stack);
    }

    public static boolean hasOnlyColorData(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        boolean hasColor = stack.has(DataComponents.DYED_COLOR) || getLegacyDisplayColor(stack) != -1;
        return hasColor
                && !stack.has(DataComponents.CUSTOM_NAME)
                && hasOnlyLegacyDisplayColorData(stack)
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

    private static int getLegacyDisplayColor(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return -1;
        }
        CompoundTag tag = customData.copyTag();
        if (!tag.contains(LEGACY_DISPLAY_TAG, Tag.TAG_COMPOUND)) {
            return -1;
        }
        CompoundTag displayTag = tag.getCompound(LEGACY_DISPLAY_TAG);
        if (!displayTag.contains(LEGACY_COLOR_TAG, Tag.TAG_ANY_NUMERIC)) {
            return -1;
        }
        return normalizeOpaqueColor(displayTag.getInt(LEGACY_COLOR_TAG));
    }

    private static boolean hasOnlyLegacyDisplayColorData(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return true;
        }
        CompoundTag tag = customData.copyTag();
        if (tag.size() != 1 || !tag.contains(LEGACY_DISPLAY_TAG, Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag displayTag = tag.getCompound(LEGACY_DISPLAY_TAG);
        return displayTag.size() == 1 && displayTag.contains(LEGACY_COLOR_TAG, Tag.TAG_ANY_NUMERIC);
    }

    private static int normalizeOpaqueColor(int color) {
        return (color & 0xFF000000) == 0 ? color | 0xFF000000 : color;
    }
}
