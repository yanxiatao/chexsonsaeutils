package git.chexson.chexsonsaeutils.crafting.directprocessing;

public interface ProcessingTaskCompletionHost {
    void completeProcessingTask(ProcessingCompiledTask task);

    default void completeProcessingTask(ProcessingCompiledTask task, ProcessingLatencyOrigin latencyOrigin) {
        completeProcessingTask(task);
    }

    boolean isWaitingForOutputReturn();

    int getProcessingWorkUnitsPerTick();
}
