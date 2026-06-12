package git.chexson.chexsonsaeutils.blockentity.crafting;

import org.jetbrains.annotations.Nullable;

public final class BoundedExecutionLane {

    private final int laneId;
    @Nullable
    private CompiledTask activeTask;

    public BoundedExecutionLane(int laneId) {
        this.laneId = laneId;
    }

    public boolean isIdle() {
        return activeTask == null;
    }

    public boolean assign(CompiledTask task) {
        if (activeTask != null) {
            return false;
        }
        activeTask = task;
        return true;
    }

    public boolean tick(int workUnits) {
        if (activeTask == null) {
            return false;
        }
        activeTask.advance(workUnits);
        return true;
    }

    @Nullable
    public CompiledTask getActiveTask() {
        return activeTask;
    }

    @Nullable
    public CompiledTask releaseIfReady() {
        if (activeTask == null || !activeTask.isReadyToComplete()) {
            return null;
        }
        CompiledTask completedTask = activeTask;
        activeTask = null;
        return completedTask;
    }

    public void clear() {
        activeTask = null;
    }

    public int getLaneId() {
        return laneId;
    }
}
