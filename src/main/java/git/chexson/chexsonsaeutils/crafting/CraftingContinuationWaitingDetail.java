package git.chexson.chexsonsaeutils.crafting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record CraftingContinuationWaitingDetail(
        UUID craftId,
        String finalOutputKey,
        long requestedAmount,
        List<CraftingContinuationWaitingBranch> waitingBranches,
        List<String> runningBranchLabels
) {

    private static final UUID EMPTY_CRAFT_ID = new UUID(0L, 0L);

    private static final String CRAFT_ID_KEY = "craft_id";
    private static final String FINAL_OUTPUT_KEY = "final_output_key";
    private static final String REQUESTED_AMOUNT_KEY = "requested_amount";
    private static final String WAITING_BRANCHES_KEY = "waiting_branches";
    private static final String RUNNING_BRANCH_LABELS_KEY = "running_branch_labels";

    public CraftingContinuationWaitingDetail {
        craftId = craftId == null ? EMPTY_CRAFT_ID : craftId;
        finalOutputKey = finalOutputKey == null ? "" : finalOutputKey;
        requestedAmount = Math.max(0L, requestedAmount);
        waitingBranches = normalizeWaitingBranches(waitingBranches);
        runningBranchLabels = normalizeRunningBranchLabels(runningBranchLabels);
    }

    public CompoundTag writeToTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(CRAFT_ID_KEY, craftId);
        tag.putString(FINAL_OUTPUT_KEY, finalOutputKey);
        tag.putLong(REQUESTED_AMOUNT_KEY, requestedAmount);

        ListTag waitingBranchTags = new ListTag();
        for (CraftingContinuationWaitingBranch waitingBranch : waitingBranches) {
            waitingBranchTags.add(waitingBranch.writeToTag());
        }
        tag.put(WAITING_BRANCHES_KEY, waitingBranchTags);

        ListTag runningBranchTags = new ListTag();
        for (String runningBranchLabel : runningBranchLabels) {
            runningBranchTags.add(StringTag.valueOf(runningBranchLabel));
        }
        tag.put(RUNNING_BRANCH_LABELS_KEY, runningBranchTags);
        return tag;
    }

    public static CraftingContinuationWaitingDetail readFromTag(CompoundTag tag) {
        if (tag == null) {
            return new CraftingContinuationWaitingDetail(EMPTY_CRAFT_ID, "", 0L, List.of(), List.of());
        }

        UUID craftId = tag.hasUUID(CRAFT_ID_KEY) ? tag.getUUID(CRAFT_ID_KEY) : EMPTY_CRAFT_ID;

        List<CraftingContinuationWaitingBranch> waitingBranches = new ArrayList<>();
        if (tag.contains(WAITING_BRANCHES_KEY, Tag.TAG_LIST)) {
            ListTag waitingBranchTags = tag.getList(WAITING_BRANCHES_KEY, Tag.TAG_COMPOUND);
            for (Tag waitingBranchTag : waitingBranchTags) {
                waitingBranches.add(CraftingContinuationWaitingBranch.readFromTag((CompoundTag) waitingBranchTag));
            }
        }

        List<String> runningBranchLabels = new ArrayList<>();
        if (tag.contains(RUNNING_BRANCH_LABELS_KEY, Tag.TAG_LIST)) {
            ListTag runningBranchTags = tag.getList(RUNNING_BRANCH_LABELS_KEY, Tag.TAG_STRING);
            for (Tag runningBranchTag : runningBranchTags) {
                runningBranchLabels.add(runningBranchTag.getAsString());
            }
        }

        return new CraftingContinuationWaitingDetail(
                craftId,
                tag.getString(FINAL_OUTPUT_KEY),
                tag.getLong(REQUESTED_AMOUNT_KEY),
                waitingBranches,
                runningBranchLabels
        );
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
