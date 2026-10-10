package git.chexson.chexsonsaeutils.crafting.directprocessing;

import org.jetbrains.annotations.Nullable;

public final class ProcessingExecutionLane {

    @Nullable
    private ProcessingTask activeTask;

    public boolean isIdle() {
        return activeTask == null;
    }

    public boolean assign(ProcessingTask task) {
        if (activeTask != null) {
            return false;
        }
        activeTask = task;
        return true;
    }

    @Nullable
    public ProcessingTask activeTask() {
        return activeTask;
    }

    public boolean tick(int workUnits) {
        if (activeTask == null) {
            return false;
        }
        activeTask.advance(workUnits);
        return true;
    }

    @Nullable
    public ProcessingTask releaseIfReady() {
        if (activeTask == null || !activeTask.isReadyToComplete()) {
            return null;
        }
        ProcessingTask completed = activeTask;
        activeTask = null;
        return completed;
    }

    public void clear() {
        activeTask = null;
    }
}
