package git.chexson.chexsonsaeutils.parts.automation.expression;

public record MultiLevelEmitterExpressionDiagnostic(
        Severity severity,
        String code,
        String message,
        int start,
        int end,
        int slotIndex
) {
    public enum Severity {
        VALID,
        WARNING,
        INVALID
    }
}
