package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterUtils;
import git.chexson.chexsonsaeutils.support.TestKeySupport.DummyKey;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static git.chexson.chexsonsaeutils.support.TestKeySupport.newRegistryFriendlyByteBuf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterLifecycleIntegrationTest {
    private static final String NBT_CONFIGURED_ITEM_COUNT = "configured_item_count";
    private static final String NBT_REPORTING_VALUES = "reportingValues";
    private static final String NBT_COMPARISON_MODES = "comparison_modes";
    private static final String NBT_LOGIC_RELATIONS = "logic_relations";
    private static final String NBT_MATCHING_MODES = "matching_modes";
    private static final String NBT_CRAFTING_MODES = "crafting_modes";

    @Test
    void emit03FlowUsesDedicatedRuntimePartInsteadOfFallbackBinding() throws IOException {
        String itemSource = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterItem.java"
        ));
        String runtimeSource = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java"
        ));

        assertTrue(itemSource.contains("MultiLevelEmitterRuntimePart.class"),
                "EMIT-03 flow must stay on dedicated runtime part path");
        assertFalse(itemSource.contains("StorageLevelEmitterPart.class"),
                "EMIT-03 flow must not regress to StorageLevelEmitterPart fallback binding");
        assertTrue(runtimeSource.contains("readFromNBT"),
                "runtime part must own readFromNBT behavior for lifecycle reload");
        assertTrue(runtimeSource.contains("writeToNBT"),
                "runtime part must own writeToNBT behavior for lifecycle reload");
        assertTrue(runtimeSource.contains("applyConfiguration"),
                "runtime part must keep multi-slot runtime state on the concrete feature path");
        assertTrue(runtimeSource.contains("publishForMenuOpen"),
                "runtime part activation should publish menu runtime binding context");
        assertTrue(runtimeSource.contains("MultiLevelEmitterMenu.openMenu"),
                "runtime part activation must open the dedicated MultiLevelEmitter menu");
        assertFalse(runtimeSource.contains("super.onPartActivate"),
                "runtime part activation must not delegate back to AE2 native level emitter menu");
        assertTrue(runtimeSource.contains("consumePublishedMenuRuntime"),
                "runtime part activation must clear transient runtime binding context after open");
        assertTrue(runtimeSource.contains("writeToStream"),
                "runtime part must synchronize multi-slot state to the client stream");
        assertTrue(runtimeSource.contains("readFromStream"),
                "runtime part must restore multi-slot state from the client stream");
        assertTrue(runtimeSource.contains("writeRuntimeSnapshot(data)"),
                "runtime part must reuse the shared runtime snapshot contract for NBT writes");
        assertTrue(runtimeSource.contains("readRuntimeSnapshot(data, false)"),
                "runtime part must reuse the shared runtime snapshot contract for NBT reads");
        assertTrue(runtimeSource.contains("protected boolean isLevelEmitterOn()"),
                "runtime part must override AE2 single-slot output evaluation");
        assertTrue(runtimeSource.contains("protected void configureWatchers()"),
                "runtime part must rebind watchers for multi-slot tracking");
        assertTrue(runtimeSource.contains("private static final String NBT_CONFIG = \"config\""),
                "runtime part must persist multi-slot config on AE2's standard config key");
        assertTrue(runtimeSource.contains("writeToChildTag(data, NBT_CONFIG, effectiveProvider)"),
                "runtime part must write marked item config into NBT through the provider-aware contract");
        assertTrue(runtimeSource.contains("readFromChildTag(data, NBT_CONFIG, effectiveProvider)"),
                "runtime part must restore marked item config from NBT through the provider-aware contract");
        assertFalse(runtimeSource.contains("multi_config"),
                "runtime part must not retain legacy config-key compatibility branches");
        assertTrue(runtimeSource.contains("expression_text"),
                "runtime part must persist applied expression text");
        assertTrue(runtimeSource.contains("expression_ownership"),
                "runtime part must persist expression ownership state");
        assertTrue(runtimeSource.contains("applyExpressionFromUi"),
                "runtime part must expose the authoritative apply-expression entry point");

        String menuSource = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java"
        ));
        assertTrue(menuSource.contains("MenuLocators.writeToPacket"),
                "custom menu open path must serialize a part locator to bind the correct emitter instance");
        assertTrue(menuSource.contains("MenuLocators.readFromPacket"),
                "network-opened menu must resolve the runtime part from the serialized locator");
        assertTrue(menuSource.contains("ACTION_APPLY_EXPRESSION"),
                "menu must register the apply-expression action key");
        assertTrue(menuSource.contains("ExpressionPayload"),
                "menu must expose the expression payload type for client actions");
        assertTrue(menuSource.contains("applyConfiguredSlotCountMutation(configuredSlots, true)"),
                "client slot-count changes must stay on the RuntimeMenu mutation boundary");
        assertTrue(menuSource.contains("applyConfiguredSlotCountMutation(configuredSlots, false)"),
                "server-applied slot-count actions must share the same RuntimeMenu authority path");
    }

    @Test
    void placeOpenNetworkLifecycleTransitionsAreDeterministic() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 5L, 1, 2L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        runtime.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);

        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 1L), true));
        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 2L), true));
        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L, 2L), false));
    }

    @Test
    void runtimeConfigurationNormalizesMissingMetadata() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                3,
                Map.of(0, 4L),
                List.of(MultiLevelEmitterPart.ComparisonMode.LESS_THAN),
                List.of()
        );

        assertEquals(Map.of(0, 4L, 1, 1L, 2, 1L), runtime.thresholds());
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.LESS_THAN,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                runtime.comparisonModes()
        );
        assertEquals(
                List.of(MultiLevelEmitterPart.LogicRelation.OR, MultiLevelEmitterPart.LogicRelation.OR),
                runtime.relations()
        );
    }

    @Test
    void reloadPathKeepsStrictFuzzyFallbackSafe() {
        MultiLevelEmitterPart.MatchingMode fuzzy =
                MultiLevelEmitterPart.MatchingMode.fromPersisted("FUZZY");
        MultiLevelEmitterPart.MatchingMode invalid =
                MultiLevelEmitterPart.MatchingMode.fromPersisted("UNKNOWN");

        assertTrue(fuzzy == MultiLevelEmitterPart.MatchingMode.IGNORE_ALL);
        assertTrue(invalid == MultiLevelEmitterPart.MatchingMode.STRICT);
    }

    @Test
    void strictFuzzyToggleCanTriggerImmediateRecompute() {
        boolean changed = MultiLevelEmitterPart.shouldRecomputeAfterMatchingModeChange(
                MultiLevelEmitterPart.MatchingMode.STRICT,
                MultiLevelEmitterPart.MatchingMode.IGNORE_ALL
        );
        boolean unchanged = MultiLevelEmitterPart.shouldRecomputeAfterMatchingModeChange(
                MultiLevelEmitterPart.MatchingMode.STRICT,
                MultiLevelEmitterPart.MatchingMode.STRICT
        );

        assertTrue(changed);
        assertNotEquals(changed, unchanged);
    }

    @Test
    void placeOpenConfigureComparisonToggleRerunsRuntimeEvaluationPath() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.applyConfiguredSlotCount(menu, 1);
        MultiLevelEmitterScreen.commitThresholdFromInput(menu, 0, 5L, 64L, true, false);
        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L), true));

        MultiLevelEmitterScreen.toggleComparisonMode(menu, 0);
        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L), true));
        assertTrue(runtime.evaluateConfiguredOutput(List.of(4L), true));
    }

    @Test
    void publishedRuntimeContextIsConsumedByFirstNetworkMenuOpen() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        MultiLevelEmitterMenu.registerRuntimeBindingResolver(
                (inventory, networkData) -> MultiLevelEmitterRuntimePart.consumePublishedMenuRuntime()
        );
        try {
            MultiLevelEmitterRuntimePart.publishForMenuOpen(runtime);

            MultiLevelEmitterMenu.RuntimeMenu firstMenu = MultiLevelEmitterMenuTestHarness.fromNetwork(null, null);
            assertTrue(firstMenu.hasRuntimePartBinding());
            MultiLevelEmitterScreen.applyConfiguredSlotCount(firstMenu, 1);
            assertEquals(1, runtime.configuredItemCount());

            MultiLevelEmitterMenu.RuntimeMenu secondMenu =
                    MultiLevelEmitterMenuTestHarness.fromNetwork(null, null);
            assertFalse(secondMenu.hasRuntimePartBinding());
        } finally {
            MultiLevelEmitterMenu.registerRuntimeBindingResolver((inventory, networkData) -> null);
            MultiLevelEmitterRuntimePart.consumePublishedMenuRuntime();
        }
    }

    @Test
    void reloadSnapshotKeepsTwoConfiguredSlotsEditable() {
        MultiLevelEmitterRuntimePart beforeReload = newRuntimePart();
        beforeReload.applyConfiguration(
                2,
                Map.of(0, 4L, 1, 7L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.LESS_THAN
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.OR)
        );

        CompoundTag persisted = new CompoundTag();
        persisted.putInt(NBT_CONFIGURED_ITEM_COUNT, 2);
        MultiLevelEmitterPart.writeThresholdsToNbt(
                beforeReload.thresholds(),
                persisted,
                NBT_REPORTING_VALUES
        );
        MultiLevelEmitterUtils.writeComparisonModesToNBT(
                beforeReload.comparisonModes(),
                persisted,
                NBT_COMPARISON_MODES
        );
        MultiLevelEmitterUtils.writeLogicRelationsToNBT(
                beforeReload.relations(),
                persisted,
                NBT_LOGIC_RELATIONS
        );

        MultiLevelEmitterRuntimePart afterReload = newRuntimePart();
        afterReload.applyConfiguration(
                persisted.getInt(NBT_CONFIGURED_ITEM_COUNT),
                MultiLevelEmitterPart.readThresholdsFromNbt(
                        persisted,
                        NBT_REPORTING_VALUES
                ),
                MultiLevelEmitterUtils.readComparisonModesFromNBT(
                        persisted,
                        NBT_COMPARISON_MODES
                ),
                MultiLevelEmitterUtils.readLogicRelationsFromNBT(
                        persisted,
                        NBT_LOGIC_RELATIONS
                )
        );
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(afterReload);
        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);

        assertEquals(2, state.configuredSlots());
        assertEquals(0, state.markedSlots());
        assertEquals(2, state.visibleSlots());
        assertEquals(4L, state.slots().get(0).threshold());
        assertEquals(7L, state.slots().get(1).threshold());
        assertEquals(MultiLevelEmitterPart.ComparisonMode.LESS_THAN, state.slots().get(1).comparisonMode());
    }

    @Test
    void reloadSnapshotPreservesRequestedCardModesUntilActualRemoval() {
        CompoundTag snapshot = createCardModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_75
                ),
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                )
        );

        MultiLevelEmitterRuntimePart restored = newCapabilityRuntimePart(false, false);
        readRuntimeSnapshot(restored, snapshot);
        setConfiguredKey(restored, 1, new DummyKey("target", "stone", 0, 0));

        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, restored.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, restored.matchingModeForSlot(1));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, restored.craftingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, restored.craftingModeForSlot(1));
        assertTrue(restored.getEmitableItems().isEmpty());

        CompoundTag preservedSnapshot = new CompoundTag();
        writeRuntimeSnapshot(restored, preservedSnapshot);
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_75
                ),
                MultiLevelEmitterUtils.readMatchingModesFromNBT(preservedSnapshot, NBT_MATCHING_MODES)
        );
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                ),
                MultiLevelEmitterUtils.readCraftingModesFromNBT(preservedSnapshot, NBT_CRAFTING_MODES)
        );

        CapabilityAwareRuntimePart capabilityAware = (CapabilityAwareRuntimePart) restored;
        capabilityAware.setInstalledCards(true, true);
        reconcileCardModes(capabilityAware);
        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, restored.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT, restored.craftingModeForSlot(1));

        capabilityAware.setInstalledCards(false, false);
        reconcileCardModes(capabilityAware);
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, restored.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, restored.craftingModeForSlot(1));
    }

    @Test
    void configuredSlotCountPersistsAcrossRuntimeSnapshotRoundTrip() throws IOException {
        MultiLevelEmitterRuntimePart beforeRoundTrip = newRuntimePart();
        beforeRoundTrip.applyConfiguration(
                3,
                Map.of(0, 4L, 1, 7L, 2, 9L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.LESS_THAN,
                        MultiLevelEmitterPart.ComparisonMode.EQUAL
                ),
                List.of(
                        MultiLevelEmitterPart.LogicRelation.AND,
                        MultiLevelEmitterPart.LogicRelation.OR
                )
        );
        beforeRoundTrip.applyExpressionFromUi("#1 AND (#2 OR #3)");

        CompoundTag snapshot = new CompoundTag();
        writeRuntimeSnapshot(beforeRoundTrip, snapshot);
        MultiLevelEmitterRuntimePart restoredFromSnapshot = newRuntimePart();
        readRuntimeSnapshot(restoredFromSnapshot, snapshot);

        var stream = newRegistryFriendlyByteBuf();
        beforeRoundTrip.writeToStream(stream);
        MultiLevelEmitterRuntimePart restoredFromStream = newRuntimePart();
        assertTrue(restoredFromStream.readFromStream(stream));

        assertRoundTripRuntimeState(restoredFromSnapshot);
        assertRoundTripRuntimeState(restoredFromStream);

        String runtimeSource = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java"
        ));
        assertTrue(runtimeSource.contains("configured_item_count"),
                "runtime snapshot persistence must stay keyed by configured_item_count");
        assertTrue(runtimeSource.contains("writeToStream("),
                "runtime part must keep the stream snapshot write path");
        assertTrue(runtimeSource.contains("readFromStream("),
                "runtime part must keep the stream snapshot read path");
        assertTrue(runtimeSource.contains("trimConfigInventoryToConfiguredSlots("),
                "runtime part must keep shrink cleanup on the authoritative runtime path");
    }

    @Test
    void streamRoundTripPreservesRequestedCardModesUntilActualRemoval() {
        MultiLevelEmitterRuntimePart beforeStreamSync = newCapabilityRuntimePart(true, true);
        readRuntimeSnapshot(
                beforeStreamSync,
                createCardModeSnapshot(
                        List.of(
                                MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                                MultiLevelEmitterPart.MatchingMode.STRICT
                        ),
                        List.of(
                                MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT,
                                MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING
                        )
                )
        );

        var stream = newRegistryFriendlyByteBuf();
        beforeStreamSync.writeToStream(stream);

        MultiLevelEmitterRuntimePart restoredFromStream = newCapabilityRuntimePart(false, false);
        assertTrue(restoredFromStream.readFromStream(stream));
        setConfiguredKey(restoredFromStream, 0, new DummyKey("target", "stream", 0, 0));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, restoredFromStream.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, restoredFromStream.matchingModeForSlot(1));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, restoredFromStream.craftingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, restoredFromStream.craftingModeForSlot(1));
        assertTrue(restoredFromStream.getEmitableItems().isEmpty());

        CompoundTag preservedSnapshot = new CompoundTag();
        writeRuntimeSnapshot(restoredFromStream, preservedSnapshot);
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                        MultiLevelEmitterPart.MatchingMode.STRICT
                ),
                MultiLevelEmitterUtils.readMatchingModesFromNBT(preservedSnapshot, NBT_MATCHING_MODES)
        );
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT,
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING
                ),
                MultiLevelEmitterUtils.readCraftingModesFromNBT(preservedSnapshot, NBT_CRAFTING_MODES)
        );

        CapabilityAwareRuntimePart capabilityAware = (CapabilityAwareRuntimePart) restoredFromStream;
        capabilityAware.setInstalledCards(true, true);
        reconcileCardModes(capabilityAware);
        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, restoredFromStream.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING, restoredFromStream.craftingModeForSlot(1));

        capabilityAware.setInstalledCards(false, false);
        reconcileCardModes(capabilityAware);
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, restoredFromStream.matchingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, restoredFromStream.craftingModeForSlot(1));
    }

    void mixedCraftingModesPersistAcrossSnapshotAndStreamRoundTrip() {
        MultiLevelEmitterRuntimePart beforeSnapshotRoundTrip = newCapabilityRuntimePart(false, true);
        beforeSnapshotRoundTrip.applyConfiguration(
                3,
                Map.of(0, 5L, 1, 1L, 2, 1L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(
                        MultiLevelEmitterPart.LogicRelation.AND,
                        MultiLevelEmitterPart.LogicRelation.AND
                )
        );
        readRuntimeSnapshot(
                beforeSnapshotRoundTrip,
                createCardModeSnapshot(
                        List.of(
                                MultiLevelEmitterPart.MatchingMode.STRICT,
                                MultiLevelEmitterPart.MatchingMode.STRICT,
                                MultiLevelEmitterPart.MatchingMode.STRICT
                        ),
                        List.of(
                                MultiLevelEmitterPart.CraftingMode.NONE,
                                MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                                MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                        )
                )
        );

        CompoundTag snapshot = new CompoundTag();
        writeRuntimeSnapshot(beforeSnapshotRoundTrip, snapshot);
        MultiLevelEmitterRuntimePart restoredFromSnapshot = newCapabilityRuntimePart(false, true);
        readRuntimeSnapshot(restoredFromSnapshot, snapshot);

        MultiLevelEmitterRuntimePart beforeStreamRoundTrip = newCapabilityRuntimePart(false, true);
        beforeStreamRoundTrip.applyConfiguration(
                3,
                Map.of(0, 5L, 1, 1L, 2, 1L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(
                        MultiLevelEmitterPart.LogicRelation.AND,
                        MultiLevelEmitterPart.LogicRelation.AND
                )
        );
        readRuntimeSnapshot(
                beforeStreamRoundTrip,
                createCardModeSnapshot(
                        List.of(
                                MultiLevelEmitterPart.MatchingMode.STRICT,
                                MultiLevelEmitterPart.MatchingMode.STRICT,
                                MultiLevelEmitterPart.MatchingMode.STRICT
                        ),
                        List.of(
                                MultiLevelEmitterPart.CraftingMode.NONE,
                                MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                                MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                        )
                )
        );

        beforeStreamRoundTrip.applyExpressionFromUi("#1 AND #2 AND #3");

        var stream = newRegistryFriendlyByteBuf();
        beforeStreamRoundTrip.writeToStream(stream);
        MultiLevelEmitterRuntimePart restoredFromStream = newCapabilityRuntimePart(false, true);
        assertTrue(restoredFromStream.readFromStream(stream));

        assertMixedCraftingRoundTripModes(restoredFromSnapshot);
        assertMixedCraftingRoundTripModes(restoredFromStream);
        assertEquals("#1 AND #2 AND #3", restoredFromStream.appliedExpressionText());

        MultiLevelEmitterMenu.RuntimeMenu reopenedMenu =
                MultiLevelEmitterMenuTestHarness.detachedForRuntime(restoredFromStream);
        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(reopenedMenu);
        assertTrue(state.slots().get(0).showCraftingControl());
        assertEquals("OFF", state.slots().get(0).craftingShortLabel());
        assertEquals("REQ", state.slots().get(1).craftingShortLabel());
        assertEquals("SUP", state.slots().get(2).craftingShortLabel());
    }

    private static void assertRoundTripRuntimeState(MultiLevelEmitterRuntimePart runtime) {
        assertEquals(3, runtime.configuredItemCount());
        assertEquals(Map.of(0, 4L, 1, 7L, 2, 9L), runtime.thresholds());
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.LESS_THAN,
                        MultiLevelEmitterPart.ComparisonMode.EQUAL
                ),
                runtime.comparisonModes()
        );
        assertEquals(
                List.of(
                        MultiLevelEmitterPart.LogicRelation.AND,
                        MultiLevelEmitterPart.LogicRelation.OR
                ),
                runtime.relations()
        );
        assertEquals("#1 AND (#2 OR #3)", runtime.appliedExpressionText());
    }

    private static void assertMixedCraftingRoundTripModes(MultiLevelEmitterRuntimePart runtime) {
        assertEquals(3, runtime.configuredItemCount());
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, runtime.craftingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING, runtime.craftingModeForSlot(1));
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT, runtime.craftingModeForSlot(2));
    }

    private static void writeRuntimeSnapshot(MultiLevelEmitterRuntimePart runtime, CompoundTag snapshot) {
        try {
            Method method = MultiLevelEmitterRuntimePart.class.getDeclaredMethod("writeRuntimeSnapshot", CompoundTag.class);
            method.setAccessible(true);
            method.invoke(runtime, snapshot);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to write runtime snapshot", exception);
        }
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
            throw new AssertionError("Unable to read runtime snapshot", exception);
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

    private static void setConfiguredKey(MultiLevelEmitterRuntimePart runtime, int slot, AEKey key) {
        runtime.getConfig().setStack(slot, key == null ? null : new GenericStack(key, 1));
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

    private static CompoundTag createCardModeSnapshot(
            List<MultiLevelEmitterPart.MatchingMode> matchingModes,
            List<MultiLevelEmitterPart.CraftingMode> craftingModes
    ) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt(NBT_CONFIGURED_ITEM_COUNT, Math.max(matchingModes.size(), craftingModes.size()));
        MultiLevelEmitterUtils.writeMatchingModesToNBT(matchingModes, snapshot, NBT_MATCHING_MODES);
        MultiLevelEmitterUtils.writeCraftingModesToNBT(craftingModes, snapshot, NBT_CRAFTING_MODES);
        return snapshot;
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
