package git.chexson.chexsonsaeutils.network.directprocessing.rules;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 打开菜单和服务器规则更新时同步一次；不依赖客户端执行服务器脚本。 */
public record DirectProcessingPackRulesPayload(int containerId, String json) implements CustomPacketPayload {
    public static final int MAX_LENGTH = 1 << 20;
    public static final Type<DirectProcessingPackRulesPayload> TYPE = new Type<>(
            ResourceLocation.parse("chexsonsaeutils:direct_processing_pack_rules"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DirectProcessingPackRulesPayload> STREAM_CODEC =
            StreamCodec.ofMember(DirectProcessingPackRulesPayload::write, DirectProcessingPackRulesPayload::read);
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeUtf(json, MAX_LENGTH);
    }
    private static DirectProcessingPackRulesPayload read(RegistryFriendlyByteBuf buffer) {
        return new DirectProcessingPackRulesPayload(buffer.readVarInt(), buffer.readUtf(MAX_LENGTH));
    }
    public static void handle(DirectProcessingPackRulesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var menu = context.player().containerMenu;
            if (menu.containerId == payload.containerId && menu instanceof DirectProcessingPackRulesTarget target) {
                target.applyPackRulesSnapshot(payload.json);
            }
        });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
