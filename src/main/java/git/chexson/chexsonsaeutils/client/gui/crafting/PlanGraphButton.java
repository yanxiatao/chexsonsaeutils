package git.chexson.chexsonsaeutils.client.gui.crafting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** 图页面工具栏的纯色按钮，背景不依赖 AE2 或资源包的按钮贴图尺寸。 */
final class PlanGraphButton extends Button {
    PlanGraphButton(int x, int y, int width, Component label, Runnable action) {
        super(x, y, width, 20, label, ignored -> action.run(), DEFAULT_NARRATION);
        setTooltip(Tooltip.create(label));
    }
    @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int border = isHoveredOrFocused() ? 0xff91b4c9 : 0xff455663;
        int fill = !active ? 0xff20272e : isHoveredOrFocused() ? 0xff354957 : 0xff293640;
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
        g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, fill);
        var font = Minecraft.getInstance().font;
        String label = font.plainSubstrByWidth(getMessage().getString(), Math.max(4, getWidth() - 6));
        g.drawCenteredString(font, label, getX() + getWidth() / 2, getY() + 6, active ? 0xffe5edf2 : 0xff82909a);
    }
}
