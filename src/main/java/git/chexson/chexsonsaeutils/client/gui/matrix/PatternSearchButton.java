package git.chexson.chexsonsaeutils.client.gui.matrix;

import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.IconButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** 复用 ExtendedAE 扩展样板终端的图标、尺寸、悬停和焦点绘制。 */
public final class PatternSearchButton extends IconButton {
    private static final ResourceLocation TEXTURE = ResourceLocation.parse("extendedae:textures/guis/nicons.png");
    private PatternSearchMode mode = PatternSearchMode.INPUT_OUTPUT;

    public PatternSearchButton() {
        super(button -> ((PatternSearchButton) button).nextMode());
        width = 12;
        height = 12;
        updateMessage();
    }
    public PatternSearchMode mode() { return mode; }
    private void nextMode() { mode = mode.next(); updateMessage(); }
    private void updateMessage() {
        setMessage(Component.translatable("gui.chexsonsaeutils.matrix.search_mode."
                + mode.name().toLowerCase(java.util.Locale.ROOT)));
    }
    @Override protected Icon getIcon() { return null; }
    @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        if (!visible) return;
        int offset = isHovered() ? 1 : 0;
        int background = isHovered() ? 40 : isFocused() ? 28 : 16;
        Blitter.texture(TEXTURE, 64, 64).src(background, 16, 12, 12)
                .dest(getX(), getY() + offset, 12, 12).zOffset(2).blit(graphics);
        int icon = switch (mode) { case INPUT -> 0; case OUTPUT -> 16; case INPUT_OUTPUT -> 32; };
        Blitter.texture(TEXTURE, 64, 64).src(icon, 48, 12, 12)
                .dest(getX(), getY() + offset).zOffset(3).blit(graphics);
    }
    @Override public Rect2i getTooltipArea() { return new Rect2i(getX(), getY(), 12, 12); }
    @Override public List<Component> getTooltipMessage() {
        return List.of(Component.translatable("gui.chexsonsaeutils.matrix.search_mode"),
                getMessage().copy().withStyle(ChatFormatting.GRAY));
    }
}
