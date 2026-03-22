package git.chexson.chexsonsaeutils.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record CraftingContinuationStatusSnapshot(
        UUID craftId,
        String finalOutputKey,
        long requestedAmount,
        boolean hasWaitingBranches,
        List<CraftingContinuationWaitingBranch> waitingBranches,
        List<String> runningBranchLabels
) {

    private static final UUID EMPTY_CRAFT_ID = new UUID(0L, 0L);

    public CraftingContinuationStatusSnapshot {
        craftId = craftId == null ? EMPTY_CRAFT_ID : craftId;
        finalOutputKey = finalOutputKey == null ? "" : finalOutputKey;
        requestedAmount = Math.max(0L, requestedAmount);
        waitingBranches = normalizeWaitingBranches(waitingBranches);
        runningBranchLabels = normalizeRunningBranchLabels(runningBranchLabels);
        hasWaitingBranches = !waitingBranches.isEmpty();
    }

    private static List<CraftingContinuationWaitingBranch> normalizeWaitingBranches(
            List<CraftingContinuationWaitingBranch> rawWaitingBranches
    ) {
        if (rawWaitingBranches == null || rawWaitingBranches.isEmpty()) {
            return List.of();
        }

        List<CraftingContinuationWaitingBranch> normalized = new ArrayList<>(rawWaitingBranches.size());
        for (CraftingContinuationWaitingBranch waitingBranch : rawWaitingBranches) {
            if (waitingBranch != null) {
                normalized.add(waitingBranch);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeRunningBranchLabels(List<String> rawRunningBranchLabels) {
        if (rawRunningBranchLabels == null || rawRunningBranchLabels.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>(rawRunningBranchLabels.size());
        for (String rawRunningBranchLabel : rawRunningBranchLabels) {
            normalized.add(rawRunningBranchLabel == null ? "" : rawRunningBranchLabel);
        }
        return List.copyOf(normalized);
    }
}
