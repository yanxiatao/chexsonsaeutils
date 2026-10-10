package git.chexson.chexsonsaeutils.config;

import java.util.List;
import java.util.Objects;

/** 线程安全的算法顺序快照，更新采用 expectedRevision 乐观并发控制。 */
public final class PlanningAlgorithmOrderService {
    private static final PlanningAlgorithmOrderService GLOBAL = new PlanningAlgorithmOrderService(
            PlanningServerConfig.order(),
            PlanningServerConfig::setOrder
    );

    private final Object updateLock = new Object();
    private final OrderPersistence persistence;
    private volatile Snapshot snapshot;

    public PlanningAlgorithmOrderService() {
        this(PlanningServerConfig.order());
    }

    public PlanningAlgorithmOrderService(List<String> order) {
        this(order, ignored -> {
        });
    }

    PlanningAlgorithmOrderService(
            List<String> order,
            OrderPersistence persistence
    ) {
        this.snapshot = new Snapshot(0L, order);
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    public static PlanningAlgorithmOrderService global() {
        return GLOBAL;
    }

    public Snapshot snapshot() {
        return this.snapshot;
    }

    public long revision() {
        return this.snapshot.revision();
    }

    public List<String> order() {
        return this.snapshot.order();
    }

    public Snapshot reload() {
        return reload(PlanningServerConfig.order());
    }

    public Snapshot reload(List<String> order) {
        Snapshot replacement = new Snapshot(0L, order);
        synchronized (this.updateLock) {
            Snapshot current = this.snapshot;
            this.snapshot = new Snapshot(current.revision() + 1L, replacement.order());
            return this.snapshot;
        }
    }

    public UpdateResult update(List<String> order, long expectedRevision) {
        List<String> validated = PlanningServerConfig.requireCompleteOrder(order);
        synchronized (this.updateLock) {
            Snapshot current = this.snapshot;
            if (expectedRevision < 0L) {
                return new UpdateResult(UpdateStatus.INVALID_REVISION, current);
            }
            if (current.revision() != expectedRevision) {
                return new UpdateResult(UpdateStatus.REVISION_MISMATCH, current);
            }
            return persistAndPublish(current, validated);
        }
    }

    private UpdateResult persistAndPublish(
            Snapshot current,
            List<String> order
    ) {
        try {
            this.persistence.save(order);
        } catch (RuntimeException exception) {
            return new UpdateResult(UpdateStatus.PERSISTENCE_FAILED, current);
        }
        Snapshot updated = new Snapshot(current.revision() + 1L, order);
        this.snapshot = updated;
        return new UpdateResult(UpdateStatus.UPDATED, updated);
    }

    public enum UpdateStatus {
        UPDATED,
        REVISION_MISMATCH,
        INVALID_REVISION,
        PERSISTENCE_FAILED
    }

    public record UpdateResult(UpdateStatus status, Snapshot snapshot) {
        public UpdateResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(snapshot, "snapshot");
        }

        public boolean updated() {
            return this.status == UpdateStatus.UPDATED;
        }
    }

    public record Snapshot(long revision, List<String> order) {
        public Snapshot {
            if (revision < 0L) {
                throw new IllegalArgumentException("Revision must not be negative");
            }
            order = PlanningServerConfig.requireCompleteOrder(order);
        }
    }

    @FunctionalInterface
    interface OrderPersistence {
        void save(List<String> order);
    }
}
