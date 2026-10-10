package git.chexson.chexsonsaeutils.network.matrix;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixServerTarget;

import java.util.Arrays;
import java.util.List;

/**
 * 装配矩阵的「客户端 → 服务端」可见行真栈索取负载。
 *
 * <p>界面只需要当前可见的那几十格本体来还原样板悬停提示，因此按窗口索取而不是
 * 全量下发；槽数有硬上限，客户端伪造大包也撑不起带宽。</p>
 */
public record PatternMatrixVisibleRequestPayload(
        int containerId,
        List<Integer> slots
) implements CustomPacketPayload {

    public static final Type<PatternMatrixVisibleRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "pattern_matrix_visible")
    );

    /** 单次索取的槽数上限。 */
    public static final int MAX_SLOTS = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternMatrixVisibleRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    PatternMatrixVisibleRequestPayload::containerId,
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()),
                    PatternMatrixVisibleRequestPayload::slots,
                    PatternMatrixVisibleRequestPayload::new
            );

    public static void handle(PatternMatrixVisibleRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof PatternMatrixServerTarget target
                    && player.containerMenu.containerId == payload.containerId()) {
                target.handleMatrixVisibleRequest(toSlotArray(payload.slots()), player);
            }
        });
    }

    /** 越界与超限的槽号直接丢弃，返回实际可用的槽号数组。 */
    static int[] toSlotArray(List<Integer> slots) {
        if (slots == null || slots.isEmpty()) {
            return new int[0];
        }
        int[] result = new int[Math.min(slots.size(), MAX_SLOTS)];
        int written = 0;
        for (int index = 0; index < result.length; index++) {
            Integer value = slots.get(index);
            if (value != null && value >= 0) {
                result[written++] = value;
            }
        }
        return written == result.length ? result : Arrays.copyOf(result, written);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
