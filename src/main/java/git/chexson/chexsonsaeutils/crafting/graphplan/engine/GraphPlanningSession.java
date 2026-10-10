package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningExecutionContractBuilder;
import git.chexson.chexsonsaeutils.crafting.graphplan.aggregate.AggregateSolver;
import git.chexson.chexsonsaeutils.crafting.graphplan.search.ResidualBestFirstPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.PlanningOverflowException;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One graph-planning request: the graph is compiled once and reused across
 * every solve probe (full amount, CRAFT_LESS bisection steps, simulate
 * fallback) under a single shared budget.
 */
public final class GraphPlanningSession {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final CraftGraph graph;
    private final CalculationStrategy strategy;
    private final GraphPlanningBudget budget;
    private final boolean retentionEnabled;
    /**
     * 真实网络存储（可空，测试环境无网格）。唯一用途：出口对账时对剩余
     * missing 逐键做 {@code extract(SIMULATE)}——与 AE2 确认界面
     * {@code CraftingPlanSummary.fromJob} 的重算口径完全一致。
     */
    @Nullable
    private final appeng.api.storage.MEStorage storage;
    @Nullable
    private final ICraftingService craftingService;
    @Nullable
    private final Level planningLevel;
    @Nullable
    private final IGrid planningGrid;
    /** 实机夹具捕获（默认关）；求解时才知道请求量，所以在这里挂到求解后写出。 */
    @Nullable
    private final GraphPlanCapture capture;

    private GraphPlanningSession(CraftGraph graph, CalculationStrategy strategy, GraphPlanningBudget budget,
            boolean retentionEnabled, @Nullable GraphPlanCapture capture,
            @Nullable appeng.api.storage.MEStorage storage,
            @Nullable ICraftingService craftingService, @Nullable IGrid planningGrid,
            @Nullable Level planningLevel) {
        this.graph = graph;
        this.strategy = strategy;
        this.budget = budget;
        this.retentionEnabled = retentionEnabled;
        this.capture = capture;
        this.storage = storage;
        this.craftingService = craftingService;
        this.planningGrid = planningGrid;
        this.planningLevel = planningLevel;
    }

    public static GraphPlanningSession create(ICraftingService craftingService, KeyCounter networkStock,
            AEKey target, CalculationStrategy strategy, GraphPlanningBudget budget, @Nullable Level level,
            boolean retentionEnabled) {
        return create(craftingService, networkStock, target, strategy, budget, level,
                retentionEnabled, null, null);
    }

    public static GraphPlanningSession create(ICraftingService craftingService, KeyCounter networkStock,
            AEKey target, CalculationStrategy strategy, GraphPlanningBudget budget, @Nullable Level level,
            boolean retentionEnabled, @Nullable appeng.api.storage.MEStorage storage,
            @Nullable IGrid planningGrid) {
        return create(craftingService, networkStock, target, strategy, budget, level,
                retentionEnabled, storage, planningGrid, level);
    }

    public static GraphPlanningSession create(ICraftingService craftingService, KeyCounter networkStock,
            AEKey target, CalculationStrategy strategy, GraphPlanningBudget budget, @Nullable Level level,
            boolean retentionEnabled, @Nullable appeng.api.storage.MEStorage storage,
            @Nullable IGrid planningGrid, @Nullable Level planningLevel) {
        budget.enter(GraphPlanningBudget.Phase.COMPILE);
        GraphPlanCapture capture = GraphPlanCapture.begin(craftingService, networkStock, target, level);
        CraftGraph graph = GraphCompiler.compile(
                capture != null ? capture.service() : craftingService,
                networkStock, target, level);
        if (GraphDiagnostics.active()) {
            // 与 GraphCompiler 的 build 诊断行内容重复，此前却是无条件 INFO：
            // 每次请求都要格式化 5 个参数并多查一次 producersByOutput。
            GraphDiagnostics.log("compile probe target=%s decline=%s craftingForTarget=%d rootProducers=%d"
                            + " rootRoutes=%d",
                    target, graph.declineReason(), graph.craftingForTargetCount(),
                    graph.producersByOutput().getOrDefault(target, java.util.List.of()).size(),
                    graph.rootRoutesCount());
        }
        return new GraphPlanningSession(graph, strategy, budget, retentionEnabled,
                capture, storage, craftingService, planningGrid, planningLevel);
    }

    public CraftGraph graph() {
        return this.graph;
    }

    public GraphPlanningBudget budget() {
        return this.budget;
    }

