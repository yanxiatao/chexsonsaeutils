package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
import appeng.menu.me.common.IncrementalUpdateHelper;
import git.chexson.chexsonsaeutils.crafting.formalmachine.IFormalMachineAggregatedPattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EnhancedCraftingStatusService {

    private EnhancedCraftingStatusService() {
    }

    public static CraftingStatus attachBlockedAmounts(CraftingStatus status, EnhancedCraftingBlockedTracker tracker) {
        return attachBlockedAmounts(status, tracker, null);
    }

    public static CraftingStatus attachBlockedAmounts(
            CraftingStatus status,
            EnhancedCraftingBlockedTracker tracker,
            IncrementalUpdateHelper changes
    ) {
        if (status == null || tracker == null) {
            return status;
        }

        for (CraftingStatusEntry entry : status.getEntries()) {
            AEKey what = resolveStatusEntryKey(entry, changes);
            if (what == null || !(entry instanceof EnhancedCraftingStatusEntry enhancedEntry)) {
                continue;
            }
            enhancedEntry.chexsonsaeutils$setBlockedAmount(tracker.chexsonsaeutils$blockedAmount(what));
        }
        return status;
    }

    public static CraftingStatus copyBlockedAmountsBySerial(CraftingStatus target, CraftingStatus source) {
        if (target == null || source == null) {
            return target;
        }

        Map<Long, Long> blockedAmounts = new HashMap<>();
        for (CraftingStatusEntry entry : source.getEntries()) {
            if (entry instanceof EnhancedCraftingStatusEntry enhancedEntry) {
                blockedAmounts.put(entry.getSerial(), enhancedEntry.chexsonsaeutils$blockedAmount());
            }
        }
        if (blockedAmounts.isEmpty()) {
            return target;
        }

        for (CraftingStatusEntry entry : target.getEntries()) {
            if (entry instanceof EnhancedCraftingStatusEntry enhancedEntry
                    && blockedAmounts.containsKey(entry.getSerial())) {
                enhancedEntry.chexsonsaeutils$setBlockedAmount(blockedAmounts.get(entry.getSerial()));
            }
        }
        return target;
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
            IPatternDetails pattern = entry.getKey();
            if (pattern instanceof IFormalMachineAggregatedPattern aggregatedPattern) {
                for (var step : aggregatedPattern.steps()) {
                    GenericStack stepOutput = step.stepPrimaryOutput();
                    if (stepOutput != null && stepOutput.what() != null) {
                        long stepTimes = step.executionCount() * times;
                        timesByOutput.computeIfAbsent(stepOutput.what(), ignored -> new ArrayList<>()).add(stepTimes);
                    }
                }
            } else {
                for (var output : pattern.getOutputs()) {
                    if (output.what() == null) {
                        continue;
                    }
                    timesByOutput.computeIfAbsent(output.what(), ignored -> new ArrayList<>()).add(times);
                }
            }
        }

        timesByOutput.replaceAll((ignored, values) -> List.copyOf(values));
        return timesByOutput;
    }

    private static AEKey resolveStatusEntryKey(CraftingStatusEntry entry, IncrementalUpdateHelper changes) {
        if (entry.getWhat() != null) {
            return entry.getWhat();
        }
        if (changes == null) {
            return null;
        }
        return changes.getBySerial(entry.getSerial());
    }
}
