package git.chexson.chexsonsaeutils.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record NativeSourceCpuHandle(CraftingCPUCluster cpu, UUID craftingId) implements SourceCpuHandle {

    @Override
    public boolean isActive() {
        if (cpu == null || !cpu.isActive()) {
            return false;
        }
        CraftingCpuLogic logic = cpu.craftingLogic;
        if (logic == null) {
            return false;
        }
        ExecutingCraftingJob job = ((CraftingCpuLogicAccessor) logic).getJob();
        if (job == null) {
            return false;
        }
        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        return accessor.getLink() != null && craftingId.equals(accessor.getLink().getCraftingID());
    }

    @Override
    public long getRequestedAmount(@Nullable AEKey what) {
        return what == null ? 0L : Math.max(0L, cpu.craftingLogic.getWaitingFor(what));
    }

    @Override
    public long getBufferedAmount(@Nullable AEKey what) {
        if (what == null) {
            return 0L;
        }
        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) cpu.craftingLogic;
        return Math.max(0L, logicAccessor.getInventory().extract(what, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @Override
    public long extractBuffered(@Nullable AEKey what, long amount, Actionable mode) {
        if (what == null || amount <= 0L) {
            return 0L;
        }
        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) cpu.craftingLogic;
        long extracted = Math.max(
                0L,
                Math.min(amount, logicAccessor.getInventory().extract(what, amount, mode))
        );
        if (mode == Actionable.MODULATE && extracted > 0L) {
            cpu.markDirty();
            logicAccessor.invokePostChange(what);
        }
        return extracted;
    }

    @Override
    public long insertBuffered(@Nullable AEKey what, long amount, Actionable mode) {
        if (what == null || amount <= 0L) {
            return 0L;
        }
        if (mode != Actionable.MODULATE) {
            return amount;
        }
        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) cpu.craftingLogic;
        logicAccessor.getInventory().insert(what, amount, Actionable.MODULATE);
        cpu.markDirty();
        logicAccessor.invokePostChange(what);
        return amount;
    }

    @Override
    public long insert(@Nullable AEKey what, long amount, Actionable mode, IActionSource source) {
        if (what == null || amount <= 0L) {
            return 0L;
        }
        return Math.max(0L, cpu.insert(what, amount, mode, source));
    }
}
