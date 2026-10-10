package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.RetentionApplier;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import org.jetbrains.annotations.Nullable;

public final class GraphPlanPostProcessor {

    private GraphPlanPostProcessor() {
    }

    public static SolveResult reconcileMissingWithStorage(
            SolveResult result,
            @Nullable MEStorage storage,
            @Nullable ICraftingService craftingService
    ) {
        if (!result.simulation() || storage == null) {
            return result;
        }
        var state = result.state();
        for (AEKey key : state.missing().keys()) {
            long missing = state.missing().getLongById(state.idOf(key));
            if (missing <= 0L) {
                continue;
            }
            long extractable = storage.extract(key, missing,
                    appeng.api.config.Actionable.SIMULATE, IActionSource.empty());
            if (GraphDiagnostics.active()) {
                GraphDiagnostics.log(
                        "reconcile key=%s missing=%d extractable=%d canEmitFor=%s",
                        key, missing, extractable,
                        craftingService != null && craftingService.canEmitFor(key));
            }
            if (extractable > 0L) {
                state.reduceMissing(key, extractable);
                state.addUsedForReconciliation(key, extractable);
                state.addStackBytes(key, extractable, 1L);
            }
        }
        return state.missing().isEmpty() ? result.withSimulation(false) : result;
    }

    @Nullable
    public static String applyRetention(SolveResult result, CraftGraph graph, long requestedAmount) {
        return RetentionApplier.apply(result.state(), graph, requestedAmount);
    }

    @Nullable
    public static String conversionDecline(SolveResult result) {
        return result.state().hasNegativeExitValues() ? "negative plan values" : null;
    }

    public static ICraftingPlan convert(SolveResult result, CraftGraph graph) {
        Ae2Amounts.logWideSummary();
        if (GraphDiagnostics.active()) {
            GraphDiagnostics.log("graph.hostBatch disabled: per-pattern tasks + runtime multi-push");
        }
        return Ae2PlanConverter.toPlan(result, graph);
    }
}
