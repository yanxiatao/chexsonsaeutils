package git.chexson.chexsonsaeutils.crafting.graphplan.diag;

/**
 * Shared wall-clock budget for one graph-planning session. The deadline is a
 * single quota shared by compilation and every solve probe; the four phases
 * (COMPILE/SOLVE/CONVERT/RETENTION) are tracked separately for diagnostics so
 * a timeout can be attributed to a stage.
 */
public final class GraphPlanningBudget {

    public enum Phase {
        COMPILE,
        SOLVE,
        CONVERT,
        RETENTION
    }

    /**
     * Thrown when the shared quota runs out. Stack traces are disabled because
     * it is control flow, not an error.
     */
    public static final class BudgetExhaustedException extends RuntimeException {
        public BudgetExhaustedException() {
            super(null, null, false, false);
        }
    }

    private final long deadlineNanos;
    private final long createdNanos = System.nanoTime();
    private final long[] phaseNanos = new long[Phase.values().length];
    private Phase phase = Phase.COMPILE;
    private long phaseStart = createdNanos;

    public GraphPlanningBudget(long budgetMillis) {
        this.deadlineNanos = budgetMillis <= 0L
                ? Long.MAX_VALUE
                : createdNanos + budgetMillis * 1_000_000L;
    }

    public void enter(Phase nextPhase) {
        long now = System.nanoTime();
        this.phaseNanos[this.phase.ordinal()] += now - this.phaseStart;
        this.phase = nextPhase;
        var progress = git.chexson.chexsonsaeutils.crafting.progress.PlanningProgressContext.current();
        if (progress != null) progress.phase(nextPhase.name());
        this.phaseStart = now;
    }

    public void check() {
        if (System.nanoTime() > this.deadlineNanos) {
            throw new BudgetExhaustedException();
        }
    }

    public boolean exhausted() {
        return System.nanoTime() > this.deadlineNanos;
    }

    public Phase phase() {
        return this.phase;
    }

    public long phaseNanos(Phase phase) {
        return this.phaseNanos[phase.ordinal()];
    }

    /**
     * 当前阶段已走的时间——仅当参数就是当前阶段时才非零。最后一个阶段没有后续
     * {@code enter()} 结账，诊断读数需要靠它封口。
     */
    public long currentPhaseNanos(Phase phase) {
        return this.phase == phase ? System.nanoTime() - this.phaseStart : 0L;
    }

    public long elapsedNanos() {
        return System.nanoTime() - this.createdNanos;
    }
}
