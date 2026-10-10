package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import net.minecraft.world.level.Level;

import java.util.List;

/** A single AE2 calculation whose constructor freezes and executes configured algorithm order. */
public final class OrderedPlanningCalculation extends CraftingCalculation {
    private final Level level;
    private final IGrid grid;
    private final ICraftingSimulationRequester requester;
    private final GenericStack output;
    private final CalculationStrategy strategy;
    private final List<PlanningAlgorithmId> order;
    private final boolean graphEligible;
    private final boolean fastEligible;
    private final long fastBudgetMillis;
    private final long graphBudgetMillis;
    private final boolean synchronousNative;
    private final long requestStartNanos;
    private final RecursivePlanningKernel recursiveKernel;
    private final KeyCounter missing = new KeyCounter();

    private boolean simulation;
    private boolean multiplePaths;

    public OrderedPlanningCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        this(level, grid, requester, output, strategy, System.nanoTime());
    }

    public OrderedPlanningCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy,
            long requestStartNanos
    ) {
        this(level, grid, requester, output, strategy,
                PlanningAlgorithmOrderService.global().snapshot().order(),
                PlanningLevelRouter.shouldUseGraphPlanning(grid),
                PlanningLevelRouter.shouldUseFastPlanning(grid),
                PlanningLevelRouter.budgetMillis(),
                PlanningLevelRouter.graphBudgetMillis(),
                false,
                requestStartNanos);
    }

    OrderedPlanningCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy,
            List<PlanningAlgorithmId> order,
            boolean graphEligible,
            boolean fastEligible,
            long fastBudgetMillis,
            long graphBudgetMillis,
            boolean synchronousNative
    ) {
        this(level, grid, requester, output, strategy, order, graphEligible, fastEligible,
                fastBudgetMillis, graphBudgetMillis, synchronousNative, System.nanoTime());
    }

    OrderedPlanningCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy,
            List<PlanningAlgorithmId> order,
            boolean graphEligible,
            boolean fastEligible,
            long fastBudgetMillis,
            long graphBudgetMillis,
            boolean synchronousNative,
            long requestStartNanos
    ) {
        super(level, grid, requester, output, strategy);
        this.level = level;
        this.grid = grid;
        this.requester = requester;
        this.output = output;
        this.strategy = strategy;
        this.order = List.copyOf(order);
        this.graphEligible = graphEligible;
        this.fastEligible = fastEligible;
        this.fastBudgetMillis = fastBudgetMillis;
        this.graphBudgetMillis = graphBudgetMillis;
        this.synchronousNative = synchronousNative;
        this.requestStartNanos = requestStartNanos;
        this.recursiveKernel = new RecursivePlanningKernel(
                level, grid, requester, output, strategy, this.graphBudgetMillis,
                networkStockSnapshot(grid));
    }

    @Override
    public ICraftingPlan run() {
        OrderedPlanningSelector.Selection<ICraftingPlan> selected = OrderedPlanningSelector.select(
                this.order,
                this::isEligible,
                this::runAttempt
        );
        if (selected == null) {
            // Defensive compatibility for an invalid/incomplete external snapshot.
            ICraftingPlan plan = runAe2Native();
            updateFinalState(plan);
            PlanningProvenance.tag(plan, PlanningProvenance.NATIVE, this.requestStartNanos, this.requester);
            return plan;
        }
        ICraftingPlan plan = selected.value();
        updateFinalState(plan);
        tagActualAlgorithm(plan, selected.algorithm(), this.requestStartNanos);
        return plan;
    }

    private void updateFinalState(ICraftingPlan plan) {
        this.simulation = plan.simulation();
        this.multiplePaths = plan.multiplePaths();
        this.missing.reset();
        this.missing.addAll(plan.missingItems());
    }

    private boolean isEligible(PlanningAlgorithmId algorithm) {
        return switch (algorithm) {
            case FROZEN_ROUTE, GRAPH -> this.graphEligible;
            case FAST -> this.fastEligible;
            case NATIVE -> true;
        };
    }

    private ICraftingPlan runAttempt(PlanningAlgorithmId algorithm) {
        if (algorithm == PlanningAlgorithmId.FROZEN_ROUTE) {
            return recursiveCycleDetected()
                    ? runRecursiveAttempt()
                    : this.recursiveKernel.solveAggregate();
        }
        if (algorithm == PlanningAlgorithmId.GRAPH) {
            return runGraphAttempt();
        }
        if (recursiveCycleDetected()) {
            return runRecursiveAttempt();
        }
        return switch (algorithm) {
            case FAST -> new FastCraftingCalculation(
                    this.level, this.grid, this.requester, this.output, this.strategy,
                    this.fastBudgetMillis).runSingleAttempt();
            case NATIVE -> runAe2Native();
            case FROZEN_ROUTE, GRAPH -> throw new IllegalStateException("handled above");
        };
    }

    private ICraftingPlan runGraphAttempt() {
        CraftGraph graph = this.recursiveKernel.session().graph();
        return graph.cycleKind() == CraftGraph.CycleKind.NONE
                ? this.recursiveKernel.solve()
                : runRecursiveAttempt();
    }

    private boolean recursiveCycleDetected() {
        return this.recursiveKernel.session().graph().cycleKind() != CraftGraph.CycleKind.NONE;
    }

    private ICraftingPlan runRecursiveAttempt() {
        return this.recursiveKernel.solveRecursive();
    }

    private static KeyCounter networkStockSnapshot(IGrid grid) {
        var stock = new KeyCounter();
        for (var entry : grid.getStorageService().getCachedInventory()) {
            if (entry.getLongValue() > 0L) {
                stock.add(entry.getKey(), entry.getLongValue());
            }
        }
        return stock;
    }

    private ICraftingPlan runAe2Native() {
        if (this.synchronousNative) {
            return new FastCraftingCalculation(
                    this.level, this.grid, this.requester, this.output, this.strategy, 0L)
                    .runSingleAttempt();
        }
        return new CraftingCalculation(
                this.level, this.grid, this.requester, this.output, this.strategy).run();
    }

    private void tagActualAlgorithm(ICraftingPlan plan, PlanningAlgorithmId algorithm, long startNanos) {
        String solver = switch (algorithm) {
            case FROZEN_ROUTE -> PlanningProvenance.FROZEN_ROUTE;
            case GRAPH -> PlanningProvenance.GRAPH;
            case FAST -> PlanningProvenance.FAST;
            case NATIVE -> PlanningProvenance.NATIVE;
        };
        PlanningProvenance.tag(plan, solver, startNanos, this.requester);
    }

    @Override
    public boolean isSimulation() {
        return this.simulation;
    }

    @Override
    public AEKey getOutput() {
        return this.output.what();
    }

    @Override
    public KeyCounter getMissingItems() {
        return this.missing;
    }

    @Override
    public boolean hasMultiplePaths() {
        return this.multiplePaths;
    }

    /** Ordered attempts are background-thread complete and never pump AE2 from the server thread. */
    @Override
    public boolean simulateFor(int micros) {
        return false;
    }
}
