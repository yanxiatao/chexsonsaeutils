package git.chexson.chexsonsaeutils.client.gui.config;

import java.util.Objects;
import java.util.function.Consumer;

/** 排序页与服务端权威 payload 之间的客户端桥。 */
public interface PlanningAlgorithmOrderClientBridge {
    boolean hasActiveConnection();

    void requestState(Consumer<RefreshResult> callback);

    void save(PlanningAlgorithmOrderSnapshot snapshot, Consumer<SaveResult> callback);

    record RefreshResult(PlanningAlgorithmOrderSnapshot snapshot, String messageKey) {
        public RefreshResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            messageKey = Objects.requireNonNullElse(messageKey, "");
        }

        public static RefreshResult success(PlanningAlgorithmOrderSnapshot snapshot) {
            return new RefreshResult(snapshot, "");
        }
    }

    record SaveResult(boolean success, PlanningAlgorithmOrderSnapshot snapshot, String messageKey) {
        public SaveResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            messageKey = Objects.requireNonNullElse(messageKey, "");
        }

        public static SaveResult success(PlanningAlgorithmOrderSnapshot snapshot) {
            return new SaveResult(true, snapshot, "gui.chexsonsaeutils.planning_order.status.saved");
        }

        public static SaveResult rejected(PlanningAlgorithmOrderSnapshot snapshot, String messageKey) {
            return new SaveResult(false, snapshot, messageKey);
        }
    }

    static PlanningAlgorithmOrderClientBridge disconnected() {
        return DisconnectedBridge.INSTANCE;
    }

    final class DisconnectedBridge implements PlanningAlgorithmOrderClientBridge {
        private static final DisconnectedBridge INSTANCE = new DisconnectedBridge();

        private DisconnectedBridge() {
        }

        @Override
        public boolean hasActiveConnection() {
            return false;
        }

        @Override
        public void requestState(Consumer<RefreshResult> callback) {
            callback.accept(new RefreshResult(
                    PlanningAlgorithmOrderSnapshot.disconnected(),
                    "gui.chexsonsaeutils.planning_order.status.disconnected"
            ));
        }

        @Override
        public void save(PlanningAlgorithmOrderSnapshot snapshot, Consumer<SaveResult> callback) {
            callback.accept(SaveResult.rejected(
                    snapshot, "gui.chexsonsaeutils.planning_order.status.disconnected"));
        }
    }
}
