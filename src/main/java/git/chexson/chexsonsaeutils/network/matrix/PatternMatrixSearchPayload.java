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

/**
 * 装配矩阵的「客户端 → 服务端」搜索负载。
 *
 * <p>搜索必须留在服务端：命中口径是样板本体（全部产物 + 全部可选输入 + 注册表 id），
 * 而客户端只持有产出物摘要，本地搜不出等价结果。回包用位图表达命中槽。</p>
 */
public record PatternMatrixSearchPayload(
        int containerId,
        String query
) implements CustomPacketPayload {

    public static final Type<PatternMatrixSearchPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "pattern_matrix_search")
    );

    /** 查询串长度上限：原版字符串编解码本身也有上限，这里先截断以免抛错。 */
    public static final int MAX_QUERY_LENGTH = 128;

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternMatrixSearchPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    PatternMatrixSearchPayload::containerId,
                    ByteBufCodecs.STRING_UTF8,
                    PatternMatrixSearchPayload::query,
                    PatternMatrixSearchPayload::new
            );

    public static PatternMatrixSearchPayload of(int containerId, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery;
        if (query.length() > MAX_QUERY_LENGTH) {
            query = query.substring(0, MAX_QUERY_LENGTH);
        }
        return new PatternMatrixSearchPayload(containerId, query);
    }

    public static void handle(PatternMatrixSearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof PatternMatrixServerTarget target
                    && player.containerMenu.containerId == payload.containerId()) {
                target.handleMatrixSearch(payload.query(), player);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
