package dev.icxd.yakou.parse;

import dev.icxd.yakou.syntax.SourcePosition;
import dev.icxd.yakou.syntax.Token;

/** Parse failure with a location pointing at the unexpected token. */
public final class ParseException extends RuntimeException {

    private final String path;
    private final Token at;

    public ParseException(Token at, String message) {
        this("", at, message);
    }

    public ParseException(String path, Token at, String message) {
        super(message);
        this.path = path != null ? path : "";
        this.at = at;
    }

    /** Source path (often absolute); may be empty if unknown. */
    public String path() {
        return path;
    }

    public Token at() {
        return at;
    }

    public SourcePosition position() {
        return at.start();
    }

    /** Detail text only (no {@code path:line:col} prefix). */
    public String detailMessage() {
        return super.getMessage();
    }

    @Override
    public String getMessage() {
        String detail = super.getMessage();
        String loc = path.isEmpty()
                ? (at.start().line() + ":" + at.start().column())
                : (path + ":" + at.start().line() + ":" + at.start().column());
        return loc + ": " + detail;
    }
}
