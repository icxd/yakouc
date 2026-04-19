package dev.icxd.yakou.syntax;

/**
 * Thrown when the source cannot be tokenized. Carries full source for rich
 * diagnostics.
 */
public final class LexException extends RuntimeException {

    private final String path;
    private final String source;
    private final SourcePosition position;

    public LexException(String path, String source, SourcePosition position, String detailMessage) {
        super(detailMessage);
        this.path = path;
        this.source = source;
        this.position = position;
    }

    public String path() {
        return path;
    }

    /**
     * Full file contents, same as passed to {@link dev.icxd.yakou.syntax.Lexer}.
     */
    public String source() {
        return source;
    }

    public SourcePosition position() {
        return position;
    }

    /**
     * Short message without location prefix (e.g.
     * {@code "unterminated string literal"}).
     */
    public String detailMessage() {
        return super.getMessage();
    }

    @Override
    public String getMessage() {
        return path + ":" + position.line() + ":" + position.column() + ": " + super.getMessage();
    }
}
