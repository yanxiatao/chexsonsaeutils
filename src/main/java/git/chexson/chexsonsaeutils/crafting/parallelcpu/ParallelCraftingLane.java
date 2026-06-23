package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface ParallelCraftingLane {

    @Nullable
    UUID getLaneId();

    boolean isLaneActive();

    Iterable<Object2LongMap.Entry<AEKey>> getWaitingStacks();

    long getRequestedAmount(@Nullable AEKey what);

    long insertIntoWaiting(AEKey what, long amount, Actionable mode);

    /**
     * Inserts into this lane's waiting list and reports both storage-visible insertion and CPU accounting progress.
     */
    default WaitingInsertResult insertIntoWaitingAndGetResult(AEKey what, long amount, Actionable mode) {
        long physicalInserted = insertIntoWaiting(what, amount, mode);
        return new WaitingInsertResult(physicalInserted, physicalInserted);
    }

    /**
     * Result for waiting-list insertion where final outputs can be accounted by the CPU without physical insertion.
     */
    record WaitingInsertResult(long physicalInserted, long accounted) {
        public WaitingInsertResult {
            physicalInserted = Math.max(0L, physicalInserted);
            accounted = Math.max(0L, accounted);
        }
    }
}
