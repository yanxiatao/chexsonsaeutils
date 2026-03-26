package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterUtils;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void runtimeMenuExposesUpgradeDerivedCapabilityAndCardModes() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(true, true);
        applyCardModeSnapshot(
                runtime,
                List.of(
                        MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                        MultiLevelEmitterPart.MatchingMode.STRICT
                ),
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                )
        );

        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        assertTrue(menu.hasFuzzyCardInstalled());
        assertTrue(menu.hasCraftingCardInstalled());
        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, menu.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, menu.matchingModeForSlot(1));
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING, menu.craftingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT, menu.craftingModeForSlot(1));
    }

    @Test
    void runtimeMenuCyclesMatchingModeThroughAuthoritativeRuntimeAction() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(true, false);
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        menu.cycleMatchingMode(0);
        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, runtime.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, menu.matchingModeForSlot(0));

        menu.cycleMatchingMode(0);
        assertEquals(MultiLevelEmitterPart.MatchingMode.PERCENT_99, runtime.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.MatchingMode.PERCENT_99, menu.matchingModeForSlot(0));

        menu.cycleMatchingMode(7);
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, menu.matchingModeForSlot(7));
    }

    @Test
    void runtimeMenuReopenObservesCollapsedCardModesWithoutCapabilities() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(false, false);
        applyCardModeSnapshot(
                runtime,
                List.of(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL),
                List.of(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT)
        );

        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        assertFalse(menu.hasFuzzyCardInstalled());
        assertFalse(menu.hasCraftingCardInstalled());
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, menu.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, menu.craftingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, menu.matchingModeForSlot(-1));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, menu.matchingModeForSlot(3));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, menu.craftingModeForSlot(3));
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

    private static MultiLevelEmitterRuntimePart newCapabilityRuntimePart(boolean fuzzyInstalled, boolean craftingInstalled) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            CapabilityAwareRuntimePart runtime =
                    (CapabilityAwareRuntimePart) allocateInstance.invoke(unsafe, CapabilityAwareRuntimePart.class);
            runtime.setInstalledCards(fuzzyInstalled, craftingInstalled);
            runtime.applyConfiguration(1, null, null, null);
            runtime.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
            return runtime;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to allocate capability-aware runtime part test instance", exception);
        }
    }

    private static void applyCardModeSnapshot(
            MultiLevelEmitterRuntimePart runtime,
            List<MultiLevelEmitterPart.MatchingMode> matchingModes,
            List<MultiLevelEmitterPart.CraftingMode> craftingModes
    ) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt("configured_item_count", Math.max(matchingModes.size(), craftingModes.size()));
        MultiLevelEmitterUtils.writeMatchingModesToNBT(matchingModes, snapshot, "matching_modes");
        MultiLevelEmitterUtils.writeCraftingModesToNBT(craftingModes, snapshot, "crafting_modes");
        readRuntimeSnapshot(runtime, snapshot);
    }

    private static void readRuntimeSnapshot(MultiLevelEmitterRuntimePart runtime, CompoundTag snapshot) {
        try {
            Method method = MultiLevelEmitterRuntimePart.class.getDeclaredMethod(
                    "readRuntimeSnapshot",
                    CompoundTag.class,
                    boolean.class
            );
            method.setAccessible(true);
            method.invoke(runtime, snapshot, false);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read runtime snapshot for menu test instance", exception);
        }
    }

    private static final class CapabilityAwareRuntimePart extends MultiLevelEmitterRuntimePart {
        private boolean fuzzyInstalled;
        private boolean craftingInstalled;

        private CapabilityAwareRuntimePart() {
            super(null);
        }

        void setInstalledCards(boolean fuzzyInstalled, boolean craftingInstalled) {
            this.fuzzyInstalled = fuzzyInstalled;
            this.craftingInstalled = craftingInstalled;
        }

        @Override
        public boolean hasFuzzyCardInstalled() {
            return fuzzyInstalled;
        }

        @Override
        public boolean hasCraftingCardInstalled() {
            return craftingInstalled;
        }
    }
}
