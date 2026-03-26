package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterPartTest {

    @Test
    void defaultsBackfillThresholdAndComparisonForMissingSlotMetadata() {
        Map<Integer, Long> thresholds = new HashMap<>();
        thresholds.put(0, 7L);

        Map<Integer, Long> normalizedThresholds =
                MultiLevelEmitterPart.normalizeThresholdsForSlotCount(thresholds, 2);
        List<MultiLevelEmitterPart.ComparisonMode> normalizedModes =
                MultiLevelEmitterPart.normalizeComparisonModesForSlotCount(List.of(), 2);

        assertEquals(7L, normalizedThresholds.get(0));
        assertEquals(1L, normalizedThresholds.get(1));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL, normalizedModes.get(0));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL, normalizedModes.get(1));
    }

    @Test
    void supportsAllComparisonOperatorsDeterministically() {
        assertTrue(MultiLevelEmitterPart.evaluateComparison(5, 5, MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL));
        assertTrue(MultiLevelEmitterPart.evaluateComparison(4, 5, MultiLevelEmitterPart.ComparisonMode.LESS_THAN));
        assertTrue(MultiLevelEmitterPart.evaluateComparison(5, 5, MultiLevelEmitterPart.ComparisonMode.EQUAL));
        assertTrue(MultiLevelEmitterPart.evaluateComparison(4, 5, MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL));
        assertFalse(MultiLevelEmitterPart.evaluateComparison(6, 5, MultiLevelEmitterPart.ComparisonMode.LESS_THAN));
    }

    @Test
    void evaluatesPerSlotConditionsIndependentlyWithOrRelation() {
        boolean finalResult = MultiLevelEmitterPart.evaluateFinalResult(
                List.of(false, true),
                List.of(MultiLevelEmitterPart.LogicRelation.OR));

        assertTrue(finalResult);
    }

    @Test
    void emptyConfigIsOnWhenNetworkIsActive() {
        boolean state = MultiLevelEmitterPart.resolveEmitterState(
                true,
                0,
                false,
                RedstoneMode.LOW_SIGNAL);

        assertTrue(state);
    }

    @Test
    void networkDownOverrideForcesOff() {
        boolean state = MultiLevelEmitterPart.resolveEmitterState(
                false,
                0,
                true,
                RedstoneMode.HIGH_SIGNAL);

        assertFalse(state);
    }

    @Test
    void legacyInvalidNbtValuesFallbackToSafeDefaults() {
        CompoundTag tag = new CompoundTag();
        ListTag relationList = new ListTag();
        relationList.add(StringTag.valueOf("INVALID_RELATION"));
        tag.put("logic_relations", relationList);

        ListTag modeList = new ListTag();
        modeList.add(StringTag.valueOf("INVALID_MODE"));
        tag.put("comparison_modes", modeList);

        List<MultiLevelEmitterPart.LogicRelation> relations =
                MultiLevelEmitterUtils.readLogicRelationsFromNBT(tag, "logic_relations");
        List<MultiLevelEmitterPart.ComparisonMode> modes =
                MultiLevelEmitterUtils.readComparisonModesFromNBT(tag, "comparison_modes");

        assertEquals(MultiLevelEmitterPart.LogicRelation.OR, relations.get(0));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL, modes.get(0));
    }

    @Test
    void regressionMissingRelationsNowDefaultsToOrInsteadOfAnd() {
        boolean finalResult = MultiLevelEmitterPart.evaluateFinalResult(
                List.of(false, true),
                List.of());

        assertTrue(finalResult);
    }

    @Test
    void matchingModeCycleFollowsAe2AlignedOrder() {
        List<MultiLevelEmitterPart.MatchingMode> actualCycle = List.of(
                MultiLevelEmitterPart.nextMatchingMode(MultiLevelEmitterPart.MatchingMode.STRICT),
                MultiLevelEmitterPart.nextMatchingMode(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL),
                MultiLevelEmitterPart.nextMatchingMode(MultiLevelEmitterPart.MatchingMode.PERCENT_99),
                MultiLevelEmitterPart.nextMatchingMode(MultiLevelEmitterPart.MatchingMode.PERCENT_75),
                MultiLevelEmitterPart.nextMatchingMode(MultiLevelEmitterPart.MatchingMode.PERCENT_50),
                MultiLevelEmitterPart.nextMatchingMode(MultiLevelEmitterPart.MatchingMode.PERCENT_25)
        );

        assertIterableEquals(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_99,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_75,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_50,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_25,
                        MultiLevelEmitterPart.MatchingMode.STRICT
                ),
                actualCycle
        );
    }

    @Test
    void legacyAndInvalidMatchingMetadataResolveSafely() {
        MultiLevelEmitterPart.MatchingMode migrated =
                MultiLevelEmitterPart.MatchingMode.fromPersisted("FUZZY");
        MultiLevelEmitterPart.MatchingMode parsed =
                MultiLevelEmitterPart.MatchingMode.fromPersisted("INVALID_MODE");
        MultiLevelEmitterPart.MatchingMode effective =
                MultiLevelEmitterPart.resolveMatchingMode(MultiLevelEmitterPart.MatchingMode.PERCENT_50, false);

        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, migrated);
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, parsed);
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, effective);
    }

    @Test
    void invalidCraftingMetadataFallsBackToNone() {
        MultiLevelEmitterPart.CraftingMode parsed =
                MultiLevelEmitterPart.CraftingMode.fromPersisted("INVALID_MODE");
        MultiLevelEmitterPart.CraftingMode effective =
                MultiLevelEmitterPart.resolveCraftingMode(
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        false
                );

        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, parsed);
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, effective);
    }

    @Test
    void configuredSlotEvaluationUsesDeterministicDefaults() {
        Map<Integer, Long> thresholds = MultiLevelEmitterPart.normalizeThresholdsForSlotCount(Map.of(0, 4L), 2);
        List<MultiLevelEmitterPart.ComparisonMode> comparisons =
                MultiLevelEmitterPart.normalizeComparisonModesForSlotCount(List.of(), 2);
        boolean result = MultiLevelEmitterPart.evaluateConfiguredSlots(
                List.of(4L, 1L),
                thresholds,
                comparisons,
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        assertTrue(result);
    }
}
