package git.chexson.chexsonsaeutils.parts.expression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntPredicate;

public final class MultiLevelEmitterExpressionCompiler {

    private static final Comparator<MultiLevelEmitterExpressionDiagnostic> DIAGNOSTIC_ORDER =
            Comparator.comparingInt(MultiLevelEmitterExpressionCompiler::severityRank)
                    .thenComparingInt(MultiLevelEmitterExpressionDiagnostic::start)
                    .thenComparingInt(MultiLevelEmitterExpressionDiagnostic::end)
                    .thenComparing(MultiLevelEmitterExpressionDiagnostic::code);

    private MultiLevelEmitterExpressionCompiler() {
    }

    public static MultiLevelEmitterExpressionCompileResult compile(
            String rawText,
            int configuredSlots,
            IntPredicate markedSlotPredicate
    ) {
        String safeRawText = rawText == null ? "" : rawText;
        List<Token> tokens = tokenize(safeRawText);
        List<Token> parserTokens = tokens.stream()
                .filter(token -> token.kind != TokenKind.WHITESPACE)
                .toList();
        List<MultiLevelEmitterExpressionDiagnostic> diagnostics = new ArrayList<>();

        ExprNode root;
        if (safeRawText.isBlank() && configuredSlots <= 0) {
            root = EmptyNode.INSTANCE;
        } else {
            Parser parser = new Parser(safeRawText, parserTokens, diagnostics);
            root = parser.parseExpression();
        }

        validateSlots(tokens, Math.max(0, configuredSlots), markedSlotPredicate, diagnostics);

        if (root != null && !containsInvalidDiagnostics(diagnostics) && mixesOperatorsWithoutGrouping(root)) {
            diagnostics.add(new MultiLevelEmitterExpressionDiagnostic(
                    MultiLevelEmitterExpressionDiagnostic.Severity.WARNING,
                    "mixed_precedence_warning",
                    "Expression mixes AND and OR without parentheses.",
                    root.start(),
                    root.end(),
                    -1
            ));
        }

        if (diagnostics.isEmpty()) {
            diagnostics.add(new MultiLevelEmitterExpressionDiagnostic(
                    MultiLevelEmitterExpressionDiagnostic.Severity.VALID,
                    "valid_expression",
                    "Expression valid.",
                    0,
                    safeRawText.length(),
                    -1
            ));
        }

        diagnostics.sort(DIAGNOSTIC_ORDER);
        MultiLevelEmitterExpressionPlan plan = containsInvalidDiagnostics(diagnostics) || root == null
                ? null
                : new CompiledPlan(root);

        return new MultiLevelEmitterExpressionCompileResult(safeRawText, tokens, diagnostics, plan);
    }

