package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterPersistenceIntegrationTest {

    @Test
    void thresholdComparisonRelationAndMatchingRoundTripIsStable() {
        CompoundTag root = new CompoundTag();

        Map<Integer, Long> thresholds = MultiLevelEmitterPart.normalizeThresholdsForSlotCount(
                Map.of(0, 5L, 1, 9L), 2);
        List<MultiLevelEmitterPart.ComparisonMode> comparisons = List.of(
                MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                MultiLevelEmitterPart.ComparisonMode.LESS_THAN
        );
        List<MultiLevelEmitterPart.LogicRelation> relations = List.of(MultiLevelEmitterPart.LogicRelation.OR);
        List<MultiLevelEmitterPart.MatchingMode> matching = List.of(
                MultiLevelEmitterPart.MatchingMode.STRICT,
                MultiLevelEmitterPart.MatchingMode.IGNORE_ALL
        );

        MultiLevelEmitterPart.writeThresholdsToNbt(thresholds, root, "reportingValues");
        MultiLevelEmitterUtils.writeComparisonModesToNBT(comparisons, root, "comparison_modes");
        MultiLevelEmitterUtils.writeLogicRelationsToNBT(relations, root, "logic_relations");
        MultiLevelEmitterUtils.writeMatchingModesToNBT(matching, root, "matching_modes");

        Map<Integer, Long> loadedThresholds = MultiLevelEmitterPart.readThresholdsFromNbt(root, "reportingValues");
        List<MultiLevelEmitterPart.ComparisonMode> loadedComparisons =
                MultiLevelEmitterUtils.readComparisonModesFromNBT(root, "comparison_modes");
        List<MultiLevelEmitterPart.LogicRelation> loadedRelations =
                MultiLevelEmitterUtils.readLogicRelationsFromNBT(root, "logic_relations");
        List<MultiLevelEmitterPart.MatchingMode> loadedMatching =
                MultiLevelEmitterUtils.readMatchingModesFromNBT(root, "matching_modes");

        assertEquals(thresholds, loadedThresholds);
        assertEquals(comparisons, loadedComparisons);
        assertEquals(relations, loadedRelations);
        assertEquals(matching, loadedMatching);
    }

    @Test
    void matchingAndCraftingModesRoundTripIsStable() {
        CompoundTag root = new CompoundTag();
        List<MultiLevelEmitterPart.MatchingMode> matching = List.of(
                MultiLevelEmitterPart.MatchingMode.PERCENT_50,
                MultiLevelEmitterPart.MatchingMode.STRICT
        );
        List<MultiLevelEmitterPart.CraftingMode> crafting = List.of(
                MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
        );

        MultiLevelEmitterUtils.writeMatchingModesToNBT(matching, root, "matching_modes");
        MultiLevelEmitterUtils.writeCraftingModesToNBT(crafting, root, "crafting_modes");

        List<MultiLevelEmitterPart.MatchingMode> loadedMatching =
                MultiLevelEmitterUtils.readMatchingModesFromNBT(root, "matching_modes");
        List<MultiLevelEmitterPart.CraftingMode> loadedCrafting =
                MultiLevelEmitterUtils.readCraftingModesFromNBT(root, "crafting_modes");

        assertEquals(matching, loadedMatching);
        assertEquals(crafting, loadedCrafting);
    }

    @Test
    void invalidMatchingModeFallsBackToStrictOnLoad() {
        CompoundTag root = new CompoundTag();
        root.putString("invalid", "FUZZY_NOT_SUPPORTED");
        MultiLevelEmitterPart.MatchingMode parsed =
                MultiLevelEmitterPart.MatchingMode.fromPersisted(root.getString("invalid"));
        MultiLevelEmitterPart.MatchingMode effective =
                MultiLevelEmitterPart.resolveMatchingMode(parsed, false);

        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, effective);
        assertTrue(effective != MultiLevelEmitterPart.MatchingMode.IGNORE_ALL);
    }

    @Test
    void invalidCraftingModeFallsBackToNoneOnLoad() {
        CompoundTag root = new CompoundTag();
        root.putString("invalid", "CRAFTING_NOT_SUPPORTED");
        MultiLevelEmitterPart.CraftingMode parsed =
                MultiLevelEmitterPart.CraftingMode.fromPersisted(root.getString("invalid"));
        MultiLevelEmitterPart.CraftingMode effective =
                MultiLevelEmitterPart.resolveCraftingMode(parsed, false);

        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, effective);
        assertTrue(effective != MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT);
    }

    @Test
    void requestedModesSurviveCapabilityUnavailableReloadUntilActualCardRemoval() {
        CompoundTag snapshot = new CompoundTag();
        MultiLevelEmitterUtils.writeMatchingModesToNBT(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_75
                ),
                snapshot,
                "matching_modes"
        );
        MultiLevelEmitterUtils.writeCraftingModesToNBT(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                ),
                snapshot,
                "crafting_modes"
        );
        snapshot.putInt("configured_item_count", 2);

        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, false);
        readRuntimeSnapshot(runtime, snapshot);

        CompoundTag preserved = new CompoundTag();
        writeRuntimeSnapshot(runtime, preserved);
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_75
                ),
                MultiLevelEmitterUtils.readMatchingModesFromNBT(preserved, "matching_modes")
        );
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                ),
                MultiLevelEmitterUtils.readCraftingModesFromNBT(preserved, "crafting_modes")
        );

        runtime.setInstalledCards(true, true);
        reconcileCardModes(runtime);
        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, runtime.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT, runtime.craftingModeForSlot(1));

        runtime.setInstalledCards(false, false);
        reconcileCardModes(runtime);

        CompoundTag cleared = new CompoundTag();
        writeRuntimeSnapshot(runtime, cleared);
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.STRICT,
                        MultiLevelEmitterPart.MatchingMode.STRICT
                ),
                MultiLevelEmitterUtils.readMatchingModesFromNBT(cleared, "matching_modes")
        );
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.NONE,
                        MultiLevelEmitterPart.CraftingMode.NONE
                ),
                MultiLevelEmitterUtils.readCraftingModesFromNBT(cleared, "crafting_modes")
        );
    }

    private static CapabilityAwareRuntimePart newCapabilityRuntimePart(boolean fuzzyInstalled, boolean craftingInstalled) {
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

    private static void readRuntimeSnapshot(MultiLevelEmitterRuntimePart runtime, CompoundTag snapshot) {
        try {
            Method method = MultiLevelEmitterRuntimePart.class.getDeclaredMethod(
                    "readRuntimeSnapshot",
                    CompoundTag.class,
                    net.minecraft.core.HolderLookup.Provider.class,
                    boolean.class
            );
            method.setAccessible(true);
            method.invoke(runtime, snapshot, RegistryAccess.EMPTY, false);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read runtime snapshot", exception);
        }
    }

    private static void writeRuntimeSnapshot(MultiLevelEmitterRuntimePart runtime, CompoundTag snapshot) {
        try {
            Method method = MultiLevelEmitterRuntimePart.class.getDeclaredMethod(
                    "writeRuntimeSnapshot",
                    CompoundTag.class,
                    net.minecraft.core.HolderLookup.Provider.class
            );
            method.setAccessible(true);
            method.invoke(runtime, snapshot, RegistryAccess.EMPTY);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to write runtime snapshot", exception);
        }
    }

    private static void reconcileCardModes(MultiLevelEmitterRuntimePart runtime) {
        try {
            Method method = MultiLevelEmitterRuntimePart.class.getDeclaredMethod("reconcileCardModes");
            method.setAccessible(true);
            method.invoke(runtime);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to reconcile card modes", exception);
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

