package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ProcessingExecutionQueue {

    private static final String NBT_TASKS = "tasks";

    private final ArrayDeque<ProcessingCompiledTask> pendingTasks = new ArrayDeque<>();
    private final List<ProcessingExecutionLane> lanes = new ArrayList<>();
    private final Map<ProcessingCompiledTask, ProcessingLatencyOrigin> latencyOrigins = new IdentityHashMap<>();
    private final int maxTaskCount;

    public ProcessingExecutionQueue(int maxTaskCount) {
        this.maxTaskCount = Math.max(1, maxTaskCount);
        lanes.add(new ProcessingExecutionLane());
    }

    public boolean offer(ProcessingCompiledTask task) {
        return offer(task, null);
    }

    public boolean offer(ProcessingCompiledTask task, ProcessingExecutionBudget budgetController) {
        return offer(task, budgetController, -1L);
    }

    public boolean offer(
            ProcessingCompiledTask task,
            ProcessingExecutionBudget budgetController,
            long acceptedTick
    ) {
        if (task == null) {
            return false;
        }
        if (totalTaskCount() >= maxTaskCount) {
            return false;
        }
        if (acceptedTick >= 0L) {
            latencyOrigins.put(task, ProcessingLatencyOrigin.single(acceptedTick));
        }
        pendingTasks.offer(task);
        return true;
    }

    public boolean tick(ProcessingTaskCompletionHost host, int laneCount) {
        return tick(host, laneCount, null);
    }

    public boolean tick(
            ProcessingTaskCompletionHost host,
            int laneCount,
            ProcessingExecutionBudget budgetController
    ) {
        ensureLaneCapacity(Math.max(1, laneCount));
        assignIdleLanes(budgetController);
        boolean didSomething = false;
        int workUnits = host.getProcessingWorkUnitsPerTick();
        for (ProcessingExecutionLane lane : lanes) {
            if (budgetController != null && !budgetController.hasTimeBudget(System.nanoTime())) {
                break;
            }
            if (lane.isIdle()) {
                continue;
            }
            didSomething |= lane.tick(workUnits);
            ProcessingCompiledTask completed = lane.releaseIfReady();
            if (completed != null) {
                if (budgetController != null && !budgetController.tryClaimComplete()) {
                    lane.assign(completed);
                    break;
                }
                ProcessingLatencyOrigin latencyOrigin = latencyOrigins.remove(completed);
                host.completeProcessingTask(completed, latencyOrigin);
                didSomething = true;
            }
        }
        assignIdleLanes(budgetController);
        return didSomething;
    }

    public boolean isBusy() {
        return totalTaskCount() >= maxTaskCount;
    }

    public boolean isIdle() {
        return pendingTasks.isEmpty() && runningTaskCount() == 0;
    }

    public int queuedTaskCount() {
        return pendingTasks.size();
    }

    public int runningTaskCount() {
        int count = 0;
        for (ProcessingExecutionLane lane : lanes) {
            if (!lane.isIdle()) {
                count++;
            }
        }
        return count;
    }

    public int totalTaskCount() {
        return queuedTaskCount() + runningTaskCount();
    }

    public int laneCountForTest() {
        return lanes.size();
    }

    public void clear() {
        pendingTasks.clear();
        latencyOrigins.clear();
        for (ProcessingExecutionLane lane : lanes) {
            lane.clear();
        }
    }

    public void writeToTag(CompoundTag rootTag, String key, HolderLookup.Provider registries) {
        ListTag tasksTag = new ListTag();
        for (ProcessingCompiledTask task : pendingTasks) {
            tasksTag.add(task.writeToTag(registries));
        }
        for (ProcessingExecutionLane lane : lanes) {
            ProcessingCompiledTask task = lane.activeTask();
            if (task != null) {
                tasksTag.add(task.writeToTag(registries));
            }
        }
        rootTag.put(key, tasksTag);
    }

    public void readFromTag(CompoundTag rootTag, String key, HolderLookup.Provider registries) {
        clear();
        ListTag tasksTag = rootTag.getList(key, Tag.TAG_COMPOUND);
        for (Tag tag : tasksTag) {
            if (tag instanceof CompoundTag compoundTag) {
                ProcessingCompiledTask task = ProcessingCompiledTask.readFromTag(compoundTag, registries);
                if (task != null) {
                    pendingTasks.offer(task);
                }
            }
        }
    }

    private void assignIdleLanes(ProcessingExecutionBudget budgetController) {
        for (ProcessingExecutionLane lane : lanes) {
            if (lane.isIdle() && !pendingTasks.isEmpty()) {
                if (budgetController != null && !budgetController.tryClaimAdmit()) {
                    return;
                }
                lane.assign(pendingTasks.removeFirst());
            }
        }
    }

    private void ensureLaneCapacity(int laneCount) {
        while (lanes.size() < laneCount) {
            lanes.add(new ProcessingExecutionLane());
        }
    }
}
