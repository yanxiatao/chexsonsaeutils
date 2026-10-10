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

import java.util.ArrayList;
import java.util.List;

/** 客户端提交完整算法排列。 */
public record PlanningOrderUpdatePayload(
        long requestId,
        long expectedRevision,
        List<String> ids
) implements CustomPacketPayload {
    public static final Type<PlanningOrderUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModIds.MODID, "planning_order_update")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, PlanningOrderUpdatePayload> STREAM_CODEC =
            StreamCodec.ofMember(PlanningOrderUpdatePayload::write, PlanningOrderUpdatePayload::decode);

    public PlanningOrderUpdatePayload {
        ids = List.copyOf(ids);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(this.requestId);
        buffer.writeLong(this.expectedRevision);
        writeIds(buffer, this.ids);
    }

    private static PlanningOrderUpdatePayload decode(RegistryFriendlyByteBuf buffer) {
        return new PlanningOrderUpdatePayload(buffer.readLong(), buffer.readLong(), readIds(buffer));
    }

    private static void writeIds(RegistryFriendlyByteBuf buffer, List<String> ids) {
        if (ids.size() > PlanningOrderProtocol.MAX_PLANNING_ALGORITHM_IDS) {
            throw new IllegalArgumentException("Planning algorithm ID count too large for network: " + ids.size());
        }
        buffer.writeVarInt(ids.size());
        ids.forEach(id -> buffer.writeUtf(id, PlanningOrderProtocol.MAX_PLANNING_ID_LENGTH));
    }

    private static List<String> readIds(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > PlanningOrderProtocol.MAX_PLANNING_ALGORITHM_IDS) {
            throw new IllegalArgumentException("Invalid planning algorithm ID count: " + count);
        }
        List<String> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(buffer.readUtf(PlanningOrderProtocol.MAX_PLANNING_ID_LENGTH));
        }
        return ids;
    }

    public static void handle(PlanningOrderUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PlanningAlgorithmOrderService service = PlanningAlgorithmOrderService.global();
            if (!player.hasPermissions(2)) {
                reply(player, payload.requestId(), PlanningOrderStatePayload.Result.NOT_AUTHORIZED,
                        service.snapshot());
                return;
            }
            if (payload.expectedRevision() < 0L) {
                reply(player, payload.requestId(), PlanningOrderStatePayload.Result.INVALID_REQUEST,
                        service.snapshot());
                return;
            }
            final List<String> order;
            try {
                order = PlanningOrderProtocol.decode(payload.ids());
            } catch (IllegalArgumentException exception) {
                reply(player, payload.requestId(), PlanningOrderStatePayload.Result.INVALID_REQUEST,
                        service.snapshot());
                return;
            }
            PlanningAlgorithmOrderService.UpdateResult result = service.update(order, payload.expectedRevision());
            reply(player, payload.requestId(), mapResult(result.status()), result.snapshot());
        });
    }

    static PlanningOrderStatePayload.Result mapResult(PlanningAlgorithmOrderService.UpdateStatus status) {
        return switch (status) {
            case UPDATED -> PlanningOrderStatePayload.Result.UPDATED;
            case REVISION_MISMATCH -> PlanningOrderStatePayload.Result.REVISION_MISMATCH;
            case INVALID_REVISION -> PlanningOrderStatePayload.Result.INVALID_REQUEST;
            case PERSISTENCE_FAILED -> PlanningOrderStatePayload.Result.PERSISTENCE_FAILED;
        };
    }

    private static void reply(
            ServerPlayer player,
            long requestId,
            PlanningOrderStatePayload.Result result,
            PlanningAlgorithmOrderService.Snapshot snapshot
    ) {
        PacketDistributor.sendToPlayer(player,
                PlanningOrderStatePayload.result(requestId, result, snapshot, player.hasPermissions(2)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
