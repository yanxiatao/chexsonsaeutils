package git.chexson.chexsonsaeutils.blockentity.crafting;

public enum TaskState {
    QUEUED,
    RUNNING,
    WAITING_OUTPUT,
    PENDING_COMPLETION,
    COMPLETE,
    FAILED
}
