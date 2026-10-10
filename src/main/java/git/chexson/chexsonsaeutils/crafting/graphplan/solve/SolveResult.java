package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitness;
import org.jetbrains.annotations.Nullable;

/**
 * Outcome of one level-1 solve: a populated ledger plus the amount and
 * simulation flag that go into {@code CraftingPlan.finalOutput}, or a decline
 * reason when the graph engine refuses the request.
 */
public record SolveResult(
        @Nullable GraphPlanningState state,
        long finalAmount,
        boolean simulation,
        @Nullable String declineReason,
        @Nullable PlanningInputWitness witness) {

    public static SolveResult success(GraphPlanningState state, long finalAmount, boolean simulation) {
        return success(state, finalAmount, simulation, null);
    }

    public static SolveResult success(GraphPlanningState state, long finalAmount, boolean simulation,
            @Nullable PlanningInputWitness witness) {
        return new SolveResult(state, finalAmount, simulation, null, witness);
    }

    public static SolveResult declined(String reason) {
        return new SolveResult(null, 0, false, reason, null);
    }

    public SolveResult withSimulation(boolean value) {
        return new SolveResult(state, finalAmount, value, declineReason, witness);
    }

    public SolveResult withWitness(@Nullable PlanningInputWitness value) {
        return new SolveResult(state, finalAmount, simulation, declineReason, value);
    }

    public boolean hasDeclined() {
        return declineReason != null;
    }
}
