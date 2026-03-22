package git.chexson.chexsonsaeutils.crafting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

public record CraftingContinuationWaitingBranch(
        String branchLabel,
        int planOrder,
        Map<String, Long> missingStacks
) {

    private static final String BRANCH_LABEL_KEY = "branch_label";
    private static final String PLAN_ORDER_KEY = "plan_order";
    private static final String MISSING_STACKS_KEY = "missing_stacks";

    public CraftingContinuationWaitingBranch {
        branchLabel = branchLabel == null ? "" : branchLabel;
        planOrder = Math.max(0, planOrder);
        missingStacks = normalizeMissingStacks(missingStacks);
    }

    public CompoundTag writeToTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(BRANCH_LABEL_KEY, branchLabel);
        tag.putInt(PLAN_ORDER_KEY, planOrder);

        CompoundTag missingStacksTag = new CompoundTag();
        missingStacks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> missingStacksTag.putLong(entry.getKey(), sanitizeAmount(entry.getValue())));
        tag.put(MISSING_STACKS_KEY, missingStacksTag);
        return tag;
    }

    public static CraftingContinuationWaitingBranch readFromTag(CompoundTag tag) {
        if (tag == null) {
            return new CraftingContinuationWaitingBranch("", 0, Map.of());
        }

        Map<String, Long> missingStacks = new LinkedHashMap<>();
        if (tag.contains(MISSING_STACKS_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag missingStacksTag = tag.getCompound(MISSING_STACKS_KEY);
            missingStacksTag.getAllKeys().stream()
                    .sorted()
                    .forEach(key -> missingStacks.put(key, sanitizeAmount(missingStacksTag.getLong(key))));
        }

        return new CraftingContinuationWaitingBranch(
                tag.getString(BRANCH_LABEL_KEY),
                tag.getInt(PLAN_ORDER_KEY),
                missingStacks
        );
    }

    private static Map<String, Long> normalizeMissingStacks(Map<String, Long> rawMissingStacks) {
        Map<String, Long> normalized = new LinkedHashMap<>();
        if (rawMissingStacks != null) {
            rawMissingStacks.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    normalized.put(key, sanitizeAmount(value));
                }
            });
        }
        return Map.copyOf(normalized);
    }

    private static long sanitizeAmount(Long amount) {
        return amount == null ? 0L : Math.max(0L, amount);
    }
}
