package git.chexson.chexsonsaeutils.network.terminal;
import appeng.helpers.InventoryAction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;
/** 会话条目操作，实际交易由 AE2 菜单执行。 */
public record TerminalInteractionPayload(int containerId, UUID session, int entryId, InventoryAction action)
        implements CustomPacketPayload {
    public static final Type<TerminalInteractionPayload> TYPE = new Type<>(ResourceLocation.parse("chexsonsaeutils:terminal_interact"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalInteractionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, v) -> { buf.writeVarInt(v.containerId); buf.writeUUID(v.session); buf.writeVarInt(v.entryId); buf.writeEnum(v.action); },
            buf -> new TerminalInteractionPayload(buf.readVarInt(), buf.readUUID(), buf.readVarInt(), buf.readEnum(InventoryAction.class)));
    public static void handle(TerminalInteractionPayload value, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().containerMenu.containerId == value.containerId
                    && context.player().containerMenu instanceof TerminalServerTarget target)
                target.interactTerminal(value.session, value.entryId, value.action);
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
