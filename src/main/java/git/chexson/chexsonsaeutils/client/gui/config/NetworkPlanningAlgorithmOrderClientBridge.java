package git.chexson.chexsonsaeutils.client.gui.config;

import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderClientResponseRouter;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderProtocol;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderStatePayload;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderStateRequestPayload;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderUpdatePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 使用 NeoForge payload 的真实客户端桥；响应严格按 requestId 匹配一次性回调。 */
public final class NetworkPlanningAlgorithmOrderClientBridge implements PlanningAlgorithmOrderClientBridge {
    private static final NetworkPlanningAlgorithmOrderClientBridge INSTANCE =
            new NetworkPlanningAlgorithmOrderClientBridge();

    private final AtomicLong nextRequestId = new AtomicLong();
    private final Map<Long, PendingRequest> pending = new HashMap<>();

    private NetworkPlanningAlgorithmOrderClientBridge() {
        PlanningOrderClientResponseRouter.install(this::receive);
    }

    public static NetworkPlanningAlgorithmOrderClientBridge instance() {
        return INSTANCE;
    }

    @Override
    public boolean hasActiveConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getConnection() != null && minecraft.player != null;
    }

    @Override
    public void requestState(Consumer<RefreshResult> callback) {
        long requestId = nextRequestId.incrementAndGet();
        synchronized (this.pending) {
            this.pending.put(requestId, new PendingRefresh(callback));
        }
        PacketDistributor.sendToServer(new PlanningOrderStateRequestPayload(requestId));
    }

    @Override
    public void save(PlanningAlgorithmOrderSnapshot snapshot, Consumer<SaveResult> callback) {
        long requestId = nextRequestId.incrementAndGet();
        synchronized (this.pending) {
            this.pending.put(requestId, new PendingSave(callback));
        }
        PacketDistributor.sendToServer(new PlanningOrderUpdatePayload(
                requestId,
                snapshot.revision(),
                snapshot.order().stream().map(PlanningAlgorithm::id).toList()
        ));
    }

    private void receive(PlanningOrderStatePayload payload) {
        PendingRequest request;
        synchronized (this.pending) {
            request = this.pending.remove(payload.requestId());
        }
        if (request == null) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            try {
                var decoded = PlanningOrderProtocol.decode(payload.ids());
                PlanningAlgorithmOrderSnapshot snapshot = new PlanningAlgorithmOrderSnapshot(
                        payload.revision(),
                        payload.editable(),
                        // decode 现在交回串名册形状：这里就地 parse 回能力侧枚举，再映射到客户端显示模型。
                        // 认不出照样抛 IllegalArgumentException（不另加兜底分支），非法 id 的结局仍是 invalidResponse()。
                        decoded.stream()
                                .map(id -> PlanningAlgorithmId.parse(id).orElseThrow(
                                        () -> new IllegalArgumentException("Unknown planning algorithm ID: " + id)))
                                .map(PlanningAlgorithm::fromProtocolId)
                                .toList()
                );
                request.complete(payload, snapshot);
            } catch (IllegalArgumentException exception) {
                request.invalidResponse();
            }
        });
    }

    private sealed interface PendingRequest permits PendingRefresh, PendingSave {
        void complete(PlanningOrderStatePayload payload, PlanningAlgorithmOrderSnapshot snapshot);

        void invalidResponse();
    }

    private record PendingRefresh(Consumer<RefreshResult> callback) implements PendingRequest {
        @Override
        public void complete(PlanningOrderStatePayload payload, PlanningAlgorithmOrderSnapshot snapshot) {
            this.callback.accept(RefreshResult.success(snapshot));
        }

        @Override
        public void invalidResponse() {
            this.callback.accept(new RefreshResult(
                    PlanningAlgorithmOrderSnapshot.disconnected(),
                    "gui.chexsonsaeutils.planning_order.status.invalid_response"));
        }
    }

    private record PendingSave(Consumer<SaveResult> callback) implements PendingRequest {
        @Override
        public void complete(PlanningOrderStatePayload payload, PlanningAlgorithmOrderSnapshot snapshot) {
            if (payload.result() == PlanningOrderStatePayload.Result.UPDATED) {
                this.callback.accept(SaveResult.success(snapshot));
            } else {
                this.callback.accept(SaveResult.rejected(snapshot, resultKey(payload.result())));
            }
        }

        @Override
        public void invalidResponse() {
            this.callback.accept(SaveResult.rejected(
                    PlanningAlgorithmOrderSnapshot.disconnected(),
                    "gui.chexsonsaeutils.planning_order.status.invalid_response"));
        }

        private static String resultKey(PlanningOrderStatePayload.Result result) {
            return switch (result) {
                case REVISION_MISMATCH -> "gui.chexsonsaeutils.planning_order.status.stale";
                case INVALID_REQUEST -> "gui.chexsonsaeutils.planning_order.status.invalid_request";
                case NOT_AUTHORIZED -> "gui.chexsonsaeutils.planning_order.status.not_authorized";
                case PERSISTENCE_FAILED -> "gui.chexsonsaeutils.planning_order.status.persistence_failed";
                default -> "gui.chexsonsaeutils.planning_order.status.rejected";
            };
        }
    }
}
