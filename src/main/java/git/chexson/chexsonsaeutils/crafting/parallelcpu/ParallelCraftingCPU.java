package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.menu.me.crafting.CraftingStatus;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class ParallelCraftingCPU implements ICraftingCPU {

    private final ParallelCraftingCpuCluster cluster;
    @Nullable
    private final UUID laneId;

    ParallelCraftingCPU(ParallelCraftingCpuCluster cluster, @Nullable UUID laneId) {
        this.cluster = cluster;
        this.laneId = laneId;
    }

    public ParallelCraftingCpuCluster cluster() {
        return cluster;
    }

    @Nullable
    public UUID laneId() {
        return laneId;
    }

    public boolean isRemainingCapacityCpu() {
        return laneId == null;
    }

    public boolean isActiveVirtualCpu() {
        return laneId != null;
    }

    public boolean acceptsSubmissions() {
        return isRemainingCapacityCpu();
    }

    @Nullable
    public ParallelCraftingLaneState lane() {
        return laneId == null ? null : cluster.findLaneState(laneId);
    }

    @Override
    public boolean isBusy() {
        return laneId != null;
    }

    @Nullable
    @Override
    public CraftingJobStatus getJobStatus() {
        ParallelCraftingLaneState lane = lane();
        return lane == null ? null : lane.getJobStatus();
    }

    @Override
    public void cancelJob() {
        ParallelCraftingLaneState lane = lane();
        if (lane != null) {
            cluster.cancelLane(lane);
        }
    }

    @Override
    public long getAvailableStorage() {
        return cluster.storageBytes();
    }

    @Override
    public int getCoProcessors() {
        return Integer.MAX_VALUE;
    }

    @Nullable
    @Override
    public Component getName() {
        if (isRemainingCapacityCpu()) {
            return Component.translatable("block.chexsonsaeutils.ae2_parallel_cpu_tool");
        }
        return Component.translatable("gui.chexsonsaeutils.ae2_parallel_cpu_tool.active_vcpu");
    }

    @Override
    public CpuSelectionMode getSelectionMode() {
        return cluster.getSelectionMode();
    }

    public CraftingStatus createMenuStatus() {
        ParallelCraftingLaneState lane = lane();
        return lane == null ? CraftingStatus.EMPTY : cluster.createMenuStatus(lane);
    }

    public boolean isSuspended() {
        ParallelCraftingLaneState lane = lane();
        return lane != null && lane.isSuspended();
    }

    public void setSuspended(boolean suspended) {
        ParallelCraftingLaneState lane = lane();
        if (lane != null) {
            lane.setSuspended(suspended);
            cluster.refreshLaneState(lane);
            if (!suspended) {
                cluster.wakeLane(lane);
            }
        }
    }

    public boolean isCantStoreItems() {
        ParallelCraftingLaneState lane = lane();
        return lane != null && lane.isCantStoreItems();
    }
}
