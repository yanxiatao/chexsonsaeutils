package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningEngine;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Request-scoped graph kernel shared by every configured algorithm's recursive path. */
final class RecursivePlanningKernel {
    private final Level level;
    private final IGrid grid;
    private final ICraftingSimulationRequester requester;
    private final GenericStack output;
    private final CalculationStrategy strategy;
    private final long budgetMillis;
    private final KeyCounter stock;
    private final long requestedAmount;
    @Nullable
    private GraphPlanningSession session;
    @Nullable
    private ICraftingPlan plan;
    private boolean solved;

    RecursivePlanningKernel(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy,
            long budgetMillis,
            KeyCounter stock
    ) {
        this.level = level;
        this.grid = grid;
        this.requester = requester;
        this.output = output;
        this.strategy = strategy;
        this.budgetMillis = budgetMillis;
        this.stock = stock;
        this.requestedAmount = output.amount();
    }

    GraphPlanningSession session() {
        if (this.session == null) {
            this.session = GraphPlanningEngine.createSession(
                    this.grid.getCraftingService(),
                    this.stock,
                    this.output.what(),
                    this.strategy,
                    this.budgetMillis,
                    this.level,
                    true,
                    this.grid.getStorageService().getInventory(),
                    this.grid
            );
        }
        return this.session;
    }

    @Nullable
    ICraftingPlan solve() {
        return solve(false, false);
    }

    @Nullable
    ICraftingPlan solveAggregate() {
        return solve(false, true);
    }

    @Nullable
    ICraftingPlan solveRecursive() {
        return solve(true, false);
    }

    @Nullable
    private ICraftingPlan solve(boolean recursiveRequired, boolean aggregate) {
        if (!this.solved) {
            var planningSession = session();
            var result = aggregate
                    ? planningSession.solveAggregate(this.requestedAmount)
                    : planningSession.solve(this.requestedAmount);
            if ((recursiveRequired || planningSession.graph().cycleKind() != CraftGraph.CycleKind.NONE)
                    && (!result.handled() || result.plan() == null || result.plan().simulation())) {
                result = planningSession.solveExact(this.requestedAmount);
            }
            this.plan = result.handled() ? result.plan() : null;
            this.solved = true;
        }
        return this.plan;
    }
}
