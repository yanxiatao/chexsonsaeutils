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
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CraftingStatusEnhancer {

    private CraftingStatusEnhancer() {
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
        if (summary == null || plan == null) {
            return summary;
        }

        Map<AEKey, List<java.math.BigInteger>> timesByOutput = collectPatternTimes(plan);
        for (CraftingPlanSummaryEntry entry : summary.getEntries()) {
            if (!(entry instanceof EnhancedCraftingPlanSummaryEntry enhancedEntry)) {
                continue;
            }
            List<java.math.BigInteger> patternTimes = timesByOutput.get(entry.getWhat());
            if (patternTimes == null || patternTimes.isEmpty()) {
                enhancedEntry.chexsonsaeutils$setPatternTimes(List.of());
                continue;
            }
            enhancedEntry.chexsonsaeutils$setPatternTimes(patternTimes);
        }
        return summary;
    }

    /** 在同步写入时排序一次，渲染时只取前几项，避免每帧全量排序。 */
    public static List<java.math.BigInteger> normalizePatternTimes(List<java.math.BigInteger> patternTimes) {
        if (patternTimes == null) return List.of();
        if (patternTimes.size() > 100000) throw new IllegalArgumentException("样板次数条目数量无效");
        for (var times : patternTimes)
            if (times == null || times.signum() < 0 || times.toByteArray().length > 4096)
                throw new IllegalArgumentException("样板次数无效");
        return patternTimes.stream().sorted(Comparator.reverseOrder()).toList();
    }

    public static List<java.math.BigInteger> displayedPatternTimes(List<java.math.BigInteger> patternTimes, int limit) {
        if (patternTimes == null || patternTimes.isEmpty() || limit <= 0) {
            return List.of();
        }

        return patternTimes.subList(0, Math.min(limit, patternTimes.size()));
    }

    private static Map<AEKey, List<java.math.BigInteger>> collectPatternTimes(ICraftingPlan plan) {
        Map<AEKey, List<java.math.BigInteger>> timesByOutput = new HashMap<>();
        for (var entry : git.chexson.chexsonsaeutils.crafting.plan.PatternRosterReadout.universeAndTimes(plan).entrySet()) {
            java.math.BigInteger times = entry.getValue().exactValue();
            if (times.signum() <= 0) {
                continue;
            }
            IPatternDetails pattern = entry.getKey();
            if (pattern instanceof HostBatchPattern aggregatedPattern) {
                for (var step : aggregatedPattern.steps()) {
                    GenericStack stepOutput = step.stepPrimaryOutput();
                    if (stepOutput != null && stepOutput.what() != null) {
                        timesByOutput.computeIfAbsent(stepOutput.what(), ignored -> new ArrayList<>()).add(times);
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
