package git.chexson.chexsonsaeutils.client.renderer.framepatternprovider;

import com.mojang.blaze3d.vertex.PoseStack;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 框架样板供应器方块实体渲染器。
 * <p>
 * 渲染被包裹的原方块（实心——原位包装架构下机器真实存在于该位置）。
 * 框架的 12 条棱由方块静态模型承担（有真实材质，物品形态同样可见），本渲染器不再绘制线框。
 */
public class FramePatternProviderBlockEntityRenderer implements BlockEntityRenderer<FramePatternProviderBlockEntity> {

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
        // 渲染被包裹的原方块（实心，renderSingleBlock 内部会做 -0.5 居中偏移）
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                capturedState,
                poseStack,
                bufferSource,
                packedLight,
                packedOverlay,
                ModelData.EMPTY,
                null
        );
    }
}
