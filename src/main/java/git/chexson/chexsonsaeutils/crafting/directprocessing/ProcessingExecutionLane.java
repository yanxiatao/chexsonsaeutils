package git.chexson.chexsonsaeutils.crafting.directprocessing;

import org.jetbrains.annotations.Nullable;

public final class ProcessingExecutionLane {

    @Nullable
    private ProcessingCompiledTask activeTask;

    public boolean isIdle() {
        return activeTask == null;
    }

    public boolean assign(ProcessingCompiledTask task) {
        if (activeTask != null) {
            return false;
        }
        activeTask = task;
        return true;
    }

    @Nullable
    public ProcessingCompiledTask activeTask() {
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
    public ProcessingCompiledTask releaseIfReady() {
        if (activeTask == null || !activeTask.isReadyToComplete()) {
            return null;
        }
        ProcessingCompiledTask completed = activeTask;
        activeTask = null;
        return completed;
    }

    public void clear() {
        activeTask = null;
    }
}
