package git.chexson.chexsonsaeutils.crafting.solve;

import git.chexson.chexsonsaeutils.crafting.material.PlanGrossAudit;

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
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import net.minecraft.world.level.Level;

import java.util.List;

/** A single AE2 calculation whose constructor freezes and executes configured algorithm order. */
public final class OrderedPlanningCalculation extends CraftingCalculation {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final Level level;
    private final IGrid grid;
    private final ICraftingSimulationRequester requester;
    private final GenericStack output;
    private final CalculationStrategy strategy;
    private final List<PlanningAlgorithmId> order;
    private final boolean graphEligible;
    private final long graphBudgetMillis;
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
                // 配置侧只交串名册；本类内部字段仍是枚举，故就地 parse 回枚举，顺位照原样保留。
                // 认不出就是"名册与枚举个名漂了"，当场抛、不静默丢档（丢档会让顺位变短而误判成缺档）。
                PlanningAlgorithmOrderService.global().snapshot().order().stream()
                        .map(id -> PlanningAlgorithmId.parse(id).orElseThrow(
                                () -> new IllegalStateException("Unknown planning algorithm ID: " + id)))
                        .toList(),
                PlanningLevelRouter.shouldUseGraphPlanning(grid),
                PlanningLevelRouter.graphBudgetMillis(),
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
            long graphBudgetMillis
    ) {
        this(level, grid, requester, output, strategy, order, graphEligible,
                graphBudgetMillis, System.nanoTime());
    }

    OrderedPlanningCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy,
            List<PlanningAlgorithmId> order,
            boolean graphEligible,
            long graphBudgetMillis,
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
        this.graphBudgetMillis = graphBudgetMillis;
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
            ICraftingPlan plan = keepRingSeed(runAe2Native());
            updateFinalState(plan);
            String solver = plan instanceof RecursivePlanContract
                    ? PlanningProvenance.GRAPH : PlanningProvenance.NATIVE;
            PlanningProvenance.tag(plan, solver, this.requestStartNanos, this.requester);
            logSelectedPlan(plan, solver + "(no-selection)");
            return plan;
        }
        ICraftingPlan plan = keepRingSeed(selected.value());
        PlanningAlgorithmId algorithm = selected.algorithm();
        if (plan != selected.value()) {
            // 换成图引擎的精确产物了，标签不能再报成 NATIVE。
            algorithm = PlanningAlgorithmId.GRAPH;
        }
        updateFinalState(plan);
        tagActualAlgorithm(plan, algorithm, this.requestStartNanos);
        logSelectedPlan(plan, algorithm.name());
        return plan;
    }

    /**
     * 最终交给 AE2 的那份计划身份。层级尝试行只说明"算出来过契约"，
     * 卡死行里的 recursive=false 又只反映执行侧作业字段，中间任何一次
     * 包装或替换都看不见——所以按提交链的最后一站单独记一行。
     */
    private void logSelectedPlan(ICraftingPlan plan, String algorithm) {
        LOGGER.info(
                "Ordered planning selected: output={}, algorithm={}, plan={}, contract={},"
                        + " patterns={}, simulation={}, missing={} {} missingKeys={}",
                plan.finalOutput(), algorithm, plan.getClass().getSimpleName(),
                plan instanceof git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract contract
                        && contract.chexsonsaeutils$usesDyeableRecursivePlanning(),
                plan.patternTimes().size(), plan.simulation(), plan.missingItems().size(),
                PlanGrossAudit.gross(plan), describeMissing(plan));
    }

    /**
     * 把计划真记下的缺口键与量原样列出来（最多 6 条）。
     *
     * <p>要分开的两件事：缺口量是<b>普通数</b> ⇒ 网络真没那么多，报缺料是正确行为；
     * 缺口量<b>恰为 {@code Long.MAX_VALUE}</b> ⇒ 这一截是被 long 域饱和出来的假缺，
     * 该修的是需求/缺口账的数域，而不是叫玩家补料。
     */
    private static String describeMissing(appeng.api.networking.crafting.ICraftingPlan plan) {
        var missing = plan.missingItems();
        if (missing == null || missing.isEmpty()) {
            return "[]";
        }
        var described = new StringBuilder("[");
        int listed = 0;
        for (var entry : missing) {
            if (entry.getKey() == null) {
                continue;
            }
            if (listed > 0) {
                described.append(", ");
            }
            described.append(entry.getKey()).append(" x ").append(entry.getLongValue());
            if (++listed >= 6) {
                described.append("...(more)");
                break;
            }
        }
        return described.append(']').toString();
    }

    /**
     * 成环邻域不得交出非递归计划：AE2 原生树把环里的样板当普通输入一次性
     * 消耗，不留可回炉的那份种子，闭池 CPU 于是永远缺这个键——现场表现就是
     * 递归样板卡死。只在图会话已经建好（本轮真的编译过邻域）时判定环，
     * 不为守卫额外触发一次编译；补不出来（含仍是模拟计划）就照原样交出。
     */
    private ICraftingPlan keepRingSeed(ICraftingPlan plan) {
        if (plan instanceof RecursivePlanContract
                || !this.recursiveKernel.hasSession()
                || this.recursiveKernel.graphCycleKind() == CraftGraph.CycleKind.NONE) {
            return plan;
        }
        ICraftingPlan recursive = this.recursiveKernel.solveRecursive();
        return recursive == null || recursive.simulation() ? plan : recursive;
    }

    private void updateFinalState(ICraftingPlan plan) {
        this.simulation = plan.simulation();
        this.multiplePaths = plan.multiplePaths();
        this.missing.reset();
        this.missing.addAll(plan.missingItems());
    }

    private boolean isEligible(PlanningAlgorithmId algorithm) {
        return switch (algorithm) {
            case GRAPH -> this.graphEligible;
            case NATIVE -> true;
        };
    }

    private ICraftingPlan runAttempt(PlanningAlgorithmId algorithm) {
        if (algorithm == PlanningAlgorithmId.GRAPH) {
            return runGraphAttempt();
        }
        if (recursiveCycleDetected()) {
            ICraftingPlan recursive = runRecursiveAttempt();
            // 只有终点算法（NATIVE）也没辙时才换成交回"材料不足"的残缺计划：玩家看得见逐键缺口，
            // 而不是聊天栏里一行 IllegalStateException。中间层级照旧交回 null，让 selector 往下试，
            // 真缺料以外的判决（预算耗尽、图 overflow、编译失败）也照旧——行为一字不变。
            if (recursive == null && algorithm == PlanningAlgorithmId.NATIVE) {
                return this.recursiveKernel.terminalShortagePlan();
            }
            return recursive;
        }
        return switch (algorithm) {
            case NATIVE -> runAe2Native();
            case GRAPH -> throw new IllegalStateException("handled above");
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

    /** AE2 原生树回落腿：AE2 自己的节流计算，逐字不动。 */
    private ICraftingPlan runAe2Native() {
        return new CraftingCalculation(
                this.level, this.grid, this.requester, this.output, this.strategy).run();
    }

    private void tagActualAlgorithm(ICraftingPlan plan, PlanningAlgorithmId algorithm, long startNanos) {
        String solver = switch (algorithm) {
            case GRAPH -> PlanningProvenance.GRAPH;
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
