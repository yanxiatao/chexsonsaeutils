package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.speedplan.FrozenRoutePlanner;
import git.chexson.chexsonsaeutils.crafting.speedplan.FrozenRoutePlanningCalculation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public final class LegacyFrozenRouteReplay extends CraftingCalculation {
    private final Level level;
    private final IGrid grid;
    private final ICraftingSimulationRequester requester;
    private final GenericStack output;
    private final CalculationStrategy strategy;
    private final List<PlanningAlgorithmId> order;
    private final RecursivePlanningKernel recursiveKernel;
    private FrozenRoutePlanningCalculation frozenCalculation;
    private FrozenRoutePlanner.CompiledProgram frozenProgram;
    private boolean frozenPrepared;
    private final KeyCounter missing = new KeyCounter();
    private boolean simulation;
    private boolean multiplePaths;

    private LegacyFrozenRouteReplay(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        super(level, grid, requester, output, strategy);
        this.level = level;
        this.grid = grid;
        this.requester = requester;
        this.output = output;
        this.strategy = strategy;
        var configuredOrder = new ArrayList<>(List.of(PlanningAlgorithmId.values()));
        configuredOrder.remove(PlanningAlgorithmId.FROZEN_ROUTE);
        configuredOrder.addFirst(PlanningAlgorithmId.FROZEN_ROUTE);
        this.order = List.copyOf(configuredOrder);
        this.recursiveKernel = new RecursivePlanningKernel(
                level, grid, requester, output, strategy, 0L, networkStockSnapshot(grid));
    }

    public static LegacyFrozenRouteReplay create(
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        return new LegacyFrozenRouteReplay(null, grid, requester, output, strategy);
    }

    @Override
    public ICraftingPlan run() {
        long start = System.nanoTime();
        OrderedPlanningSelector.Selection<ICraftingPlan> selected = OrderedPlanningSelector.select(
                this.order,
                algorithm -> true,
                this::runAttempt
        );
        if (selected == null) {
            ICraftingPlan plan = runAe2Native();
            updateFinalState(plan);
            PlanningProvenance.tag(plan, PlanningProvenance.NATIVE, start, this.requester);
            return plan;
        }
        ICraftingPlan plan = selected.value();
        updateFinalState(plan);
        tagActualAlgorithm(plan, selected.algorithm(), start);
        return plan;
    }

    private ICraftingPlan runAttempt(PlanningAlgorithmId algorithm) {
        if (algorithm == PlanningAlgorithmId.FROZEN_ROUTE) {
            prepareFrozen();
            if (recursiveCycleDetected()) {
                return runRecursiveAttempt();
            }
            return this.frozenCalculation.runPreparedAttempt(this.frozenProgram);
        }
        if (algorithm == PlanningAlgorithmId.GRAPH) {
            return runGraphAttempt();
        }
        if (recursiveCycleDetected()) {
            return runRecursiveAttempt();
        }
        return switch (algorithm) {
            case FAST -> new FastCraftingCalculation(
                    this.level, this.grid, this.requester, this.output, this.strategy, 0L)
                    .runSingleAttempt();
            case NATIVE -> runAe2Native();
            case FROZEN_ROUTE, GRAPH -> throw new IllegalStateException("handled above");
        };
    }

    private void prepareFrozen() {
        if (this.frozenPrepared) {
            return;
        }
        this.frozenCalculation = new FrozenRoutePlanningCalculation(
                this.level, this.grid, this.requester, this.output, this.strategy);
        this.frozenProgram = this.frozenCalculation.prepareProgram();
        this.frozenPrepared = true;
    }

    private ICraftingPlan runGraphAttempt() {
        CraftGraph graph = this.recursiveKernel.session().graph();
        return graph.cycleKind() == CraftGraph.CycleKind.NONE
                ? this.recursiveKernel.solve()
                : runRecursiveAttempt();
    }

    private boolean recursiveCycleDetected() {
        prepareFrozen();
        if (this.frozenProgram != null) {
            return this.frozenProgram.hasCycles();
        }
        return this.recursiveKernel.session().graph().cycleKind() != CraftGraph.CycleKind.NONE;
    }

    private ICraftingPlan runRecursiveAttempt() {
        return this.recursiveKernel.solveRecursive();
    }

    private ICraftingPlan runAe2Native() {
        return new FastCraftingCalculation(
                this.level, this.grid, this.requester, this.output, this.strategy, 0L)
                .runSingleAttempt();
    }

    private void updateFinalState(ICraftingPlan plan) {
        this.simulation = plan.simulation();
        this.multiplePaths = plan.multiplePaths();
        this.missing.reset();
        this.missing.addAll(plan.missingItems());
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

    private static KeyCounter networkStockSnapshot(IGrid grid) {
        var stock = new KeyCounter();
        for (var entry : grid.getStorageService().getCachedInventory()) {
            if (entry.getLongValue() > 0L) {
                stock.add(entry.getKey(), entry.getLongValue());
            }
        }
        return stock;
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

    @Override
    public boolean simulateFor(int micros) {
        return false;
    }
}
