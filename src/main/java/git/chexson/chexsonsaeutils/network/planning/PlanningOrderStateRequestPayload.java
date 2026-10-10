package git.chexson.chexsonsaeutils.network.planning;

import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 客户端请求当前服务端权威排序。 */
public record PlanningOrderStateRequestPayload(long requestId) implements CustomPacketPayload {
    public static final Type<PlanningOrderStateRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModIds.MODID, "planning_order_state_request")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, PlanningOrderStateRequestPayload> STREAM_CODEC =
            StreamCodec.ofMember(PlanningOrderStateRequestPayload::write, PlanningOrderStateRequestPayload::decode);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(this.requestId);
    }

    private static PlanningOrderStateRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        return new PlanningOrderStateRequestPayload(buffer.readLong());
    }

    public static void handle(PlanningOrderStateRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PlanningAlgorithmOrderService.Snapshot snapshot = PlanningAlgorithmOrderService.global().snapshot();
                PacketDistributor.sendToPlayer(player, PlanningOrderStatePayload.state(
                        payload.requestId(), snapshot, player.hasPermissions(2)));
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
