package git.chexson.chexsonsaeutils.parts.automation;

import appeng.api.config.RedstoneMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MultiLevelEmitterPart {

    public record SlotEvaluation(boolean participating, boolean result) {
        public static SlotEvaluation participating(boolean result) {
            return new SlotEvaluation(true, result);
        }

        public static SlotEvaluation inactive() {
            return new SlotEvaluation(false, false);
        }
    }

    public record AggregationResult(int participatingCount, boolean result) {
        public boolean hasParticipatingSlots() {
            return participatingCount > 0;
        }
    }

    public enum ComparisonMode {
        GREATER_OR_EQUAL,
        LESS_THAN,
        EQUAL,
        NOT_EQUAL;

        public static ComparisonMode fromPersisted(String value) {
            if (value == null || value.isBlank()) {
                return GREATER_OR_EQUAL;
            }
            try {
                return ComparisonMode.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return GREATER_OR_EQUAL;
            }
        }
    }

    public enum LogicRelation {
        AND,
        OR;

        public static LogicRelation fromPersisted(String value) {
            if (value == null || value.isBlank()) {
                return OR;
            }
            try {
                return LogicRelation.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return OR;
            }
        }
    }

    public enum MatchingMode {
        STRICT,
        IGNORE_ALL,
        PERCENT_99,
        PERCENT_75,
        PERCENT_50,
        PERCENT_25;

        public static MatchingMode fromPersisted(String value) {
            if (value == null || value.isBlank()) {
                return STRICT;
            }
            String normalizedValue = value.trim().toUpperCase(Locale.ROOT);
            if ("FUZZY".equals(normalizedValue)) {
                return IGNORE_ALL;
            }
            try {
                return MatchingMode.valueOf(normalizedValue);
            } catch (IllegalArgumentException ignored) {
                return STRICT;
            }
        }
    }

    public enum CraftingMode {
        NONE,
        EMIT_WHILE_CRAFTING,
        EMIT_TO_CRAFT;

        public static CraftingMode fromPersisted(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            try {
                return CraftingMode.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return NONE;
            }
        }
    }

    private MultiLevelEmitterPart() {
    }

    public static Map<Integer, Long> normalizeThresholdsForSlotCount(
            Map<Integer, Long> thresholds,
            int slotCount
    ) {
        int normalizedSlotCount = Math.max(0, slotCount);
        Map<Integer, Long> normalized = new LinkedHashMap<>(normalizedSlotCount);
        for (int slot = 0; slot < normalizedSlotCount; slot++) {
            long persisted = thresholds == null ? 1L : thresholds.getOrDefault(slot, 1L);
            normalized.put(slot, sanitizeThreshold(persisted));
        }
        return normalized;
    }

    public static List<ComparisonMode> normalizeComparisonModesForSlotCount(
            List<ComparisonMode> persistedModes,
            int slotCount
    ) {
        int normalizedSlotCount = Math.max(0, slotCount);
        List<ComparisonMode> modes = new ArrayList<>(normalizedSlotCount);
        for (int slot = 0; slot < normalizedSlotCount; slot++) {
            ComparisonMode mode = persistedModes != null && slot < persistedModes.size()
                    ? persistedModes.get(slot)
                    : null;
            modes.add(mode == null ? ComparisonMode.GREATER_OR_EQUAL : mode);
        }
        return modes;
    }

    public static List<LogicRelation> normalizeRelationsForSlotCount(
            List<LogicRelation> persistedRelations,
            int slotCount
    ) {
        int relationCount = Math.max(0, slotCount - 1);
        List<LogicRelation> relations = new ArrayList<>(relationCount);
        for (int index = 0; index < relationCount; index++) {
            LogicRelation relation = persistedRelations != null && index < persistedRelations.size()
                    ? persistedRelations.get(index)
                    : null;
            relations.add(relation == null ? LogicRelation.OR : relation);
        }
        return relations;
    }

    public static List<MatchingMode> normalizeMatchingModesForSlotCount(
            List<MatchingMode> persistedModes,
            int slotCount,
            boolean fuzzyCapabilityAvailable
    ) {
        int normalizedSlotCount = Math.max(0, slotCount);
        List<MatchingMode> modes = new ArrayList<>(normalizedSlotCount);
        for (int slot = 0; slot < normalizedSlotCount; slot++) {
            MatchingMode requested = readRequestedMatchingMode(persistedModes, slot);
            modes.add(resolveMatchingMode(requested, fuzzyCapabilityAvailable));
        }
        return modes;
    }

    public static List<MatchingMode> normalizeRequestedMatchingModesForSlotCount(
            List<MatchingMode> persistedModes,
            int slotCount
    ) {
        int normalizedSlotCount = Math.max(0, slotCount);
        List<MatchingMode> modes = new ArrayList<>(normalizedSlotCount);
        for (int slot = 0; slot < normalizedSlotCount; slot++) {
            modes.add(readRequestedMatchingMode(persistedModes, slot));
        }
        return modes;
    }

    public static MatchingMode resolveMatchingMode(MatchingMode requested, boolean fuzzyCapabilityAvailable) {
        MatchingMode effective = requested == null ? MatchingMode.STRICT : requested;
        if (effective != MatchingMode.STRICT && !fuzzyCapabilityAvailable) {
            return MatchingMode.STRICT;
        }
        return effective;
    }

    public static MatchingMode nextMatchingMode(MatchingMode current) {
        MatchingMode effective = current == null ? MatchingMode.STRICT : current;
        return switch (effective) {
            case STRICT -> MatchingMode.IGNORE_ALL;
            case IGNORE_ALL -> MatchingMode.PERCENT_99;
            case PERCENT_99 -> MatchingMode.PERCENT_75;
            case PERCENT_75 -> MatchingMode.PERCENT_50;
            case PERCENT_50 -> MatchingMode.PERCENT_25;
            case PERCENT_25 -> MatchingMode.STRICT;
        };
    }

    public static List<CraftingMode> normalizeCraftingModesForSlotCount(
            List<CraftingMode> persistedModes,
            int slotCount,
            boolean craftingCapabilityAvailable
    ) {
        int normalizedSlotCount = Math.max(0, slotCount);
        List<CraftingMode> modes = new ArrayList<>(normalizedSlotCount);
        for (int slot = 0; slot < normalizedSlotCount; slot++) {
            CraftingMode requested = readRequestedCraftingMode(persistedModes, slot);
            modes.add(resolveCraftingMode(requested, craftingCapabilityAvailable));
        }
        return modes;
    }

    public static List<CraftingMode> normalizeRequestedCraftingModesForSlotCount(
            List<CraftingMode> persistedModes,
            int slotCount
    ) {
        int normalizedSlotCount = Math.max(0, slotCount);
        List<CraftingMode> modes = new ArrayList<>(normalizedSlotCount);
        for (int slot = 0; slot < normalizedSlotCount; slot++) {
            modes.add(readRequestedCraftingMode(persistedModes, slot));
        }
        return modes;
    }

    public static CraftingMode resolveCraftingMode(CraftingMode requested, boolean craftingCapabilityAvailable) {
        CraftingMode effective = requested == null ? CraftingMode.NONE : requested;
        if (effective != CraftingMode.NONE && !craftingCapabilityAvailable) {
            return CraftingMode.NONE;
        }
        return effective;
    }

    public static CraftingMode nextCraftingMode(CraftingMode current) {
        CraftingMode effective = current == null ? CraftingMode.NONE : current;
        return switch (effective) {
            case NONE -> CraftingMode.EMIT_WHILE_CRAFTING;
            case EMIT_WHILE_CRAFTING -> CraftingMode.EMIT_TO_CRAFT;
            case EMIT_TO_CRAFT -> CraftingMode.NONE;
        };
    }

    public static boolean isExpressionParticipatingCraftingMode(CraftingMode craftingMode) {
        CraftingMode effective = craftingMode == null ? CraftingMode.NONE : craftingMode;
        return effective == CraftingMode.EMIT_WHILE_CRAFTING
                || effective == CraftingMode.EMIT_TO_CRAFT;
    }

    public static boolean shouldRecomputeAfterMatchingModeChange(MatchingMode previous, MatchingMode current) {
        MatchingMode before = previous == null ? MatchingMode.STRICT : previous;
        MatchingMode after = current == null ? MatchingMode.STRICT : current;
        return before != after;
    }

    public static boolean evaluateComparison(long actual, long threshold, ComparisonMode mode) {
        ComparisonMode effectiveMode = mode == null ? ComparisonMode.GREATER_OR_EQUAL : mode;
        return switch (effectiveMode) {
            case GREATER_OR_EQUAL -> actual >= threshold;
            case LESS_THAN -> actual < threshold;
            case EQUAL -> actual == threshold;
            case NOT_EQUAL -> actual != threshold;
        };
    }

    public static boolean evaluateFinalResult(List<Boolean> slotResults, List<LogicRelation> relations) {
        return evaluateFinalResultWithParticipation(asParticipatingSlots(slotResults), relations).result();
    }

    public static AggregationResult evaluateFinalResultWithParticipation(
            List<SlotEvaluation> slotResults,
            List<LogicRelation> relations
    ) {
        if (slotResults == null || slotResults.isEmpty()) {
            return new AggregationResult(0, false);
        }

        SlotEvaluation aggregate = SlotEvaluation.inactive();
        int participatingCount = 0;
        for (int slot = 0; slot < slotResults.size(); slot++) {
            SlotEvaluation next = slotResults.get(slot);
            if (next == null || !next.participating()) {
                continue;
            }

            participatingCount++;
            if (!aggregate.participating()) {
                aggregate = next;
                continue;
            }

            LogicRelation relation = relations != null && slot - 1 < relations.size()
                    ? relations.get(slot - 1)
                    : LogicRelation.OR;
            aggregate = relation == LogicRelation.AND
                    ? SlotEvaluation.participating(aggregate.result() && next.result())
                    : SlotEvaluation.participating(aggregate.result() || next.result());
        }

        return new AggregationResult(participatingCount, aggregate.result());
    }

    public static boolean evaluateConfiguredSlots(
            List<Long> actualValues,
            Map<Integer, Long> thresholds,
            List<ComparisonMode> comparisons,
            List<LogicRelation> relations
    ) {
        return evaluateConfiguredSlotsWithParticipation(actualValues, thresholds, comparisons, relations).result();
    }

    public static AggregationResult evaluateConfiguredSlotsWithParticipation(
            List<Long> actualValues,
            Map<Integer, Long> thresholds,
            List<ComparisonMode> comparisons,
            List<LogicRelation> relations
    ) {
        if (actualValues == null || actualValues.isEmpty()) {
            return new AggregationResult(0, false);
        }
        List<SlotEvaluation> slotResults =
                evaluateSlotComparisonsWithParticipation(actualValues, thresholds, comparisons);
        return evaluateFinalResultWithParticipation(slotResults, relations);
    }

    public static List<Boolean> evaluateSlotComparisons(
            List<Long> actualValues,
            Map<Integer, Long> thresholds,
            List<ComparisonMode> comparisons
    ) {
        return evaluateSlotComparisonsWithParticipation(actualValues, thresholds, comparisons).stream()
                .map(SlotEvaluation::result)
                .toList();
    }

    public static List<SlotEvaluation> evaluateSlotComparisonsWithParticipation(
            List<Long> actualValues,
            Map<Integer, Long> thresholds,
            List<ComparisonMode> comparisons
    ) {
        if (actualValues == null || actualValues.isEmpty()) {
            return List.of();
        }
        Map<Integer, Long> safeThresholds = thresholds == null ? Map.of() : thresholds;
        List<ComparisonMode> safeComparisons = comparisons == null ? List.of() : comparisons;
        List<SlotEvaluation> slotResults = new ArrayList<>(actualValues.size());
        for (int slot = 0; slot < actualValues.size(); slot++) {
            long actual = actualValues.get(slot);
            long threshold = sanitizeThreshold(safeThresholds.getOrDefault(slot, 1L));
            ComparisonMode mode = slot < safeComparisons.size()
                    ? safeComparisons.get(slot)
                    : ComparisonMode.GREATER_OR_EQUAL;
            slotResults.add(SlotEvaluation.participating(evaluateComparison(actual, threshold, mode)));
        }
        return List.copyOf(slotResults);
    }

    public static boolean evaluateConfiguredOutput(
            List<Long> actualValues,
            Map<Integer, Long> thresholds,
            List<ComparisonMode> comparisons,
            List<LogicRelation> relations,
            boolean networkActive,
            RedstoneMode mode
    ) {
        int configuredItemCount = actualValues == null ? 0 : actualValues.size();
        if (configuredItemCount <= 0) {
            return resolveEmitterState(networkActive, 0, false, mode);
        }
        Map<Integer, Long> normalizedThresholds = normalizeThresholdsForSlotCount(thresholds, configuredItemCount);
        List<ComparisonMode> normalizedComparisons =
                normalizeComparisonModesForSlotCount(comparisons, configuredItemCount);
        List<LogicRelation> normalizedRelations = normalizeRelationsForSlotCount(relations, configuredItemCount);
        AggregationResult evaluationResult = evaluateConfiguredSlotsWithParticipation(
                actualValues,
                normalizedThresholds,
                normalizedComparisons,
                normalizedRelations
        );
        return resolveEmitterState(networkActive, configuredItemCount, evaluationResult, mode);
    }

    public static boolean resolveEmitterState(
            boolean networkActive,
            int configuredItemCount,
            boolean evaluationResult,
            RedstoneMode mode
    ) {
        if (!networkActive) {
            return false;
        }

        if (configuredItemCount <= 0) {
            return true;
        }

        RedstoneMode effectiveMode = mode == null ? RedstoneMode.HIGH_SIGNAL : mode;
        return switch (effectiveMode) {
            case LOW_SIGNAL -> !evaluationResult;
            case HIGH_SIGNAL -> evaluationResult;
            default -> evaluationResult;
        };
    }

    public static boolean resolveEmitterState(
            boolean networkActive,
            int configuredItemCount,
            AggregationResult evaluationResult,
            RedstoneMode mode
    ) {
        AggregationResult safeResult = evaluationResult == null ? new AggregationResult(0, false) : evaluationResult;
        int effectiveConfiguredCount = safeResult.hasParticipatingSlots() ? configuredItemCount : 0;
        return resolveEmitterState(networkActive, effectiveConfiguredCount, safeResult.result(), mode);
    }

    public static long sanitizeThreshold(long persistedThreshold) {
        return persistedThreshold <= 0L ? 1L : persistedThreshold;
    }

    public static void writeThresholdsToNbt(Map<Integer, Long> thresholds, CompoundTag target, String key) {
        CompoundTag thresholdTag = new CompoundTag();
        if (thresholds != null) {
            for (Map.Entry<Integer, Long> entry : thresholds.entrySet()) {
                thresholdTag.putLong(String.valueOf(entry.getKey()), sanitizeThreshold(entry.getValue()));
            }
        }
        target.put(key, thresholdTag);
    }

    public static Map<Integer, Long> readThresholdsFromNbt(CompoundTag source, String key) {
        Map<Integer, Long> thresholds = new LinkedHashMap<>();
        if (source == null || key == null || !source.contains(key, Tag.TAG_COMPOUND)) {
            return thresholds;
        }
        CompoundTag thresholdTag = source.getCompound(key);
        for (String rawKey : thresholdTag.getAllKeys()) {
            try {
                int slot = Integer.parseInt(rawKey);
                thresholds.put(slot, sanitizeThreshold(thresholdTag.getLong(rawKey)));
            } catch (NumberFormatException ignored) {
                // Ignore invalid legacy keys.
            }
        }
        return thresholds;
    }

    public static List<SlotEvaluation> asParticipatingSlots(List<Boolean> slotResults) {
        if (slotResults == null || slotResults.isEmpty()) {
            return List.of();
        }
        List<SlotEvaluation> evaluations = new ArrayList<>(slotResults.size());
        for (Boolean slotResult : slotResults) {
            evaluations.add(SlotEvaluation.participating(Boolean.TRUE.equals(slotResult)));
        }
        return List.copyOf(evaluations);
    }

    private static MatchingMode readRequestedMatchingMode(List<MatchingMode> persistedModes, int slot) {
        return persistedModes != null && slot < persistedModes.size()
                ? persistedModes.get(slot)
                : MatchingMode.STRICT;
    }

    private static CraftingMode readRequestedCraftingMode(List<CraftingMode> persistedModes, int slot) {
        return persistedModes != null && slot < persistedModes.size()
                ? persistedModes.get(slot)
                : CraftingMode.NONE;
    }
}
