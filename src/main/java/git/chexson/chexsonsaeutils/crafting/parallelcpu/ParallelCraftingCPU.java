package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.menu.me.crafting.CraftingStatus;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public final class ParallelCraftingCPU implements ICraftingCPU {

    private final ParallelCraftingCpuCluster cluster;
    private final Kind kind;

    ParallelCraftingCPU(ParallelCraftingCpuCluster cluster, Kind kind) {
        this.cluster = cluster;
        this.kind = kind;
    }

    public ParallelCraftingCpuCluster cluster() {
        return cluster;
    }

    public boolean acceptsSubmissions() {
        return kind == Kind.FAKE_POOL;
    }

    @Override
    public boolean isBusy() {
        return kind == Kind.ACTIVE_SUMMARY && cluster.activeLaneCount() > 0;
    }

    @Nullable
    @Override
    public CraftingJobStatus getJobStatus() {
        return kind == Kind.ACTIVE_SUMMARY ? cluster.getSummaryJobStatus() : null;
    }

    @Override
    public void cancelJob() {
        if (kind == Kind.ACTIVE_SUMMARY) {
            cluster.cancelAllJobs();
        }
    }

    @Override
    public long getAvailableStorage() {
        return cluster.storageBytes();
    }

    @Override
    public int getCoProcessors() {
        return ParallelCraftingCpuConfig.current().coProcessorsPerVirtualCpu();
    }

    @Nullable
    @Override
    public Component getName() {
        return kind == Kind.FAKE_POOL
                ? Component.translatable("block.chexsonsaeutils.ae2_parallel_cpu_tool")
                : Component.translatable("gui.chexsonsaeutils.ae2_parallel_cpu_tool.active_summary");
    }

    @Override
    public CpuSelectionMode getSelectionMode() {
        return cluster.getSelectionMode();
    }

    public CraftingStatus createMenuStatus() {
        return kind == Kind.ACTIVE_SUMMARY ? cluster.createMenuStatus() : CraftingStatus.EMPTY;
    }

    public boolean isSuspended() {
        return kind == Kind.ACTIVE_SUMMARY && cluster.isSuspended();
    }

    public void setSuspended(boolean suspended) {
        if (kind == Kind.ACTIVE_SUMMARY) {
            cluster.setSuspended(suspended);
        }
    }

    public boolean isCantStoreItems() {
        return kind == Kind.ACTIVE_SUMMARY && cluster.isCantStoreItems();
    }

    enum Kind {
        FAKE_POOL,
        ACTIVE_SUMMARY
    }
}
