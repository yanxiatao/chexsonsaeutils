package git.chexson.chexsonsaeutils.network.crafting;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 精确确认账本分片；每包有界，账本总长度不受单个 writeUtf 限制。 */
public record WidePlanChunkPayload(int containerId, long revision, int index, int count, String data)
        implements CustomPacketPayload {
    /** 小于 AE2 单字符串上限，限制每个网络包的字符串长度。 */
    public static final int CHUNK_LENGTH = 16_384;
    public static final Type<WidePlanChunkPayload> TYPE =
            new Type<>(ResourceLocation.parse("chexsonsaeutils:wide_plan_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WidePlanChunkPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeVarInt(value.containerId);
                buf.writeVarLong(value.revision);
                buf.writeVarInt(value.index);
                buf.writeVarInt(value.count);
                buf.writeUtf(value.data, CHUNK_LENGTH);
            },
            buf -> new WidePlanChunkPayload(buf.readVarInt(), buf.readVarLong(), buf.readVarInt(),
                    buf.readVarInt(), buf.readUtf(CHUNK_LENGTH)));

    public WidePlanChunkPayload {
        validate(revision, index, count, data);
    }

    static void validate(long revision, int index, int count, String data) {
        if (revision < 0 || count < 1 || count > (Integer.MAX_VALUE - 1) / CHUNK_LENGTH + 1
                || index < 0 || index >= count || data == null || data.length() > CHUNK_LENGTH)
            throw new IllegalArgumentException("精确确认账本分片非法");
    }

    /** 拆分账本，空账本也发送一片以清除上一轮计划。 */
    public static List<WidePlanChunkPayload> split(int containerId, long revision, String encoded) {
        int count = encoded.isEmpty() ? 1 : (encoded.length() - 1) / CHUNK_LENGTH + 1;
        List<WidePlanChunkPayload> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = index * CHUNK_LENGTH;
            int end = (int) Math.min((long) start + CHUNK_LENGTH, encoded.length());
            chunks.add(new WidePlanChunkPayload(containerId, revision, index, count, encoded.substring(start, end)));
        }
        return chunks;
    }

    /** 仅当前容器可以接收，关闭菜单后到达的分片直接丢弃。 */
    public static void handle(WidePlanChunkPayload value, IPayloadContext context) {
        context.enqueueWork(() -> {
            var menu = context.player().containerMenu;
            if (menu.containerId == value.containerId && menu instanceof WidePlanChunkTarget target)
                target.receiveWidePlanChunk(value.revision, value.index, value.count, value.data);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
