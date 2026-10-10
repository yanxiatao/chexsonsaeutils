package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** 在渲染线程一次取得真实 AEKey 图标，PNG 全图复用同一份图标而非文字卡片。 */
final class PlanGraphIcons {
    private PlanGraphIcons() {}
    static Map<AEKey, BufferedImage> capture(Collection<AEKey> source) throws IOException {
        RenderSystem.assertOnRenderThread();
        var keys = new ArrayList<>(new LinkedHashSet<>(source));
        if (keys.isEmpty()) return Map.of();
        int columns = (int) Math.ceil(Math.sqrt(keys.size())), scale = PlanGraphPng.SCALE, cell = 20 * scale;
        int w = columns * cell, h = ((keys.size() + columns - 1) / columns) * cell;
        var minecraft = Minecraft.getInstance();
        var target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var view = RenderSystem.getModelViewStack(); view.pushMatrix();
        try {
            target.setClearColor(0, 0, 0, 0); target.clear(Minecraft.ON_OSX); target.bindWrite(true);
            view.identity().translate(0, 0, -2000); RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, w, h, 0, 1000, 3000), VertexSorting.ORTHOGRAPHIC_Z);
            var graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            graphics.pose().scale(scale, scale, 1);
            for (int i = 0; i < keys.size(); i++) AEKeyRendering.drawInGui(minecraft, graphics, (i % columns) * 20 + 2, (i / columns) * 20 + 2, keys.get(i));
            graphics.flush();
            try (var image = Screenshot.takeScreenshot(target)) {
                var atlas = ImageIO.read(new ByteArrayInputStream(image.asByteArray()));
                if (atlas == null) throw new IOException("图标图集读取失败");
                var result = new HashMap<AEKey, BufferedImage>();
                for (int i = 0; i < keys.size(); i++) result.put(keys.get(i), atlas.getSubimage((i % columns) * cell + 2 * scale, (i / columns) * cell + 2 * scale, 16 * scale, 16 * scale));
                return Map.copyOf(result);
            }
        } finally {
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            view.popMatrix(); RenderSystem.applyModelViewMatrix();
            minecraft.getMainRenderTarget().bindWrite(true); RenderSystem.setShaderColor(1, 1, 1, 1);
            target.destroyBuffers();
        }
    }
}
