package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompileResult;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompiler;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionDiagnostic;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterExpressionValidationTest {

    @Test
    void outOfRangeSlotReferenceIsInvalid() {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "#1 OR #4",
                3,
                index -> true
        );

        assertTrue(result.isInvalid());
        assertNull(result.plan());
        assertEquals("out_of_range_slot", result.primaryDiagnostic().code());
        assertEquals(6, result.primaryDiagnostic().start());
        assertEquals(8, result.primaryDiagnostic().end());
        assertEquals(3, result.primaryDiagnostic().slotIndex());
    }

    @Test
    void unmarkedSlotReferenceProducesWarningButStillCompiles() {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "#1 OR #2",
                2,
                index -> index == 0
        );

        assertFalse(result.isInvalid());
        assertTrue(result.hasWarnings());
        assertNotNull(result.plan());
        assertEquals("unmarked_slot", result.primaryDiagnostic().code());
        assertEquals(MultiLevelEmitterExpressionDiagnostic.Severity.WARNING, result.primaryDiagnostic().severity());
        assertEquals(6, result.primaryDiagnostic().start());
        assertEquals(8, result.primaryDiagnostic().end());
        assertEquals(1, result.primaryDiagnostic().slotIndex());
    }

    @Test
    void unexpectedTokenReportsPrimaryDiagnosticSpan() {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "#1 XOR #2",
                2,
                index -> true
        );

        assertTrue(result.isInvalid());
        assertNull(result.plan());
        assertEquals("unexpected_token", result.primaryDiagnostic().code());
        assertEquals(3, result.primaryDiagnostic().start());
        assertEquals(6, result.primaryDiagnostic().end());
    }

    @Test
    void balancedWarningStillKeepsCompilationPlan() {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "#1 OR #2 AND #3",
                3,
                index -> true
        );

        assertFalse(result.isInvalid());
        assertTrue(result.hasWarnings());
        assertNotNull(result.plan());
    }

    @Test
    void compilePreservesRawTextUntilExplicitFormat() {
        String rawText = " #1 and ( #2 or #3 ) ";

        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                rawText,
                3,
                index -> true
        );

        assertEquals(rawText, result.rawText());
        assertEquals("#1 AND (#2 OR #3)", MultiLevelEmitterExpressionFormatter.format(rawText));
    }
}
