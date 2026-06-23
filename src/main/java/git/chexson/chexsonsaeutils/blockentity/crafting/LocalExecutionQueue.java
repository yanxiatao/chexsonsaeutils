package git.chexson.chexsonsaeutils.blockentity.crafting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class LocalExecutionQueue {

    private static final String NBT_TASKS = "tasks";

    private final ArrayDeque<CompiledTask> pendingTasks = new ArrayDeque<>();
    private final List<BoundedExecutionLane> lanes = new ArrayList<>();
    private final int maxTaskCount;

    public LocalExecutionQueue(int maxTaskCount) {
        this.maxTaskCount = Math.max(1, maxTaskCount);
    }

    public boolean offer(CompiledTask task) {
        if (totalTaskCount() >= maxTaskCount) {
            return false;
        }
        pendingTasks.offer(task);
        return true;
    }

    public CoalesceOfferResult offerOrCoalesce(CompiledTask task, QueueBudgetContext budgetContext) {
        if (task == null) {
            return CoalesceOfferResult.REJECTED;
        }
        CompiledTask target = findCoalesceTarget(task, budgetContext);
        if (target != null) {
            if (!target.tryAppendExecutionCount(task.getExecutionCount())) {
                return offer(task) ? CoalesceOfferResult.ACCEPTED : CoalesceOfferResult.REJECTED;
            }
            return CoalesceOfferResult.COALESCED;
        }
        return offer(task) ? CoalesceOfferResult.ACCEPTED : CoalesceOfferResult.REJECTED;
    }

    public CoalesceOfferResult offerOrCoalesce(CompiledTask task, BatchExecutionMode batchExecutionMode, int laneCount) {
        int preferredLaneFloor = Math.max(1, laneCount);
        DynamicExecutionBudgetModel unlimitedBudget = new DynamicExecutionBudgetModel(
                Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4,
                preferredLaneFloor,
                Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4,
                queuedTaskCount(),
                runningTaskCount(),
                countDistinctBatchKeys(),
                false
        );
        return offerOrCoalesce(task, new QueueBudgetContext(batchExecutionMode, unlimitedBudget, preferredLaneFloor));
    }

    public void reconfigureActiveLanes(DynamicExecutionBudgetModel budgetModel) {
        int activeLanes = runningTaskCount();
        while (!pendingTasks.isEmpty() && activeLanes < budgetModel.laneActivationTarget()) {
            boolean fillsUniqueKeyFloor = activeLanes < budgetModel.preferredLaneFloor()
                    && countDistinctActiveBatchKeys() < Math.max(1, budgetModel.preferredLaneFloor());
            if (!budgetModel.canActivateLane(activeLanes, fillsUniqueKeyFloor)) {
                break;
            }
            ensureLaneCapacity(activeLanes + 1);
            activeLanes++;
        }
    }

    public void reconfigureLaneCount(int laneCount) {
        ensureLaneCapacity(Math.max(1, laneCount));
    }

    public boolean tick(AbstractHighCapacityCraftingHostBlockEntity host) {
        DynamicExecutionBudgetModel budgetModel = host.getCurrentBudgetModel();
        reconfigureActiveLanes(budgetModel);
        assignIdleLanes(budgetModel);
        host.updateExecutionPeaks();
        boolean didSomething = false;
        for (BoundedExecutionLane lane : lanes) {
            if (lane.isIdle()) {
                continue;
            }
            didSomething |= lane.tick(1);
            CompiledTask completed = lane.releaseIfReady();
            if (completed != null) {
                host.completeTask(completed);
                didSomething = true;
                if (host.isWaitingAeReturn()) {
                    return didSomething;
                }
            }
        }
        assignIdleLanes(budgetModel);
        return didSomething;
    }

    public boolean isBusy() {
        return totalTaskCount() >= maxTaskCount;
    }

    public boolean isAtCapacity() {
        return totalTaskCount() >= maxTaskCount;
    }

    public boolean isIdle() {
        return pendingTasks.isEmpty() && runningTaskCount() == 0;
    }

    public int queuedTaskCount() {
        return pendingTasks.size();
    }

    public int totalTaskCount() {
        return queuedTaskCount() + runningTaskCount();
    }

    public int runningTaskCount() {
        int running = 0;
        for (BoundedExecutionLane lane : lanes) {
            if (!lane.isIdle()) {
                running++;
            }
        }
        return running;
    }

    public List<CompiledTask> getActiveTasks() {
        List<CompiledTask> activeTasks = new ArrayList<>();
        for (BoundedExecutionLane lane : lanes) {
            if (!lane.isIdle() && lane.getActiveTask() != null) {
                activeTasks.add(lane.getActiveTask());
            }
        }
        return List.copyOf(activeTasks);
    }

    public List<CompiledTask> getAllTasks() {
        List<CompiledTask> allTasks = new ArrayList<>(pendingTasks);
        for (BoundedExecutionLane lane : lanes) {
            if (!lane.isIdle() && lane.getActiveTask() != null) {
                allTasks.add(lane.getActiveTask());
            }
        }
        return List.copyOf(allTasks);
    }

    public void clear() {
        pendingTasks.clear();
        for (BoundedExecutionLane lane : lanes) {
            lane.clear();
        }
    }

    public void writeToTag(CompoundTag rootTag, String key) {
        ListTag tasksTag = new ListTag();
        for (CompiledTask pendingTask : pendingTasks) {
            tasksTag.add(pendingTask.writeToTag());
        }
        for (BoundedExecutionLane lane : lanes) {
            if (!lane.isIdle() && lane.getActiveTask() != null) {
                tasksTag.add(lane.getActiveTask().writeToTag());
            }
        }
        rootTag.put(key, tasksTag);
    }

    public void readFromTag(CompoundTag rootTag, String key) {
        clear();
        ListTag tasksTag = rootTag.getList(key, Tag.TAG_COMPOUND);
        for (Tag tag : tasksTag) {
            if (tag instanceof CompoundTag compoundTag) {
                pendingTasks.offer(CompiledTask.readFromTag(compoundTag));
            }
        }
    }

    private void assignIdleLanes(DynamicExecutionBudgetModel budgetModel) {
        for (BoundedExecutionLane lane : lanes) {
            if (lane.isIdle() && !pendingTasks.isEmpty()) {
                boolean fillsUniqueKeyFloor = countDistinctActiveBatchKeys() < budgetModel.preferredLaneFloor();
                if (!budgetModel.canActivateLane(runningTaskCount(), fillsUniqueKeyFloor)) {
                    return;
                }
                lane.assign(removeNextFairPendingTask());
            }
        }
    }

    @SuppressWarnings("unused")
    private void assignIdleLanes() {
        assignIdleLanes(new DynamicExecutionBudgetModel(
                Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4,
                Math.max(1, lanes.size()),
                Math.max(1, lanes.size()),
                Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4,
                queuedTaskCount(),
                runningTaskCount(),
                countDistinctBatchKeys(),
                false
        ));
    }

    private CompiledTask removeNextFairPendingTask() {
        Set<CompiledTask> activeTasks = new HashSet<>();
        for (BoundedExecutionLane lane : lanes) {
            CompiledTask activeTask = lane.getActiveTask();
            if (activeTask != null) {
                activeTasks.add(activeTask);
            }
        }
        Iterator<CompiledTask> iterator = pendingTasks.iterator();
        while (iterator.hasNext()) {
            CompiledTask pendingTask = iterator.next();
            if (!hasRunningSameBatchKey(pendingTask, activeTasks)) {
                iterator.remove();
                return pendingTask;
            }
        }
        return pendingTasks.removeFirst();
    }

    private static boolean hasRunningSameBatchKey(CompiledTask pendingTask, Set<CompiledTask> activeTasks) {
        for (CompiledTask activeTask : activeTasks) {
            if (activeTask.hasSameBatchKey(pendingTask)) {
                return true;
            }
        }
        return false;
    }

    private CompiledTask findCoalesceTarget(CompiledTask task, QueueBudgetContext budgetContext) {
        BatchExecutionMode batchExecutionMode = budgetContext == null
                ? BatchExecutionMode.OFF
                : budgetContext.batchExecutionMode();
        if (batchExecutionMode == BatchExecutionMode.OFF) {
            return null;
        }
        if (batchExecutionMode == BatchExecutionMode.SAME_PATTERN_COALESCE) {
            CompiledTask tail = pendingTasks.peekLast();
            int maxExecutionCount = maxPhysicalTaskExecutionCount(budgetContext);
            if (tail == null || !tail.canCoalesceWith(task, maxExecutionCount)) {
                return null;
            }
            return tail.canSafelyAppendExecutionCount(task.getExecutionCount()) ? tail : null;
        }
        if (batchExecutionMode == BatchExecutionMode.SAME_PATTERN_DRAIN) {
            return findDrainCoalesceTarget(task, budgetContext);
        }
        return null;
    }

    private CompiledTask findDrainCoalesceTarget(CompiledTask task, QueueBudgetContext budgetContext) {
        CompiledTask leastLoadedTarget = null;
        int maxExecutionCount = maxPhysicalTaskExecutionCount(budgetContext);
        for (BoundedExecutionLane lane : lanes) {
            CompiledTask activeTask = lane.getActiveTask();
            if (activeTask != null && activeTask.canDrainCoalesceWith(task, maxExecutionCount)) {
                leastLoadedTarget = selectLeastLoadedTarget(leastLoadedTarget, activeTask, task, maxExecutionCount);
            }
        }
        for (CompiledTask pendingTask : pendingTasks) {
            if (pendingTask.canDrainCoalesceWith(task, maxExecutionCount)) {
                leastLoadedTarget = selectLeastLoadedTarget(leastLoadedTarget, pendingTask, task, maxExecutionCount);
            }
        }
        if (leastLoadedTarget != null
                && budgetContext != null
                && !budgetContext.budgetModel().canAppendBatchExecution(countDistinctBatchKeys() < budgetContext.preferredLaneFloor())) {
            return null;
        }
        return leastLoadedTarget;
    }

    private static CompiledTask selectLeastLoadedTarget(
            CompiledTask currentTarget,
            CompiledTask candidate,
            CompiledTask incoming,
            int maxExecutionCount
    ) {
        if (!candidate.canDrainCoalesceWith(incoming, maxExecutionCount)
                || !candidate.canSafelyAppendExecutionCount(incoming.getExecutionCount())) {
            return currentTarget;
        }
        if (currentTarget == null || candidate.getExecutionCount() < currentTarget.getExecutionCount()) {
            return candidate;
        }
        return currentTarget;
    }

    private static int maxPhysicalTaskExecutionCount(QueueBudgetContext budgetContext) {
        return Integer.MAX_VALUE;
    }

    private void ensureLaneCapacity(int laneCount) {
        while (lanes.size() < laneCount) {
            lanes.add(new BoundedExecutionLane(lanes.size()));
        }
    }

    public int countOutstandingLogicalExecutions() {
        int count = 0;
        for (CompiledTask task : getAllTasks()) {
            count = saturatedAdd(count, task.getExecutionCount());
        }
        return count;
    }

    public int countDistinctBatchKeys() {
        List<CompiledTask> representatives = new ArrayList<>();
        int count = 0;
        for (CompiledTask task : getAllTasks()) {
            boolean matched = false;
            for (CompiledTask representative : representatives) {
                if (representative.hasSameBatchKey(task)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                representatives.add(task);
                count++;
            }
        }
        return count;
    }

    public int countDistinctActiveBatchKeys() {
        List<CompiledTask> representatives = new ArrayList<>();
        int count = 0;
        for (CompiledTask task : getActiveTasks()) {
            boolean matched = false;
            for (CompiledTask representative : representatives) {
                if (representative.hasSameBatchKey(task)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                representatives.add(task);
                count++;
            }
        }
        return count;
    }

    public int countQueuedLogicalExecutions() {
        int count = 0;
        for (CompiledTask task : pendingTasks) {
            count = saturatedAdd(count, task.getExecutionCount());
        }
        return count;
    }

    public int countRunningLogicalExecutions() {
        int count = 0;
        for (CompiledTask task : getActiveTasks()) {
            count = saturatedAdd(count, task.getExecutionCount());
        }
        return count;
    }

    public int activeLaneCapacity() {
        return lanes.size();
    }

    public enum CoalesceOfferResult {
        ACCEPTED,
        COALESCED,
        REJECTED
    }

    private static int saturatedAdd(int left, int right) {
        long value = (long) Math.max(0, left) + Math.max(0, right);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
