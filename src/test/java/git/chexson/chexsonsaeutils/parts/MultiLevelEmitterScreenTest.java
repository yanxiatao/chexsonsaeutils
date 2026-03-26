package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.RedstoneMode;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterUtils;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompiler;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionOwnership;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
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
        assertEquals("ALL", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL));
        assertEquals("99", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.PERCENT_99));
        assertEquals("75", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.PERCENT_75));
        assertEquals("50", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.PERCENT_50));
        assertEquals("25", MultiLevelEmitterScreen.fuzzyShortLabel(MultiLevelEmitterPart.MatchingMode.PERCENT_25));
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
    void toggleMatchingModeDelegatesToMenuAuthority() {
        MultiLevelEmitterRuntimePart runtime = newCapabilityRuntimePart(true);
        MultiLevelEmitterMenu.RuntimeMenu menu = MultiLevelEmitterMenuTestHarness.detachedForRuntime(runtime);

        MultiLevelEmitterScreen.toggleMatchingMode(menu, 0);

        assertEquals(MultiLevelEmitterPart.MatchingMode.IGNORE_ALL, menu.matchingModeForSlot(0));
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
        assertEquals("ALL", first.fuzzyShortLabel());
        assertFuzzyTooltip(first.fuzzyTooltip(), "gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.ignore_all");
        assertEquals(MultiLevelEmitterPart.MatchingMode.PERCENT_75, second.matchingMode());
        assertTrue(second.emphasizeFuzzyMode());
        assertEquals("75", second.fuzzyShortLabel());
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
        assertTrue(source.contains("EXPRESSION_LABEL_Y = 22"));
        assertTrue(source.contains("EXPRESSION_INPUT_Y = 32"));
        assertTrue(source.contains("SCROLL_BUTTON_SIZE = 12"));
        assertTrue(source.contains("SCROLL_BUTTON_X = 151"));
        assertTrue(source.contains("SCROLL_UP_BUTTON_Y = ROW_TOP + 4"));
        assertTrue(source.contains("SCROLL_DOWN_BUTTON_Y = ROW_TOP + ROW_HEIGHT - 1"));
        assertTrue(source.contains("MODE_X = 96"));
        assertTrue(source.contains("FUZZY_MODE_X = 123"));
        assertTrue(source.contains("FUZZY_HIGHLIGHT_COLOR = 0x33D0A146"));
        assertTrue(source.contains("new ThresholdEditBox(font, 0, 0, 38, 14, row)"));
        assertTrue(source.contains("0, 0, 24, 14"));
        assertTrue(source.contains("slot.showFuzzyControl()"));
        assertTrue(source.contains("slot.fuzzyTooltip()"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.apply_expression"));
        assertTrue(source.contains("gui.chexsonsaeutils.multi_level_emitter.format_expression"));
        assertTrue(source.contains("setFormatter("));
        assertTrue(source.contains("setResponder("));
        assertTrue(source.contains("mouseScrolled(double mouseX, double mouseY, double wheelDelta)"));
        assertTrue(source.contains("isMouseOverConfigPanel(mouseX, mouseY)"));
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
        assertTrue(source.contains("findField(Slot.class, \"f_40220_\")"));
        assertTrue(source.contains("findField(Slot.class, \"f_40221_\")"));
        assertTrue(source.contains("findField(EditBox.class, \"f_94102_\")"));
        assertFalse(source.contains("UpgradeableScreen"));
        assertFalse(source.contains("ServerSettingToggleButton"));
        assertFalse(source.contains("Settings.FUZZY_MODE"));
        assertFalse(source.contains("EMPTY_HINT = Component.literal(\"No item marked\")"));
        assertFalse(source.contains("AND is evaluated before OR. Use parentheses to group."));
        assertFalse(source.contains("Emitter configuration UI is active."));
        assertFalse(source.contains("findField(Slot.class, \"x\")"));
        assertFalse(source.contains("findField(Slot.class, \"y\")"));
        assertFalse(source.contains("findField(EditBox.class, \"highlightPos\")"));
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
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.slot_layout_changed"));
        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.strict"));
        assertTrue(english.contains("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.percent_25"));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.strict"));
        assertTrue(chinese.contains("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode.percent_25"));
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
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            CapabilityAwareRuntimePart runtime =
                    (CapabilityAwareRuntimePart) allocateInstance.invoke(unsafe, CapabilityAwareRuntimePart.class);
            runtime.setInstalledCards(fuzzyInstalled);
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
            throw new AssertionError("Unable to read runtime snapshot for screen test instance", exception);
        }
    }

    private static void assertFuzzyTooltip(net.minecraft.network.chat.Component tooltip, String expectedModeKey) {
        assertTrue(tooltip.getContents() instanceof TranslatableContents);
        TranslatableContents wrapper = (TranslatableContents) tooltip.getContents();
        assertEquals("gui.chexsonsaeutils.multi_level_emitter.fuzzy_mode", wrapper.getKey());
        assertEquals(1, wrapper.getArgs().length);
        assertTrue(wrapper.getArgs()[0] instanceof net.minecraft.network.chat.Component);
        net.minecraft.network.chat.Component nested = (net.minecraft.network.chat.Component) wrapper.getArgs()[0];
        assertTrue(nested.getContents() instanceof TranslatableContents);
        TranslatableContents detail = (TranslatableContents) nested.getContents();
        assertEquals(expectedModeKey, detail.getKey());
    }

    private static final class CapabilityAwareRuntimePart extends MultiLevelEmitterRuntimePart {
        private boolean fuzzyInstalled;

        private CapabilityAwareRuntimePart() {
            super(null);
        }

        void setInstalledCards(boolean fuzzyInstalled) {
            this.fuzzyInstalled = fuzzyInstalled;
        }

        @Override
        public boolean hasFuzzyCardInstalled() {
            return fuzzyInstalled;
        }
    }
}
