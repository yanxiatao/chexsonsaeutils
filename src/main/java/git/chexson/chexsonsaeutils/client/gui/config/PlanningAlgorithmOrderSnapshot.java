package git.chexson.chexsonsaeutils.client.gui.config;

import java.util.List;
import java.util.Objects;

/**
 * 服务端下发或客户端提交的算法顺序快照。
 *
 * <p>{@code revision} 用于服务端乐观并发检查；列表始终由模型校验为固定项的排列。
 */
public record PlanningAlgorithmOrderSnapshot(
        long revision,
        boolean editable,
        List<PlanningAlgorithm> order
) {
    public PlanningAlgorithmOrderSnapshot {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        order = List.copyOf(Objects.requireNonNull(order, "order"));
        FixedPlanningAlgorithmOrder.validate(order);
    }

    public static PlanningAlgorithmOrderSnapshot disconnected() {
        return new PlanningAlgorithmOrderSnapshot(0L, false, FixedPlanningAlgorithmOrder.defaultOrder());
    }
}
