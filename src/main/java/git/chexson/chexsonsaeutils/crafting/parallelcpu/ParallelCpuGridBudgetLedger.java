package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;

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
        return claimPatternPushes(1L) == 1L;
    }

    public long claimPatternPushes(long requested) {
        long claimed = claim(requested, limits.maxPatternPushesPerTick(), patternPushesUsed, BudgetType.PATTERN_PUSH);
        patternPushesUsed += claimed;
        return claimed;
    }

    public boolean tryClaimProviderCheck() {
        return claimProviderChecks(1L) == 1L;
    }

    public long claimProviderChecks(long requested) {
        long claimed = claim(requested, limits.maxProviderChecksPerTick(), providerChecksUsed,
                BudgetType.PROVIDER_CHECK);
        providerChecksUsed += claimed;
        return claimed;
    }

    public boolean tryClaimExtractPatternInputs() {
        return claimExtractPatternInputs(1L) == 1L;
    }

    public long claimExtractPatternInputs(long requested) {
        long claimed = claim(requested, limits.maxExtractPatternInputsPerTick(), extractPatternInputsUsed,
                BudgetType.EXTRACT_PATTERN_INPUTS);
        extractPatternInputsUsed += claimed;
        return claimed;
    }

    public boolean tryClaimReinjectPatternInputs() {
        return claimReinjectPatternInputs(1L) == 1L;
    }

    public long claimReinjectPatternInputs(long requested) {
        long claimed = claim(requested, limits.maxReinjectPatternInputsPerTick(), reinjectPatternInputsUsed,
                BudgetType.REINJECT_PATTERN_INPUTS);
        reinjectPatternInputsUsed += claimed;
        return claimed;
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

    private long claim(long requested, long limit, long used, BudgetType exhaustedType) {
        if (requested <= 0L) {
            return 0L;
        }
        long remaining = Math.max(0L, limit - used);
        long claimed = Math.min(requested, remaining);
        if (claimed < requested) {
            exhaustedTypes.add(exhaustedType);
        }
        return claimed;
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

        public static Limits fromSettings(ParallelCraftingCpuConfig.Settings settings) {
            if (settings == null) {
                return defaults();
            }
            return new Limits(
                    settings.maxPatternPushesPerTickPerGrid(),
                    settings.maxProviderChecksPerTickPerGrid(),
                    settings.maxPatternPushesPerTickPerGrid(),
                    settings.maxPatternPushesPerTickPerGrid(),
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
