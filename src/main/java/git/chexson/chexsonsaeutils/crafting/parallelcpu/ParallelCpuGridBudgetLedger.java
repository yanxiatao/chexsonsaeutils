package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;

import java.util.EnumSet;
import java.util.Set;

public final class ParallelCpuGridBudgetLedger {

    public static final long DEFAULT_TICK_BUDGET_NANOS = 20_000_000L;
    public static final long HARD_MAX_TICK_BUDGET_NANOS = 45_000_000L;

    private final Limits limits;
    private final EnumSet<BudgetType> exhaustedTypes = EnumSet.noneOf(BudgetType.class);
    private long currentTick = Long.MIN_VALUE;
    private long tickStartNanos;
    private long laneSliceDeadlineNanos = -1L;

    public ParallelCpuGridBudgetLedger() {
        this(Limits.defaults());
    }

    public ParallelCpuGridBudgetLedger(Limits limits) {
        this.limits = limits == null ? Limits.defaults() : limits;
    }

    public void resetForTick(long tick, long startNanos) {
        currentTick = tick;
        tickStartNanos = Math.max(0L, startNanos);
        exhaustedTypes.clear();
    }

    public boolean hasTimeBudget(long nowNanos) {
        if (tickStartNanos <= 0L) {
            return true;
        }
        if (nowNanos - tickStartNanos < limits.tickBudgetNanos()) {
            return true;
        }
        exhaustedTypes.add(BudgetType.TIME);
        return false;
    }

    /**
     * lane 时间片（多 job 公平）：每 lane 进入 tick 时按「剩余时间 ÷ 剩余
     * lane 数」领一份截止时刻；提前跑完把余额让给后面的 lane（下次调用
     * beginLaneSlice 重新按剩余均分）。此前 grid 级 20ms 被第一个 lane 的
     * 任务循环独占——同计划/跨计划的其他 job 每 tick 都在 isExhausted
     * 断点之后，表现为「一个 job 跑到底才轮到下一个」。
     */
    public void beginLaneSlice(long nowNanos, int remainingLanes) {
        long budget = limits.tickBudgetNanos();
        if (tickStartNanos <= 0L || budget <= 0L || remainingLanes <= 0) {
            return;
        }
        long elapsed = Math.max(0L, nowNanos - tickStartNanos);
        long remaining = budget - elapsed;
        if (remaining <= 0L) {
            return;
        }
        laneSliceDeadlineNanos = nowNanos + Math.max(1L, remaining / remainingLanes);
    }

    /** 当前 lane 的时间片是否耗尽（未开片 = 只受 grid 总预算约束）。 */
    public boolean laneSliceExhausted(long nowNanos) {
        return laneSliceDeadlineNanos > 0L && nowNanos >= laneSliceDeadlineNanos;
    }

    public boolean isExhausted() {
        return !exhaustedTypes.isEmpty();
    }

    public Set<BudgetType> exhaustedTypes() {
        return Set.copyOf(exhaustedTypes);
    }

    public long currentTick() {
        return currentTick;
    }

    public Limits limits() {
        return limits;
    }

    public enum BudgetType {
        TIME
    }

    public record Limits(
            long tickBudgetNanos
    ) {
        public Limits {
            tickBudgetNanos = Math.max(1L, Math.min(HARD_MAX_TICK_BUDGET_NANOS, tickBudgetNanos));
        }

        public static Limits fromSettings(ChexsonsaeutilsCompatibilityConfig.ParallelCpuSettings settings) {
            if (settings == null) {
                return defaults();
            }
            return new Limits(settings.tickBudgetNanosPerGrid());
        }

        public static Limits defaults() {
            return new Limits(DEFAULT_TICK_BUDGET_NANOS);
        }
    }
}
