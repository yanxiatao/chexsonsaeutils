package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiLevelEmitterMenuTest {

    @Test
    void visibleSlotCountDefaultsToSingleSlot() {
        assertEquals(1, MultiLevelEmitterUtils.calculateVisibleSlotCount(0, 64));
        assertEquals(1, MultiLevelEmitterUtils.calculateVisibleSlotCount(1, 64));
    }

    @Test
    void visibleSlotCountMatchesEnabledSlotCountAfterExpansion() {
        assertEquals(2, MultiLevelEmitterUtils.calculateVisibleSlotCount(2, 64));
        assertEquals(11, MultiLevelEmitterUtils.calculateVisibleSlotCount(11, 64));
    }

    @Test
    void visibleSlotCountClampsToCapacity() {
        assertEquals(63, MultiLevelEmitterUtils.calculateVisibleSlotCount(63, 64));
        assertEquals(64, MultiLevelEmitterUtils.calculateVisibleSlotCount(128, 64));
        assertEquals(4, MultiLevelEmitterUtils.calculateVisibleSlotCount(10, 4));
    }

    @Test
    void rapidThresholdEditsPersistLatestValueAfterNormalization() {
        Map<Integer, Long> thresholds = new HashMap<>();
        thresholds.put(1, 5L);
        thresholds.put(1, 21L);
        thresholds.put(1, 34L);

        Map<Integer, Long> normalized = MultiLevelEmitterPart.normalizeThresholdsForSlotCount(thresholds, 3);

        assertEquals(1L, normalized.get(0));
        assertEquals(34L, normalized.get(1));
        assertEquals(1L, normalized.get(2));
    }

    @Test
    void reopenNormalizationBackfillsMissingComparisonAndRelationDefaults() {
        List<MultiLevelEmitterPart.ComparisonMode> normalizedModes =
                MultiLevelEmitterPart.normalizeComparisonModesForSlotCount(
                        List.of(MultiLevelEmitterPart.ComparisonMode.LESS_THAN),
                        3);
        List<MultiLevelEmitterPart.LogicRelation> normalizedRelations =
                MultiLevelEmitterPart.normalizeRelationsForSlotCount(
                        new ArrayList<>(List.of(MultiLevelEmitterPart.LogicRelation.AND)),
                        3);

        assertEquals(MultiLevelEmitterPart.ComparisonMode.LESS_THAN, normalizedModes.get(0));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL, normalizedModes.get(1));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL, normalizedModes.get(2));
        assertEquals(MultiLevelEmitterPart.LogicRelation.AND, normalizedRelations.get(0));
        assertEquals(MultiLevelEmitterPart.LogicRelation.OR, normalizedRelations.get(1));
    }

    @Test
    void resetAllComparisonsSetsEverySlotToGreaterOrEqual() {
        List<MultiLevelEmitterPart.ComparisonMode> modes =
                MultiLevelEmitterMenu.resetAllComparisonsToGreaterOrEqual(4);
        assertEquals(4, modes.size());
        for (MultiLevelEmitterPart.ComparisonMode mode : modes) {
            assertEquals(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL, mode);
        }
    }

    @Test
    void deleteOnlyTrailingEmptySlots() {
        boolean[] configured = new boolean[]{true, false, true, false, false};
        assertEquals(false, MultiLevelEmitterMenu.canDeleteTrailingEmptySlot(configured, 1));
        assertEquals(true, MultiLevelEmitterMenu.canDeleteTrailingEmptySlot(configured, 3));
        assertEquals(true, MultiLevelEmitterMenu.canDeleteTrailingEmptySlot(configured, 4));
    }

    @Test
    void slotGrowthRemainsUnlimitedInVisiblePolicy() {
        assertEquals(2, MultiLevelEmitterMenu.nextVisibleSlotCountUnlimited(0));
        assertEquals(7, MultiLevelEmitterMenu.nextVisibleSlotCountUnlimited(6));
        assertEquals(129, MultiLevelEmitterMenu.nextVisibleSlotCountUnlimited(128));
    }

    @Test
    void compactionReindexesConfiguredSlotsDeterministically() {
        int[] compacted = MultiLevelEmitterMenu.compactConfiguredSlots(new int[]{0, 3, 7});
        assertEquals(0, compacted[0]);
        assertEquals(1, compacted[1]);
        assertEquals(2, compacted[2]);
    }

    @Test
    void runtimeMenuExposesEditableRuntimeStateForVisibleRows() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 5L, 1, 9L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.LESS_THAN
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.OR)
        );

        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        assertEquals(2, menu.configuredSlotCount());
        assertEquals(2, menu.visibleSlotCount());
        assertEquals(0, menu.markedSlotCount());
        assertEquals(64, menu.totalSlotCapacity());
        assertEquals(true, menu.isSlotConfigured(0));
        assertEquals(true, menu.isSlotConfigured(1));
        assertEquals(5L, menu.thresholdForSlot(0));
        assertEquals(9L, menu.thresholdForSlot(1));
        assertEquals(1L, menu.thresholdForSlot(2));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL, menu.comparisonModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.LESS_THAN, menu.comparisonModeForSlot(1));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL, menu.comparisonModeForSlot(2));
    }

    @Test
    void menuConfiguredSlotCountStillUsesRuntimeAuthority() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        menu.setConfiguredSlotCount(3);

        assertEquals(3, runtime.configuredItemCount());
        assertEquals(3, menu.configuredSlotCount());

        menu.setConfiguredSlotCount(999);

        assertEquals(MultiLevelEmitterMenu.SLOT_CAPACITY, runtime.configuredItemCount());
        assertEquals(MultiLevelEmitterMenu.SLOT_CAPACITY, menu.configuredSlotCount());
    }

    private static MultiLevelEmitterRuntimePart newRuntimePart() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            MultiLevelEmitterRuntimePart runtime =
                    (MultiLevelEmitterRuntimePart) allocateInstance.invoke(unsafe, MultiLevelEmitterRuntimePart.class);
            runtime.applyConfiguration(1, null, null, null);
            runtime.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
            return runtime;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to allocate runtime part test instance", exception);
        }
    }
}
