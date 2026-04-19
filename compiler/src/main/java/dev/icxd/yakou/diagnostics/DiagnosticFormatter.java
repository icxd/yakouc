package dev.icxd.yakou.diagnostics;

import dev.icxd.yakou.resolve.ResolveIssue;
import dev.icxd.yakou.typeck.TypeIssue;
import dev.icxd.yakou.parse.ParseException;
import dev.icxd.yakou.syntax.LexException;
import dev.icxd.yakou.syntax.SourcePosition;
import dev.icxd.yakou.syntax.SourceSpan;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * rustc-style colored diagnostics: {@code error:}, {@code --> path:line:col},
 * source line, caret.
 */
public final class DiagnosticFormatter {

    private final PrintStream out;
    private final boolean color;

    public DiagnosticFormatter(PrintStream out, boolean color) {
        this.out = out;
        this.color = color;
    }

    public static DiagnosticFormatter stderr() {
        return new DiagnosticFormatter(System.err, Ansi.useColor());
    }

    public void printLex(LexException e) {
        String path = shortenPath(e.path());
        String title = e.detailMessage();
        SourcePosition pos = e.position();
        String source = e.source();

        if (color) {
            out.println(Ansi.errorWord() + ": " + title);
            out.println(
                    Ansi.dim(" --> ")
                            + Ansi.brightCyan(path + ":" + pos.line() + ":" + pos.column()));
        } else {
            out.println("error: " + title);
            out.println(" --> " + path + ":" + pos.line() + ":" + pos.column());
        }

        if (source == null || source.isEmpty()) {
            return;
        }

        String lineText = SourceSnippet.lineAt(source, pos.line());
        int lineNo = pos.line();
        int col = pos.column();
        int numWidth = Math.max(1, String.valueOf(lineNo).length());

        if (color) {
            out.println(Ansi.dim(" " + " ".repeat(numWidth) + " |"));
        } else {
            out.println(" " + " ".repeat(numWidth) + " |");
        }

        String num = String.format("%" + numWidth + "d", lineNo);
        String body = lineText.isEmpty() ? " " : lineText;
        if (color) {
            out.println(" " + Ansi.dim(num) + Ansi.dim(" | ") + body);
        } else {
            out.println(" " + num + " | " + body);
        }

        int underlineStart = Math.max(0, col - 1);
        String spaces = " ".repeat(underlineStart);
        String margin = " ".repeat(1 + numWidth);
        if (color) {
            out.println(margin + Ansi.dim(" |") + " " + Ansi.green(spaces + "^"));
        } else {
            out.println(margin + " | " + spaces + "^");
        }
    }

    /**
     * Parse error with optional source snippet (same layout as {@link #printLex}).
     */
    public void printParse(String path, String source, ParseException e) {
        String shortPath = shortenPath(path);
        String title = e.detailMessage();
        SourcePosition pos = e.position();
        if (color) {
            out.println(Ansi.errorWord() + ": " + title);
            out.println(
                    Ansi.dim(" --> ")
                            + Ansi.brightCyan(shortPath + ":" + pos.line() + ":" + pos.column()));
        } else {
            out.println("error: " + title);
            out.println(" --> " + shortPath + ":" + pos.line() + ":" + pos.column());
        }

        if (source == null || source.isEmpty()) {
            return;
        }

        String lineText = SourceSnippet.lineAt(source, pos.line());
        int lineNo = pos.line();
        int col = pos.column();
        int numWidth = Math.max(1, String.valueOf(lineNo).length());

        if (color) {
            out.println(Ansi.dim(" " + " ".repeat(numWidth) + " |"));
        } else {
            out.println(" " + " ".repeat(numWidth) + " |");
        }

        String num = String.format("%" + numWidth + "d", lineNo);
        String body = lineText.isEmpty() ? " " : lineText;
        if (color) {
            out.println(" " + Ansi.dim(num) + Ansi.dim(" | ") + body);
        } else {
            out.println(" " + num + " | " + body);
        }

        int underlineStart = Math.max(0, col - 1);
        String spaces = " ".repeat(underlineStart);
        String margin = " ".repeat(1 + numWidth);
        if (color) {
            out.println(margin + Ansi.dim(" |") + " " + Ansi.green(spaces + "^"));
        } else {
            out.println(margin + " | " + spaces + "^");
        }
    }

