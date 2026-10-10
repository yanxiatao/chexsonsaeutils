package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.ICraftingPlan;

/**
 * Outcome of one graph-planning solve: either a finished plan, or a decline
 * reason telling the caller to fall through to the optimized tree planners.
 * A budget exhaustion additionally records which phase ran out so the shell
 * can route the fallback: COMPILE/SOLVE timeouts hand over to the optimized
 * tree, while CONVERT/RETENTION timeouts (the solve itself finished, only the
 * AE2 round-trip was cut short) skip the tree and go straight to the native
 * per-tick planner — re-running the whole tree would just exhaust its budget
 * too.
 */
public record PlanningAttemptResult(
        boolean handled,
        @Nullable ICraftingPlan plan,
        @Nullable String declineReason,
        @Nullable GraphPlanningBudget.Phase budgetExhaustedPhase
) {

    public static PlanningAttemptResult handled(ICraftingPlan plan) {
        return new PlanningAttemptResult(true, plan, null, null);
    }

    public static PlanningAttemptResult declined(String reason) {
        return new PlanningAttemptResult(false, null, reason, null);
    }

    public static PlanningAttemptResult budgetExhausted(String reason,
            GraphPlanningBudget.Phase phase) {
        return new PlanningAttemptResult(false, null, reason, phase);
    }

    public boolean exhaustedDuringPostSolve() {
        return this.budgetExhaustedPhase == GraphPlanningBudget.Phase.CONVERT
                || this.budgetExhaustedPhase == GraphPlanningBudget.Phase.RETENTION;
    }
}
