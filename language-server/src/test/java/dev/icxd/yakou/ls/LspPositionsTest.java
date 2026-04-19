package dev.icxd.yakou.ls;

import dev.icxd.yakou.syntax.SourcePosition;
import dev.icxd.yakou.syntax.SourceSpan;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LspPositionsTest {

    @Test
    void spanMapsToZeroBasedRange() {
        SourceSpan span = new SourceSpan(new SourcePosition(2, 3, 0), new SourcePosition(2, 5, 0));
        Range r = LspPositions.spanToRange(span);
        assertEquals(1, r.getStart().getLine());
        assertEquals(2, r.getStart().getCharacter());
        assertEquals(1, r.getEnd().getLine());
        assertEquals(4, r.getEnd().getCharacter());
    }

    @Test
    void emptySpanOnOneLineExpandsEndByOneCharacter() {
        SourceSpan span = new SourceSpan(new SourcePosition(1, 2, 0), new SourcePosition(1, 2, 0));
        Range r = LspPositions.spanToRange(span);
        assertEquals(0, r.getStart().getLine());
        assertEquals(1, r.getStart().getCharacter());
        assertEquals(0, r.getEnd().getLine());
        assertEquals(2, r.getEnd().getCharacter());
    }
}
