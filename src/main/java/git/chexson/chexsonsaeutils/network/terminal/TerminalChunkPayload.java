package git.chexson.chexsonsaeutils.network.terminal;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import git.chexson.chexsonsaeutils.terminal.sync.TerminalFrameCodec;
import java.util.UUID;
/** 有界库存字节分片，完整帧由目标菜单原子提交。 */
public record TerminalChunkPayload(int containerId, UUID session, long revision, int index, int count, byte[] data)
        implements CustomPacketPayload {
    public static final Type<TerminalChunkPayload> TYPE = new Type<>(ResourceLocation.parse("chexsonsaeutils:terminal_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalChunkPayload> STREAM_CODEC = StreamCodec.of(
            (buf, v) -> { buf.writeVarInt(v.containerId); buf.writeUUID(v.session); buf.writeVarLong(v.revision);
                buf.writeVarInt(v.index); buf.writeVarInt(v.count); buf.writeByteArray(v.data); },
            buf -> new TerminalChunkPayload(buf.readVarInt(), buf.readUUID(), buf.readVarLong(), buf.readVarInt(),
                    buf.readVarInt(), buf.readByteArray(TerminalFrameCodec.CHUNK_BYTES)));
    public static void handle(TerminalChunkPayload value, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.containerMenu.containerId != value.containerId) return;
            if (player.containerMenu instanceof TerminalClientTarget target)
                target.receiveTerminalChunk(value.session, value.revision, value.index, value.count, value.data);
            else NativeTerminalRouter.receive(player, value);
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
