package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
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
        MultiLevelEmitterPart.MatchingMode requested = MultiLevelEmitterPart.MatchingMode.FUZZY;
        MultiLevelEmitterPart.MatchingMode effective =
                MultiLevelEmitterPart.resolveMatchingMode(requested, false);
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, effective);
    }

    @Test
    void matchingModesNormalizePerSlot() {
        List<MultiLevelEmitterPart.MatchingMode> normalized =
                MultiLevelEmitterPart.normalizeMatchingModesForSlotCount(
                        List.of(MultiLevelEmitterPart.MatchingMode.STRICT),
                        3,
                        true
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
                MultiLevelEmitterPart.MatchingMode.FUZZY));
        assertFalse(MultiLevelEmitterPart.shouldRecomputeAfterMatchingModeChange(
                MultiLevelEmitterPart.MatchingMode.STRICT,
                MultiLevelEmitterPart.MatchingMode.STRICT));
    }
}
