package git.chexson.chexsonsaeutils.crafting.directprocessing;

public interface ProcessingTaskCompletionHost {
    void completeProcessingTask(ProcessingTask task);

    default void completeProcessingTask(ProcessingTask task, ProcessingLatencyOrigin latencyOrigin) {
        completeProcessingTask(task);
    }

    boolean isWaitingForOutputReturn();

    int getProcessingWorkUnitsPerTick();
}
