package git.chexson.chexsonsaeutils.client.gui.playersimulation;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import java.util.function.Supplier;

/** 沿用 AE2 按钮背景，绘制随操作状态变化的专用像素图标。 */
public final class SimulationIconButton extends IconButton {
    /** 图集顺序对应 tools/playersimulation/generate_icons.py，单格 16 像素。 */
    public enum Glyph { LEFT, RIGHT, TAP, HOLD, BLOCK, ENTITY, AIR, RANDOM, ROUND_ROBIN, SEQUENTIAL,
        MATCH, IGNORE, TIMER, CONTINUOUS, SNEAK, STAND, RETURN, IDENTITY, AUTO, NORTH, SOUTH, WEST, EAST, UP, DOWN }
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("chexsonsaeutils","textures/gui/player_simulation_icons.png");
    private final Supplier<Glyph> glyph;
    public SimulationIconButton(OnPress press,Supplier<Glyph> glyph) { super(press); this.glyph = glyph; }
    @Override protected Icon getIcon() { return null; }
    @Override public void renderWidget(GuiGraphics graphics,int mouseX,int mouseY,float partial) {
        super.renderWidget(graphics,mouseX,mouseY,partial);
        if (!visible) return;
        graphics.pose().pushPose();
        graphics.pose().translate(0,0,3);
        graphics.blit(TEXTURE,getX(),getY()+1+(isHovered()?1:0),glyph.get().ordinal()*16,0,16,16,Glyph.values().length*16,16);
        graphics.pose().popPose();
    }
}
