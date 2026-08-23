package git.chexson.chexsonsaeutils.network.custompatternencoder;

import java.util.List;

import io.netty.buffer.ByteBuf;

import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.menu.custompatternencoder.CustomPatternEncoderMenu;

/**
 * 框架样板编码 GUI 的「服务端 → 客户端」数据同步负载。
 * <p>
 * 动机：编码 GUI 需要展示输入样板的稀疏输入列表与当前槽位映射（变长数据），
 * 而 AE2 的 @GuiSync 注解只支持定长字段，因此用自定义负载推送。
 * 客户端按 containerId 校验后写入菜单字段（updateFromServer）。
 */
public record CustomPatternEncoderUpdatePayload(
        int containerId,
        List<GenericStack> sparseInputs,
        int[] slotMapping,
        int[] extractSlots,
        boolean overflowStacks
) implements CustomPacketPayload {

    public static final Type<CustomPatternEncoderUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "custom_pattern_encoder_update")
    );

    private static final StreamCodec<ByteBuf, int[]> INT_ARRAY_STREAM_CODEC = ByteBufCodecs.INT
            .apply(ByteBufCodecs.<ByteBuf, Integer>list())
            .map(list -> list.stream().mapToInt(Integer::intValue).toArray(),
                    arr -> {
                        var list = new java.util.ArrayList<Integer>(arr.length);
                        for (int v : arr) {
                            list.add(v);
                        }
                        return list;
                    });

    public static final StreamCodec<RegistryFriendlyByteBuf, CustomPatternEncoderUpdatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    CustomPatternEncoderUpdatePayload::containerId,
                    GenericStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    CustomPatternEncoderUpdatePayload::sparseInputs,
                    INT_ARRAY_STREAM_CODEC,
                    CustomPatternEncoderUpdatePayload::slotMapping,
                    INT_ARRAY_STREAM_CODEC,
                    CustomPatternEncoderUpdatePayload::extractSlots,
                    ByteBufCodecs.BOOL,
                    CustomPatternEncoderUpdatePayload::overflowStacks,
                    CustomPatternEncoderUpdatePayload::new
            );

    public static void handle(CustomPatternEncoderUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.containerMenu instanceof CustomPatternEncoderMenu menu
                    && menu.containerId == payload.containerId()) {
                menu.updateFromServer(payload.sparseInputs(), payload.slotMapping(), payload.extractSlots(),
                        payload.overflowStacks());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}