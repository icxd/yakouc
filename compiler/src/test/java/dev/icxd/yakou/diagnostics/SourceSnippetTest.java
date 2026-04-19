package dev.icxd.yakou.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SourceSnippetTest {

    @Test
    void lineAtSingleLineWithoutNewline() {
        assertEquals("\"", SourceSnippet.lineAt("\"", 1));
    }

    @Test
    void lineAtMultiline() {
        assertEquals("b", SourceSnippet.lineAt("a\nb\nc", 2));
    }
}
