package git.chexson.chexsonsaeutils.client.gui.matrix;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 装配矩阵的提示与文本排版工具：shift 明细提示、重复行去重、右上角页码与按宽缩放文本。
 */
public final class MatrixText {

    private MatrixText() {
    }

    /** 按住 shift 时把槽位明细行按字符串内容去重后追加。 */
    public static List<Component> shiftDetailTooltipDistinct(
            List<Component> base,
            boolean shiftDown,
            Slot hoveredSlot,
            ItemStack stack,
            int matrixSlotIndex,
            Supplier<List<Component>> detailLines
    ) {
        List<Component> detail = detailWhenShiftHeld(
                shiftDown, hoveredSlot, stack, matrixSlotIndex, detailLines);
        if (detail == null) {
            return base;
        }
        List<Component> augmented = new ArrayList<>(base);
        appendMissingLines(augmented, detail);
        return List.copyOf(augmented);
    }

    private static List<Component> detailWhenShiftHeld(
            boolean shiftDown,
            Slot hoveredSlot,
            ItemStack stack,
            int matrixSlotIndex,
            Supplier<List<Component>> detailLines
    ) {
        if (!shiftDown || hoveredSlot == null || stack.isEmpty() || matrixSlotIndex < 0) {
            return null;
        }
        List<Component> detail = detailLines.get();
        return detail == null || detail.isEmpty() ? null : detail;
    }

    /** 按字符串内容去重后追加，避免明细行与物品自带提示重复。 */
    public static void appendMissingLines(List<Component> tooltip, List<Component> additions) {
        if (tooltip == null || additions == null || additions.isEmpty()) {
            return;
        }
        var existingLines = tooltip.stream()
                .filter(Objects::nonNull)
                .map(Component::getString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (Component addition : additions) {
            if (addition == null) {
                continue;
            }
            if (existingLines.add(addition.getString())) {
                tooltip.add(addition);
            }
        }
    }

    /** 右上角页码标签，与两台提供方页面的 "当前/总数" 写法一致。 */
    public static void drawPageIndicator(
            GuiGraphics guiGraphics,
            Font font,
            int imageWidth,
            int y,
            int pageIndex,
            int pageCount
    ) {
        String pageText = (pageIndex + 1) + "/" + pageCount;
        guiGraphics.drawString(font, pageText, imageWidth - 8 - font.width(pageText), y, 0x404040);
    }

    public static float fittedScale(Font font, Component text, int maxWidth, float preferredScale, float minimumScale) {
        int textWidth = Math.max(1, font.width(text));
        float widthScale = maxWidth / (float) textWidth;
        return Mth.clamp(widthScale, minimumScale, preferredScale);
    }

    public static void drawCenteredFitted(
            GuiGraphics guiGraphics,
            Font font,
            Component text,
            int x,
            int y,
            int width,
            int height,
            int color,
            float preferredScale,
            float minimumScale
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float scale = fittedScale(font, text, width, preferredScale, minimumScale);
        int scaledHeight = Math.max(1, Mth.ceil(font.lineHeight * scale));
        int drawY = y + Math.max(0, (height - scaledHeight) / 2);
        guiGraphics.enableScissor(x, y, x + width, y + height);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x + width / 2.0f, drawY, 0.0f);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, text, -font.width(text) / 2, 0, color, false);
        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();
    }

    public static List<Component> wrapLabelToWidth(Font font, String value, int maxWidth) {
        List<Component> lines = new ArrayList<>();
        if (value.isEmpty()) {
            lines.add(Component.empty());
            return lines;
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (builder.length() > 0 && font.width(builder.toString() + current) > maxWidth) {
                lines.add(Component.literal(builder.toString()));
                builder.setLength(0);
            }
            builder.append(current);
        }
        if (builder.length() > 0) {
            lines.add(Component.literal(builder.toString()));
        }
        return lines.isEmpty() ? List.of(Component.empty()) : List.copyOf(lines);
    }

    public static String shortenTextToWidth(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (builder.length() > 0 && font.width(builder.toString() + current + ellipsis) > maxWidth) {
                break;
            }
            builder.append(current);
        }
        return builder.isEmpty() ? ellipsis : builder + ellipsis;
    }
}
