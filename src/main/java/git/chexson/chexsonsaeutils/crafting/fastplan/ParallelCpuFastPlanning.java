package git.chexson.chexsonsaeutils.crafting.fastplan;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether a crafting-plan calculation should use this mod's parallel
 * CPU fast path instead of AE2's throttled native calculation.
 *
 * <p>The fast path is used only when the AE network actually hosts one of this
 * mod's parallel CPUs ("when AE uses our CPU"), the feature toggle is on, and
 * the parallel CPU feature itself is enabled. Any uncertainty falls through to
 * the native path.
 */
public final class ParallelCpuFastPlanning {

    private ParallelCpuFastPlanning() {
    }

    public static boolean shouldUseFastPlanning(@Nullable IGrid grid) {
        if (grid == null) {
            return false;
        }
        if (!FeatureGates.isEnabled(
                ChexsonsaeutilsCompatibilityConfig.PARALLEL_CPU_FAST_PLANNING_ENABLED,
                "parallelCpuFastPlanningEnabled")) {
            return false;
        }
        if (!FeatureGates.isEnabled(
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

    private static boolean hasActiveParallelCpu(IGrid grid) {
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
}
