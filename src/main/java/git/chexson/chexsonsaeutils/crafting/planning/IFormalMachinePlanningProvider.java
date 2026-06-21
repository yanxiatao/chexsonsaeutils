package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.networking.crafting.ICraftingProvider;
import net.minecraft.core.BlockPos;

public interface IFormalMachinePlanningProvider extends ICraftingProvider {
    int getCurrentOperationTicks();
    BlockPos getBlockPos();

    default void recordPlanningLiveSnapshotRequestForTest() {}
    default void recordPlanningAggregationRequestForTest(long requestedAmount) {}
    default void recordPlanningWorkEstimateForTest(long estimatedWork, boolean accepted) {}
    default void recordDeterministicPlanningHitForTest(long wallClockNanos) {}
}
