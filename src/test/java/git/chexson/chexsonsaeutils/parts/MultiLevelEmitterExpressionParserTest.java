package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.parts.expression.MultiLevelEmitterExpressionCompileResult;
import git.chexson.chexsonsaeutils.parts.expression.MultiLevelEmitterExpressionCompiler;
import git.chexson.chexsonsaeutils.parts.expression.MultiLevelEmitterExpressionDiagnostic;
import git.chexson.chexsonsaeutils.parts.expression.MultiLevelEmitterExpressionFormatter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterExpressionParserTest {

    @Test
    void parsesGroupedAndExpression() {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "#1 AND (#2 OR #3)",
                3,
                index -> true
        );

        assertFalse(result.isInvalid());
        assertNotNull(result.plan());
        assertTrue(result.plan().evaluate(List.of(true, false, true)));
        assertFalse(result.plan().evaluate(List.of(true, false, false)));
        assertEquals(3, result.plan().highestReferencedSlot());
        assertEquals(MultiLevelEmitterExpressionDiagnostic.Severity.VALID, result.primaryDiagnostic().severity());
    }

    @Test
    void andPrecedenceBeatsOrWithoutParentheses() {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "#1 OR #2 AND #3",
                3,
                index -> true
        );

        assertFalse(result.isInvalid());
        assertTrue(result.hasWarnings());
        assertTrue(result.plan().evaluate(List.of(false, true, true)));
        assertFalse(result.plan().evaluate(List.of(false, true, false)));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> "mixed_precedence_warning".equals(diagnostic.code())));
    }

    @Test
    void nestedParenthesesEvaluateDeterministically() {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "((#1 OR #2) AND (#3 OR #4))",
                4,
                index -> true
        );

        assertFalse(result.isInvalid());
        assertNotNull(result.plan());
        assertTrue(result.plan().evaluate(List.of(false, true, true, false)));
        assertFalse(result.plan().evaluate(List.of(false, true, false, false)));
    }

    @Test
    void missingOperandProducesInvalidDiagnostic() {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "#1 AND",
                1,
                index -> true
        );

        assertTrue(result.isInvalid());
        assertNull(result.plan());
        assertEquals("missing_operand", result.primaryDiagnostic().code());
        assertEquals(6, result.primaryDiagnostic().start());
        assertEquals(6, result.primaryDiagnostic().end());
    }

    @Test
    void unbalancedParenthesesProduceInvalidDiagnostic() {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "#1 AND (#2 OR #3",
                3,
                index -> true
        );

        assertTrue(result.isInvalid());
        assertNull(result.plan());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> "missing_closing_paren".equals(diagnostic.code())));
    }

    @Test
    void defaultExpressionGenerationUsesFlatOrChain() {
        assertEquals("", MultiLevelEmitterExpressionFormatter.defaultExpressionForSlots(0));
        assertEquals("#1", MultiLevelEmitterExpressionFormatter.defaultExpressionForSlots(1));
        assertEquals("#1 OR #2 OR #3", MultiLevelEmitterExpressionFormatter.defaultExpressionForSlots(3));
    }

    @Test
    void explicitFormatterNormalizesOperatorsAndParenthesisSpacing() {
        assertEquals(
                "#1 AND (#2 OR (#3 AND #4))",
                MultiLevelEmitterExpressionFormatter.format("  #1 and ( #2 or ( #3 and #4 ) )  ")
        );
        assertEquals("((#1))", MultiLevelEmitterExpressionFormatter.format("(( #1 ))"));
    }

    @Test
    void nestedExpressionTokensRetainSpanOffsets() throws Exception {
        MultiLevelEmitterExpressionCompileResult result = MultiLevelEmitterExpressionCompiler.compile(
                "#1 AND (#2 OR (#3 AND #4))",
                4,
                index -> true
        );

        assertEquals(List.of(
                "SLOT_REFERENCE:#1@0:2",
                "WHITESPACE: @2:3",
                "AND:AND@3:6",
                "WHITESPACE: @6:7",
                "LEFT_PAREN:(@7:8",
                "SLOT_REFERENCE:#2@8:10",
                "WHITESPACE: @10:11",
                "OR:OR@11:13",
                "WHITESPACE: @13:14",
                "LEFT_PAREN:(@14:15",
                "SLOT_REFERENCE:#3@15:17",
                "WHITESPACE: @17:18",
                "AND:AND@18:21",
                "WHITESPACE: @21:22",
                "SLOT_REFERENCE:#4@22:24",
                "RIGHT_PAREN:)@24:25",
                "RIGHT_PAREN:)@25:26"
        ), tokenSummaries(result.tokens()));
    }

    private static List<String> tokenSummaries(List<?> tokens) throws Exception {
        List<String> summaries = new ArrayList<>(tokens.size());
        for (Object token : tokens) {
            Method kind = token.getClass().getDeclaredMethod("kind");
            Method text = token.getClass().getDeclaredMethod("text");
            Method start = token.getClass().getDeclaredMethod("start");
            Method end = token.getClass().getDeclaredMethod("end");
            kind.setAccessible(true);
            text.setAccessible(true);
            start.setAccessible(true);
            end.setAccessible(true);
            summaries.add(kind.invoke(token) + ":" + text.invoke(token) + "@" + start.invoke(token) + ":" + end.invoke(token));
        }
        return summaries;
    }
}
