package git.chexson.chexsonsaeutils.parts.automation;

import appeng.api.config.RedstoneMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MultiLevelEmitterPart {

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
        FUZZY;

        public static MatchingMode fromPersisted(String value) {
            if (value == null || value.isBlank()) {
                return STRICT;
            }
            try {
                return MatchingMode.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return STRICT;
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
            MatchingMode requested = persistedModes != null && slot < persistedModes.size()
                    ? persistedModes.get(slot)
                    : MatchingMode.STRICT;
            modes.add(resolveMatchingMode(requested, fuzzyCapabilityAvailable));
        }
        return modes;
    }

    public static MatchingMode resolveMatchingMode(MatchingMode requested, boolean fuzzyCapabilityAvailable) {
        MatchingMode effective = requested == null ? MatchingMode.STRICT : requested;
        if (effective == MatchingMode.FUZZY && !fuzzyCapabilityAvailable) {
            return MatchingMode.STRICT;
        }
        return effective;
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
        if (slotResults == null || slotResults.isEmpty()) {
            return false;
        }

        boolean result = Boolean.TRUE.equals(slotResults.get(0));
        for (int slot = 1; slot < slotResults.size(); slot++) {
            LogicRelation relation = relations != null && slot - 1 < relations.size()
                    ? relations.get(slot - 1)
                    : LogicRelation.OR;
            boolean next = Boolean.TRUE.equals(slotResults.get(slot));
            if (relation == LogicRelation.AND) {
                result = result && next;
            } else {
                result = result || next;
            }
        }
        return result;
    }

    public static boolean evaluateConfiguredSlots(
            List<Long> actualValues,
            Map<Integer, Long> thresholds,
            List<ComparisonMode> comparisons,
            List<LogicRelation> relations
    ) {
        if (actualValues == null || actualValues.isEmpty()) {
            return false;
        }
        List<Boolean> slotResults = evaluateSlotComparisons(actualValues, thresholds, comparisons);
        return evaluateFinalResult(slotResults, relations);
    }

    public static List<Boolean> evaluateSlotComparisons(
            List<Long> actualValues,
            Map<Integer, Long> thresholds,
            List<ComparisonMode> comparisons
    ) {
        if (actualValues == null || actualValues.isEmpty()) {
            return List.of();
        }
        Map<Integer, Long> safeThresholds = thresholds == null ? Map.of() : thresholds;
        List<ComparisonMode> safeComparisons = comparisons == null ? List.of() : comparisons;
        List<Boolean> slotResults = new ArrayList<>(actualValues.size());
        for (int slot = 0; slot < actualValues.size(); slot++) {
            long actual = actualValues.get(slot);
            long threshold = sanitizeThreshold(safeThresholds.getOrDefault(slot, 1L));
            ComparisonMode mode = slot < safeComparisons.size()
                    ? safeComparisons.get(slot)
                    : ComparisonMode.GREATER_OR_EQUAL;
            slotResults.add(evaluateComparison(actual, threshold, mode));
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
        boolean evaluationResult = evaluateConfiguredSlots(
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
}
