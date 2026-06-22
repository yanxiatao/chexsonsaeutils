package git.chexson.chexsonsaeutils.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCpuCluster;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record ParallelActiveCpuHandle(ParallelCraftingCpuCluster cluster, UUID craftingId)
        implements SourceCpuHandle {

    @Override
    public boolean isActive() {
        return cluster != null && cluster.isCraftActive(craftingId);
    }

    @Override
    public long getRequestedAmount(@Nullable AEKey what) {
        return cluster == null ? 0L : cluster.getRequestedAmountForCraft(craftingId, what);
    }

    @Override
    public long getBufferedAmount(@Nullable AEKey what) {
        return cluster == null ? 0L : cluster.getBufferedAmountForCraft(craftingId, what);
    }

    @Override
    public long extractBuffered(@Nullable AEKey what, long amount, Actionable mode) {
        return cluster == null ? 0L : cluster.extractBufferedForCraft(craftingId, what, amount, mode);
    }

    @Override
    public long insertBuffered(@Nullable AEKey what, long amount, Actionable mode) {
        return cluster == null ? 0L : cluster.insertBufferedForCraft(craftingId, what, amount, mode);
    }

    @Override
    public long insert(@Nullable AEKey what, long amount, Actionable mode, IActionSource source) {
        return cluster == null ? 0L
                : cluster.insertIntoWaitingForCraft(craftingId, what, amount, mode);
    }
}