    /** Plain compiler error (no snippet), e.g. CLI or I/O. */
    public void printError(String message) {
        if (color) {
            out.println(Ansi.errorWord() + ": " + message);
        } else {
            out.println("error: " + message);
        }
    }

    /**
     * Name-resolution (or similar) issue with an optional source span; falls
     * back to {@link #printError} when span or source is missing.
     */
    public void printResolve(String path, String source, ResolveIssue issue) {
        if (issue.span().isEmpty() || source == null || source.isEmpty()) {
            printError(shortenPath(path) + ": " + issue.message());
            return;
        }
        printAtSpan(path, source, issue.message(), issue.span().get());
    }

    /**
     * Type-checking error with optional span (same layout as
     * {@link #printResolve}).
     */
    public void printTypeIssue(String path, String source, TypeIssue issue) {
        if (issue.span().isEmpty() || source == null || source.isEmpty()) {
            printError(shortenPath(path) + ": " + issue.message());
            return;
        }
        printAtSpan(path, source, issue.message(), issue.span().get());
    }

    private void printAtSpan(String path, String source, String message, SourceSpan sp) {
        String shortPath = shortenPath(path);
        SourcePosition start = sp.start();
        SourcePosition end = sp.endExclusive();

        if (color) {
            out.println(Ansi.errorWord() + ": " + message);
            out.println(
                    Ansi.dim(" --> ")
                            + Ansi.brightCyan(shortPath + ":" + start.line() + ":" + start.column()));
        } else {
            out.println("error: " + message);
            out.println(" --> " + shortPath + ":" + start.line() + ":" + start.column());
        }

        String lineText = SourceSnippet.lineAt(source, start.line());
        int lineNo = start.line();
        int numWidth = Math.max(1, String.valueOf(lineNo).length());

        if (color) {
            out.println(Ansi.dim(" " + " ".repeat(numWidth) + " |"));
        } else {
            out.println(" " + " ".repeat(numWidth) + " |");
        }

        String num = String.format("%" + numWidth + "d", lineNo);
        String body = lineText.isEmpty() ? " " : lineText;
        if (color) {
            out.println(" " + Ansi.dim(num) + Ansi.dim(" | ") + body);
        } else {
            out.println(" " + num + " | " + body);
        }

        int underlineStart = Math.max(0, start.column() - 1);
        int width;
        if (start.line() == end.line()) {
            width = Math.max(1, end.column() - start.column());
        } else {
            width = Math.max(1, body.length() - underlineStart);
        }
        String carets = "^".repeat(Math.min(width, Math.max(1, body.length() - underlineStart)));
        String spaces = " ".repeat(underlineStart);
        String margin = " ".repeat(1 + numWidth);
        if (color) {
            out.println(margin + Ansi.dim(" |") + " " + Ansi.green(spaces + carets));
        } else {
            out.println(margin + " | " + spaces + carets);
        }
    }

    private static String shortenPath(String path) {
        if (path == null) {
            return "<unknown>";
        }
        try {
            Path p = Path.of(path);
            Path cwd = Path.of("").toAbsolutePath().normalize();
            Path abs = p.toAbsolutePath().normalize();
            if (abs.startsWith(cwd)) {
                Path rel = cwd.relativize(abs);
                if (!rel.toString().isEmpty()) {
                    return rel.toString();
                }
            }
        } catch (@SuppressWarnings("unused") Exception ignored) {
            // keep full path
        }
        return path;
    }
}
