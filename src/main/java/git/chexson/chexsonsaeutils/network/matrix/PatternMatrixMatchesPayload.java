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
 * 装配矩阵的「服务端 → 客户端」搜索命中回包。
 *
 * <p>命中用位图表达：一万六千槽只需两千字节，且界面按位取命中格子做整行过滤，
 * 不必为每个命中槽单独发一条记录。</p>
 */
public record PatternMatrixMatchesPayload(
        int containerId,
        int matchCount,
        List<Long> matchBits
) implements CustomPacketPayload {

    public static final Type<PatternMatrixMatchesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModIds.MODID, "pattern_matrix_matches")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternMatrixMatchesPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    PatternMatrixMatchesPayload::containerId,
                    ByteBufCodecs.VAR_INT,
                    PatternMatrixMatchesPayload::matchCount,
                    ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()),
                    PatternMatrixMatchesPayload::matchBits,
                    PatternMatrixMatchesPayload::new
            );

    public static PatternMatrixMatchesPayload of(int containerId, int matchCount, long[] bits) {
        Long[] words = new Long[bits.length];
        for (int index = 0; index < bits.length; index++) {
            words[index] = bits[index];
        }
        return new PatternMatrixMatchesPayload(containerId, matchCount, List.of(words));
    }

    /**
     * 长度语义（D6b 两侧同一把尺）：{@code matchBits} 就是生产者的全长位图，写侧不裁尾、
     * 读侧不补回，往返逐字相等。「取消过滤」由空查询腿显式下发空集合表达，
     * 带查询的零命中腿下发全长零位图，两者不再靠裁尾后的长度差异区分。
     */
    public long[] bitsArray() {
        long[] bits = new long[matchBits.size()];
        for (int index = 0; index < bits.length; index++) {
            bits[index] = matchBits.get(index);
        }
        return bits;
    }

    public static void handle(PatternMatrixMatchesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player != null
                    && player.containerMenu instanceof PatternMatrixTarget target
                    && target.matchesContainerId(payload.containerId())) {
                target.applyMatrixMatches(payload.bitsArray(), payload.matchCount());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
