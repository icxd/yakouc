package dev.icxd.yakou.syntax;

public record Token(TokenKind kind, String lexeme, SourcePosition start) {

    @Override
    public String toString() {
        return kind + " \"" + lexeme + "\" @ " + start.line() + ":" + start.column();
    }
}
