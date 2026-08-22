package git.chexson.chexsonsaeutils.client.renderer.framepatternprovider;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

/**
 * 框架样板供应器方块实体渲染器。
 * <p>
 * 渲染被包裹的原方块（半透明），并在方块边缘绘制白色半透明边框线框（12 条棱），
 * 提示玩家该方块处于框架包裹状态。
 */
public class FramePatternProviderBlockEntityRenderer implements BlockEntityRenderer<FramePatternProviderBlockEntity> {

    /** 边框线颜色 alpha（半透明）。 */
    private static final float BORDER_ALPHA = 0.5F;

    @Override
    public void render(
            FramePatternProviderBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        BlockState capturedState = blockEntity.getCapturedState();
        if (capturedState == null) {
            return;
        }
        // 渲染被包裹的原方块（实心——原位包装架构下机器真实存在于该位置，
        // renderSingleBlock 内部会做 -0.5 居中偏移）
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                capturedState,
                poseStack,
                bufferSource,
                packedLight,
                packedOverlay,
                ModelData.EMPTY,
                null
        );
        renderFrameBorder(poseStack, bufferSource);
    }

    /**
     * 绘制 12 条棱的白色半透明边框线框（RenderType.lines，自带 alpha 混合）。
     */
    private static void renderFrameBorder(PoseStack poseStack, MultiBufferSource bufferSource) {
        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        float min = 0.0F;
        float max = 1.0F;
        // 底部 4 条棱
        addLine(lines, matrix, min, min, min, max, min, min);
        addLine(lines, matrix, max, min, min, max, min, max);
        addLine(lines, matrix, max, min, max, min, min, max);
        addLine(lines, matrix, min, min, max, min, min, min);
        // 顶部 4 条棱
        addLine(lines, matrix, min, max, min, max, max, min);
        addLine(lines, matrix, max, max, min, max, max, max);
        addLine(lines, matrix, max, max, max, min, max, max);
        addLine(lines, matrix, min, max, max, min, max, min);
        // 垂直 4 条棱
        addLine(lines, matrix, min, min, min, min, max, min);
        addLine(lines, matrix, max, min, min, max, max, min);
        addLine(lines, matrix, max, min, max, max, max, max);
        addLine(lines, matrix, min, min, max, min, max, max);
    }

    private static void addLine(
            VertexConsumer consumer,
            Matrix4f matrix,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2
    ) {
        consumer.addVertex(matrix, x1, y1, z1).setColor(1.0F, 1.0F, 1.0F, BORDER_ALPHA).setNormal(0.0F, 1.0F, 0.0F);
        consumer.addVertex(matrix, x2, y2, z2).setColor(1.0F, 1.0F, 1.0F, BORDER_ALPHA).setNormal(0.0F, 1.0F, 0.0F);
    }
}