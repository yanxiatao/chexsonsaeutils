package git.chexson.chexsonsaeutils.blockentity.crafting;

public final class DynamicExecutionBudgetModel {

    private static final int SOFT_BUDGET_PER_SPEED_CARD = 16;
    private static final int HARD_BUDGET_PER_SPEED_CARD = 24;
    private static final int SOFT_BUDGET_BASE = 24;
    private static final int HARD_BUDGET_BASE = 32;
    private static final int COMPLETION_COST = 12;
    private static final int BATCH_APPEND_COST = 1;
    private static final int BLOCKED_PENALTY = 16;
    private static final int FALLBACK_PENALTY = 2;
    private static final int INSERT_PRESSURE_DIVISOR = 8;
    private static final int COMPLETION_SLICE_HARD_BUDGET_DIVISOR = 16;
    private static final int COMPLETION_SLICE_SOFT_BUDGET_DIVISOR = 32;
    private static final int FAST_PATH_EXTRACTION_TELEMETRY_PER_HARD_BUDGET = 64;
    private static final int FAST_PATH_EXTRACTION_TELEMETRY_PER_PREFERRED_LANE = 256;

    private final int softBudget;
    private final int hardBudget;
    private int remainingSoftBudget;
    private int remainingHardBudget;
    private final int preferredLaneFloor;
    private final int laneActivationTarget;
    private final int completionTarget;
    private final int dispatchTarget;
    private final int completionSliceTargetExecutions;
    private final int completionSliceBudget;
    private final int physicalTaskExecutionBudget;
    private int remainingCompletionSliceBudget;
    private int remainingBatchAssemblyBudget;
    private final int fastPathExtractionBudget;
    private final int remainingFastPathExtractionBudget;
    private final int queuedTasks;
    private final int runningTasks;
    private final int distinctBatchKeys;
    private final boolean blocked;

    public DynamicExecutionBudgetModel(
            int softBudget,
            int hardBudget,
            int preferredLaneFloor,
            int laneActivationTarget,
            int completionTarget,
            int dispatchTarget,
            int batchAssemblyBudget,
            int queuedTasks,
            int runningTasks,
            int distinctBatchKeys,
            boolean blocked
    ) {
        this.softBudget = Math.max(1, softBudget);
        this.hardBudget = Math.max(this.softBudget, hardBudget);
        this.remainingSoftBudget = this.softBudget;
        this.remainingHardBudget = this.hardBudget;
        this.preferredLaneFloor = Math.max(1, preferredLaneFloor);
        this.laneActivationTarget = Math.max(1, laneActivationTarget);
        this.completionTarget = Math.max(1, completionTarget);
        this.dispatchTarget = Math.max(1, dispatchTarget);
        this.completionSliceTargetExecutions = Math.max(1, this.completionTarget);
        this.completionSliceBudget = Math.max(
                this.completionSliceTargetExecutions,
                Math.max(1, this.hardBudget) * COMPLETION_SLICE_HARD_BUDGET_DIVISOR
        );
        this.physicalTaskExecutionBudget = Math.max(
                1,
                safeIntMultiply(this.completionSliceBudget, Math.max(1, this.preferredLaneFloor))
        );
        this.remainingCompletionSliceBudget = this.completionSliceBudget;
        this.remainingBatchAssemblyBudget = Math.max(1, batchAssemblyBudget);
        this.fastPathExtractionBudget = Math.max(
                1,
                Math.min(
                        safeIntMultiply(
                                Math.max(1, this.preferredLaneFloor),
                                FAST_PATH_EXTRACTION_TELEMETRY_PER_PREFERRED_LANE
                        ),
                        safeIntMultiply(
                                Math.max(1, this.hardBudget),
                                FAST_PATH_EXTRACTION_TELEMETRY_PER_HARD_BUDGET
                        )
                )
        );
        this.remainingFastPathExtractionBudget = this.fastPathExtractionBudget;
        this.queuedTasks = Math.max(0, queuedTasks);
        this.runningTasks = Math.max(0, runningTasks);
        this.distinctBatchKeys = Math.max(0, distinctBatchKeys);
        this.blocked = blocked;
    }

    public static DynamicExecutionBudgetModel fromHost(AbstractHighCapacityCraftingHostBlockEntity host) {
        int speedCards = Math.max(0, host.getInstalledSpeedCardCount());
        int queuedTasks = host.getQueuedTaskCount();
        int runningTasks = host.getRunningTaskCount();
        int distinctBatchKeys = host.getDistinctBatchKeyCount();
        boolean blocked = host.isWaitingAeReturn();

        int blockedPenalty = blocked ? BLOCKED_PENALTY + Math.min(16, host.getWaitingPenaltyForBudget()) : 0;

        int softBudget = softCap(speedCards) - blockedPenalty;
        int hardBudget = hardCap(speedCards) - Math.min(blockedPenalty, hardCap(speedCards) / 2);
        int preferredLaneFloor = Math.max(1, host.getPreferredLaneFloor());
        int completionTarget = Math.max(
                1,
                Math.max(
                        32,
                        (1 + speedCards) * Math.max(32, preferredLaneFloor * 16)
                )
        );
        int dispatchTarget = Math.max(
                1,
                Math.min(
                        queuedTasks + runningTasks + distinctBatchKeys + 1,
                        1 + speedCards + Math.max(0, softBudget / Math.max(1, COMPLETION_COST / 2))
                )
        );
        int laneActivationTarget = Math.max(
                1,
                Math.min(
                        Math.max(1, queuedTasks + runningTasks),
                        Math.max(
                                preferredLaneFloor,
                                Math.max(
                                        Math.min(Math.max(1, queuedTasks + runningTasks), Math.max(1, distinctBatchKeys)),
                                        dispatchTarget
                                )
                        )
                )
        );
        int batchAssemblyBudget = Math.max(
                1,
                Math.max(
                        softBudget * 32,
                        (queuedTasks + runningTasks + 1) * Math.max(1, preferredLaneFloor)
                )
        );
        return new DynamicExecutionBudgetModel(
                Math.max(1, softBudget),
                Math.max(1, Math.max(softBudget, hardBudget)),
                preferredLaneFloor,
                laneActivationTarget,
                completionTarget,
                dispatchTarget,
                batchAssemblyBudget,
                queuedTasks,
                runningTasks,
                distinctBatchKeys,
                blocked
        );
    }

