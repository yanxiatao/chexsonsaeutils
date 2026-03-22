package git.chexson.chexsonsaeutils.parts.expression;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record MultiLevelEmitterExpressionCompileResult(
        String rawText,
        List<?> tokens,
        List<MultiLevelEmitterExpressionDiagnostic> diagnostics,
        MultiLevelEmitterExpressionPlan plan
) {
    private static final Comparator<MultiLevelEmitterExpressionDiagnostic> PRIMARY_DIAGNOSTIC_ORDER =
            Comparator.comparingInt(MultiLevelEmitterExpressionCompileResult::severityRank)
                    .thenComparingInt(MultiLevelEmitterExpressionDiagnostic::start)
                    .thenComparingInt(MultiLevelEmitterExpressionDiagnostic::end);

    public MultiLevelEmitterExpressionCompileResult {
        rawText = rawText == null ? "" : rawText;
        tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean isInvalid() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == MultiLevelEmitterExpressionDiagnostic.Severity.INVALID);
    }

    public boolean hasWarnings() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == MultiLevelEmitterExpressionDiagnostic.Severity.WARNING);
    }

    public MultiLevelEmitterExpressionDiagnostic primaryDiagnostic() {
        return diagnostics.stream()
                .min(PRIMARY_DIAGNOSTIC_ORDER)
                .orElseGet(() -> new MultiLevelEmitterExpressionDiagnostic(
                        MultiLevelEmitterExpressionDiagnostic.Severity.VALID,
                        "valid_expression",
                        "Expression valid.",
                        0,
                        Math.max(0, rawText.length()),
                        -1
                ));
    }

    private static int severityRank(MultiLevelEmitterExpressionDiagnostic diagnostic) {
        return switch (diagnostic.severity()) {
            case INVALID -> 0;
            case WARNING -> 1;
            case VALID -> 2;
        };
    }
}