    private SolveResult solveLedger(long requestedAmount) {
        boolean lexicographic = ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_LEXICOGRAPHIC,
                "graphPlanningLexicographic");
        long residualStart = GraphDiagnostics.active() ? System.nanoTime() : 0L;
        SolveResult tree = ResidualBestFirstPlanner.solve(
                this.graph, this.strategy, requestedAmount);
        boolean linearFallback = tree.hasDeclined();
        if (linearFallback) {
            GraphDiagnostics.log("solve.residual declined: %s", tree.declineReason());
            tree = LinearPlanner.solve(this.graph, this.strategy, requestedAmount,
                    lexicographic, this.budget);
        }
        if (GraphDiagnostics.active()) {
            GraphDiagnostics.log("solve.split residualMs=%d lexicographicRequested=%s"
                            + " linearFallback=%s fallbackResult=%s",
                    (System.nanoTime() - residualStart) / 1_000_000L,
                    lexicographic, linearFallback,
                    linearFallback ? (tree.hasDeclined() ? "declined" : "available") : "not-run");
        }
        return tree;
    }

    private SolveResult solveAggregateLedger(long requestedAmount) {
        SolveResult result = AggregateSolver.solve(
                this.graph, this.strategy, requestedAmount, this.budget);
        if (!result.hasDeclined()) {
            return result;
        }
        GraphDiagnostics.log("solve.aggregate declined: %s", result.declineReason());
        return solveExactLedger(requestedAmount, this.budget);
    }

    private SolveResult solveExactLedger(long requestedAmount) {
        return solveExactLedger(requestedAmount, null);
    }

    private SolveResult solveExactLedger(long requestedAmount, @Nullable GraphPlanningBudget solveBudget) {
        boolean lexicographic = ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_LEXICOGRAPHIC,
                "graphPlanningLexicographic");
        return LinearPlanner.solve(this.graph, this.strategy, requestedAmount,
                lexicographic, solveBudget);
    }

    /**
     * Runs the complete solve for the requested amount and converts the result
     * into an AE2 plan. Any decline, overflow, or budget exhaustion is reported
     * as a declined result so the caller falls through to the tree planners.
     */
    public PlanningAttemptResult solve(long requestedAmount) {
        return solve(requestedAmount, SolveMode.DEFAULT);
    }

    public PlanningAttemptResult solveAggregate(long requestedAmount) {
        return solve(requestedAmount, SolveMode.AGGREGATE);
    }

    public PlanningAttemptResult solveExact(long requestedAmount) {
        return solve(requestedAmount, SolveMode.EXACT);
    }

    private PlanningAttemptResult solve(long requestedAmount, SolveMode mode) {
        boolean enforceBudget = mode != SolveMode.EXACT;
        if (this.capture != null) {
            this.capture.writeAsync(requestedAmount);
        }
        try {
            this.budget.enter(GraphPlanningBudget.Phase.SOLVE);
            if (enforceBudget) {
                this.budget.check();
            }
            SolveResult result = switch (mode) {
                case DEFAULT -> solveLedger(requestedAmount);
                case AGGREGATE -> solveAggregateLedger(requestedAmount);
                case EXACT -> solveExactLedger(requestedAmount);
            };
            if (result.declineReason() != null) {
                return PlanningAttemptResult.declined(result.declineReason());
            }
            result = GraphPlanPostProcessor.reconcileMissingWithStorage(
                    result, this.storage, this.craftingService);
            if (this.retentionEnabled) {
                this.budget.enter(GraphPlanningBudget.Phase.RETENTION);
                if (enforceBudget) {
                    this.budget.check();
                }
                String retentionDecline = GraphPlanPostProcessor.applyRetention(
                        result, this.graph, requestedAmount);
                if (retentionDecline != null) {
                    return PlanningAttemptResult.declined(retentionDecline);
                }
            }
            this.budget.enter(GraphPlanningBudget.Phase.CONVERT);
            if (enforceBudget) {
                this.budget.check();
            }
            String conversionDecline = GraphPlanPostProcessor.conversionDecline(result);
            if (conversionDecline != null) {
                GraphDiagnostics.log("graph.negativeExit decline amount=%d", requestedAmount);
                return PlanningAttemptResult.declined(conversionDecline);
            }
            if (result.witness() == null && this.graph.cycleKind() == CraftGraph.CycleKind.NONE) {
                var contract = PlanningExecutionContractBuilder.build(result.state(), this.graph);
                if (contract != null && contract.complete()) {
                    result = result.withWitness(contract);
                }
            }
            return PlanningAttemptResult.handled(GraphPlanPostProcessor.convert(result, this.graph));
        } catch (PlanningOverflowException failure) {
            return PlanningAttemptResult.declined("graph overflow");
        } catch (GraphPlanningBudget.BudgetExhaustedException failure) {
            return PlanningAttemptResult.budgetExhausted(
                    "graph planning budget exhausted during " + this.budget.phase(),
                    this.budget.phase());
        }
    }

    private enum SolveMode {
        DEFAULT,
        AGGREGATE,
        EXACT
    }
}
