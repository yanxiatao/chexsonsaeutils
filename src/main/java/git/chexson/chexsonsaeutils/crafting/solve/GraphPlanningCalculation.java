package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningEngine;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.PlanningAttemptResult;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCalculationAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.NetworkCraftingSimulationStateAccessor;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Chexson's AE Utils — level-1 graph crafting-plan calculation.
 *
 * <p>The planning neighbourhood is compiled once into an immutable graph and
 * solved linearly, sharing the graph across every probe (full amount,
 * CRAFT_LESS bisection, simulate fallback). Anything the graph engine cannot
 * express yet (multiple routes, fuzzy variants, recursive patterns) is
 * reported as a decline and falls through to the level-2 optimized tree
 * ({@link FastCraftingCalculation}), which in turn falls back to the native
 * AE2 calculation.
 */
public class GraphPlanningCalculation extends CraftingCalculation {

    private final Level graphLevel;
    private final IGrid graphGrid;
    private final ICraftingSimulationRequester graphRequester;
    private final GenericStack graphOutput;
    private final CalculationStrategy graphStrategy;
    private final long graphRequestedAmount;
    private final long graphBudgetMillis;
    private final KeyCounter graphNetworkStock;

    private final KeyCounter graphMissing = new KeyCounter();
    private boolean graphSimulate = false;
    @Nullable
    private ICraftingPlan lastPlan = null;

    public GraphPlanningCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester simRequester,
            GenericStack output,
            CalculationStrategy strategy,
            long budgetMillis
    ) {
        super(level, grid, simRequester, output, strategy);
        this.graphLevel = level;
        this.graphGrid = grid;
        this.graphRequester = simRequester;
        this.graphOutput = output;
        this.graphStrategy = strategy;
        this.graphRequestedAmount = output.amount();
        this.graphBudgetMillis = budgetMillis;
        this.graphNetworkStock = resolveNetworkStock(grid);
    }

    /**
     * Reuses the network snapshot the super constructor just built on the
     * server thread (player sources re-query, machine sources use the cached
     * inventory). Environments without the mixin layer (unit tests) rebuild
     * the stock from the storage service's cached inventory.
     */
    private KeyCounter resolveNetworkStock(IGrid grid) {
        NetworkCraftingSimulationState networkInv = null;
        try {
            networkInv = ((CraftingCalculationAccessor) this).chexsonsaeutils$getNetworkInv();
        } catch (ClassCastException | LinkageError ignored) {
            // Mixin layer absent (unit tests).
        }
        if (networkInv != null) {
            try {
                return ((NetworkCraftingSimulationStateAccessor) networkInv).chexsonsaeutils$getList();
            } catch (ClassCastException | LinkageError ignored) {
                // Accessor absent (unit tests).
            }
        }
        var stock = new KeyCounter();
        for (var stack : grid.getStorageService().getCachedInventory()) {
            long amount = stack.getLongValue();
            if (amount > 0) {
                stock.add(stack.getKey(), amount);
            }
        }
        return stock;
    }

    @Override
    public ICraftingPlan run() {
        long start = System.nanoTime();
        ICraftingPlan graphPlan = runSingleAttempt();
        if (graphPlan != null) {
            return graphPlan;
        }
        if (this.planningSession != null
                && this.lastAttempt != null
                && this.lastAttempt.exhaustedDuringPostSolve()) {
            return PlanningLevelRouter.logFallbackPlan("native", this.graphOutput,
                    runNativeFallback(), start);
        }
        return PlanningLevelRouter.logFallbackPlan("fast", this.graphOutput,
                runFastFallback(), start);
    }

    /**
     * Runs only the graph algorithm. A {@code null} result is a controlled
     * decline, including budget exhaustion and non-cancellation failures.
     */
    @Nullable
    public ICraftingPlan runSingleAttempt() {
        long start = System.nanoTime();
        boolean diagnostics = ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_DIAGNOSTICS, "graphPlanningDiagnostics");
        if (diagnostics) {
            GraphDiagnostics.begin();
        }
        try {
            PlanningAttemptResult result = runGraphPlanning();
            this.lastAttempt = result;
            PlanningLevelRouter.logGraphOutcome(this.graphOutput, this.graphStrategy, result, start,
                    this.planningSession == null ? null : this.planningSession.budget(),
                    this.graphRequester);
            if (diagnostics) {
                GraphDiagnostics.dumpAndClear("Graph planning trace for "
                        + this.graphOutput.what() + " x " + this.graphOutput.amount() + ":");
            }
            if (!result.handled()) {
                return null;
            }
            this.lastPlan = result.plan();
            this.graphSimulate = result.plan().simulation();
            this.graphMissing.reset();
            this.graphMissing.addAll(result.plan().missingItems());
            return result.plan();
        } catch (RuntimeException failure) {
            if (OrderedPlanningSelector.isCancellation(failure)) {
                throw failure;
            }
            PlanningLevelRouter.logGraphCrash(this.graphOutput, failure);
            return null;
        } finally {
            if (diagnostics) {
                GraphDiagnostics.end();
            }
        }
    }

    /**
     * Post-solve budget exhaustion means the graph already produced a full
     * solution and only the AE2 round-trip was cut short — re-running the
     * whole optimized tree would exhaust its budget as well, so hand the
     * request to the native per-tick planner instead.
     */
    private ICraftingPlan runNativeFallback() {
        return super.run();
    }

    @Nullable
    private git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession planningSession;
    @Nullable
    private PlanningAttemptResult lastAttempt;

    private PlanningAttemptResult runGraphPlanning() {
        var session = GraphPlanningEngine.createSession(
                this.graphGrid.getCraftingService(),
                this.graphNetworkStock,
                this.graphOutput.what(),
                this.graphStrategy,
                this.graphBudgetMillis,
                this.graphLevel,
                false,
                this.graphGrid.getStorageService().getInventory(),
                this.graphGrid
        );
        this.planningSession = session;
        return session.solve(this.graphRequestedAmount);
    }

    private ICraftingPlan runFastFallback() {
        return new FastCraftingCalculation(
                this.graphLevel,
                this.graphGrid,
                this.graphRequester,
                this.graphOutput,
                this.graphStrategy,
                this.graphBudgetMillis
        ).run();
    }

    @Override
    public boolean isSimulation() {
        return this.graphSimulate;
    }

    @Override
    public AEKey getOutput() {
        return this.graphOutput.what();
    }

    @Override
    public KeyCounter getMissingItems() {
        return this.graphMissing;
    }

    @Override
    public boolean hasMultiplePaths() {
        return this.lastPlan != null && this.lastPlan.multiplePaths();
    }

    /**
     * Never blocks the server thread; the graph path is self-sufficient on its
     * background thread and does not rely on per-tick pumping.
     */
    @Override
    public boolean simulateFor(int micros) {
        return false;
    }
}
