package git.chexson.chexsonsaeutils.network.matrix;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixTarget;

import java.util.List;

/**
 * 装配矩阵的「服务端 → 客户端」格子同步负载。
 * <p>
 * 动机：机器最多有一万六千个样板槽，逐格下发样板本体会把配方数据整份推给客户端，
 * 因此只下发每格的展示栈（产出物）与状态码，并按包分批限流；客户端据此在本地
 * 完成滚动窗口与搜索过滤，不再向服务端请求翻页。
 * <p>
 * 每包都带上服务端权威总槽数，客户端据此扩缩本地视图；包到达顺序无关，
 * 因为写入按槽号定位。
 */
public record PatternMatrixEntriesPayload(
        int containerId,
        int totalSlots,
        List<PatternMatrixEntry> entries
) implements CustomPacketPayload {

    public static final Type<PatternMatrixEntriesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "pattern_matrix_entries")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternMatrixEntriesPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    PatternMatrixEntriesPayload::containerId,
                    ByteBufCodecs.VAR_INT,
                    PatternMatrixEntriesPayload::totalSlots,
                    PatternMatrixEntry.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    PatternMatrixEntriesPayload::entries,
                    PatternMatrixEntriesPayload::new
            );

    public static void handle(PatternMatrixEntriesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 用 context.player() 而非 Minecraft.getInstance()，避免专用服务端加载客户端类。
            var player = context.player();
            if (player != null
                    && player.containerMenu instanceof PatternMatrixTarget target
                    && target.matchesContainerId(payload.containerId())) {
                target.applyMatrixEntries(payload.totalSlots(), payload.entries());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