    public boolean canActivateLane(int activeLanes, boolean fillsUniqueKeyFloor) {
        if (blocked) {
            return false;
        }
        if (activeLanes < 0) {
            return false;
        }
        if (fillsUniqueKeyFloor && activeLanes < preferredLaneFloor) {
            return true;
        }
        return activeLanes < laneActivationTarget;
    }

    public boolean canAppendBatchExecution(boolean prioritizeUniqueKeyFloor) {
        if (blocked || remainingBatchAssemblyBudget < BATCH_APPEND_COST) {
            return false;
        }
        if (prioritizeUniqueKeyFloor && runningTasks + queuedTasks < preferredLaneFloor && distinctBatchKeys > 1) {
            return false;
        }
        remainingBatchAssemblyBudget -= BATCH_APPEND_COST;
        return true;
    }

    public boolean canDispatchExecution() {
        return !blocked;
    }

    public boolean tryClaimFastPathExtractionExecution() {
        return canDispatchExecution();
    }

    public boolean canCompleteAnotherTask(int completedThisTick) {
        if (blocked || completedThisTick >= completionTarget) {
            return false;
        }
        if (remainingHardBudget < COMPLETION_COST) {
            return false;
        }
        if (remainingSoftBudget < COMPLETION_COST && completedThisTick > 0) {
            return false;
        }
        remainingSoftBudget = Math.max(0, remainingSoftBudget - COMPLETION_COST);
        remainingHardBudget -= COMPLETION_COST;
        return true;
    }

    public int claimCompletionSliceExecutions(int requestedExecutions) {
        if (blocked || requestedExecutions <= 0) {
            return 0;
        }
        if (remainingHardBudget <= 0 || remainingSoftBudget <= 0 || remainingCompletionSliceBudget <= 0) {
            return 0;
        }
        int allowed = Math.min(requestedExecutions, completionSliceTargetExecutions);
        allowed = Math.min(allowed, remainingCompletionSliceBudget);
        if (allowed <= 0) {
            return 0;
        }

        int hardBudgetExecutions = remainingHardBudget * COMPLETION_SLICE_HARD_BUDGET_DIVISOR;
        int softBudgetExecutions = remainingSoftBudget * COMPLETION_SLICE_SOFT_BUDGET_DIVISOR;
        allowed = Math.min(allowed, hardBudgetExecutions);
        allowed = Math.min(allowed, softBudgetExecutions);
        if (allowed <= 0) {
            return 0;
        }

        int hardCost = Math.max(1, (int) Math.ceil(allowed / (double) COMPLETION_SLICE_HARD_BUDGET_DIVISOR));
        int softCost = Math.max(1, (int) Math.ceil(allowed / (double) COMPLETION_SLICE_SOFT_BUDGET_DIVISOR));
        remainingCompletionSliceBudget -= allowed;
        remainingHardBudget = Math.max(0, remainingHardBudget - hardCost);
        remainingSoftBudget = Math.max(0, remainingSoftBudget - softCost);
        return allowed;
    }

    public int preferredLaneFloor() {
        return preferredLaneFloor;
    }

    public int completionTarget() {
        return completionTarget;
    }

    public int laneActivationTarget() {
        return laneActivationTarget;
    }

    public int dispatchTarget() {
        return dispatchTarget;
    }

    public int completionSliceTargetExecutions() {
        return completionSliceTargetExecutions;
    }

    public int completionSliceBudget() {
        return completionSliceBudget;
    }

    public int physicalTaskExecutionBudget() {
        return physicalTaskExecutionBudget;
    }

    public int fastPathExtractionBudget() {
        return fastPathExtractionBudget;
    }

    public int remainingFastPathExtractionBudget() {
        return remainingFastPathExtractionBudget;
    }

    public int remainingCompletionSliceBudget() {
        return remainingCompletionSliceBudget;
    }

    public int softBudget() {
        return softBudget;
    }

    public int hardBudget() {
        return hardBudget;
    }

    public int remainingSoftBudget() {
        return remainingSoftBudget;
    }

    public int remainingHardBudget() {
        return remainingHardBudget;
    }

    public boolean blocked() {
        return blocked;
    }

    private static int softCap(int speedCards) {
        return SOFT_BUDGET_BASE + speedCards * SOFT_BUDGET_PER_SPEED_CARD;
    }

    private static int hardCap(int speedCards) {
        return HARD_BUDGET_BASE + speedCards * HARD_BUDGET_PER_SPEED_CARD;
    }

    private static int safeIntMultiply(int left, int right) {
        long value = (long) Math.max(1, left) * Math.max(1, right);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
