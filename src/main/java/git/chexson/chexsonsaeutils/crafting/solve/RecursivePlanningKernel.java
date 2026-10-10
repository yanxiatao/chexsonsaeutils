package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanPostProcessor;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningEngine;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.PlanningAttemptResult;
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
    /** 本次请求唯一一趟求解的判决原文：只在末级算法也没辙时用来如实报"材料不足"。 */
    @Nullable
    private PlanningAttemptResult decline;
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

    boolean hasSession() {
        return this.session != null;
    }

    CraftGraph.CycleKind graphCycleKind() {
        return session().graph().cycleKind();
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
        return solve("graph", false);
    }

    @Nullable
    ICraftingPlan solveRecursive() {
        return solve("recursive", true);
    }

    @Nullable
    private ICraftingPlan solve(String attempt, boolean recursiveRequired) {
        if (!this.solved) {
            long startNanos = System.nanoTime();
            var planningSession = session();
            var result = planningSession.solve(this.requestedAmount,
                    GraphPlanningSession.BudgetPolicy.ENFORCED);
            // 环/递归场景的免预算再解一趟：这条豁免是「递归样板卡死」复现的唯一屏障——
            // 实机默认的图规划预算会把这一趟顶成 budgetExhausted ⇒ 落原生树。别顺手收掉。
            // 触发条件与离线回放腿共用会话层那一处判定。
            if (planningSession.shouldRetryWithoutBudget(recursiveRequired, result)) {
                result = planningSession.solve(this.requestedAmount,
                        GraphPlanningSession.BudgetPolicy.EXEMPT);
            }
            // 有序规划这一腿不经任何会打印图层级判决的外壳（原 GraphPlanningCalculation 已摘），
            // 缺这行就完全看不到图层级是否被尝试、以什么理由拒绝——卡死只能靠离线回放猜。
            PlanningLevelRouter.logGraphAttemptOutcome(attempt, this.output, result, startNanos,
                    planningSession.budget());
            this.plan = result.handled() ? result.plan() : null;
            if (!result.handled()) {
                this.decline = result;
            }
            this.solved = true;
        }
        return this.plan;
    }

    /**
     * 终点算法（NATIVE）也没辙时的「材料不足」回执：只在守恒闸门逐键报了缺口时才交得出东西，
     * 其余判决（预算耗尽、图 overflow、编译失败）照旧交回 {@code null} —— 那条
     * {@code IllegalStateException: Terminal planning algorithm declined: NATIVE} 的行为一字不变。
     *
     * <p>交回的是 {@code simulation=true} 的残缺计划：AE2 的确认界面据此显示逐键缺口
     * （&gt; long 的那一截按宽域账真值显示），玩家看得见"还差多少"，而不是聊天栏里一行异常。
     */
    @Nullable
    ICraftingPlan terminalShortagePlan() {
        var decline = this.decline;
        if (decline == null || this.session == null) {
            return null;
        }
        return GraphPlanPostProcessor.shortagePlan(decline, this.session.graph());
    }
}
