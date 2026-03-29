package git.chexson.chexsonsaeutils.pattern;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertContains;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;

class PatternTerminalEntryContractTest {
    private static final Path PROCESSING_SLOT_RULE_DRAFT = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRuleDraft.java");
    private static final Path PROCESSING_SLOT_RULE_PAYLOAD = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRulePayload.java");
    private static final Path PROCESSING_SLOT_RULE_HOST = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/ProcessingSlotRuleHost.java");
    private static final Path PROCESSING_PATTERN_REPLACEMENT_SCREEN = javaSource(
            "git/chexson/chexsonsaeutils/client/gui/implementations/ProcessingPatternReplacementScreen.java");
    private static final Path PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/client/gui/PatternEncodingTermScreenRuleMixin.java");
    private static final Path PATTERN_ENCODING_TERM_MENU_RULE_MIXIN = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/menu/PatternEncodingTermMenuRuleMixin.java");
    private static final Path MIXINS = resourcePath("chexsonsaeutils.mixins.json");

    @Test
    void processingRuleDraftRecordCarriesMenuAuthoritativeSelections() throws IOException {
        assertContains(PROCESSING_SLOT_RULE_DRAFT, "record ProcessingSlotRuleDraft(");
        assertContains(PROCESSING_SLOT_RULE_DRAFT, "List<ResourceLocation> sourceTagIds");
        assertContains(PROCESSING_SLOT_RULE_DRAFT, "Set<ResourceLocation> selectedTagIds");
        assertContains(PROCESSING_SLOT_RULE_DRAFT, "Set<ResourceLocation> explicitCandidateIds");
        assertContains(PROCESSING_SLOT_RULE_PAYLOAD, "record ProcessingSlotRulePayload(");
    }

    @Test
    void processingRuleHostDefinesMenuAuthorityBridge() throws IOException {
        assertContains(PROCESSING_SLOT_RULE_HOST, "interface ProcessingSlotRuleHost");
        assertContains(PROCESSING_SLOT_RULE_HOST, "buildProcessingSlotRuleDraft(int slotIndex)");
        assertContains(PROCESSING_SLOT_RULE_HOST, "getProcessingSlotRuleDraft(int slotIndex)");
        assertContains(PROCESSING_SLOT_RULE_HOST, "getProcessingSlotRuleSourceStack(int slotIndex)");
        assertContains(PROCESSING_SLOT_RULE_HOST, "requestSaveProcessingSlotRuleDraft(ProcessingSlotRulePayload payload)");
        assertContains(PROCESSING_SLOT_RULE_HOST, "requestClearProcessingSlotRuleDraft(int slotIndex)");
    }

    @Test
    void replacementScreenShellReadsDraftsFromHostBridge() throws IOException {
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "extends AESubScreen");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "ProcessingSlotRuleHost");
        assertContains(PROCESSING_PATTERN_REPLACEMENT_SCREEN, "PatternEncodingTermScreen");
    }

    @Test
    void ctrlLeftOnProcessingInputOpensRuleScreen() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "Screen.hasControlDown()");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "InputConstants.MOUSE_BUTTON_LEFT");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "menu.getMode() == EncodingMode.PROCESSING");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN,
                "menu.getSlotSemantic(slot) == SlotSemantics.PROCESSING_INPUTS");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "slot.isActive()");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "menu.getProcessingInputSlots()");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN,
                "switchToScreen(new ProcessingPatternReplacementScreen");
    }

    @Test
    void processingOutputsAndNormalPatternsDoNotOpenRuleScreen() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN,
                "menu.getSlotSemantic(slot) == SlotSemantics.PROCESSING_INPUTS");
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "menu.getMode() == EncodingMode.PROCESSING");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN, "Map<Integer, ProcessingSlotRuleDraft>");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN,
                "registerClientAction(\"chexsonSaveProcessingSlotRuleDraft\"");
        assertContains(PATTERN_ENCODING_TERM_MENU_RULE_MIXIN,
                "registerClientAction(\"chexsonClearProcessingSlotRuleDraft\"");
    }

    @Test
    void nonItemProcessingInputDoesNotOpenRuleScreen() throws IOException {
        assertContains(PATTERN_ENCODING_TERM_SCREEN_RULE_MIXIN, "currentStack.what() instanceof AEItemKey");
        assertContains(MIXINS, "ae2.menu.PatternEncodingTermMenuRuleMixin");
        assertContains(MIXINS, "ae2.client.gui.PatternEncodingTermScreenRuleMixin");
    }
}
