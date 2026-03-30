package git.chexson.chexsonsaeutils.pattern;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertContains;

class ProcessingPatternReplacementDualModeMatrixTest {

    private static final Path TERMINAL_ENTRY_CONTRACT_TEST = Path.of(
            "src/test/java/git/chexson/chexsonsaeutils/pattern/PatternTerminalEntryContractTest.java");
    private static final Path TERMINAL_RULE_MENU_TEST = Path.of(
            "src/test/java/git/chexson/chexsonsaeutils/pattern/PatternTerminalRuleMenuTest.java");
    private static final Path PERSISTENCE_TEST = Path.of(
            "src/test/java/git/chexson/chexsonsaeutils/pattern/ProcessingPatternReplacementPersistenceTest.java");
    private static final Path PLANNING_TEST = Path.of(
            "src/test/java/git/chexson/chexsonsaeutils/pattern/ProcessingPatternReplacementPlanningTest.java");
    private static final Path EXECUTION_TEST = Path.of(
            "src/test/java/git/chexson/chexsonsaeutils/pattern/ProcessingPatternReplacementExecutionTest.java");
    private static final Path CONFIG_GATE_TEST = Path.of(
            "src/test/java/git/chexson/chexsonsaeutils/pattern/ProcessingPatternReplacementConfigGateTest.java");
    private static final Path NATIVE_FALLBACK_CONTRACT_TEST = Path.of(
            "src/test/java/git/chexson/chexsonsaeutils/pattern/ProcessingPatternReplacementNativeFallbackContractTest.java");

    @Test
    void enabledModeMatrixCoversV11CompatibilityChain() throws IOException {
        assertContains(TERMINAL_ENTRY_CONTRACT_TEST, "ctrlLeftOnProcessingInputOpensRuleScreen");
        assertContains(TERMINAL_RULE_MENU_TEST, "terminalStatusBadgeUsesThreeStateProjection");
        assertContains(TERMINAL_RULE_MENU_TEST, "phaseFourRegressionSuiteCoversTerminalStateAndExecutionBoundary");
        assertContains(PERSISTENCE_TEST, "writesAndReadsSlotRulesFromPatternChildTag");
        assertContains(PLANNING_TEST, "planningSelectorRejectsReplacementWhenItCannotCoverRequestedGroups");
        assertContains(EXECUTION_TEST, "pushInputsToExternalInventoryRejectsIrrelevantCandidates");
    }

    @Test
    void disabledModeMatrixCoversNativeFallbackBoundary() throws IOException {
        assertContains(CONFIG_GATE_TEST, "startupPluginDisablesReplacementBundleWhenPersistedFalse");
        assertContains(CONFIG_GATE_TEST, "commonSetupGuardsDecoderRegistrationWithReplacementGate");
        assertContains(NATIVE_FALLBACK_CONTRACT_TEST, "disabledModeSkipsReplacementTerminalAndRuntimeMixins");
        assertContains(NATIVE_FALLBACK_CONTRACT_TEST, "disabledModeKeepsDecoderAndMetadataWritebackOutOfNativePath");
    }
}
