package dev.icxd.yakou.diagnostics;

/** Pulls a single source line for diagnostics (1-based line index). */
public final class SourceSnippet {

    private SourceSnippet() {
    }

    public static String lineAt(String source, int line1Based) {
        if (line1Based < 1) {
            return "";
        }
        String[] lines = source.split("\\R", -1);
        if (line1Based > lines.length) {
            return "";
        }
        return lines[line1Based - 1];
    }
}
