package git.chexson.chexsonsaeutils.crafting.directprocessing;

public final class ProcessingExecutionBudget {

    public static final Limits NORMAL_LIMITS = new Limits(
            64,
            64,
            1_000_000L,
            1_024
    );
    public static final Limits HIGH_LIMITS = new Limits(
            256,
            256,
            2_000_000L,
            4_096
    );
    public static final Limits BENCHMARK_LIMITS = new Limits(
            4_096,
            4_096,
            5_000_000L,
            65_536
    );

    private final Limits limits;
    private int admitTokens;
    private int completeTokens;
    private long deadlineNanos;
    private long tickStartedAtNanos;

    public ProcessingExecutionBudget(Limits limits) {
        this.limits = limits == null ? NORMAL_LIMITS : limits;
        resetForTick(System.nanoTime());
    }

    public static ProcessingExecutionBudget normal() {
        return new ProcessingExecutionBudget(NORMAL_LIMITS);
    }

    public static ProcessingExecutionBudget high() {
        return new ProcessingExecutionBudget(HIGH_LIMITS);
    }

    public static ProcessingExecutionBudget benchmark() {
        return new ProcessingExecutionBudget(BENCHMARK_LIMITS);
    }

    public static ProcessingExecutionBudget forProfile(String profileName) {
        if ("benchmark".equalsIgnoreCase(profileName)) {
            return benchmark();
        }
        if ("high".equalsIgnoreCase(profileName)) {
            return high();
        }
        return normal();
    }

    public void resetForTick(long nowNanos) {
        tickStartedAtNanos = nowNanos;
        admitTokens = limits.admitTokens();
        completeTokens = limits.completeTokens();
        deadlineNanos = safeAdd(nowNanos, limits.wallNanos());
    }

    public boolean tryClaimAdmit() {
        if (!hasTimeBudget(System.nanoTime()) || admitTokens <= 0) {
            return false;
        }
        admitTokens--;
        return true;
    }

    public boolean tryClaimComplete() {
        if (!hasTimeBudget(System.nanoTime()) || completeTokens <= 0) {
            return false;
        }
        completeTokens--;
        return true;
    }

    public boolean hasTimeBudget(long nowNanos) {
        return nowNanos - deadlineNanos < 0L;
    }

    public int maxCoalescedExecutions() {
        return limits.maxCoalescedExecutions();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                admitTokens,
                completeTokens,
                Math.max(0L, deadlineNanos - System.nanoTime()),
                tickStartedAtNanos,
                limits
        );
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    public record Limits(
            int admitTokens,
            int completeTokens,
            long wallNanos,
            int maxCoalescedExecutions
    ) {
        public Limits {
            admitTokens = Math.max(1, admitTokens);
            completeTokens = Math.max(1, completeTokens);
            wallNanos = Math.max(1L, wallNanos);
            maxCoalescedExecutions = Math.max(1, maxCoalescedExecutions);
        }
    }

    public record Snapshot(
            int admitTokensRemaining,
            int completeTokensRemaining,
            long wallNanosRemaining,
            long tickStartedAtNanos,
            Limits limits
    ) {
    }
}
