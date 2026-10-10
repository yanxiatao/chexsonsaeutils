package git.chexson.chexsonsaeutils.crafting.graphplan.engine;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.RetentionApplier;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphDiagnostics;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.plan.PatternRosterReadout;
import git.chexson.chexsonsaeutils.crafting.material.PlanGrossAudit;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.PlanningOverflowException;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;

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
        // 真值供给读在库存快照上，与编译拓扑无关：按请求挂到图上，供需判据与图同生命周期取用。
        // 溢出键在 networkStock 里只长成 Long.MAX_VALUE，那一截只有这里能捞回来。
        PlanningSupplyTruth supplyTruth = PlanningSupplyTruth.capture(planningGrid, networkStock);
        graph.attachSupplyTruth(supplyTruth);
        if (!supplyTruth.isEmpty()) {
            // 一次规划请求一行：实机据此判断「规划侧读到了无限盘真值」这条链在不在跑
            LOGGER.info("Graph planning wide supply truth: target={} sources={} wideKeys={} supply={}",
                    target, supplyTruth.wideSourceCount(), supplyTruth.wideKeyCount(),
                    supplyTruth.wideSupplies());
        }
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

    /**
     * 本次请求的真值供给（只读，随编译图一起出门）。供需判据要问「池子里到底有多少」时读这里，
     * 别在网络的 {@code long} 读数上猜——饱和键在那里一律长成 {@code Long.MAX_VALUE}。
     */
    public PlanningSupplyTruth supplyTruth() {
        return this.graph.supplyTruth();
    }

    public GraphPlanningBudget budget() {
        return this.budget;
    }

    /**
     * 免预算再解那一趟的<b>唯一触发判定</b>：生产 {@code RecursivePlanningKernel.solve} 与离线
     * 回放腿 {@code RuntimeReplayRunner.productionGraphPlan} 都调这里，不许两处各写一遍条件。
     *
     * <p>两臂缺一不可：图这一侧要么真成环、要么这一趟本来就必须交递归计划
     * （{@code recursiveRequired}）；判决这一侧必须是计预算那一趟没交出可用计划
     * （被拒、{@code null}、或只是模拟计划）。摘掉这条豁免，实机默认的图规划预算会把
     * 环场景那一趟顶成 {@code budgetExhausted} ⇒ 末级回落原生树 ⇒「递归样板卡死」复现。
     */
    public boolean shouldRetryWithoutBudget(boolean recursiveRequired, PlanningAttemptResult attempt) {
        if (!recursiveRequired && this.graph.cycleKind() == CraftGraph.CycleKind.NONE) {
            return false;
        }
        return !attempt.handled() || attempt.plan() == null || attempt.plan().simulation();
    }

    /**
     * 唯一生产求解器 {@link LinearPlanner} 那一趟。
     *
     * <p>{@code enforceBudget} 只决定<b>计不计预算</b>：交 {@code null} 时
     * {@link LinearPlanner} 内部的计费点一次都不走——环场景的再解必须跑得完
     * （触发判定见 {@link #shouldRetryWithoutBudget}）。
     *
     * <p>这里原来还有一趟"独立残量最优优先搜索"（{@code search/ResidualBestFirstPlanner}）打底、
     * DECLINE 才进 {@link LinearPlanner}。摘掉它换的不是死码：实机 2026-10-02 那一单成环的
     * {@code ae2:creative_storage_cell x 1e8} 走的就是 {@code Graph planning[recursive]} ⇒
     * 原 {@code SolveMode.DEFAULT} ⇒ 由那一趟先交出计划。等价的凭据不是"没人调"，
     * 而是 {@code LinearPlanner} 与 AE2 原生树的逐字段对拍（含 {@code bytes}），
     * 见 {@code graphplan/GraphPlanningEquivalenceTest} 与
     * {@code MaterialClosureGateSelfProducedReachDemandTest} 的字节水位臂。
     */
    private SolveResult solveLedger(CraftGraph graph, PlanAmount requestedAmount, boolean enforceBudget) {
        boolean lexicographic = ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_LEXICOGRAPHIC,
                "graphPlanningLexicographic");
        return LinearPlanner.solve(graph, this.strategy, requestedAmount,
                lexicographic, enforceBudget ? this.budget : null);
    }

    /**
     * Runs the complete solve for the requested amount and converts the result
     * into an AE2 plan. Any decline, overflow, or budget exhaustion is reported
     * as a declined result so the caller falls through to the tree planners.
     */
    public PlanningAttemptResult solve(long requestedAmount) {
        return solve(requestedAmount, BudgetPolicy.ENFORCED);
    }

    /**
     * 一趟完整规划：解 → 出口对账 → 转换。
     *
     * <p>注册型无限元件（不在任何单元里、网络报量有限而 SIMULATE 给满）由出口对账的<b>延后判决</b>
     * 处理（{@code GraphPlanPostProcessor#deferSaturatedMissingToSubmitLeg}）：真需求越出 long 而
     * SIMULATE 承诺顶到 long 天花板时，那份承诺是哨兵不是供给，规划期判不了够不够。判决交给执行侧：
     * 提交腿只认明这一键交不交得出<b>一刀</b>（≤long），剩下的那一截由按批常态续取，取不满就如实
     * 停滞报缺。这里不再做第二趟"网格层认无限源"——它与有限盘在读侧同形，认中即 mint，
     * 读数漂移一次就是凭空多料。
     */
    public PlanningAttemptResult solve(long requestedAmount, BudgetPolicy policy) {
        return planOnce(PlanAmount.of(requestedAmount), policy);
    }

    /** 精确根请求入口，复用编译、对账、留种与转换，不投影为 long。 */
    public PlanningAttemptResult solve(PlanAmount requestedAmount, BudgetPolicy policy) {
        return planOnce(requestedAmount, policy);
    }

    /**
     * 一趟完整规划：解 → 出口对账 → 留种核对 → 转换。
     *
     * <p>留种不在这里做第二趟：那颗要留在网络里的种早在建图后就被 {@code CraftGraph.retainedSeeds()}
     * 从可花库存里扣住了，补种那一发是这一次求解自己排出来的。
     *
     * <p>{@code policy} 只决定<b>计不计预算</b>（会话层策略），不再决定用哪个求解器：
     * 聚合与独立残量搜索那两档都已摘，环场景的免预算再解因此留在会话层。
     */
    private PlanningAttemptResult planOnce(PlanAmount requestedAmount, BudgetPolicy policy) {
        if (this.capture != null) {
            if (requestedAmount.isFast()) this.capture.writeAsync(requestedAmount.fastValue());
            else LOGGER.warn("当前回放文件仅支持 long 根请求，跳过捕获宽域订单 {}", requestedAmount);
        }
        boolean enforceBudget = policy == BudgetPolicy.ENFORCED;
        CraftGraph planGraph = this.graph;
        try {
            this.budget.enter(GraphPlanningBudget.Phase.SOLVE);
            if (enforceBudget) {
                this.budget.check();
            }
            SolveResult result = solveLedger(planGraph, requestedAmount, enforceBudget);
            if (result.declineReason() != null) {
                // 守恒闸门判真缺料时把被拒的那份账本一并带回：末级算法也没辙时才用它报"材料不足"
                return result.hasShortage() ? PlanningAttemptResult.declinedWithLedger(result)
                        : PlanningAttemptResult.declined(result.declineReason());
            }
            logStageGross("solve", policy, requestedAmount, planGraph, result.state());
            result = GraphPlanPostProcessor.reconcileMissingWithStorage(
                    result, this.storage, this.craftingService);
            logStageGross("reconcile", policy, requestedAmount, planGraph, result.state());
            if (this.retentionEnabled) {
                this.budget.enter(GraphPlanningBudget.Phase.RETENTION);
                if (enforceBudget) {
                    this.budget.check();
                }
                String retentionDecline = GraphPlanPostProcessor.applyRetention(
                        result, planGraph, requestedAmount);
                if (retentionDecline != null) {
                    return PlanningAttemptResult.declined(retentionDecline);
                }
                if (!result.state().missing().isEmpty()) {
                    // 留种无起步种时把缺口记进 missing 账本而不是拒计划：缺料计划必须带
                    // simulation 标记，否则执行侧会照单吞掉一份没有来源的料。
                    result = result.withSimulation(true);
                }
                logStageGross("retention", policy, requestedAmount, planGraph, result.state());
            }
            this.budget.enter(GraphPlanningBudget.Phase.CONVERT);
            if (enforceBudget) {
                this.budget.check();
            }
            String conversionDecline = GraphPlanPostProcessor.conversionDecline(result);
            if (conversionDecline != null) {
                GraphDiagnostics.log("graph.negativeExit decline amount=%s", requestedAmount);
                return PlanningAttemptResult.declined(conversionDecline);
            }
            ICraftingPlan plan = GraphPlanPostProcessor.convert(result, planGraph);
            // 规划段出口：此刻还没进提交链，缺口只能算在 solve/reconcile/retention/convert 头上。
            LOGGER.info("Graph planning gross: stage=convert policy={} amount={} patterns={} {}",
                    policy, requestedAmount, plan.patternTimes().size(), PlanGrossAudit.gross(plan));
            // ⑩ 规划出口那一侧点名：用<b>与建表同一把尺</b>（PatternRosterReadout 唯一的组装口）交一行
            // stage=exit 的名单，宇宙＝patternTimes ∪ 宽域份数账。若那几张是在规划出口之后、建表之前
            // （提交链/useless 折叠壳/载体）整张从字典里消失，这一行的 total 会高于建表那一行，
            // 少掉的那几张凭 token 里的 <b>definition ＋份数</b>并列对上；只有同名两张才退化到
            // #i/#s 分实例——<b>不许</b>拿 #s 跨壳对号：useless 的 ScaledProcessingPattern(inner,k)
            // 把 getOutputs() 每枚 amount 与 getInputs()[i].getMultiplier() 都 ×k、definition 也换成
            // 带 SMART_DOUBLING_OPERATIONS 组件的那一枚，壳前壳后 #s 必然不同（2.3.9 字节码，
            // 用例 PatternStableIdentityTest#scalingFoldingShellDoesNotPreserveStableDigest）。
            // <b>只进 debug.log</b>
            // （DEBUG 档在 PrismLauncher 实例的 debug.log 开着；latest.log 读不到它，不是没打）。纯读数。
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Graph planning pattern roster: {}",
                        PatternRosterReadout.formatLine("exit", PatternRosterReadout.universeAndTimes(plan)));
            }
            return PlanningAttemptResult.handled(plan);
        } catch (PlanningOverflowException failure) {
            return PlanningAttemptResult.declined("graph overflow");
        } catch (GraphPlanningBudget.BudgetExhaustedException failure) {
            return PlanningAttemptResult.budgetExhausted(
                    "graph planning budget exhausted during " + this.budget.phase(),
                    this.budget.phase());
        }
    }

    /** 规划链逐段毛账：同一口径在每段出口量一次，缺口在哪段现形就算在谁头上。 */
    private void logStageGross(String stage, BudgetPolicy policy, PlanAmount requestedAmount,
            CraftGraph planGraph, @Nullable GraphPlanningState state) {
        if (state == null) {
            return;
        }
        // 中间段是一整趟全表算量（545 任务 × 每槽候选数），只在开诊断时才算；
        // solve 与 convert 两头常开——缺口要么在规划表里，要么在提交链上，两处足够定界。
        if (!"solve".equals(stage) && !"convert".equals(stage) && !GraphDiagnostics.active()) {
            return;
        }
        var usedItems = state.usedItems();
        var balances = PlanGrossAudit.measure(state.craftsWide(), usedItems);
        var holes = PlanGrossAudit.holeKeys(balances);
        LOGGER.info("Graph planning gross: stage={} policy={} amount={} patterns={} firings={} {}{}",
                stage, policy, requestedAmount, state.craftsWide().size(),
                state.craftsWide().total(),
                PlanGrossAudit.describe(balances, usedItems, state.emittedItems(),
                        state.missingItems()),
                holes.isEmpty() ? ""
                        : " gate=" + MaterialClosureGate.explainHoles(state, planGraph, holes));
    }

    /**
     * 会话层的<b>预算策略</b>：只决定这一趟计不计预算，不再决定用哪个求解器
     * （聚合那一档连同 {@code SolveMode} 一起摘掉了）。
     */
    public enum BudgetPolicy {
        /** 常规趟：solve / retention / convert 三段都计费，预算耗尽即交回 budgetExhausted。 */
        ENFORCED,
        /**
         * 免预算趟：环或递归场景第一趟没解出来、或只交出模拟计划时的再解一趟
         * （触发方见 {@code RecursivePlanningKernel.solve}）。
         * 摘掉它，实机默认的图规划预算会把这一趟顶成 {@code BudgetExhaustedException}
         * ⇒ 末级回落原生树 ⇒「递归样板卡死」那一族玩家症状复现。
         */
        EXEMPT
    }
}
