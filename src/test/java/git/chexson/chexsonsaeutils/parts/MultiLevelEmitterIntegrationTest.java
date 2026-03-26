package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import com.google.common.collect.ImmutableSet;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterIntegrationTest {
    private static final String NBT_CONFIGURED_ITEM_COUNT = "configured_item_count";
    private static final String NBT_REPORTING_VALUES = "reportingValues";
    private static final String NBT_COMPARISON_MODES = "comparison_modes";
    private static final String NBT_LOGIC_RELATIONS = "logic_relations";
    private static final String NBT_MATCHING_MODES = "matching_modes";
    private static final String NBT_CRAFTING_MODES = "crafting_modes";

    @Test
    void runtimePathIsAnchoredAndRejectsPassThroughRegression() throws IOException {
        String itemSource = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterItem.java"
        ));
        String runtimeSource = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java"
        ));

        assertTrue(itemSource.contains("MultiLevelEmitterRuntimePart.class"),
                "runtime feature path must stay bound to MultiLevelEmitterRuntimePart");
        assertFalse(itemSource.contains("StorageLevelEmitterPart.class"),
                "runtime feature path must not regress to StorageLevelEmitterPart fallback");
        assertTrue(runtimeSource.contains("evaluateConfiguredOutput("),
                "runtime part must expose a concrete evaluation entry point");
        assertTrue(runtimeSource.contains("evaluateSlotComparisonsWithParticipation"),
                "storage-path regression coverage must keep the participation-aware comparison helper");
        assertTrue(runtimeSource.contains("isRequesting("),
                "runtime part must read AE2 crafting request state directly");
        assertTrue(runtimeSource.contains("findFuzzy("),
                "runtime part must aggregate fuzzy slots through KeyCounter.findFuzzy(...)");
    }

    @Test
    void runtimeEvaluationUsesPerSlotThresholdsAndNetworkPrecedence() {
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

        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 1L), true));
        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 2L), true));
        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L, 2L), false));
    }

    @Test
    void thresholdCommitTransitionChangesRuntimeOutputDeterministically() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 6L, 1, 3L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );

        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L, 2L), true));

        runtime.applyConfiguration(
                2,
                Map.of(0, 5L, 1, 3L),
                runtime.comparisonModes(),
                runtime.relations()
        );

        assertTrue(runtime.evaluateConfiguredOutput(List.of(5L, 2L), true));
    }

    @Test
    void runtimeReloadConfigRoundTripKeepsOutputDeterministic() {
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

        List<Long> observedValues = List.of(4L, 9L);
        boolean before = beforeReload.evaluateConfiguredOutput(observedValues, true);
        boolean after = afterReload.evaluateConfiguredOutput(observedValues, true);

        assertEquals(beforeReload.thresholds(), afterReload.thresholds());
        assertEquals(beforeReload.comparisonModes(), afterReload.comparisonModes());
        assertEquals(beforeReload.relations(), afterReload.relations());
        assertTrue(before);
        assertEquals(before, after);
    }

    @Test
    void runtimeMenuReopenRetainsTwoConfiguredSlotsWithDistinctState() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 4L, 1, 7L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.OR)
        );

        MultiLevelEmitterMenu.RuntimeMenu firstMenu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);
        MultiLevelEmitterScreen.RuntimeScreenState firstState = MultiLevelEmitterScreen.snapshotState(firstMenu);
        MultiLevelEmitterMenu.RuntimeMenu reopenedMenu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);
        MultiLevelEmitterScreen.RuntimeScreenState reopenedState = MultiLevelEmitterScreen.snapshotState(reopenedMenu);

        assertEquals(2, firstState.configuredSlots());
        assertEquals(2, reopenedState.configuredSlots());
        assertEquals(0, reopenedState.markedSlots());
        assertEquals(2, reopenedState.visibleSlots());
        assertEquals(firstState.slots().get(0).threshold(), reopenedState.slots().get(0).threshold());
        assertEquals(firstState.slots().get(1).threshold(), reopenedState.slots().get(1).threshold());
        assertEquals(firstState.slots().get(1).comparisonMode(), reopenedState.slots().get(1).comparisonMode());
    }

    @Test
    void changingOneSlotLeavesTheOtherSlotStateIntact() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 6L, 1, 3L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.commitThresholdFromInput(menu, 0, 5L, 64L, true, false);
        MultiLevelEmitterScreen.toggleComparisonMode(menu, 0);

        assertEquals(5L, runtime.thresholds().get(0));
        assertEquals(3L, runtime.thresholds().get(1));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.LESS_THAN, runtime.comparisonModes().get(0));
        assertEquals(MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL, runtime.comparisonModes().get(1));
        assertFalse(runtime.evaluateConfiguredOutput(List.of(5L, 3L), true));
        assertTrue(runtime.evaluateConfiguredOutput(List.of(4L, 2L), true));
    }

    @Test
    void readObservedValuesMixesStrictAndPerSlotFuzzyAggregation() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(true);
        runtime.applyConfiguration(
                2,
                Map.of(0, 1L, 1, 1L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        readRuntimeSnapshot(runtime, createMatchingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.STRICT,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_75
                )
        ));

        DummyKey strictKey = new DummyKey("strict", "strict", 0, 0);
        DummyKey fuzzyFilter = new DummyKey("durable", "filter", 10, 100);
        DummyKey fuzzySibling = new DummyKey("durable", "sibling", 15, 100);
        DummyKey fuzzyExcluded = new DummyKey("durable", "excluded", 80, 100);

        setConfiguredKey(runtime, 0, strictKey);
        setConfiguredKey(runtime, 1, fuzzyFilter);

        KeyCounter inventory = new KeyCounter();
        inventory.add(strictKey, 3L);
        inventory.add(fuzzyFilter, 5L);
        inventory.add(fuzzySibling, 7L);
        inventory.add(fuzzyExcluded, 11L);

        assertEquals(List.of(3L, 12L), readObservedValues(runtime, gridWithInventory(inventory)));
    }

    @Test
    void readObservedValuesKeepsEmptyPreconfiguredFuzzySlotsInert() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(true);
        runtime.applyConfiguration(
                2,
                Map.of(0, 1L, 1, 1L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        readRuntimeSnapshot(runtime, createMatchingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.STRICT,
                        MultiLevelEmitterPart.MatchingMode.IGNORE_ALL
                )
        ));

        DummyKey strictKey = new DummyKey("strict", "strict", 0, 0);
        DummyKey fuzzyCandidate = new DummyKey("durable", "candidate", 20, 100);

        setConfiguredKey(runtime, 0, strictKey);

        KeyCounter inventory = new KeyCounter();
        inventory.add(strictKey, 4L);
        inventory.add(fuzzyCandidate, 99L);

        assertEquals(List.of(4L, 0L), readObservedValues(runtime, gridWithInventory(inventory)));
    }

    @Test
    void configureWatchersOnlyWatchesAllForMarkedNonStrictSlots() {
        WatcherAwareRuntimePart runtime = newWatcherAwareRuntimePart(true);
        runtime.applyConfiguration(
                2,
                Map.of(0, 1L, 1, 1L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        readRuntimeSnapshot(runtime, createMatchingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.MatchingMode.STRICT,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_50
                )
        ));

        DummyKey strictKey = new DummyKey("strict", "strict", 0, 0);
        DummyKey fuzzyKey = new DummyKey("durable", "filter", 45, 100);
        setConfiguredKey(runtime, 0, strictKey);

        RecordingStackWatcher storageWatcher = new RecordingStackWatcher();
        RecordingStackWatcher craftingWatcher = new RecordingStackWatcher();
        attachWatchers(runtime, storageWatcher, craftingWatcher);

        runtime.invokeConfigureWatchers();

        assertFalse(storageWatcher.watchAll);
        assertEquals(Set.of(strictKey), storageWatcher.addedKeys);

        setConfiguredKey(runtime, 1, fuzzyKey);
        runtime.invokeConfigureWatchers();

        assertTrue(storageWatcher.watchAll);
        assertTrue(storageWatcher.addedKeys.isEmpty());
    }

    @Test
    void emitToCraftExposureDedupesDuplicateMarkedKeys() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.applyConfiguration(3, null, null, null);
        readRuntimeSnapshot(runtime, createCraftingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT,
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING
                )
        ));

        DummyKey duplicatedKey = new DummyKey("target", "shared", 0, 0);
        setConfiguredKey(runtime, 0, duplicatedKey);
        setConfiguredKey(runtime, 1, duplicatedKey);
        setConfiguredKey(runtime, 2, duplicatedKey);

        assertEquals(Set.of(duplicatedKey), runtime.getEmitableItems());
    }

    @Test
    void configureWatchersAddsMarkedRequestAndSupplyKeysToCraftingWatcher() {
        WatcherAwareRuntimePart runtime = newWatcherAwareRuntimePart(false, true);
        runtime.applyConfiguration(3, null, null, null);
        readRuntimeSnapshot(runtime, createCraftingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.NONE,
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                )
        ));

        DummyKey storageKey = new DummyKey("stored", "strict", 0, 0);
        DummyKey emitWhileKey = new DummyKey("crafting", "active", 0, 0);
        DummyKey emitToCraftKey = new DummyKey("crafting", "target", 0, 0);
        setConfiguredKey(runtime, 0, storageKey);
        setConfiguredKey(runtime, 1, emitWhileKey);
        setConfiguredKey(runtime, 2, emitToCraftKey);

        RecordingStackWatcher storageWatcher = new RecordingStackWatcher();
        RecordingStackWatcher craftingWatcher = new RecordingStackWatcher();
        attachWatchers(runtime, storageWatcher, craftingWatcher);

        runtime.invokeConfigureWatchers();

        assertFalse(storageWatcher.watchAll);
        assertEquals(Set.of(storageKey), storageWatcher.addedKeys);
        assertFalse(craftingWatcher.watchAll);
        assertEquals(Set.of(emitWhileKey, emitToCraftKey), craftingWatcher.addedKeys);
    }

    @Test
    void runtimeEvaluationCombinesStorageAndEmitWhileCraftingSlots() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.applyConfiguration(
                2,
                Map.of(0, 5L, 1, 99L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        readRuntimeSnapshot(runtime, createCraftingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.NONE,
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING
                )
        ));
        runtime.applyExpressionFromUi("#1 AND #2");

        DummyKey storedKey = new DummyKey("stored", "ingot", 0, 0);
        DummyKey craftingKey = new DummyKey("crafted", "gear", 0, 0);
        setConfiguredKey(runtime, 0, storedKey);
        setConfiguredKey(runtime, 1, craftingKey);

        KeyCounter inventory = new KeyCounter();
        inventory.add(storedKey, 5L);

        assertTrue(runtime.evaluateConfiguredOutput(gridWithInventoryAndRequests(inventory, Set.of(craftingKey)), true));
        assertFalse(runtime.evaluateConfiguredOutput(gridWithInventoryAndRequests(inventory, Set.of()), true));
    }

    @Test
    void repeatedEmitWhileCraftingSlotsStillRespectOverallRelations() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.applyConfiguration(
                2,
                Map.of(0, 1L, 1, 1L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        readRuntimeSnapshot(runtime, createCraftingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING
                )
        ));
        runtime.applyExpressionFromUi("#1 AND #2");

        DummyKey leftKey = new DummyKey("crafted", "left", 0, 0);
        DummyKey rightKey = new DummyKey("crafted", "right", 0, 0);
        setConfiguredKey(runtime, 0, leftKey);
        setConfiguredKey(runtime, 1, rightKey);

        KeyCounter inventory = new KeyCounter();

        assertFalse(runtime.evaluateConfiguredOutput(gridWithInventoryAndRequests(inventory, Set.of(leftKey)), true));
        assertTrue(runtime.evaluateConfiguredOutput(gridWithInventoryAndRequests(inventory, Set.of(leftKey, rightKey)), true));
    }

    @Test
    void mixedOffReqSupExpressionKeepsBothCraftingRowsParticipating() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.applyConfiguration(
                3,
                Map.of(0, 5L, 1, 1L, 2, 1L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(
                        MultiLevelEmitterPart.LogicRelation.OR,
                        MultiLevelEmitterPart.LogicRelation.OR
                )
        );
        readRuntimeSnapshot(runtime, createCraftingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.NONE,
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                )
        ));
        runtime.applyExpressionFromUi("#1 OR #2 OR #3");

        DummyKey storageKey = new DummyKey("stored", "ingot", 0, 0);
        DummyKey requestKey = new DummyKey("crafting", "gear", 0, 0);
        DummyKey providerKey = new DummyKey("crafting", "plate", 0, 0);
        setConfiguredKey(runtime, 0, storageKey);
        setConfiguredKey(runtime, 1, requestKey);
        setConfiguredKey(runtime, 2, providerKey);

        KeyCounter inventory = new KeyCounter();

        assertFalse(runtime.evaluateConfiguredOutput(gridWithInventoryAndRequests(inventory, Set.of()), true));
        assertTrue(runtime.evaluateConfiguredOutput(gridWithInventoryAndRequests(inventory, Set.of(requestKey)), true));
        assertTrue(runtime.evaluateConfiguredOutput(gridWithInventoryAndRequests(inventory, Set.of(providerKey)), true));
    }

    @Test
    void emitToCraftRedstoneOnlyTurnsOnWhileRequested() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.applyConfiguration(
                2,
                Map.of(0, 1L, 1, 1L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.AND)
        );
        readRuntimeSnapshot(runtime, createCraftingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT,
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING
                )
        ));

        DummyKey providerOnlyKey = new DummyKey("crafted", "provider", 0, 0);
        setConfiguredKey(runtime, 0, providerOnlyKey);

        assertFalse(runtime.evaluateConfiguredOutput(gridWithInventoryAndRequests(new KeyCounter(), Set.of()), true));
        assertTrue(runtime.evaluateConfiguredOutput(
                gridWithInventoryAndRequests(new KeyCounter(), Set.of(providerOnlyKey)),
                true
        ));
    }

    @Test
    void emitToCraftExposureUpdatesImmediatelyWhenModeOrMarkedKeyChanges() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.applyConfiguration(1, null, null, null);
        readRuntimeSnapshot(runtime, createCraftingModeSnapshot(
                List.of(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT)
        ));

        DummyKey targetKey = new DummyKey("target", "only", 0, 0);
        setConfiguredKey(runtime, 0, targetKey);
        assertEquals(Set.of(targetKey), runtime.getEmitableItems());

        runtime.cycleCraftingModeFromUi(0);
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, runtime.craftingModeForSlot(0));
        assertTrue(runtime.getEmitableItems().isEmpty());

        runtime.cycleCraftingModeFromUi(0);
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING, runtime.craftingModeForSlot(0));
        assertTrue(runtime.getEmitableItems().isEmpty());

        runtime.cycleCraftingModeFromUi(0);
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT, runtime.craftingModeForSlot(0));
        assertEquals(Set.of(targetKey), runtime.getEmitableItems());

        setConfiguredKey(runtime, 0, null);
        assertTrue(runtime.getEmitableItems().isEmpty());
    }

    @Test
    void streamRoundTripPreservesMixedCraftingRuntimeSemantics() {
        CapabilityAwareRuntimePart beforeRoundTrip = newCapabilityRuntimePart(false, true);
        beforeRoundTrip.applyConfiguration(
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
        readRuntimeSnapshot(beforeRoundTrip, createCraftingModeSnapshot(
                List.of(
                        MultiLevelEmitterPart.CraftingMode.NONE,
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                )
        ));

        FriendlyByteBuf stream = new FriendlyByteBuf(Unpooled.buffer());
        beforeRoundTrip.writeToStream(stream);

        CapabilityAwareRuntimePart restored = newCapabilityRuntimePart(false, true);
        assertTrue(restored.readFromStream(stream));
        DummyKey storedKey = new DummyKey("stored", "ingot", 0, 0);
        DummyKey requestKey = new DummyKey("crafting", "gear", 0, 0);
        DummyKey providerKey = new DummyKey("crafting", "plate", 0, 0);
        setConfiguredKey(restored, 0, storedKey);
        setConfiguredKey(restored, 1, requestKey);
        setConfiguredKey(restored, 2, providerKey);
        restored.applyExpressionFromUi("#1 AND #2 AND #3");
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, restored.craftingModeForSlot(0));
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING, restored.craftingModeForSlot(1));
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT, restored.craftingModeForSlot(2));
        assertEquals(Set.of(providerKey), restored.getEmitableItems());
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

    private static CapabilityAwareRuntimePart newCapabilityRuntimePart(boolean fuzzyInstalled) {
        return newCapabilityRuntimePart(fuzzyInstalled, false);
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

    private static WatcherAwareRuntimePart newWatcherAwareRuntimePart(boolean fuzzyInstalled) {
        return newWatcherAwareRuntimePart(fuzzyInstalled, false);
    }

    private static WatcherAwareRuntimePart newWatcherAwareRuntimePart(boolean fuzzyInstalled, boolean craftingInstalled) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            WatcherAwareRuntimePart runtime =
                    (WatcherAwareRuntimePart) allocateInstance.invoke(unsafe, WatcherAwareRuntimePart.class);
            runtime.setInstalledCards(fuzzyInstalled, craftingInstalled);
            runtime.applyConfiguration(1, null, null, null);
            runtime.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
            return runtime;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to allocate watcher-aware runtime part test instance", exception);
        }
    }

    private static List<Long> readObservedValues(MultiLevelEmitterRuntimePart runtime, IGrid grid) {
        try {
            Method method = MultiLevelEmitterRuntimePart.class.getDeclaredMethod("readObservedValues", IGrid.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Long> observed = (List<Long>) method.invoke(runtime, grid);
            return observed;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read observed values", exception);
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
            throw new AssertionError("Unable to read runtime snapshot for integration test instance", exception);
        }
    }

    private static CompoundTag createMatchingModeSnapshot(List<MultiLevelEmitterPart.MatchingMode> matchingModes) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt(NBT_CONFIGURED_ITEM_COUNT, matchingModes.size());
        MultiLevelEmitterUtils.writeMatchingModesToNBT(matchingModes, snapshot, NBT_MATCHING_MODES);
        return snapshot;
    }

    private static CompoundTag createCraftingModeSnapshot(List<MultiLevelEmitterPart.CraftingMode> craftingModes) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt(NBT_CONFIGURED_ITEM_COUNT, craftingModes.size());
        MultiLevelEmitterUtils.writeCraftingModesToNBT(craftingModes, snapshot, NBT_CRAFTING_MODES);
        return snapshot;
    }

    private static void setConfiguredKey(MultiLevelEmitterRuntimePart runtime, int slot, AEKey key) {
        runtime.getConfig().setStack(slot, key == null ? null : new GenericStack(key, 1));
    }

    private static void attachWatchers(
            MultiLevelEmitterRuntimePart runtime,
            RecordingStackWatcher storageWatcher,
            RecordingStackWatcher craftingWatcher
    ) {
        try {
            Field storageWatcherField =
                    MultiLevelEmitterRuntimePart.class.getSuperclass().getDeclaredField("storageWatcher");
            storageWatcherField.setAccessible(true);
            storageWatcherField.set(runtime, storageWatcher);

            Field craftingWatcherField =
                    MultiLevelEmitterRuntimePart.class.getSuperclass().getDeclaredField("craftingWatcher");
            craftingWatcherField.setAccessible(true);
            craftingWatcherField.set(runtime, craftingWatcher);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to attach watcher test doubles", exception);
        }
    }

    private static IGrid gridWithInventory(KeyCounter inventory) {
        return gridWithInventoryAndRequests(inventory, Set.of());
    }

    private static IGrid gridWithInventoryAndRequests(KeyCounter inventory, Set<AEKey> requestingKeys) {
        IStorageService storageService = (IStorageService) Proxy.newProxyInstance(
                IStorageService.class.getClassLoader(),
                new Class<?>[]{IStorageService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCachedInventory" -> inventory;
                    case "getInventory" -> null;
                    default -> defaultValue(method.getReturnType());
                }
        );
        ICraftingService craftingService = new RecordingCraftingService(requestingKeys);
        return (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(),
                new Class<?>[]{IGrid.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStorageService" -> storageService;
                    case "getCraftingService" -> craftingService;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class RecordingCraftingService implements ICraftingService {
        private final Set<AEKey> requestedKeys;

        private RecordingCraftingService(Set<AEKey> requestedKeys) {
            this.requestedKeys = new HashSet<>(requestedKeys);
        }

        @Override
        public Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
            return List.of();
        }

        @Override
        public void refreshNodeCraftingProvider(IGridNode node) {
        }

        @Override
        public AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter) {
            return null;
        }

        @Override
        public Future<ICraftingPlan> beginCraftingCalculation(
                Level level,
                ICraftingSimulationRequester simRequester,
                AEKey craftWhat,
                long amount,
                CalculationStrategy strategy
        ) {
            return null;
        }

        @Override
        public ICraftingSubmitResult submitJob(
                ICraftingPlan job,
                ICraftingRequester requestingMachine,
                ICraftingCPU target,
                boolean prioritizePower,
                IActionSource src
        ) {
            return null;
        }

        @Override
        public ImmutableSet<ICraftingCPU> getCpus() {
            return ImmutableSet.of();
        }

        @Override
        public boolean canEmitFor(AEKey what) {
            return false;
        }

        @Override
        public Set<AEKey> getCraftables(AEKeyFilter filter) {
            return Set.of();
        }

        @Override
        public boolean isRequesting(AEKey what) {
            return requestedKeys.contains(what);
        }

        @Override
        public long getRequestedAmount(AEKey what) {
            return requestedKeys.contains(what) ? 1L : 0L;
        }

        @Override
        public boolean isRequestingAny() {
            return !requestedKeys.isEmpty();
        }
    }

    private static class CapabilityAwareRuntimePart extends MultiLevelEmitterRuntimePart {
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

    private static final class WatcherAwareRuntimePart extends CapabilityAwareRuntimePart {
        private WatcherAwareRuntimePart() {
            super();
        }

        void invokeConfigureWatchers() {
            super.configureWatchers();
        }
    }

    private static final class RecordingStackWatcher implements IStackWatcher {
        private boolean watchAll;
        private final java.util.LinkedHashSet<AEKey> addedKeys = new java.util.LinkedHashSet<>();

        @Override
        public void setWatchAll(boolean watchAll) {
            this.watchAll = watchAll;
        }

        @Override
        public void add(AEKey stack) {
            addedKeys.add(stack);
        }

        @Override
        public void remove(AEKey stack) {
            addedKeys.remove(stack);
        }

        @Override
        public void reset() {
            watchAll = false;
            addedKeys.clear();
        }
    }

    private static final class DummyKeyType extends AEKeyType {
        private static final DummyKeyType INSTANCE = new DummyKeyType();

        private DummyKeyType() {
            super(Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:test")),
                    DummyKey.class,
                    Component.literal("Test"));
        }

        @Override
        public AEKey readFromPacket(FriendlyByteBuf input) {
            return null;
        }

        @Override
        public AEKey loadKeyFromTag(CompoundTag tag) {
            return null;
        }
    }

    private static final class DummyKey extends AEKey {
        private final String primaryKey;
        private final String variantId;
        private final int fuzzyValue;
        private final int fuzzyMaxValue;

        private DummyKey(String primaryKey, String variantId, int fuzzyValue, int fuzzyMaxValue) {
            this.primaryKey = primaryKey;
            this.variantId = variantId;
            this.fuzzyValue = fuzzyValue;
            this.fuzzyMaxValue = fuzzyMaxValue;
        }

        @Override
        public AEKeyType getType() {
            return DummyKeyType.INSTANCE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("primary", primaryKey);
            tag.putString("variant", variantId);
            tag.putInt("fuzzyValue", fuzzyValue);
            tag.putInt("fuzzyMaxValue", fuzzyMaxValue);
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return primaryKey;
        }

        @Override
        public int getFuzzySearchValue() {
            return fuzzyValue;
        }

        @Override
        public int getFuzzySearchMaxValue() {
            return fuzzyMaxValue;
        }

        @Override
        public ResourceLocation getId() {
            return Objects.requireNonNull(ResourceLocation.tryParse(
                    "chexsonsaeutils:" + primaryKey + "_" + variantId
            ));
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(primaryKey + ":" + variantId);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DummyKey dummyKey)) {
                return false;
            }
            return fuzzyValue == dummyKey.fuzzyValue
                    && fuzzyMaxValue == dummyKey.fuzzyMaxValue
                    && Objects.equals(primaryKey, dummyKey.primaryKey)
                    && Objects.equals(variantId, dummyKey.variantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(primaryKey, variantId, fuzzyValue, fuzzyMaxValue);
        }
    }
}
