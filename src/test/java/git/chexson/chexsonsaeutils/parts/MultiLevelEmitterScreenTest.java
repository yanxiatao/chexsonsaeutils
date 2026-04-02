package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterUtils;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompiler;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionOwnership;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterScreenTest {

    @Test
    void thresholdCommitsOnEnterOrBlurOnly() {
        assertTrue(MultiLevelEmitterScreen.shouldCommitThresholdOnInput(true, false));
        assertTrue(MultiLevelEmitterScreen.shouldCommitThresholdOnInput(false, true));
        assertFalse(MultiLevelEmitterScreen.shouldCommitThresholdOnInput(false, false));
    }

    @Test
    void thresholdNormalizationClampsAndSanitizes() {
        assertEquals(0L, MultiLevelEmitterScreen.normalizeThresholdForCommit(-5L, 999L));
        assertEquals(12L, MultiLevelEmitterScreen.normalizeThresholdForCommit(12L, 999L));
        assertEquals(20L, MultiLevelEmitterScreen.normalizeThresholdForCommit(40L, 20L));
    }

    @Test
    void strictFuzzyTogglePreservesThresholdValue() {
        assertEquals(64L, MultiLevelEmitterScreen.preserveThresholdOnMatchingModeToggle(64L));
    }

    @Test
    void fuzzyShortLabelsMatchExactAe2ModeCycleContract() {
        assertEquals("STR", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.STRICT));
        assertEquals("*", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL));
        assertEquals("99%", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.PERCENT_99));
        assertEquals("75%", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.PERCENT_75));
        assertEquals("50%", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.PERCENT_50));
        assertEquals("25%", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.PERCENT_25));
    }

    @Test
    void configuredTotalLabelStaysSynchronized() {
        assertEquals("0/1", MultiLevelEmitterScreen.configuredOverTotalLabel(0, 1));
        assertEquals("3/8", MultiLevelEmitterScreen.configuredOverTotalLabel(3, 8));
    }

    @Test
    void configuredSlotCountChangesClampToMenuCapacity() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.applyConfiguredSlotCount(menu, 128);

        assertEquals(MultiLevelEmitterMenu.SLOT_CAPACITY, runtime.configuredItemCount());
    }

    @Test
    void runtimeScreenSnapshotShowsTwoConfiguredSlotsWithEditableRows() {
        MultiLevelEmitterRuntimePart runtime = newRuntimePart();
        runtime.applyConfiguration(
                2,
                Map.of(0, 8L, 1, 3L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL
                ),
                List.of(MultiLevelEmitterPart.LogicRelation.OR)
        );
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);

        assertEquals(2, state.configuredSlots());
        assertEquals(0, state.markedSlots());
        assertEquals(2, state.visibleSlots());
        assertEquals(64, state.totalSlots());
        assertEquals("#1 OR #2", state.appliedExpressionText());
        assertEquals(MultiLevelEmitterExpressionOwnership.AUTO, state.expressionOwnership());
        assertFalse(state.expressionInvalid());
        assertEquals(2, state.slots().stream().filter(MultiLevelEmitterScreen.SlotView::configured).count());
        assertEquals(true, state.slots().get(0).enabled());
        assertEquals(8L, state.slots().get(0).threshold());
        assertEquals("!=", MultiLevelEmitterScreen.comparisonModeLabel(state.slots().get(1).comparisonMode()));
        assertEquals("8", MultiLevelEmitterScreen.thresholdFieldValue(state.slots().get(0).threshold()));
    }

    @Test
    void runtimeScreenSnapshotKeepsExpressionEditableAndOffRowsUnlockedWithCraftingCard() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.applyConfiguration(2, Map.of(0, 8L, 1, 3L), null, List.of(MultiLevelEmitterPart.LogicRelation.OR));
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);

        assertFalse(state.expressionLocked());
        assertTrue(state.craftingLockTooltip().toString().contains(
                "gui.chexsonsaeutils.multi_level_emitter.crafting_lock_tooltip"
        ));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, state.slots().get(0).craftingMode());
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, state.slots().get(1).craftingMode());
        assertFalse(state.slots().get(0).thresholdLocked());
        assertFalse(state.slots().get(0).comparisonLocked());
        assertFalse(state.slots().get(1).thresholdLocked());
        assertFalse(state.slots().get(1).comparisonLocked());
    }

    @Test
    void runtimeScreenSnapshotKeepsMixedOffReqSupRowContractsVisibleUnderCraftingCard() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.applyConfiguration(
                3,
                Map.of(0, 8L, 1, 3L, 2, 21L),
                List.of(
                        MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL,
                        MultiLevelEmitterPart.ComparisonMode.LESS_THAN
                ),
                List.of(
                        MultiLevelEmitterPart.LogicRelation.OR,
                        MultiLevelEmitterPart.LogicRelation.AND
                )
        );
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);
        menu.cycleCraftingMode(1);
        menu.cycleCraftingMode(2);
        menu.cycleCraftingMode(2);

        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);

        assertFalse(state.expressionLocked());
        assertEquals(menu.appliedExpressionText(), state.appliedExpressionText());

        MultiLevelEmitterScreen.SlotView off = state.slots().get(0);
        MultiLevelEmitterScreen.SlotView req = state.slots().get(1);
        MultiLevelEmitterScreen.SlotView sup = state.slots().get(2);

        assertEquals("8", MultiLevelEmitterScreen.thresholdFieldValue(off.threshold()));
        assertEquals("3", MultiLevelEmitterScreen.thresholdFieldValue(req.threshold()));
        assertEquals("21", MultiLevelEmitterScreen.thresholdFieldValue(sup.threshold()));
        assertEquals(">=", MultiLevelEmitterScreen.comparisonModeLabel(off.comparisonMode()));
        assertEquals("!=", MultiLevelEmitterScreen.comparisonModeLabel(req.comparisonMode()));
        assertEquals("<", MultiLevelEmitterScreen.comparisonModeLabel(sup.comparisonMode()));
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, off.craftingMode());
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING, req.craftingMode());
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT, sup.craftingMode());
        assertFalse(off.thresholdLocked());
        assertFalse(off.comparisonLocked());
        assertTrue(req.thresholdLocked());
        assertTrue(req.comparisonLocked());
        assertTrue(sup.thresholdLocked());
        assertTrue(sup.comparisonLocked());
        assertTrue(off.showCraftingControl());
        assertTrue(req.showCraftingControl());
        assertTrue(sup.showCraftingControl());
        assertEquals("OFF", off.craftingShortLabel());
        assertEquals("REQ", req.craftingShortLabel());
        assertEquals("SUP", sup.craftingShortLabel());
    }

    @Test
    void runtimeScreenSnapshotClearsCraftingCardLocksWhenCardMissing() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(false, false);
        runtime.applyConfiguration(2, Map.of(0, 8L, 1, 3L), null, List.of(MultiLevelEmitterPart.LogicRelation.OR));
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);

        assertFalse(state.expressionLocked());
        assertFalse(state.slots().get(0).thresholdLocked());
        assertFalse(state.slots().get(0).comparisonLocked());
        assertFalse(state.slots().get(1).thresholdLocked());
        assertFalse(state.slots().get(1).comparisonLocked());
    }

    @Test
    void runtimeScreenSnapshotUnlocksReqRowsImmediatelyWhenCraftingCardIsRemovedWithoutReopeningMenu() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.applyConfiguration(2, Map.of(0, 8L, 1, 3L), null, List.of(MultiLevelEmitterPart.LogicRelation.OR));
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);
        menu.cycleCraftingMode(0);

        MultiLevelEmitterScreen.RuntimeScreenState lockedState = MultiLevelEmitterScreen.snapshotState(menu);
        runtime.setInstalledCards(false, false);
        MultiLevelEmitterScreen.RuntimeScreenState unlockedState = MultiLevelEmitterScreen.snapshotState(menu);

        assertFalse(lockedState.expressionLocked());
        assertTrue(lockedState.slots().get(0).thresholdLocked());
        assertTrue(lockedState.slots().get(0).comparisonLocked());
        assertFalse(lockedState.slots().get(1).thresholdLocked());
        assertFalse(lockedState.slots().get(1).comparisonLocked());
        assertFalse(unlockedState.expressionLocked());
        assertFalse(unlockedState.slots().get(0).thresholdLocked());
        assertFalse(unlockedState.slots().get(0).comparisonLocked());
        assertFalse(unlockedState.slots().get(1).thresholdLocked());
        assertFalse(unlockedState.slots().get(1).comparisonLocked());
    }

    @Test
    void toggleMatchingModeDelegatesToMenuAuthority() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(true);
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.toggleMatchingMode(menu, 0);

        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, menu.matchingModeForSlot(0));
    }

    @Test
    void toggleCraftingModeDelegatesToMenuAuthority() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(false, true);
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.toggleCraftingMode(menu, 0);

        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING, menu.craftingModeForSlot(0));
    }

    @Test
    void runtimeScreenSnapshotPreservesSavedFuzzyModesForUnmarkedSlots() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(true);
        applyMatchingModeSnapshot(
                runtime,
                List.of(
                        MultiLevelEmitterPart.MatchingMode.IGNORE_ALL,
                        MultiLevelEmitterPart.MatchingMode.PERCENT_75
                )
        );
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);
        MultiLevelEmitterScreen.SlotView first = state.slots().get(0);
        MultiLevelEmitterScreen.SlotView second = state.slots().get(1);

        assertEquals(0, state.markedSlots());
        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, first.matchingMode());
        assertTrue(first.showFuzzyControl());
        assertTrue(first.emphasizeFuzzyMode());
        assertEquals("*", first.fuzzyShortLabel());
        assertFuzzyTooltip(first.fuzzyTooltip(), "gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.ignore_all");
        assertEquals(MultiLevelEmitterPart.MatchingMode.PERCENT_75, second.matchingMode());
        assertTrue(second.emphasizeFuzzyMode());
        assertEquals("75%", second.fuzzyShortLabel());
        assertFuzzyTooltip(second.fuzzyTooltip(), "gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.percent_75");
    }

    @Test
    void runtimeScreenSnapshotHidesFuzzyControlsWhenCardMissing() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(false);
        applyMatchingModeSnapshot(runtime, List.of(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL));
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);
        MultiLevelEmitterScreen.SlotView slot = state.slots().get(0);

        assertFalse(slot.showFuzzyControl());
        assertFalse(slot.emphasizeFuzzyMode());
        assertEquals(MultiLevelEmitterPart.MatchingMode.STRICT, slot.matchingMode());
        assertEquals("STR", slot.fuzzyShortLabel());
        assertFuzzyTooltip(slot.fuzzyTooltip(), "gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.strict");
    }

    @Test
    void craftingShortLabelsMatchCompactRuntimeContract() {
        assertEquals("OFF", MultiLevelEmitterScreen.craftingShortLabel(MultiLevelEmitterPart.CraftingMode.NONE));
        assertEquals("REQ", MultiLevelEmitterScreen.craftingShortLabel(
                MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING
        ));
        assertEquals("SUP", MultiLevelEmitterScreen.craftingShortLabel(
                MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
        ));
    }

    @Test
    void runtimeScreenSnapshotProjectsCraftingModeStateAndHints() {
        CapabilityAwareRuntimePart runtime = newCapabilityRuntimePart(false, true);
        runtime.setDuplicateEmitToCraftSlots(List.of(2));
        applyCardModeSnapshot(
                runtime,
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
        );
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);
        MultiLevelEmitterScreen.SlotView off = state.slots().get(0);
        MultiLevelEmitterScreen.SlotView req = state.slots().get(1);
        MultiLevelEmitterScreen.SlotView sup = state.slots().get(2);

        assertTrue(off.showCraftingControl());
        assertFalse(off.emphasizeCraftingMode());
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, off.craftingMode());
        assertEquals("OFF", off.craftingShortLabel());
        assertFalse(off.duplicateEmitToCraftTarget());
        assertCraftingTooltipContains(
                off.craftingTooltip(),
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.disabled",
                true,
                false
        );

        assertTrue(req.showCraftingControl());
        assertTrue(req.emphasizeCraftingMode());
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING, req.craftingMode());
        assertEquals("REQ", req.craftingShortLabel());
        assertFalse(req.duplicateEmitToCraftTarget());
        assertCraftingTooltipContains(
                req.craftingTooltip(),
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.while_crafting",
                true,
                false
        );

        assertTrue(sup.showCraftingControl());
        assertTrue(sup.emphasizeCraftingMode());
        assertEquals(MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT, sup.craftingMode());
        assertEquals("SUP", sup.craftingShortLabel());
        assertTrue(sup.duplicateEmitToCraftTarget());
        assertCraftingTooltipContains(
                sup.craftingTooltip(),
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.to_craft",
                true,
                true
        );
    }

    @Test
    void runtimeScreenSnapshotHidesCraftingControlsWhenCraftingCardMissing() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(false, false);
        applyCardModeSnapshot(
                runtime,
                List.of(
                        MultiLevelEmitterPart.MatchingMode.STRICT,
                        MultiLevelEmitterPart.MatchingMode.STRICT
                ),
                List.of(
                        MultiLevelEmitterPart.CraftingMode.EMIT_WHILE_CRAFTING,
                        MultiLevelEmitterPart.CraftingMode.EMIT_TO_CRAFT
                )
        );
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);
        MultiLevelEmitterScreen.SlotView first = state.slots().get(0);
        MultiLevelEmitterScreen.SlotView second = state.slots().get(1);

        assertFalse(first.showCraftingControl());
        assertFalse(first.emphasizeCraftingMode());
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, first.craftingMode());
        assertEquals("OFF", first.craftingShortLabel());
        assertTrue(first.craftingTooltip().toString().contains("gui.chexsonsaeutils.multi_level_emitter.crafting_mode"));
        assertTrue(first.craftingTooltip().toString().contains(
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.disabled"
        ));
        assertTrue(first.craftingTooltip().toString().contains(
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.unmarked_hint"
        ));
        assertFalse(first.craftingTooltip().toString().contains(
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.duplicate_emit_to_craft"
        ));

        assertFalse(second.showCraftingControl());
        assertFalse(second.emphasizeCraftingMode());
        assertEquals(MultiLevelEmitterPart.CraftingMode.NONE, second.craftingMode());
        assertEquals("OFF", second.craftingShortLabel());
        assertTrue(second.craftingTooltip().toString().contains("gui.chexsonsaeutils.multi_level_emitter.crafting_mode"));
        assertTrue(second.craftingTooltip().toString().contains(
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.disabled"
        ));
        assertTrue(second.craftingTooltip().toString().contains(
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.unmarked_hint"
        ));
        assertFalse(second.craftingTooltip().toString().contains(
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.duplicate_emit_to_craft"
        ));
    }

    @Test
    void validateExpressionDraftDelegatesToSharedCompiler() {
        var expected = MultiLevelEmitterExpressionCompiler.compile("#1 OR #2", 2, slotIndex -> slotIndex == 0);
        var actual = MultiLevelEmitterScreen.validateExpressionDraft("#1 OR #2", 2, slotIndex -> slotIndex == 0);

        assertEquals(expected.rawText(), actual.rawText());
        assertEquals(expected.primaryDiagnostic(), actual.primaryDiagnostic());
        assertEquals(expected.isInvalid(), actual.isInvalid());
    }

    @Test
    void activeThresholdDraftSurvivesUnchangedRemoteSnapshot() {
        MultiLevelEmitterScreen.ThresholdSyncDecision decision =
                MultiLevelEmitterScreen.resolveThresholdSync("77", true, 64L, 64L);

        assertEquals("77", decision.fieldValue());
        assertTrue(decision.preserveLocalDraft());
    }

    @Test
    void activeThresholdDraftResetsWhenRemoteSameSlotChanges() {
        MultiLevelEmitterScreen.ThresholdSyncDecision decision =
                MultiLevelEmitterScreen.resolveThresholdSync("77", true, 64L, 80L);

        assertEquals("80", decision.fieldValue());
        assertFalse(decision.preserveLocalDraft());
    }

    @Test
    void scrollOffsetClampsToLastValidPageAfterRemoteShrink() {
        assertEquals(3, MultiLevelEmitterScreen.clampScrollOffset(5, 5, 2));
    }

    @Test
    void runtimeScreenSourceContainsRealControlsInsteadOfPlaceholderCopy() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java"
        ));

        assertTrue(source.contains("ThresholdEditBox"));
        assertTrue(source.contains("Button.builder"));
        assertTrue(source.contains("fuzzyModeButtons"));
        assertTrue(source.contains("snapshotState(menu)"));
        assertTrue(source.contains("extends AEBaseScreen"));
        assertTrue(source.contains("MAX_RENDERED_ROWS = 2"));
        assertTrue(source.contains("CONTENT_WIDTH = 160"));
        assertTrue(source.contains("TOP_ACTION_BUTTON_SIZE = 13"));
        assertTrue(source.contains("TOP_ACTION_BUTTON_Y = 5"));
        assertTrue(source.contains("ACTION_PRIMARY_ROW_Y = 67"));
        assertTrue(source.contains("ACTION_SECONDARY_ROW_Y = 82"));
        assertTrue(source.contains("ACTION_FORMAT_WIDTH = 78"));
        assertTrue(source.contains("ACTION_APPLY_WIDTH = 81"));
        assertTrue(source.contains("CONFIG_PANEL_X = 7"));
        assertTrue(source.contains("CONFIG_PANEL_Y = 95"));
        assertTrue(source.contains("CONFIG_PANEL_WIDTH = 162"));
        assertTrue(source.contains("CONFIG_PANEL_HEIGHT = 34"));
        assertTrue(source.contains("SLOT_X = 33"));
        assertTrue(source.contains("THRESHOLD_X = 52"));
        assertTrue(source.contains("THRESHOLD_INPUT_WIDTH = 32"));
        assertTrue(source.contains("EXPRESSION_LABEL_Y = 22"));
        assertTrue(source.contains("EXPRESSION_INPUT_Y = 32"));
        assertTrue(source.contains("SCROLL_BUTTON_SIZE = 12"));
        assertTrue(source.contains("SCROLL_BUTTON_X = 151"));
        assertTrue(source.contains("SCROLL_UP_BUTTON_Y = ROW_TOP + 4"));
        assertTrue(source.contains("SCROLL_DOWN_BUTTON_Y = ROW_TOP + ROW_HEIGHT - 1"));
        assertTrue(source.contains("MODE_X = 86"));
        assertTrue(source.contains("COMPARISON_BUTTON_WIDTH = 22"));
        assertTrue(source.contains("FUZZY_MODE_X = 111"));
        assertTrue(source.contains("CRAFTING_MODE_BUTTON_WIDTH = 28"));
        assertTrue(source.contains("FUZZY_MODE_BUTTON_SIZE = 16"));
        assertTrue(source.contains("ROW_SHADE_RIGHT = 145"));
        assertTrue(source.contains("FUZZY_HIGHLIGHT_COLOR = 0x33D0A146"));
        assertTrue(source.contains("new ThresholdEditBox(font, 0, 0, THRESHOLD_INPUT_WIDTH, 14, row)"));
        assertTrue(source.contains("new FuzzyModeButton("));
        assertTrue(source.contains("slot.showFuzzyControl()"));
        assertTrue(source.contains("slot.fuzzyTooltip()"));
        assertTrue(source.contains("fuzzyModeButton.setMatchingMode(slot.matchingMode())"));
        assertTrue(source.contains("private final class FuzzyModeButton extends Button"));
        assertTrue(source.contains("Icon.TOOLBAR_BUTTON_BACKGROUND"));
        assertTrue(source.contains("Icon.FUZZY_IGNORE"));
        assertTrue(source.contains("Icon.FUZZY_PERCENT_25"));
        assertTrue(source.contains("craftingModeButtons"));
        assertTrue(source.contains("toggleCraftingMode"));
        assertTrue(source.contains("slot.showCraftingControl()"));
        assertTrue(source.contains("slot.craftingTooltip()"));
        assertTrue(source.contains("duplicateEmitToCraftTarget()"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.apply_expression"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.format_expression"));
        assertTrue(source.contains("setFormatter("));
        assertTrue(source.contains("setResponder("));
        assertTrue(source.contains(
                "mouseScrolled(double mouseX, double mouseY, double horizontalDelta, double verticalDelta)"
        ));
        assertTrue(source.contains("isMouseOverConfigPanel(mouseX, mouseY)"));
        assertTrue(source.contains("ROW_SHADE_RIGHT = 145"));
        assertTrue(source.contains("SCROLL_BUTTON_X = 151"));
        assertTrue(source.contains("ROW_SHADE_RIGHT, y + ROW_SHADE_HEIGHT"));
        assertTrue(source.contains("menu.getSlots(SlotSemantics.CONFIG)"));
        assertTrue(source.contains("setSlotPosition(slot, SLOT_X, rowBaseY(row))"),
                "setSlotPosition(slot, SLOT_X, ROW_TOP + row * ROW_HEIGHT)");
        assertTrue(source.contains("return ROW_TOP + row * ROW_HEIGHT;"));
        assertTrue(source.contains("FittedTextButton"));
        assertTrue(source.contains("drawFittedText("));
        assertTrue(source.contains("drawCenteredFittedText("));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.normalize_expression_spacing"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.save_expression"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.add_slot"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.remove_slot"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.expression_hint"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.precedence_hint"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.valid_status"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.warning_status"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.invalid_status"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.slot_layout_changed"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.unmarked_slot_status"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.out_of_range_slot_status"));
        assertTrue(source.contains("StyleManager.loadStyleDoc(\"/screens/multi_level_emitter.json\")"));
        assertTrue(source.contains("ObfuscationReflectionHelper.findField("));
        assertTrue(source.contains("findField(Slot.class, \"x\", \"f_40220_\")"));
        assertTrue(source.contains("findField(Slot.class, \"y\", \"f_40221_\")"));
        assertTrue(source.contains("findField(EditBox.class, \"highlightPos\", \"f_94102_\")"));
        assertFalse(source.contains("UpgradeableScreen"));
        assertFalse(source.contains("ServerSettingToggleButton"));
        assertFalse(source.contains("Settings.FUZZY_MODE"));
        assertFalse(source.contains("EMPTY_HINT = Component.literal(\"No item marked\")"));
        assertFalse(source.contains("AND is evaluated before OR. Use parentheses to group."));
        assertFalse(source.contains("Emitter configuration UI is active."));
    }

    @Test
    void customRuntimeScreenStyleResourcesExist() {
        assertTrue(Files.exists(Path.of("src/main/resources/assets/ae2/screens/multi_level_emitter.json")));
        assertTrue(Files.exists(Path.of("src/main/resources/assets/ae2/textures/guis/multi_level_emitter.png")));
    }

    @Test
    void customRuntimeScreenStyleRemovesLegacyEmitterWidgetsAndUsesCustomTitle() throws Exception {
        String style = Files.readString(Path.of("src/main/resources/assets/ae2/screens/multi_level_emitter.json"));

        assertTrue(style.contains("\"translate\": \"gui.chexsonsaeutils.multi_slot_emitter\""));
        assertTrue(style.contains("\"PLAYER_INVENTORY\""));
        assertTrue(style.contains("\"PLAYER_HOTBAR\""));
        assertTrue(style.contains("\"bottom\": 80"));
        assertTrue(style.contains("\"bottom\": 22"));
        assertFalse(style.contains("common/player_inventory.json"));
        assertFalse(style.contains("\"translate\": \"gui.ae2.LevelEmitter\""));
        assertFalse(style.contains("\"levelInput\""));
        assertFalse(style.contains("\"level\""));
        assertFalse(style.contains("\"CONFIG\""));
    }

    @Test
    void screenSourceContainsExpressionHelperMethods() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java"
        ));

        assertTrue(source.contains("validateExpressionDraft("));
        assertTrue(source.contains("insertOperator("));
        assertTrue(source.contains("wrapSelectionWithParentheses("));
        assertTrue(source.contains("insertSlotReference("));
        assertTrue(source.contains("formatDraftExpression("));
        assertTrue(source.contains("canApplyExpression("));
    }

    @Test
    void screenSourceContainsPhaseThreeSyncHelpers() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java"
        ));

        assertTrue(source.contains("record ThresholdSyncDecision("));
        assertTrue(source.contains("record ExpressionDraftSyncDecision("));
        assertTrue(source.contains("resolveThresholdSync("));
        assertTrue(source.contains("resolveExpressionDraftSync("));
        assertTrue(source.contains("clampScrollOffset("));
    }

    @Test
    void runtimeScreenSourceUsesPhaseThreeSyncDecisions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java"
        ));

        assertTrue(source.contains("MultiLevelEmitterScreen.resolveThresholdSync("));
        assertTrue(source.contains("MultiLevelEmitterScreen.resolveExpressionDraftSync("));
        assertTrue(source.contains("MultiLevelEmitterScreen.clampScrollOffset("));
        assertTrue(source.contains("previousConfiguredSlots"));
        assertTrue(source.contains("lastServerThresholds"));
        assertTrue(source.contains("state.expressionLocked()"));
        assertTrue(source.contains("expressionInput.active = !expressionLocked;"));
        assertTrue(source.contains("slot.thresholdLocked()"));
        assertTrue(source.contains("slot.comparisonLocked()"));
        assertTrue(source.contains("slotReferenceButton.active = enabled && !state.expressionLocked();"));
        assertTrue(source.contains("COLOR_READONLY_INPUT_TEXT = 0x7A7A7A"));
        assertTrue(source.contains("input.active = enabled && !slot.thresholdLocked();"));
        assertTrue(source.contains("input.setEditable(enabled && !slot.thresholdLocked())"));
        assertTrue(source.contains("COLOR_INPUT_TEXT = EditBox.DEFAULT_TEXT_COLOR"));
        assertTrue(source.contains("input.setTextColorUneditable(COLOR_READONLY_INPUT_TEXT)"));
        assertTrue(source.contains("comparisonButton.active = enabled && !slot.comparisonLocked()"));
        assertTrue(source.contains("input.setTooltip(Tooltip.create(state.craftingLockTooltip()))"));
        assertTrue(source.contains("comparisonButton.setTooltip(Tooltip.create(state.craftingLockTooltip()))"));
        assertTrue(source.contains(
                "helperStatus = Component.translatable(\"gui.chexsonsaeutils.multi_level_emitter.crafting_lock_helper\")"
        ));
        assertTrue(source.contains("craftingModeButton.visible = slot.showCraftingControl()"));
        assertTrue(source.contains("scrollUpButton.active"));
    }

    @Test
    void runtimeScreenSourceKeepsUpgradePanelReachable() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java"
        ));

        assertTrue(source.contains("new UpgradesPanel("));
        assertTrue(source.contains("menu.getSlots(SlotSemantics.UPGRADE)"));
        assertTrue(source.contains("this::getCompatibleUpgrades"));
        assertTrue(source.contains("private List<Component> getCompatibleUpgrades()"));
        assertTrue(source.contains("menu.getUpgrades()"));
        assertTrue(source.contains("Upgrades.getTooltipLinesForMachine(upgrades.getUpgradableItem())"));
    }

    @Test
    void runtimeScreenSourceResetsLocalSessionStateOnInit() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java"
        ));

        assertTrue(source.contains("resetLocalUiState();"));
        assertTrue(source.contains("scrollOffset = 0;"));
        assertTrue(source.contains("expressionDirty = false;"));
        assertTrue(source.contains("clearFocus();"));
    }

    @Test
    void topologyResetCopyExistsInAllRuntimeLocales() throws Exception {
        String english = Files.readString(Path.of("src/main/resources/assets/chexsonsaeutils/lang/en_us.json"));
        String chinese = Files.readString(Path.of("src/main/resources/assets/chexsonsaeutils/lang/zh_cn.json"));

        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.slot_layout_changed"));
        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_lock_helper"));
        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_lock_tooltip"));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.slot_layout_changed"));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_lock_helper"));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_lock_tooltip"));
        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.strict"));
        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.percent_25"));
        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_mode.disabled"));
        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_mode.while_crafting"));
        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_mode.duplicate_emit_to_craft"));
        assertTrue(chinese.contains("\\u5df2\\u5b89\\u88c5\\u5408\\u6210\\u5361\\uff1a\\u8868\\u8fbe\\u5f0f\\u4ecd\\u53ef\\u7f16\\u8f91"));
        assertTrue(chinese.contains("\\u82e5\\u8981\\u4fee\\u6539 REQ/SUP \\u884c\\u7684\\u9608\\u503c\\u6216\\u6bd4\\u8f83\\u65b9\\u5f0f"));
        assertFalse(chinese.contains(
                "\"gui.chexsonsaeutils.multi_level_emitter.crafting_lock_helper\": " +
                        "\"\\u5df2\\u5b89\\u88c5\\u5408\\u6210\\u5361\\uff1aexpression"
        ));
        assertFalse(chinese.contains(
                "\"gui.chexsonsaeutils.multi_level_emitter.crafting_lock_tooltip\": " +
                        "\"expression \\u4ecd\\u53ef\\u7f16\\u8f91"
        ));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.strict"));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.percent_25"));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_mode.disabled"));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_mode.while_crafting"));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_mode.duplicate_emit_to_craft"));
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

    private static MultiLevelEmitterRuntimePart newCapabilityRuntimePart(boolean fuzzyInstalled) {
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

    private static void applyMatchingModeSnapshot(
            MultiLevelEmitterRuntimePart runtime,
            List<MultiLevelEmitterPart.MatchingMode> matchingModes
    ) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt("configured_item_count", matchingModes.size());
        MultiLevelEmitterUtils.writeMatchingModesToNBT(matchingModes, snapshot, "matching_modes");
        readRuntimeSnapshot(runtime, snapshot);
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
                    net.minecraft.core.HolderLookup.Provider.class,
                    boolean.class
            );
            method.setAccessible(true);
            method.invoke(runtime, snapshot, RegistryAccess.EMPTY, false);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read runtime snapshot for screen test instance", exception);
        }
    }

    private static void assertFuzzyTooltip(net.minecraft.network.chat.Component tooltip, String expectedModeKey) {
        String rendered = tooltip.toString();
        assertTrue(rendered.contains("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode"));
        assertTrue(rendered.contains(expectedModeKey));
    }

    private static void assertCraftingTooltipContains(
            Component tooltip,
            String expectedModeKey,
            boolean expectUnmarkedHint,
            boolean expectDuplicateHint
    ) {
        String rendered = tooltip.toString();
        assertTrue(rendered.contains("gui.chexsonsaeutils.multi_level_emitter.crafting_mode"));
        assertTrue(rendered.contains(expectedModeKey));
        assertEquals(expectUnmarkedHint, rendered.contains(
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.unmarked_hint"
        ));
        assertEquals(expectDuplicateHint, rendered.contains(
                "gui.chexsonsaeutils.multi_level_emitter.crafting_mode.duplicate_emit_to_craft"
        ));
    }

    private static final class CapabilityAwareRuntimePart extends MultiLevelEmitterRuntimePart {
        private boolean fuzzyInstalled;
        private boolean craftingInstalled;
        private List<Integer> duplicateEmitToCraftSlots = List.of();

        private CapabilityAwareRuntimePart() {
            super(null);
        }

        void setInstalledCards(boolean fuzzyInstalled, boolean craftingInstalled) {
            this.fuzzyInstalled = fuzzyInstalled;
            this.craftingInstalled = craftingInstalled;
            if (this.duplicateEmitToCraftSlots == null) {
                this.duplicateEmitToCraftSlots = List.of();
            }
        }

        void setDuplicateEmitToCraftSlots(List<Integer> duplicateEmitToCraftSlots) {
            this.duplicateEmitToCraftSlots = duplicateEmitToCraftSlots == null
                    ? List.of()
                    : List.copyOf(duplicateEmitToCraftSlots);
        }

        @Override
        public boolean hasFuzzyCardInstalled() {
            return fuzzyInstalled;
        }

        @Override
        public boolean hasCraftingCardInstalled() {
            return craftingInstalled;
        }

        @Override
        public boolean hasDuplicateEmitToCraftTarget(int slotIndex) {
            return duplicateEmitToCraftSlots.contains(slotIndex);
        }
    }
}
