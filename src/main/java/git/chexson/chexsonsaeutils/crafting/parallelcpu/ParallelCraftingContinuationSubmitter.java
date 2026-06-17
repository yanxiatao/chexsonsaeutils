package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import org.jetbrains.annotations.Nullable;

/**
 * Bridges continuation partial submit requests into the parallel CPU grid.
 *
 * <p>The native AE2 continuation path only knows {@code CraftingCPUCluster}. This interface lets the continuation mixin
 * ask the parallel CPU mixin to handle the same request before falling back to native CPUs.</p>
 */
public interface ParallelCraftingContinuationSubmitter {
    /**
     * Submits an ignore-missing simulation plan to a parallel CPU target or auto-selected parallel CPU.
     *
     * @return submit result when the parallel CPU grid handled the request, otherwise {@code null}
     */
    @Nullable
    ICraftingSubmitResult submitParallelContinuationPartialJob(
            ICraftingPlan job,
            @Nullable ICraftingRequester requestingMachine,
            @Nullable ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src
    );
}
