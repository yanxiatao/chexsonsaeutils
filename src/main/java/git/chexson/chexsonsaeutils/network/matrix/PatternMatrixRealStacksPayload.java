package git.chexson.chexsonsaeutils.network.matrix;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import git.chexson.chexsonsaeutils.modid.ModIds;

import java.util.List;

/**
 * 装配矩阵的「服务端 → 客户端」可见行真栈回包。
 *
 * <p>只回应当前可见的几十格，且仅在窗口或内容变化时索取；服务端按片下发，
 * 单包不超过固定条数。客户端把它填进视图，格子的渲染与悬停提示随即换成样板本体。</p>
 */
public record PatternMatrixRealStacksPayload(
        int containerId,
        List<PatternMatrixRealStack> stacks
) implements CustomPacketPayload {

    public static final Type<PatternMatrixRealStacksPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModIds.MODID, "pattern_matrix_real_stacks")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternMatrixRealStacksPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    PatternMatrixRealStacksPayload::containerId,
                    PatternMatrixRealStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    PatternMatrixRealStacksPayload::stacks,
                    PatternMatrixRealStacksPayload::new
            );

    public static void handle(PatternMatrixRealStacksPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player != null
                    && player.containerMenu instanceof PatternMatrixTarget target
                    && target.matchesContainerId(payload.containerId())) {
                target.applyMatrixRealStacks(payload.stacks());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
