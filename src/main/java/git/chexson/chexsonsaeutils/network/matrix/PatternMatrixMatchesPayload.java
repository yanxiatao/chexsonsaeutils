package git.chexson.chexsonsaeutils.network.matrix;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixTarget;

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
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "pattern_matrix_matches")
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
        int usedWords = trailingWordCount(bits);
        Long[] words = new Long[usedWords];
        for (int index = 0; index < usedWords; index++) {
            words[index] = bits[index];
        }
        return new PatternMatrixMatchesPayload(containerId, matchCount, List.of(words));
    }

    /**
     * 去掉尾部空字以压缩包体。至少保留一个字：本方法只服务于「有查询」的回包，
     * 零命中时若下发空数组，客户端会把它当成取消过滤而不是过滤后无结果。
     */
    private static int trailingWordCount(long[] bits) {
        int last = bits.length;
        while (last > 1 && bits[last - 1] == 0L) {
            last--;
        }
        return last;
    }

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
