package git.chexson.chexsonsaeutils.pattern;

import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleDraft;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRulePayload;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleValidation;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotTagService;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertContains;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertDoesNotContain;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternTerminalRuleMenuTest {
    private static final Path PROCESSING_PATTERN_REPLACEMENT_SCREEN = javaSource(
            "git/chexson/chexsonsaeutils/client/gui/implementations/ProcessingPatternReplacementScreen.java");
    private static final Path PATTERN_ENCODING_TERM_MENU_RULE_MIXIN = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java");
    private static final Path PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/client/gui/PatternEncodingTermScreenRuleMixin.java");
    private static final Path EXECUTION_TEST = Path.of(
            "src", "test", "java", "git", "chexson", "chexsonsaeutils", "pattern",
            "ProcessingPatternReplacementExecutionTest.java");
    private static final Path PLANNING_TEST = Path.of(
            "src", "test", "java", "git", "chexson", "chexsonsaeutils", "pattern",
            "ProcessingPatternReplacementPlanningTest.java");
    private static final Path PROCESSING_PATTERN_REPLACEMENT_JSON = resourcePath(
            "assets/ae2/screens/processing_pattern_replacement.json");
    private static final Path EN_US = resourcePath("assets/chexsonsaeutils/lang/en_us.json");
    private static final Path ZH_CN = resourcePath("assets/chexsonsaeutils/lang/zh_cn.json");
    private static final ResourceLocation SOURCE_TAG_ALPHA = id("chexsonsaeutils", "source/alpha");
    private static final ResourceLocation SOURCE_TAG_BETA = id("chexsonsaeutils", "source/beta");
    private static final ResourceLocation SOURCE_TAG_GAMMA = id("chexsonsaeutils", "source/gamma");
    private static final ResourceLocation SHARED_ITEM = id("chexsonsaeutils", "shared_item");
    private static final ResourceLocation INVALID_ITEM = id("chexsonsaeutils", "invalid_item");

    @Test
    void reopenedMenuRetainsDraftSelectionsWithinCurrentSession() {
        Map<Integer, ProcessingSlotRuleDraft> draftStore = new LinkedHashMap<>();
        ProcessingSlotRuleValidation validation = new ProcessingSlotRuleValidation(createService(
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                Map.of(),
                orderedMap(
                        SHARED_ITEM, orderedSet(SOURCE_TAG_ALPHA),
                        INVALID_ITEM, orderedSet(SOURCE_TAG_GAMMA)
                )
        ));
        ProcessingSlotRuleDraft baseDraft = new ProcessingSlotRuleDraft(
                4,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                Set.of(),
                Set.of()
        );
        ProcessingSlotRulePayload payload = new ProcessingSlotRulePayload(
                4,
                orderedSet(SOURCE_TAG_ALPHA),
                orderedSet(SHARED_ITEM)
        );

        ProcessingSlotRuleDraft savedDraft = validation.saveDraft(draftStore, baseDraft, payload);

        assertEquals(savedDraft, draftStore.get(4));
        assertEquals(orderedSet(SOURCE_TAG_ALPHA), draftStore.get(4).selectedTagIds());
        assertEquals(orderedSet(SHARED_ITEM), draftStore.get(4).explicitCandidateIds());

        ProcessingSlotRuleDraft reopenedDraft = draftStore.get(4);
        assertEquals(savedDraft, reopenedDraft);

        validation.clearDraft(draftStore, 4);
        assertNull(draftStore.get(4));
    }

    @Test
    void saveRejectsExplicitCandidateWithoutSharedTag() {
        Map<Integer, ProcessingSlotRuleDraft> draftStore = new LinkedHashMap<>();
        ProcessingSlotRuleValidation validation = new ProcessingSlotRuleValidation(createService(
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                Map.of(),
                orderedMap(
                        SHARED_ITEM, orderedSet(SOURCE_TAG_ALPHA),
                        INVALID_ITEM, orderedSet(SOURCE_TAG_GAMMA)
                )
        ));
        ProcessingSlotRuleDraft baseDraft = new ProcessingSlotRuleDraft(
                2,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                Set.of(),
                Set.of()
        );
        ProcessingSlotRulePayload payload = new ProcessingSlotRulePayload(
                2,
                orderedSet(SOURCE_TAG_BETA, SOURCE_TAG_GAMMA),
                orderedSet(SHARED_ITEM, INVALID_ITEM)
        );

        ProcessingSlotRuleDraft savedDraft = validation.saveDraft(draftStore, baseDraft, payload);

        assertEquals(orderedSet(SOURCE_TAG_BETA), savedDraft.selectedTagIds());
        assertEquals(orderedSet(SHARED_ITEM), savedDraft.explicitCandidateIds());
        assertTrue(savedDraft.explicitCandidateIds().contains(SHARED_ITEM));
        assertFalse(savedDraft.explicitCandidateIds().contains(INVALID_ITEM));
    }

    @Test
    void screenAssumesItemBackedEntryContract() throws IOException {
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "ProcessingSlotCandidateGroup");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "requestSaveProcessingSlotRuleDraft");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "requestClearProcessingSlotRuleDraft");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "chexsonSaveProcessingSlotRuleDraft");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "ProcessingSlotRuleValidation");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, ".saveDraft(");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_JSON, "\"generatedBackground\"");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_JSON, "\"width\": 200");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_JSON, "\"height\": 216");
        assertContains(EN_US, "gui.chexsonsaeutils.processing_pattern_rule.title");
        assertContains(ZH_CN, "gui.chexsonsaeutils.processing_pattern_rule.title");
        assertDoesNotContain(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "unavailableReasonKey");
        assertDoesNotContain(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "configurable()");
    }

    @Test
    void clientRequestPathUpdatesLocalDraftStateBeforeForwardingToServer() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "chexsonsaeutils$applySaveProcessingSlotRuleDraft(payload);");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "sendClientAction(\"chexsonSaveProcessingSlotRuleDraft\", payload);");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "chexsonsaeutils$applyClearProcessingSlotRuleDraft(slotIndex);");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "sendClientAction(\"chexsonClearProcessingSlotRuleDraft\", slotIndex);");
    }

    @Test
    void groupedIconLayoutUsesAe2StyleScreenAsset() throws IOException {
        assertContains(PROCESSING_PATTERN_REPLACEMENT_JSON, "\"generatedBackground\"");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "guiGraphics.renderItem(");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "BuiltInRegistries.ITEM.get(");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "TagSectionRow");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "ItemIconCell");
        assertContains(EN_US, "gui.chexsonsaeutils.processing_pattern_rule.grouped_content");
        assertContains(ZH_CN, "gui.chexsonsaeutils.processing_pattern_rule.grouped_content");
    }

    @Test
    void groupedIconInteractionsPreserveMenuAuthoritativeSaveContract() throws IOException {
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "handleTagToggleClick(");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "handleItemIconClick(");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "findTagSectionAt(");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "findItemIconCellAt(");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "groupedScrollbar.getCurrentScroll()");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN,
                "new ProcessingSlotRulePayload(slotIndex, selectedTagIds, explicitCandidateIds)");
        assertContains(EN_US, "gui.chexsonsaeutils.processing_pattern_rule.interaction_hint");
        assertContains(ZH_CN, "gui.chexsonsaeutils.processing_pattern_rule.interaction_hint");
    }

    @Test
    void tagToggleAndItemIconToggleAreSeparateSelections() throws IOException {
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "selectedTagIds.add(tagSectionRow.tagId())");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "selectedTagIds.remove(tagSectionRow.tagId())");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "explicitCandidateIds.add(itemIconCell.itemId())");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "explicitCandidateIds.remove(itemIconCell.itemId())");
        assertDoesNotContain(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "CandidateRow");
    }

    @Test
    void processingPatternEncodeWritesPersistedReplacementRules() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "method = \"encodeProcessingPattern\"");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "ProcessingPatternReplacementPersistence");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "chexsonsaeutils_processing_replacements");
    }

    @Test
    void encodedPatternFallbackRestoresPersistedRuleForMatchingSource() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "buildProcessingSlotRuleDraft");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "encodedPatternSlot.getItem()");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, ".restoreRuleDraft(");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "this.processingInputSlots[slotIndex]");
        assertDoesNotContain(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "this.slots.get(slotIndex)");
    }

    @Test
    void replacementScreenUsesHostBridgeForProcessingInputSourceStack() throws IOException {
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "processingSlotRuleHost.getProcessingSlotRuleSourceStack(slotIndex)");
        assertDoesNotContain(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "getMenu().slots.get(slotIndex)");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "getProcessingSlotRuleSourceStack(int slotIndex)");
    }

    @Test
    void buildProcessingSlotRuleStatusPrefersSessionDraftBeforeEncodedFallback() throws IOException {
        Path processingSlotRuleHostPath = javaSource(
                "git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRuleHost.java");
        Path processingSlotRuleStatusPath = javaSource(
                "git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRuleStatus.java");
        Path processingSlotRuleVisualStatePath = javaSource(
                "git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRuleVisualState.java");

        assertContains(processingSlotRuleHostPath, "getProcessingSlotRuleStatus(int slotIndex)");
        assertContains(processingSlotRuleStatusPath, "ProcessingSlotRuleVisualState visualState");
        assertContains(processingSlotRuleVisualStatePath, "UNCONFIGURED");
        assertContains(processingSlotRuleVisualStatePath, "PARTIALLY_INVALID");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "buildProcessingSlotRuleStatus");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN,
                "ProcessingSlotRuleDraft existingDraft = chexsonsaeutils$processingSlotRuleDrafts.get(slotIndex);");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "return chexsonsaeutils$buildProcessingSlotRuleStatus(slotIndex, existingDraft);");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "return chexsonsaeutils$restoreEncodedPatternRuleStatus(slotIndex);");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "ProcessingSlotRuleVisualState.UNCONFIGURED");
    }

    @Test
    void terminalStatusBadgeUsesThreeStateProjection() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "method = \"renderSlot\"");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "getProcessingSlotRuleStatus(");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "menu.getMode() == EncodingMode.PROCESSING");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "menu.getSlotSemantic(slot) == SlotSemantics.PROCESSING_INPUTS");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "ProcessingSlotRuleVisualState.CONFIGURED");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "ProcessingSlotRuleVisualState.PARTIALLY_INVALID");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "guiGraphics.fill(");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "leftPos + slot.x + 6");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "topPos + slot.y + 4");
    }

    @Test
    void phaseFourRegressionSuiteCoversTerminalStateAndExecutionBoundary() throws IOException {
        assertContains(EXECUTION_TEST, "pushInputsToExternalInventoryRejectsIrrelevantCandidates");
        assertContains(PLANNING_TEST, "planningSelectorNeverReturnsIrrelevantCandidate");
        assertContains(PLANNING_TEST, "planningMixinsRespectReplacementAwarePossibleInputs");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "ProcessingSlotRuleVisualState.PARTIALLY_INVALID");
        assertContains(Path.of(
                        "src", "test", "java", "git", "chexson", "chexsonsaeutils", "pattern",
                        "PatternTerminalRuleMenuTest.java"),
                "terminalStatusBadgeUsesThreeStateProjection");
    }

    @Test
    void terminalTooltipUsesSingleLineStatusCopy() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "method = \"renderTooltip\"");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN,
                "Component.translatable(\"gui.chexsonsaeutils.processing_pattern_rule.status.not_configured\")");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN,
                "Component.translatable(\"gui.chexsonsaeutils.processing_pattern_rule.status.configured\")");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN,
                "Component.translatable(\"gui.chexsonsaeutils.processing_pattern_rule.status.partially_invalid\")");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "List.of(statusLine)");
        assertDoesNotContain(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "selected_summary");
        assertDoesNotContain(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "interaction_hint");
        assertDoesNotContain(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "sidebar");
    }

    @Test
    void statusCopyTranslationsExistForBothLocales() throws IOException {
        assertContains(EN_US, "gui.chexsonsaeutils.processing_pattern_rule.status.not_configured");
        assertContains(EN_US, "gui.chexsonsaeutils.processing_pattern_rule.status.configured");
        assertContains(EN_US, "gui.chexsonsaeutils.processing_pattern_rule.status.partially_invalid");
        assertContains(ZH_CN, "gui.chexsonsaeutils.processing_pattern_rule.status.not_configured");
        assertContains(ZH_CN, "gui.chexsonsaeutils.processing_pattern_rule.status.configured");
        assertContains(ZH_CN, "gui.chexsonsaeutils.processing_pattern_rule.status.partially_invalid");
    }

    @Test
    void ctrlLeftClickReentryStillTargetsReplacementScreen() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "Screen.hasControlDown()");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "InputConstants.MOUSE_BUTTON_LEFT");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN,
                "switchToScreen(new ProcessingPatternReplacementScreen<>(chexsonsaeutils$self(), processingInputIndex))");
        assertDoesNotContain(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "IconButton");
        assertDoesNotContain(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "context menu");
    }

    @Test
    void processingInputClickUsesArrayIdentityInsteadOfRawSlotIndex() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "slot.isActive() && isHovering(slot, mouseX, mouseY)");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "FakeSlot[] processingInputSlots = menu.getProcessingInputSlots();");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "if (processingInputSlots[index] == slot)");
        assertDoesNotContain(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN,
                "new ProcessingPatternReplacementScreen<>(chexsonsaeutils$self(), slot.index)");
    }

    @Test
    void encodedPatternFallbackSkipsRuleWhenSourceChanged() throws IOException {
        Path persistencePath = javaSource(
                "git/chexson/chexsonsaeutils/pattern/replacement/ProcessingPatternReplacementPersistence.java");
        assertContains(persistencePath, "source_item");
        assertContains(persistencePath, "currentSourceItemId.equals(rule.sourceItemId())");
    }

    private static ProcessingSlotTagService createService(
            List<ResourceLocation> sourceTags,
            Map<ResourceLocation, List<ResourceLocation>> tagMembers,
            Map<ResourceLocation, Set<ResourceLocation>> itemTags
    ) {
        return new ProcessingSlotTagService(
                ignored -> sourceTags,
                tagId -> tagMembers.getOrDefault(tagId, List.of()),
                itemId -> itemTags.getOrDefault(itemId, Set.of())
        );
    }

    private static <K, V> Map<K, V> orderedMap(Object... entries) {
        Map<K, V> ordered = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) entries[i];
            @SuppressWarnings("unchecked")
            V value = (V) entries[i + 1];
            ordered.put(key, value);
        }
        return ordered;
    }

    @SafeVarargs
    private static <T> Set<T> orderedSet(T... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
