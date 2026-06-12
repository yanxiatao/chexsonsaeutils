package git.chexson.chexsonsaeutils.crafting.directprocessing;

public final class ProcessingExecutionBudgetController {

    public static final Limits NORMAL_LIMITS = new Limits(
            64,
            64,
            16,
            16_384L,
            1_000_000L,
            1_024
    );
    public static final Limits HIGH_LIMITS = new Limits(
            256,
            256,
            32,
            65_536L,
            2_000_000L,
            4_096
    );
    public static final Limits BENCHMARK_LIMITS = new Limits(
            4_096,
            4_096,
            128,
            1_048_576L,
            5_000_000L,
            65_536
    );

    private final Limits limits;
    private int admitTokens;
    private int completeTokens;
    private int aeReturnOps;
    private long aeReturnAmount;
    private long deadlineNanos;
    private long tickStartedAtNanos;

    public ProcessingExecutionBudgetController(Limits limits) {
        this.limits = limits == null ? NORMAL_LIMITS : limits;
        resetForTick(System.nanoTime());
    }

    public static ProcessingExecutionBudgetController normal() {
        return new ProcessingExecutionBudgetController(NORMAL_LIMITS);
    }

    public static ProcessingExecutionBudgetController high() {
        return new ProcessingExecutionBudgetController(HIGH_LIMITS);
    }

    public static ProcessingExecutionBudgetController benchmark() {
        return new ProcessingExecutionBudgetController(BENCHMARK_LIMITS);
    }

    public static ProcessingExecutionBudgetController forProfile(String profileName) {
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
        aeReturnOps = limits.aeReturnOps();
        aeReturnAmount = limits.aeReturnAmount();
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

    public boolean tryClaimAeReturn(long amount) {
        return tryClaimAeReturn(amount, 1);
    }

    public boolean tryClaimAeReturn(long amount, int operations) {
        long requestedAmount = Math.max(1L, amount);
        int requestedOperations = Math.max(1, operations);
        if (!hasTimeBudget(System.nanoTime())
                || aeReturnOps < requestedOperations
                || aeReturnAmount < requestedAmount) {
            return false;
        }
        aeReturnOps -= requestedOperations;
        aeReturnAmount -= requestedAmount;
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
                aeReturnOps,
                aeReturnAmount,
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
            int aeReturnOps,
            long aeReturnAmount,
            long wallNanos,
            int maxCoalescedExecutions
    ) {
        public Limits {
            admitTokens = Math.max(1, admitTokens);
            completeTokens = Math.max(1, completeTokens);
            aeReturnOps = Math.max(1, aeReturnOps);
            aeReturnAmount = Math.max(1L, aeReturnAmount);
            wallNanos = Math.max(1L, wallNanos);
            maxCoalescedExecutions = Math.max(1, maxCoalescedExecutions);
        }
    }

    public record Snapshot(
            int admitTokensRemaining,
            int completeTokensRemaining,
            int aeReturnOpsRemaining,
            long aeReturnAmountRemaining,
            long wallNanosRemaining,
            long tickStartedAtNanos,
            Limits limits
    ) {
    }
}
