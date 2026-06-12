package git.chexson.chexsonsaeutils.blockentity.crafting;

public record QueueBudgetContext(
        BatchExecutionMode batchExecutionMode,
        DynamicExecutionBudgetModel budgetModel,
        int preferredLaneFloor
) {

    public QueueBudgetContext {
        batchExecutionMode = batchExecutionMode == null ? BatchExecutionMode.OFF : batchExecutionMode;
        preferredLaneFloor = Math.max(1, preferredLaneFloor);
    }
}
