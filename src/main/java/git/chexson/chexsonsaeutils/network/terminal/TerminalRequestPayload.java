package git.chexson.chexsonsaeutils.network.terminal;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;
/** 打开菜单握手和全量重同步请求；nonce 阻止复用 containerId 的旧包进入新菜单。 */
public record TerminalRequestPayload(int containerId, UUID session) implements CustomPacketPayload {
    public static final Type<TerminalRequestPayload> TYPE = new Type<>(ResourceLocation.parse("chexsonsaeutils:terminal_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> { buf.writeVarInt(value.containerId); buf.writeUUID(value.session); },
            buf -> new TerminalRequestPayload(buf.readVarInt(), buf.readUUID()));
    public static void handle(TerminalRequestPayload value, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu.containerId == value.containerId) {
                if (player.containerMenu instanceof TerminalServerTarget target) target.requestTerminalSnapshot(value.session);
                else NativeTerminalRouter.request(player, value.session);
            }
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
