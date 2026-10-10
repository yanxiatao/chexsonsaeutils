package git.chexson.chexsonsaeutils.client.gui.config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 不依赖 Minecraft 客户端的固定项排序模型。
 *
 * <p>只允许重排既有项，不提供输入、添加或删除入口。
 */
public final class FixedPlanningAlgorithmOrder {
    private static final List<PlanningAlgorithm> DEFAULTS = List.of(
            PlanningAlgorithm.FROZEN_ROUTE,
            PlanningAlgorithm.GRAPH,
            PlanningAlgorithm.OPTIMIZED_TREE,
            PlanningAlgorithm.NATIVE_AE2
    );

    private final ArrayList<PlanningAlgorithm> order;

    public FixedPlanningAlgorithmOrder(List<PlanningAlgorithm> order) {
        validate(order);
        this.order = new ArrayList<>(order);
    }

    public static FixedPlanningAlgorithmOrder defaults() {
        return new FixedPlanningAlgorithmOrder(DEFAULTS);
    }

    public static FixedPlanningAlgorithmOrder from(PlanningAlgorithmOrderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new FixedPlanningAlgorithmOrder(snapshot.order());
    }

    public static List<PlanningAlgorithm> defaultOrder() {
        return DEFAULTS;
    }

    public static void validate(List<PlanningAlgorithm> order) {
        Objects.requireNonNull(order, "order");
        if (order.size() != DEFAULTS.size()
                || order.stream().anyMatch(Objects::isNull)
                || !EnumSet.copyOf(order).equals(EnumSet.copyOf(DEFAULTS))) {
            throw new IllegalArgumentException("算法顺序必须是全部固定项的唯一排列");
        }
    }

    public List<PlanningAlgorithm> order() {
        return List.copyOf(this.order);
    }

    public boolean canMoveUp(PlanningAlgorithm algorithm) {
        return this.order.indexOf(algorithm) > 0;
    }

    public boolean canMoveDown(PlanningAlgorithm algorithm) {
        int index = this.order.indexOf(algorithm);
        return index >= 0 && index < this.order.size() - 1;
    }

    public boolean moveUp(PlanningAlgorithm algorithm) {
        int index = this.order.indexOf(algorithm);
        if (index <= 0) {
            return false;
        }
        swap(this.order, index, index - 1);
        return true;
    }

    public boolean moveDown(PlanningAlgorithm algorithm) {
        int index = this.order.indexOf(algorithm);
        if (index < 0 || index >= this.order.size() - 1) {
            return false;
        }
        swap(this.order, index, index + 1);
        return true;
    }

    public boolean move(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= this.order.size() || toIndex < 0 || toIndex >= this.order.size()) {
            return false;
        }
        if (fromIndex == toIndex) {
            return true;
        }
        PlanningAlgorithm moved = this.order.remove(fromIndex);
        this.order.add(toIndex, moved);
        return true;
    }

    public void restoreDefaults() {
        this.order.clear();
        this.order.addAll(DEFAULTS);
    }

    public boolean isDefault() {
        return this.order.equals(DEFAULTS);
    }

    public boolean hasSameOrder(PlanningAlgorithmOrderSnapshot snapshot) {
        return this.order.equals(snapshot.order());
    }

    public PlanningAlgorithmOrderSnapshot snapshot(long revision, boolean editable) {
        return new PlanningAlgorithmOrderSnapshot(revision, editable, this.order);
    }

    private static void swap(List<PlanningAlgorithm> order, int left, int right) {
        PlanningAlgorithm value = order.get(left);
        order.set(left, order.get(right));
        order.set(right, value);
    }
}
