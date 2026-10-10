package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;

import java.util.EnumSet;
import java.util.Set;

public final class ParallelCpuGridBudgetLedger {

    public static final long DEFAULT_MAX_PATTERN_PUSHES_PER_TICK = 1_048_576L;
    public static final long DEFAULT_MAX_PROVIDER_CHECKS_PER_TICK = 8_388_608L;
    public static final long DEFAULT_MAX_EXTRACT_PATTERN_INPUTS_PER_TICK = 1_048_576L;
    public static final long DEFAULT_MAX_REINJECT_PATTERN_INPUTS_PER_TICK = 1_048_576L;
    public static final long DEFAULT_TICK_BUDGET_NANOS = 20_000_000L;
    public static final long HARD_MAX_TICK_BUDGET_NANOS = 45_000_000L;

    private final Limits limits;
    private final EnumSet<BudgetType> exhaustedTypes = EnumSet.noneOf(BudgetType.class);
    private long currentTick = Long.MIN_VALUE;
    private long tickStartNanos;
    private long laneSliceDeadlineNanos = -1L;
    private long patternPushesUsed;
    private long providerChecksUsed;
    private long extractPatternInputsUsed;
    private long reinjectPatternInputsUsed;

    public ParallelCpuGridBudgetLedger() {
        this(Limits.defaults());
    }

    public ParallelCpuGridBudgetLedger(Limits limits) {
        this.limits = limits == null ? Limits.defaults() : limits;
    }

    public void resetForTick(long tick, long startNanos) {
        currentTick = tick;
        tickStartNanos = Math.max(0L, startNanos);
        patternPushesUsed = 0L;
        providerChecksUsed = 0L;
        extractPatternInputsUsed = 0L;
        reinjectPatternInputsUsed = 0L;
        exhaustedTypes.clear();
    }

    public boolean tryClaimPatternPush() {
        patternPushesUsed++;
        return true;
    }

    public long claimPatternPushes(long requested) {
        if (requested <= 0L) {
            return 0L;
        }
        patternPushesUsed = saturatingAdd(patternPushesUsed, requested);
        return requested;
    }

    public boolean tryClaimProviderCheck() {
        providerChecksUsed++;
        return true;
    }

    public long claimProviderChecks(long requested) {
        if (requested <= 0L) {
            return 0L;
        }
        providerChecksUsed = saturatingAdd(providerChecksUsed, requested);
        return requested;
    }

    public boolean tryClaimExtractPatternInputs() {
        extractPatternInputsUsed++;
        return true;
    }

    public long claimExtractPatternInputs(long requested) {
        if (requested <= 0L) {
            return 0L;
        }
        extractPatternInputsUsed = saturatingAdd(extractPatternInputsUsed, requested);
        return requested;
    }

    public boolean tryClaimReinjectPatternInputs() {
        reinjectPatternInputsUsed++;
        return true;
    }

    public long claimReinjectPatternInputs(long requested) {
        if (requested <= 0L) {
            return 0L;
        }
        reinjectPatternInputsUsed = saturatingAdd(reinjectPatternInputsUsed, requested);
        return requested;
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

    public Snapshot snapshot() {
        return new Snapshot(
                currentTick,
                patternPushesUsed,
                providerChecksUsed,
                extractPatternInputsUsed,
                reinjectPatternInputsUsed,
                limits.maxPatternPushesPerTick(),
                limits.maxProviderChecksPerTick(),
                limits.maxExtractPatternInputsPerTick(),
                limits.maxReinjectPatternInputsPerTick(),
                limits.tickBudgetNanos(),
                exhaustedTypes()
        );
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public enum BudgetType {
        PATTERN_PUSH,
        PROVIDER_CHECK,
        EXTRACT_PATTERN_INPUTS,
        REINJECT_PATTERN_INPUTS,
        TIME
    }

    public record Limits(
            long maxPatternPushesPerTick,
            long maxProviderChecksPerTick,
            long maxExtractPatternInputsPerTick,
            long maxReinjectPatternInputsPerTick,
            long tickBudgetNanos
    ) {
        public Limits {
            maxPatternPushesPerTick = Math.max(1L, maxPatternPushesPerTick);
            maxProviderChecksPerTick = Math.max(1L, maxProviderChecksPerTick);
            maxExtractPatternInputsPerTick = Math.max(1L, maxExtractPatternInputsPerTick);
            maxReinjectPatternInputsPerTick = Math.max(1L, maxReinjectPatternInputsPerTick);
            tickBudgetNanos = Math.max(1L, Math.min(HARD_MAX_TICK_BUDGET_NANOS, tickBudgetNanos));
        }

        public static Limits fromSettings(ChexsonsaeutilsCompatibilityConfig.ParallelCpuSettings settings) {
            if (settings == null) {
                return defaults();
            }
            return new Limits(
                    settings.maxPatternPushesPerTickPerGrid(),
                    settings.maxProviderChecksPerTickPerGrid(),
                    settings.maxExtractPatternInputsPerTickPerGrid(),
                    settings.maxReinjectPatternInputsPerTickPerGrid(),
                    settings.tickBudgetNanosPerGrid()
            );
        }

        public static Limits defaults() {
            return new Limits(
                    DEFAULT_MAX_PATTERN_PUSHES_PER_TICK,
                    DEFAULT_MAX_PROVIDER_CHECKS_PER_TICK,
                    DEFAULT_MAX_EXTRACT_PATTERN_INPUTS_PER_TICK,
                    DEFAULT_MAX_REINJECT_PATTERN_INPUTS_PER_TICK,
                    DEFAULT_TICK_BUDGET_NANOS
            );
        }
    }

    public record Snapshot(
            long currentTick,
            long patternPushesUsed,
            long providerChecksUsed,
            long extractPatternInputsUsed,
            long reinjectPatternInputsUsed,
            long maxPatternPushesPerTick,
            long maxProviderChecksPerTick,
            long maxExtractPatternInputsPerTick,
            long maxReinjectPatternInputsPerTick,
            long tickBudgetNanos,
            Set<BudgetType> exhaustedTypes
    ) {
    }
}
