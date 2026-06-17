package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EnhancedCraftingStatusService {

    private EnhancedCraftingStatusService() {
    }

    public static CraftingStatus attachBlockedAmounts(CraftingStatus status, EnhancedCraftingBlockedTracker tracker) {
        if (status == null || tracker == null) {
            return status;
        }

        for (CraftingStatusEntry entry : status.getEntries()) {
            if (entry.getWhat() == null || !(entry instanceof EnhancedCraftingStatusEntry enhancedEntry)) {
                continue;
            }
            enhancedEntry.chexsonsaeutils$setBlockedAmount(tracker.chexsonsaeutils$blockedAmount(entry.getWhat()));
        }
        return status;
    }

    public static CraftingPlanSummary attachPatternTimes(CraftingPlanSummary summary, ICraftingPlan plan) {
        if (summary == null || plan == null || plan.patternTimes().isEmpty()) {
            return summary;
        }

        Map<AEKey, List<Long>> timesByOutput = collectPatternTimes(plan);
        for (CraftingPlanSummaryEntry entry : summary.getEntries()) {
            if (!(entry instanceof EnhancedCraftingPlanSummaryEntry enhancedEntry)) {
                continue;
            }
            List<Long> patternTimes = timesByOutput.get(entry.getWhat());
            if (patternTimes == null || patternTimes.isEmpty()) {
                enhancedEntry.chexsonsaeutils$setPatternTimes(List.of());
                continue;
            }
            enhancedEntry.chexsonsaeutils$setPatternTimes(patternTimes);
        }
        return summary;
    }

    public static List<Long> sortedPatternTimes(List<Long> patternTimes, int limit) {
        if (patternTimes == null || patternTimes.isEmpty() || limit <= 0) {
            return List.of();
        }

        return patternTimes.stream()
                .sorted(Comparator.reverseOrder())
                .limit(limit)
                .toList();
    }

    private static Map<AEKey, List<Long>> collectPatternTimes(ICraftingPlan plan) {
        Map<AEKey, List<Long>> timesByOutput = new HashMap<>();
        for (var entry : plan.patternTimes().entrySet()) {
            long times = entry.getValue();
            if (times <= 0L) {
                continue;
            }
            for (var output : entry.getKey().getOutputs()) {
                if (output.what() == null) {
                    continue;
                }
                timesByOutput.computeIfAbsent(output.what(), ignored -> new ArrayList<>()).add(times);
            }
        }

        timesByOutput.replaceAll((ignored, values) -> List.copyOf(values));
        return timesByOutput;
    }
}
