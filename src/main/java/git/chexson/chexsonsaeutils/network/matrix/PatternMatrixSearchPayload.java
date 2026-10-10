package git.chexson.chexsonsaeutils.network.matrix;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import git.chexson.chexsonsaeutils.modid.ModIds;

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
            ResourceLocation.fromNamespaceAndPath(ModIds.MODID, "pattern_matrix_search")
    );

    /** 查询串长度上限：写侧在线侧强制、读侧按同一把尺断言（D6b），不再是只在下发前截断的线下口径。 */
    public static final int MAX_QUERY_LENGTH = 128;

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternMatrixSearchPayload> STREAM_CODEC =
            StreamCodec.ofMember(PatternMatrixSearchPayload::writeToNetwork, PatternMatrixSearchPayload::readFromNetwork);

    private void writeToNetwork(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new EncoderException("query exceeds wire bound " + MAX_QUERY_LENGTH + ": " + query.length());
        }
        ByteBufCodecs.STRING_UTF8.encode(buffer, query);
    }

    private static PatternMatrixSearchPayload readFromNetwork(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        String query = ByteBufCodecs.STRING_UTF8.decode(buffer);
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new DecoderException("query exceeds wire bound " + MAX_QUERY_LENGTH + ": " + query.length());
        }
        return new PatternMatrixSearchPayload(containerId, query);
    }

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
