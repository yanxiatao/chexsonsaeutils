package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.PlanningAttemptResult;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Routes a crafting-plan calculation to one of the three solving levels:
 * graph planning (level 1), the optimized tree path (level 2, fast path),
 * or AE2's throttled native tree (level 3).
 *
 * <p>Today this exposes the level-2 gating: the fast path is used only when
 * the AE network hosts one of this mod's parallel CPUs, the feature toggles
 * are on, and the parallel CPU feature itself is enabled. The opt-in
 * {@code parallelCpuFastPlanningAnyGridEnabled} switch (default off) lets any
 * grid use the fast path even without a parallel CPU. Any uncertainty falls
 * through to the native path. Level-1 graph gating joins here in the
 * graph-planning phase.
 */
public final class PlanningLevelRouter {

    static final Logger LOGGER = LogUtils.getLogger();

    private PlanningLevelRouter() {
    }

    public static boolean shouldUseFastPlanning(@Nullable IGrid grid) {
        if (grid == null) {
            return false;
        }
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.PARALLEL_CPU_FAST_PLANNING_ENABLED,
                "parallelCpuFastPlanningEnabled")) {
            return false;
        }
        if (ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.PARALLEL_CPU_FAST_PLANNING_ANY_GRID_ENABLED,
                "parallelCpuFastPlanningAnyGridEnabled")) {
            return true;
        }
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_ENABLED,
                "parallelCraftingCpuEnabled")) {
            return false;
        }
        // 宿主批重写已接入图路径与 L2/L3 crafts 层，含宿主网格不再回落——
        // 此前守门依赖的 hasActiveHosts 探测本身有 bug（按抽象类枚举永远
        // false），修好探测后旧守门会误拦含宿主网格。
        return hasActiveParallelCpu(grid);
    }

    /**
     * Level-1 graph gating: global toggle plus the same grid requirement as the
     * fast path (an active parallel CPU, or the any-grid opt-in).
     */
    public static boolean shouldUseGraphPlanning(@Nullable IGrid grid) {
        if (grid == null) {
            return false;
        }
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_ENABLED, "graphPlanningEnabled")) {
            return false;
        }
        if (ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_ANY_GRID_ENABLED,
                "graphPlanningAnyGridEnabled")) {
            return true;
        }
        if (!ChexsonsaeutilsCompatibilityConfig.featureEnabled(
                ChexsonsaeutilsCompatibilityConfig.PARALLEL_CRAFTING_CPU_ENABLED,
                "parallelCraftingCpuEnabled")) {
            return false;
        }
        return hasActiveParallelCpu(grid);
    }

    public static long budgetMillis() {
        return ChexsonsaeutilsCompatibilityConfig
                .intValue(ChexsonsaeutilsCompatibilityConfig.PARALLEL_CPU_FAST_PLANNING_BUDGET_MILLIS);
    }

    public static long graphBudgetMillis() {
        return ChexsonsaeutilsCompatibilityConfig
                .intValue(ChexsonsaeutilsCompatibilityConfig.GRAPH_PLANNING_BUDGET_MILLIS);
    }

    public static boolean hasActiveParallelCpu(IGrid grid) {
        try {
            for (AE2ParallelCpuToolBlockEntity machine : grid.getMachines(AE2ParallelCpuToolBlockEntity.class)) {
                if (machine != null && machine.canProcessParallelCpuJobs()) {
                    return true;
                }
            }
            for (IGridNode node : grid.getNodes()) {
                if (node != null
                        && node.getOwner() instanceof AE2ParallelCpuToolBlockEntity owner
                        && owner.canProcessParallelCpuJobs()) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // Never let capability detection break crafting; fall back to native.
            return false;
        }
        return false;
    }

    /** One-line diagnostics for the level-1 graph attempt (handled/declined/budget). */
    public static void logGraphOutcome(GenericStack output, CalculationStrategy strategy,
            PlanningAttemptResult result, long elapsedNanos,
            @Nullable git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget budget) {
        logGraphOutcome(output, strategy, result, elapsedNanos, budget, null);
    }

    public static void logGraphOutcome(GenericStack output, CalculationStrategy strategy,
            PlanningAttemptResult result, long elapsedNanos,
            @Nullable git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget budget,
            @Nullable appeng.api.networking.crafting.ICraftingSimulationRequester requester) {
        String request = output.what() + " x " + output.amount();
        long millis = (System.nanoTime() - elapsedNanos) / 1_000_000L;
        if (result.handled()) {
            PlanningProvenance.tag(result.plan(), PlanningProvenance.GRAPH, elapsedNanos, requester);
            String phases = "";
            if (budget != null) {
                long compileNanos = budget.phaseNanos(
                        git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget.Phase.COMPILE);
                long solveNanos = budget.phaseNanos(
                        git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget.Phase.SOLVE);
                long convertNanos = budget.phaseNanos(
                        git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget.Phase.CONVERT);
                long retentionNanos = budget.phaseNanos(
                        git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget.Phase.RETENTION);
                // CONVERT 是最后一个阶段，没有后续 enter() 来结账——不封口的话它的耗时
                // 会整段掉进 other，读数误导（此前一直显示 convert 0）。
                convertNanos += budget.currentPhaseNanos(
                        git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget.Phase.CONVERT);
                // other = 标题栏读数 − 四段之和：会话建立前的准备、provider 取用、
                // 出口日志等都落在这里。缺口不显式记账时无从判断该往哪儿优化。
                long otherMillis = Math.max(0L,
                        millis - (compileNanos + solveNanos + convertNanos + retentionNanos) / 1_000_000L);
                phases = " (compile %d / solve %d / convert %d / retention %d / other %d ms)".formatted(
                        compileNanos / 1_000_000L, solveNanos / 1_000_000L, convertNanos / 1_000_000L,
                        retentionNanos / 1_000_000L, otherMillis);
            }
            LOGGER.info("Graph planning handled: {}, strategy {}, took {} ms{}, plan {{{}}}",
                    request, strategy, millis, phases, describe(result.plan()));
        } else if (result.budgetExhaustedPhase() != null) {
            LOGGER.info("Graph planning budget exhausted during {}: {}, took {} ms, falling back",
                    result.budgetExhaustedPhase(), request, millis);
        } else {
            LOGGER.info("Graph planning declined ({}): {}, took {} ms, falling back",
                    result.declineReason(), request, millis);
        }
    }

    /** Crash diagnostics for an unexpected level-1 failure. */
    public static void logGraphCrash(GenericStack output, RuntimeException failure) {
        String message = String.valueOf(failure.getMessage());
        LOGGER.info("Graph planning crashed ({}: {}): {}, falling back",
                failure.getClass().getSimpleName(),
                message.length() > 200 ? message.substring(0, 200) : message,
                output.what() + " x " + output.amount());
    }

    /** One-line diagnostics for whichever lower level produced the final plan. */
    public static ICraftingPlan logFallbackPlan(String tier, GenericStack output,
            ICraftingPlan plan, long elapsedNanos) {
        LOGGER.info("Graph fallback ({}): {}, took {} ms, plan {{{}}}",
                tier, output.what() + " x " + output.amount(),
                (System.nanoTime() - elapsedNanos) / 1_000_000L, describe(plan));
        return plan;
    }

    private static String describe(@Nullable ICraftingPlan plan) {
        if (plan == null) {
            return "null";
        }
        var output = plan.finalOutput();
        return "bytes=" + plan.bytes()
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
