package dev.icxd.yakou.syntax;

/**
 * Half-open range {@code [start, endExclusive)} in source text. Positions use
 * the same 1-based line/column and 0-based offset conventions as
 * {@link SourcePosition}.
 */
public record SourceSpan(SourcePosition start, SourcePosition endExclusive) {

    /** Span covering a single token (including its lexeme). */
    public static SourceSpan of(Token token) {
        return new SourceSpan(token.start(), endExclusive(token));
    }

    /**
     * Span from the start of {@code first} through the end of {@code last}'s
     * lexeme.
     */
    public static SourceSpan between(Token first, Token last) {
        return new SourceSpan(first.start(), endExclusive(last));
    }

    /**
     * Position immediately after {@code token}'s lexeme (handles newlines inside
     * the lexeme).
     */
    public static SourcePosition endExclusive(Token token) {
        int line = token.start().line();
        int col = token.start().column();
        int off = token.start().offset();
        String lex = token.lexeme();
        for (int i = 0; i < lex.length(); i++) {
            char c = lex.charAt(i);
            if (c == '\n') {
                line++;
                col = 1;
            } else if (c != '\r') {
                col++;
            }
            off++;
        }
        return new SourcePosition(line, col, off);
    }
}
