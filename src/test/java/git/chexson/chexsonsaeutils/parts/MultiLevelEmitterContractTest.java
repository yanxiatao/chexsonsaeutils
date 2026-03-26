package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterContractTest {

    @Test
    void networkDownOverridesEvaluationResult() {
        assertFalse(MultiLevelEmitterPart.resolveEmitterState(false, 2, true, RedstoneMode.HIGH_SIGNAL));
    }

    @Test
    void emptyConfigIsOnWhenNetworkActive() {
        assertTrue(MultiLevelEmitterPart.resolveEmitterState(true, 0, false, RedstoneMode.HIGH_SIGNAL));
    }

    @Test
    void fuzzyFallsBackToStrictWhenCapabilityMissing() {
        MultiLevelEmitterPart.MatchingMode requested = MultiLevelEmitterPart.MatchingMode.IGNORE_ALL;
        MultiLevelEmitterPart.MatchingMode effective =
                MultiLevelEmitterPart.resolveMatchingMode(requested, false);
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, effective);
    }

    @Test
    void matchingModesNormalizePerSlot() {
        List<MultiLevelEmitterPart.MatchingMode> normalized =
                MultiLevelEmitterPart.normalizeMatchingModesForSlotCount(
                        List.of(
                                MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                                MultiLevelEmitterPart.MatchingMode.STRICT,
                                MultiLevelEmitterPart.MatchingMode.PERCENT_75
                        ),
                        3,
                        false
                );
        assertEquals(3, normalized.size());
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, normalized.get(0));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, normalized.get(1));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, normalized.get(2));
    }

    @Test
    void matchingModeChangeTriggersRecompute() {
        assertTrue(MultiLevelEmitterPart.shouldRecomputeAfterMatchingModeChange(
                MultiLevelEmitterPart.MatchingMode.STRICT,
                MultiLevelEmitterPart.MatchingMode.IGNORE_ALL));
        assertFalse(MultiLevelEmitterPart.shouldRecomputeAfterMatchingModeChange(
                MultiLevelEmitterPart.MatchingMode.STRICT,
                MultiLevelEmitterPart.MatchingMode.STRICT));
    }

    @Test
    void craftingFallsBackToNoneWhenCapabilityMissing() {
        MultiLevelEmitterPart.CraftingMode requested = MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT;
        MultiLevelEmitterPart.CraftingMode effective =
                MultiLevelEmitterPart.resolveCraftingMode(requested, false);

        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, effective);
    }

    @Test
    void craftingModesNormalizePerSlotWhenCardIsMissing() {
        List<MultiLevelEmitterPart.CraftingMode> normalized =
                MultiLevelEmitterPart.normalizeCraftingModesForSlotCount(
                        List.of(
                                MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                                MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                        ),
                        3,
                        false
                );

        assertEquals(3, normalized.size());
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, normalized.get(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, normalized.get(1));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, normalized.get(2));
    }
}
