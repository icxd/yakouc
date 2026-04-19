package dev.icxd.yakou.syntax;

import static dev.icxd.yakou.syntax.TokenKind.EOF;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/** Prints a human-readable token trace for {@code --print-tokens}. */
public final class TokenDebug {

    private TokenDebug() {
    }

    /**
     * One line per token: {@code path:line:col: KIND lexeme}. String lexemes are
     * shown in double
     * quotes with escapes; {@link TokenKind#EOF} is printed as {@code EOF} with an
     * empty lexeme.
     */
    public static void printTokens(PrintStream out, Path path, List<Token> tokens) {
        String label = path.toString();
        for (Token t : tokens) {
            out.println(label + ":" + t.start().line() + ":" + t.start().column() + ": " + formatLexeme(t));
        }
    }

    static String formatLexeme(Token t) {
        if (t.kind() == EOF) {
            return "EOF";
        }
        return t.kind() + " " + quoteLexeme(t);
    }

    private static String quoteLexeme(Token t) {
        String s = t.lexeme();
        if (t.kind() == TokenKind.STRING_LITERAL) {
            return "\"" + escapeStringContents(s) + "\"";
        }
        if (s.isEmpty() || s.indexOf(' ') >= 0 || s.indexOf('\t') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + escapeStringContents(s) + "\"";
        }
        return s;
    }

    private static String escapeStringContents(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
