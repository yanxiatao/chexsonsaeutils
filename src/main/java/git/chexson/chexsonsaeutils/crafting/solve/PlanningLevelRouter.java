package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.ParallelCpuProvider;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.PlanningAttemptResult;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Routes a crafting-plan calculation to one of the two solving levels:
 * graph planning (level 1) or AE2's throttled native tree (the fallback leg).
 *
 * <p>只服务自有并行 CPU：图规划要求网格里真有本模组的活跃并行 CPU，
 * 且相关开关与本模组的并行 CPU 功能都开着。任何不确定一律落回原生路径。
 * 原先允许"任意网格"也走我方算法的两个入口已按战略删除——不留兼容壳，
 * 旧 TOML 里残留的键没人读，自然被忽略。
 */
public final class PlanningLevelRouter {

    static final Logger LOGGER = LogUtils.getLogger();

    private PlanningLevelRouter() {
    }

    /**
     * Level-1 图规划门控：总开关 + 网格判据（网格持有本模组活跃并行 CPU）。
     */
    public static boolean shouldUseGraphPlanning(@Nullable IGrid grid) {
        if (grid == null) {
            return false;
        }
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_ENABLED, "graphPlanningEnabled")) {
            return false;
        }
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_ENABLED,
                "parallelCraftingCpuEnabled")) {
            return false;
        }
        return hasActiveParallelCpu(grid);
    }

    public static long graphBudgetMillis() {
        return ChexsonsaeutilsCompatibilityConfig
                .intValue(ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_BUDGET_MILLIS);
    }

    public static boolean hasActiveParallelCpu(IGrid grid) {
        try {
            for (IGridNode node : grid.getNodes()) {
                if (node != null
                        && node.getOwner() instanceof ParallelCpuProvider owner
                        && owner.canProcessParallelCpuJobs()) {
                    return true;
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Parallel CPU capability detection failed, falling back to native planning", failure);
            return false;
        }
        return false;
    }

    /** One-line diagnostics for the level-1 graph attempt (handled/declined/budget). */
    public static void logGraphOutcome(GenericStack output, CalculationStrategy strategy,
            PlanningAttemptResult result, long elapsedNanos,
            @Nullable GraphPlanningBudget budget) {
        logGraphOutcome(output, strategy, result, elapsedNanos, budget, null);
    }

    public static void logGraphOutcome(GenericStack output, CalculationStrategy strategy,
            PlanningAttemptResult result, long elapsedNanos,
            @Nullable GraphPlanningBudget budget,
            @Nullable appeng.api.networking.crafting.ICraftingSimulationRequester requester) {
        if (result.handled()) {
            PlanningProvenance.tag(result.plan(), PlanningProvenance.GRAPH, elapsedNanos, requester);
        }
        describeOutcome(null, output, result, elapsedNanos, budget);
    }

    /**
     * 有序规划（GRAPH 尝试）的图层级结果行。
     *
     * <p>与 {@link #logGraphOutcome} 同一份口径，但不打 provenance——有序路径
     * 之后会按实际选中的算法自行打标。缺了这行，实机日志里既看不到图层级
     * 是否被尝试，也看不到它为什么拒绝，只能靠离线回放猜。
     */
    public static void logGraphAttemptOutcome(@Nullable String attempt, GenericStack output,
            PlanningAttemptResult result, long elapsedNanos,
            @Nullable GraphPlanningBudget budget) {
        describeOutcome(attempt, output, result, elapsedNanos, budget);
    }

    private static void describeOutcome(@Nullable String attempt, GenericStack output,
            PlanningAttemptResult result, long elapsedNanos,
            @Nullable GraphPlanningBudget budget) {
        String request = output.what() + " x " + output.amount();
        long millis = (System.nanoTime() - elapsedNanos) / 1_000_000L;
        if (result.handled()) {
            String phases = "";
            if (budget != null) {
                long compileNanos = budget.phaseNanos(
                        GraphPlanningBudget.Phase.COMPILE);
                long solveNanos = budget.phaseNanos(
                        GraphPlanningBudget.Phase.SOLVE);
                long convertNanos = budget.phaseNanos(
                        GraphPlanningBudget.Phase.CONVERT);
                long retentionNanos = budget.phaseNanos(
                        GraphPlanningBudget.Phase.RETENTION);
                // CONVERT 是最后一个阶段，没有后续 enter() 来结账——不封口的话它的耗时
                // 会整段掉进 other，读数误导（此前一直显示 convert 0）。
                convertNanos += budget.currentPhaseNanos(
                        GraphPlanningBudget.Phase.CONVERT);
                // other = 标题栏读数 − 四段之和：会话建立前的准备、provider 取用、
                // 出口日志等都落在这里。缺口不显式记账时无从判断该往哪儿优化。
                long otherMillis = Math.max(0L,
                        millis - (compileNanos + solveNanos + convertNanos + retentionNanos) / 1_000_000L);
                phases = " (compile %d / solve %d / convert %d / retention %d / other %d ms)".formatted(
                        compileNanos / 1_000_000L, solveNanos / 1_000_000L, convertNanos / 1_000_000L,
                        retentionNanos / 1_000_000L, otherMillis);
            }
            LOGGER.info("Graph planning[{}] handled: {}, took {} ms{}, plan {{{}}}",
                    attemptLabel(attempt), request, millis, phases, describe(result.plan()));
        } else if (result.budgetExhaustedPhase() != null) {
            LOGGER.info("Graph planning[{}] budget exhausted during {}: {}, took {} ms, falling back",
                    attemptLabel(attempt), result.budgetExhaustedPhase(), request, millis);
        } else {
            LOGGER.info("Graph planning[{}] declined ({}): {}, took {} ms, falling back",
                    attemptLabel(attempt), result.declineReason(), request, millis);
        }
    }

    private static String attemptLabel(@Nullable String attempt) {
        return attempt == null ? "graph" : attempt;
    }

    /** Crash diagnostics for an unexpected level-1 failure. */
    public static void logGraphCrash(GenericStack output, RuntimeException failure) {
        String message = String.valueOf(failure.getMessage());
        LOGGER.info("Graph planning crashed ({}: {}): {}, falling back",
                failure.getClass().getSimpleName(),
                message.length() > 200 ? message.substring(0, 200) : message,
                output.what() + " x " + output.amount());
    }

    /** 回落腿那一行读数：图没有交出计划，交出去的是 AE2 原生树的结果。 */
    public static ICraftingPlan logFallbackPlan(GenericStack output,
            ICraftingPlan plan, long elapsedNanos) {
        LOGGER.info("Graph fallback (native): {}, took {} ms, plan {{{}}}",
                output.what() + " x " + output.amount(),
                (System.nanoTime() - elapsedNanos) / 1_000_000L, describe(plan));
        return plan;
    }

    private static String describe(@Nullable ICraftingPlan plan) {
        if (plan == null) {
            return "null";
        }
        var output = plan.finalOutput();
        return "bytes=" + git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier.resolveBytes(plan).exactValue()
                + ", ae2Bytes=" + plan.bytes()
                + ", simulate=" + plan.simulation()
                + ", multiplePaths=" + plan.multiplePaths()
                + ", finalOutput=" + (output == null ? "null" : output.what() + " x " + output.amount())
                + ", planClass=" + plan.getClass().getName()
                + ", missing=" + describeMissing(plan);
    }

    private static String describeMissing(ICraftingPlan plan) {
        var missing = plan.missingItems();
        if (missing == null || missing.isEmpty()) {
            return "[]";
        }
        var sb = new StringBuilder("[");
        int shown = 0;
        for (var entry : missing) {
            if (shown++ > 0) {
                sb.append(", ");
            }
            if (shown > 8) {
                sb.append("...(+").append(missing.size() - 8).append(')');
                break;
            }
            sb.append(entry.getKey()).append(" x ").append(entry.getLongValue());
        }
        return sb.append(']').toString();
    }
}