    private static List<Token> tokenize(String rawText) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < rawText.length()) {
            char current = rawText.charAt(index);
            if (Character.isWhitespace(current)) {
                int start = index;
                while (index < rawText.length() && Character.isWhitespace(rawText.charAt(index))) {
                    index++;
                }
                tokens.add(new Token(TokenKind.WHITESPACE, rawText.substring(start, index), start, index, -1));
                continue;
            }
            if (current == '(') {
                tokens.add(new Token(TokenKind.LEFT_PAREN, "(", index, index + 1, -1));
                index++;
                continue;
            }
            if (current == ')') {
                tokens.add(new Token(TokenKind.RIGHT_PAREN, ")", index, index + 1, -1));
                index++;
                continue;
            }
            if (current == '#') {
                int start = index;
                index++;
                while (index < rawText.length() && Character.isDigit(rawText.charAt(index))) {
                    index++;
                }
                if (index > start + 1) {
                    String value = rawText.substring(start, index);
                    int slotNumber;
                    try {
                        slotNumber = Integer.parseInt(value.substring(1));
                    } catch (NumberFormatException exception) {
                        slotNumber = Integer.MAX_VALUE;
                    }
                    tokens.add(new Token(TokenKind.SLOT_REFERENCE, value, start, index, slotNumber));
                } else {
                    while (index < rawText.length() && !Character.isWhitespace(rawText.charAt(index))
                            && rawText.charAt(index) != '(' && rawText.charAt(index) != ')') {
                        index++;
                    }
                    tokens.add(new Token(
                            TokenKind.INVALID_FRAGMENT,
                            rawText.substring(start, index),
                            start,
                            index,
                            -1
                    ));
                }
                continue;
            }
            if (Character.isLetter(current)) {
                int start = index;
                while (index < rawText.length() && Character.isLetter(rawText.charAt(index))) {
                    index++;
                }
                String value = rawText.substring(start, index);
                String normalized = value.toUpperCase(Locale.ROOT);
                if ("AND".equals(normalized)) {
                    tokens.add(new Token(TokenKind.AND, value, start, index, -1));
                } else if ("OR".equals(normalized)) {
                    tokens.add(new Token(TokenKind.OR, value, start, index, -1));
                } else {
                    tokens.add(new Token(TokenKind.INVALID_FRAGMENT, value, start, index, -1));
                }
                continue;
            }

            int start = index;
            while (index < rawText.length() && !Character.isWhitespace(rawText.charAt(index))
                    && rawText.charAt(index) != '(' && rawText.charAt(index) != ')') {
                if (rawText.charAt(index) == '#') {
                    break;
                }
                index++;
            }
            if (index == start) {
                index++;
            }
            tokens.add(new Token(
                    TokenKind.INVALID_FRAGMENT,
                    rawText.substring(start, index),
                    start,
                    index,
                    -1
            ));
        }
        return List.copyOf(tokens);
    }

    private static void validateSlots(
            List<Token> tokens,
            int configuredSlots,
            IntPredicate markedSlotPredicate,
            List<MultiLevelEmitterExpressionDiagnostic> diagnostics
    ) {
        IntPredicate safeMarkedSlotPredicate = markedSlotPredicate == null ? index -> true : markedSlotPredicate;
        for (Token token : tokens) {
            if (token.kind != TokenKind.SLOT_REFERENCE) {
                continue;
            }
            int slotNumber = token.slotNumber;
            if (slotNumber < 1 || slotNumber > configuredSlots) {
                diagnostics.add(new MultiLevelEmitterExpressionDiagnostic(
                        MultiLevelEmitterExpressionDiagnostic.Severity.INVALID,
                        "out_of_range_slot",
                        token.text + " is out of range for the current slot list.",
                        token.start,
                        token.end,
                        slotNumber - 1
                ));
                continue;
            }
            if (!safeMarkedSlotPredicate.test(slotNumber - 1)) {
                diagnostics.add(new MultiLevelEmitterExpressionDiagnostic(
                        MultiLevelEmitterExpressionDiagnostic.Severity.WARNING,
                        "unmarked_slot",
                        token.text + " references an existing slot with no marked item.",
                        token.start,
                        token.end,
                        slotNumber - 1
                ));
            }
        }
    }

    private static boolean containsInvalidDiagnostics(List<MultiLevelEmitterExpressionDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == MultiLevelEmitterExpressionDiagnostic.Severity.INVALID);
    }

    private static boolean mixesOperatorsWithoutGrouping(ExprNode node) {
        if (node == null || node instanceof ParenthesizedNode || node instanceof SlotNode || node instanceof EmptyNode) {
            return false;
        }
        if (node instanceof BinaryNode binaryNode) {
            if (childHasDifferentOperator(binaryNode.operator, binaryNode.left)
                    || childHasDifferentOperator(binaryNode.operator, binaryNode.right)) {
                return true;
            }
            return mixesOperatorsWithoutGrouping(binaryNode.left) || mixesOperatorsWithoutGrouping(binaryNode.right);
        }
        return false;
    }

    private static boolean childHasDifferentOperator(TokenKind operator, ExprNode child) {
        return child instanceof BinaryNode binaryChild
                && !(child instanceof ParenthesizedNode)
                && binaryChild.operator != operator;
    }

    private static int severityRank(MultiLevelEmitterExpressionDiagnostic diagnostic) {
        return switch (diagnostic.severity()) {
            case INVALID -> 0;
            case WARNING -> 1;
            case VALID -> 2;
        };
    }

    private enum TokenKind {
        SLOT_REFERENCE,
        AND,
        OR,
        LEFT_PAREN,
        RIGHT_PAREN,
        WHITESPACE,
        INVALID_FRAGMENT
    }

    static final class Token {
        private final TokenKind kind;
        private final String text;
        private final int start;
        private final int end;
        private final int slotNumber;

        private Token(TokenKind kind, String text, int start, int end, int slotNumber) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.text = Objects.requireNonNull(text, "text");
            this.start = start;
            this.end = end;
            this.slotNumber = slotNumber;
        }

        public String kind() {
            return kind.name();
        }

        public String text() {
            return text;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public int slotNumber() {
            return slotNumber;
        }
    }

    private static final class Parser {
        private final String rawText;
        private final List<Token> tokens;
        private final List<MultiLevelEmitterExpressionDiagnostic> diagnostics;
        private int index;

        private Parser(
                String rawText,
                List<Token> tokens,
                List<MultiLevelEmitterExpressionDiagnostic> diagnostics
        ) {
            this.rawText = rawText;
            this.tokens = tokens;
            this.diagnostics = diagnostics;
        }

        private ExprNode parseExpression() {
            if (tokens.isEmpty()) {
                addDiagnostic(
                        MultiLevelEmitterExpressionDiagnostic.Severity.INVALID,
                        "missing_operand",
                        "Expression is missing an operand.",
                        0,
                        rawText.length()
                );
                return new ErrorNode(0, rawText.length());
            }

            ExprNode expression = parseOrExpression();
            while (!isAtEnd()) {
                Token token = advance();
                addDiagnostic(
                        MultiLevelEmitterExpressionDiagnostic.Severity.INVALID,
                        "unexpected_token",
                        "Unexpected token '" + token.text + "'.",
                        token.start,
                        token.end
                );
            }
            return expression;
        }

        private ExprNode parseOrExpression() {
            ExprNode left = parseAndExpression();
            while (match(TokenKind.OR)) {
                Token operator = previous();
                ExprNode right = parseAndExpression();
                left = new BinaryNode(TokenKind.OR, left, right, left.start(), right.end());
            }
            return left;
        }

        private ExprNode parseAndExpression() {
            ExprNode left = parsePrimary();
            while (match(TokenKind.AND)) {
                Token operator = previous();
                ExprNode right = parsePrimary();
                left = new BinaryNode(TokenKind.AND, left, right, left.start(), right.end());
            }
            return left;
        }

        private ExprNode parsePrimary() {
            if (isAtEnd()) {
                int caret = rawText.length();
                addDiagnostic(
                        MultiLevelEmitterExpressionDiagnostic.Severity.INVALID,
                        "missing_operand",
                        "Expression is missing an operand.",
                        caret,
                        caret
                );
                return new ErrorNode(caret, caret);
            }

            Token token = advance();
            return switch (token.kind) {
                case SLOT_REFERENCE -> new SlotNode(token.slotNumber, token.start, token.end);
                case LEFT_PAREN -> parseParenthesized(token);
                case INVALID_FRAGMENT -> {
                    addDiagnostic(
                            MultiLevelEmitterExpressionDiagnostic.Severity.INVALID,
                            "unexpected_token",
                            "Unexpected token '" + token.text + "'.",
                            token.start,
                            token.end
                    );
                    yield new ErrorNode(token.start, token.end);
                }
                case RIGHT_PAREN, AND, OR -> {
                    addDiagnostic(
                            MultiLevelEmitterExpressionDiagnostic.Severity.INVALID,
                            "missing_operand",
                            "Expression is missing an operand.",
                            token.start,
                            token.end
                    );
                    yield new ErrorNode(token.start, token.end);
                }
                case WHITESPACE -> throw new IllegalStateException("Whitespace tokens should not reach the parser.");
            };
        }

        private ExprNode parseParenthesized(Token openParen) {
            ExprNode inner = parseOrExpression();
            if (match(TokenKind.RIGHT_PAREN)) {
                Token closeParen = previous();
                return new ParenthesizedNode(inner, openParen.start, closeParen.end);
            }

            addDiagnostic(
                    MultiLevelEmitterExpressionDiagnostic.Severity.INVALID,
                    "missing_closing_paren",
                    "Missing closing parenthesis.",
                    openParen.start,
                    openParen.end
            );
            return new ParenthesizedNode(inner, openParen.start, Math.max(openParen.end, inner.end()));
        }

        private boolean match(TokenKind kind) {
            if (check(kind)) {
                advance();
                return true;
            }
            return false;
        }

        private boolean check(TokenKind kind) {
            return !isAtEnd() && peek().kind == kind;
        }

        private Token advance() {
            if (!isAtEnd()) {
                index++;
            }
            return previous();
        }

        private boolean isAtEnd() {
            return index >= tokens.size();
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token previous() {
            return tokens.get(index - 1);
        }

        private void addDiagnostic(
                MultiLevelEmitterExpressionDiagnostic.Severity severity,
                String code,
                String message,
                int start,
                int end
        ) {
            diagnostics.add(new MultiLevelEmitterExpressionDiagnostic(severity, code, message, start, end, -1));
        }
    }

    private interface ExprNode {
        boolean evaluate(List<Boolean> slotResults);

        int highestReferencedSlot();

        int start();

        int end();
    }

    private record SlotNode(int slotNumber, int start, int end) implements ExprNode {
        @Override
        public boolean evaluate(List<Boolean> slotResults) {
            int index = slotNumber - 1;
            return slotResults != null
                    && index >= 0
                    && index < slotResults.size()
                    && Boolean.TRUE.equals(slotResults.get(index));
        }

        @Override
        public int highestReferencedSlot() {
            return slotNumber;
        }
    }

    private record BinaryNode(TokenKind operator, ExprNode left, ExprNode right, int start, int end) implements ExprNode {
        @Override
        public boolean evaluate(List<Boolean> slotResults) {
            return switch (operator) {
                case AND -> left.evaluate(slotResults) && right.evaluate(slotResults);
                case OR -> left.evaluate(slotResults) || right.evaluate(slotResults);
                default -> false;
            };
        }

        @Override
        public int highestReferencedSlot() {
            return Math.max(left.highestReferencedSlot(), right.highestReferencedSlot());
        }
    }

    private record ParenthesizedNode(ExprNode inner, int start, int end) implements ExprNode {
        @Override
        public boolean evaluate(List<Boolean> slotResults) {
            return inner.evaluate(slotResults);
        }

        @Override
        public int highestReferencedSlot() {
            return inner.highestReferencedSlot();
        }
    }

    private record ErrorNode(int start, int end) implements ExprNode {
        @Override
        public boolean evaluate(List<Boolean> slotResults) {
            return false;
        }

        @Override
        public int highestReferencedSlot() {
            return 0;
        }
    }

    private enum EmptyNode implements ExprNode {
        INSTANCE;

        @Override
        public boolean evaluate(List<Boolean> slotResults) {
            return false;
        }

        @Override
        public int highestReferencedSlot() {
            return 0;
        }

        @Override
        public int start() {
            return 0;
        }

        @Override
        public int end() {
            return 0;
        }
    }

    private static final class CompiledPlan implements MultiLevelEmitterExpressionPlan {
        private final ExprNode root;

        private CompiledPlan(ExprNode root) {
            this.root = root;
        }

        @Override
        public boolean evaluate(List<Boolean> slotResults) {
            return root.evaluate(slotResults);
        }

        @Override
        public int highestReferencedSlot() {
            return root.highestReferencedSlot();
        }
    }
}
