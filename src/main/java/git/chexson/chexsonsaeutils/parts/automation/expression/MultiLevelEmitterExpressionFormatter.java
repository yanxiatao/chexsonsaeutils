package git.chexson.chexsonsaeutils.parts.automation.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MultiLevelEmitterExpressionFormatter {

    private MultiLevelEmitterExpressionFormatter() {
    }

    public static String defaultExpressionForSlots(int slotCount) {
        if (slotCount <= 0) {
            return "";
        }
        List<String> references = new ArrayList<>(slotCount);
        for (int slot = 1; slot <= slotCount; slot++) {
            references.add("#" + slot);
        }
        return String.join(" OR ", references);
    }

    public static String format(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        List<Token> tokens = tokenize(rawText);
        StringBuilder formatted = new StringBuilder(rawText.length());
        Token previous = null;
        for (Token token : tokens) {
            if (token.type == TokenType.WHITESPACE) {
                continue;
            }
            if (formatted.length() > 0 && needsSpace(previous, token)) {
                formatted.append(' ');
            }
            formatted.append(switch (token.type) {
                case OPERATOR -> token.text.toUpperCase(Locale.ROOT);
                default -> token.text;
            });
            previous = token;
        }
        return formatted.toString().trim();
    }

    private static boolean needsSpace(Token previous, Token current) {
        if (previous == null) {
            return false;
        }
        if (previous.type == TokenType.LEFT_PAREN || current.type == TokenType.RIGHT_PAREN) {
            return false;
        }
        if (previous.type == TokenType.OPERATOR || current.type == TokenType.OPERATOR) {
            return true;
        }
        return current.type != TokenType.RIGHT_PAREN;
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
                tokens.add(new Token(TokenType.WHITESPACE, rawText.substring(start, index)));
                continue;
            }
            if (current == '(') {
                tokens.add(new Token(TokenType.LEFT_PAREN, "("));
                index++;
                continue;
            }
            if (current == ')') {
                tokens.add(new Token(TokenType.RIGHT_PAREN, ")"));
                index++;
                continue;
            }
            if (current == '#') {
                int start = index;
                index++;
                while (index < rawText.length() && Character.isDigit(rawText.charAt(index))) {
                    index++;
                }
                tokens.add(new Token(TokenType.SLOT_REFERENCE, rawText.substring(start, index)));
                continue;
            }
            if (Character.isLetter(current)) {
                int start = index;
                while (index < rawText.length() && Character.isLetter(rawText.charAt(index))) {
                    index++;
                }
                String word = rawText.substring(start, index);
                String normalized = word.toUpperCase(Locale.ROOT);
                if ("AND".equals(normalized) || "OR".equals(normalized)) {
                    tokens.add(new Token(TokenType.OPERATOR, word));
                } else {
                    tokens.add(new Token(TokenType.TEXT, word));
                }
                continue;
            }

            int start = index;
            while (index < rawText.length() && !Character.isWhitespace(rawText.charAt(index))
                    && rawText.charAt(index) != '(' && rawText.charAt(index) != ')') {
                index++;
            }
            tokens.add(new Token(TokenType.TEXT, rawText.substring(start, index)));
        }
        return tokens;
    }

    private enum TokenType {
        SLOT_REFERENCE,
        OPERATOR,
        LEFT_PAREN,
        RIGHT_PAREN,
        TEXT,
        WHITESPACE
    }

    private record Token(TokenType type, String text) {
    }
}
