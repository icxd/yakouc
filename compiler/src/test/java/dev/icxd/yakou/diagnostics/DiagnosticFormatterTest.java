package dev.icxd.yakou.diagnostics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.icxd.yakou.syntax.LexException;
import dev.icxd.yakou.syntax.SourcePosition;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DiagnosticFormatterTest {

    @Test
    void lexDiagnosticIncludesSnippetWithoutColor() {
        var pos = new SourcePosition(1, 1, 0);
        var ex = new LexException("err.yk", "\"", pos, "unterminated string literal");
        var buf = new ByteArrayOutputStream();
        new DiagnosticFormatter(new PrintStream(buf, true, StandardCharsets.UTF_8), false).printLex(ex);
        String s = buf.toString(StandardCharsets.UTF_8);
        assertTrue(s.contains("error: unterminated string literal"));
        assertTrue(s.contains("err.yk:1:1"));
        assertTrue(s.contains("1 |"));
        assertTrue(s.contains("\""));
        assertTrue(s.contains("^"));
    }

    @Test
    void simpleErrorWithoutColor() {
        var buf = new ByteArrayOutputStream();
        new DiagnosticFormatter(new PrintStream(buf, true, StandardCharsets.UTF_8), false)
                .printError("no input files");
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("error: no input files"));
    }
}
