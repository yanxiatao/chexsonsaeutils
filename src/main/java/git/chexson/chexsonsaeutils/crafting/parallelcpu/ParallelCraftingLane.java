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

    default long insertIntoWaiting(
            AEKey what,
            long amount,
            Actionable mode,
            boolean preferBufferFinalOutput
    ) {
        return insertIntoWaiting(what, amount, mode);
    }
}
