package dev.icxd.yakou.ls;

import dev.icxd.yakou.syntax.SourcePosition;
import dev.icxd.yakou.syntax.SourceSpan;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Maps Yakou 1-based line/column to LSP 0-based (UTF-16 code unit) positions.
 * The
 * compiler uses Java {@link String}-style indices; align with LSP UTF-16
 * columns.
 */
final class LspPositions {

    private LspPositions() {
    }

    static Position toPosition(SourcePosition p) {
        return new Position(Math.max(0, p.line() - 1), Math.max(0, p.column() - 1));
    }

    /**
     * LSP {@link Range} end is exclusive (same convention as {@link SourceSpan}'s
     * {@code endExclusive}).
     */
    static Range spanToRange(SourceSpan span) {
        Position start = toPosition(span.start());
        Position end = toPosition(span.endExclusive());
        Range r = new Range(start, end);
        if (start.getLine() == end.getLine()
                && start.getCharacter() == end.getCharacter()) {
            end.setCharacter(end.getCharacter() + 1);
        }
        return r;
    }

    static Range pointRange(SourcePosition p) {
        Position start = toPosition(p);
        Position end = new Position(start.getLine(), start.getCharacter() + 1);
        return new Range(start, end);
    }

    static Range lineStartRange(int oneBasedLine) {
        int line = Math.max(0, oneBasedLine - 1);
        Position p = new Position(line, 0);
        return new Range(p, p);
    }

    /**
     * Convert LSP {@link Position} (0-based line, UTF-16 column) to a source
     * offset.
     */
    static int utf16Offset(String text, Position pos) {
        int start = lineStartOffset(text, pos.getLine());
        int want = pos.getCharacter();
        int j = start;
        int utf16 = 0;
        while (j < text.length() && utf16 < want) {
            char ch = text.charAt(j);
            if (ch == '\n' || ch == '\r') {
                break;
            }
            if (Character.isHighSurrogate(ch) && j + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(j + 1))) {
                j += 2;
            } else {
                j++;
            }
            utf16++;
        }
        return j;
    }

    static int lineStartOffset(String text, int zeroBasedLine) {
        int line = 0;
        int i = 0;
        while (i < text.length() && line < zeroBasedLine) {
            char c = text.charAt(i++);
            if (c == '\r') {
                if (i < text.length() && text.charAt(i) == '\n') {
                    i++;
                }
                line++;
            } else if (c == '\n') {
                line++;
            }
        }
        return i;
    }

    /**
     * Inverse of {@link #utf16Offset}: 0-based line and UTF-16 column at
     * {@code wantOffset}.
     */
    static Position offsetToPosition(String text, int wantOffset) {
        int clamped = Math.min(Math.max(0, wantOffset), text.length());
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < clamped;) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i += 2;
                } else {
                    i++;
                }
                line++;
                lineStart = i;
            } else if (c == '\n') {
                i++;
                line++;
                lineStart = i;
            } else {
                i++;
            }
        }
        int colUtf16 = utf16Column(text, lineStart, clamped);
        return new Position(line, colUtf16);
    }

    /** UTF-16 column distance from {@code fromInclusive} to {@code toExclusive}. */
    static int utf16Column(String text, int fromInclusive, int toExclusive) {
        int utf16 = 0;
        int i = fromInclusive;
        while (i < toExclusive && i < text.length()) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                i += 2;
            } else {
                i++;
            }
            utf16++;
        }
        return utf16;
    }
}
