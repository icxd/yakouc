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
}
