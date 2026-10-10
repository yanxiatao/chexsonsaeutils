package git.chexson.chexsonsaeutils.network.planning;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** 服务端返回的权威排序快照，以及更新成功或拒绝结果。 */
public record PlanningOrderStatePayload(
        long requestId,
        Result result,
        long revision,
        boolean editable,
        List<String> ids
) implements CustomPacketPayload {
    public static final Type<PlanningOrderStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "planning_order_state")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, PlanningOrderStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(PlanningOrderStatePayload::write, PlanningOrderStatePayload::decode);

    public PlanningOrderStatePayload {
        ids = List.copyOf(ids);
    }

    public static PlanningOrderStatePayload state(
            long requestId,
            PlanningAlgorithmOrderService.Snapshot snapshot,
            boolean editable
    ) {
        return create(requestId, Result.STATE, snapshot, editable);
    }

    public static PlanningOrderStatePayload result(
            long requestId,
            Result result,
            PlanningAlgorithmOrderService.Snapshot snapshot,
            boolean editable
    ) {
        return create(requestId, result, snapshot, editable);
    }

    private static PlanningOrderStatePayload create(
            long requestId,
            Result result,
            PlanningAlgorithmOrderService.Snapshot snapshot,
            boolean editable
    ) {
        return new PlanningOrderStatePayload(
                requestId,
                result,
                snapshot.revision(),
                editable,
                PlanningOrderProtocol.encode(snapshot)
        );
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(this.requestId);
        buffer.writeUtf(this.result.name());
        buffer.writeLong(this.revision);
        buffer.writeBoolean(this.editable);
        writeIds(buffer, this.ids);
    }

    private static PlanningOrderStatePayload decode(RegistryFriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        Result result = Result.valueOf(buffer.readUtf(32));
        long revision = buffer.readLong();
        boolean editable = buffer.readBoolean();
        return new PlanningOrderStatePayload(requestId, result, revision, editable, readIds(buffer));
    }

    private static void writeIds(RegistryFriendlyByteBuf buffer, List<String> ids) {
        buffer.writeVarInt(ids.size());
        ids.forEach(buffer::writeUtf);
    }

    private static List<String> readIds(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > 16) {
            throw new IllegalArgumentException("Invalid planning algorithm ID count: " + count);
        }
        List<String> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(buffer.readUtf(64));
        }
        return ids;
    }

    public static void handle(PlanningOrderStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> PlanningOrderClientResponseRouter.dispatch(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Result {
        STATE,
        UPDATED,
        REVISION_MISMATCH,
        INVALID_REQUEST,
        NOT_AUTHORIZED,
        PERSISTENCE_FAILED
    }
}
