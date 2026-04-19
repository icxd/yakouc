package dev.icxd.yakou.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class LexerTest {

    @Test
    void tokenizesTestYkFixture() throws IOException {
        String source = loadTestYkSource();
        List<Token> tokens = new Lexer(source, "test.yk").tokenizeAll();

        assertTrue(tokens.size() > 50, "expected a nontrivial token stream");
        assertEquals(TokenKind.EOF, tokens.get(tokens.size() - 1).kind());

        assertEquals(TokenKind.KW_USE, tokens.get(0).kind());
        assertEquals("use", tokens.get(0).lexeme());
        assertEquals(TokenKind.IDENT, tokens.get(1).kind());
        assertEquals("java", tokens.get(1).lexeme());
        assertEquals(TokenKind.COLON_COLON, tokens.get(2).kind());

        List<TokenKind> kinds = tokens.stream().map(Token::kind).collect(Collectors.toList());
        assertTrue(kinds.contains(TokenKind.KW_SEALED));
        assertTrue(kinds.contains(TokenKind.LT_COLON));
        assertTrue(kinds.contains(TokenKind.COLON_COLON));
        assertTrue(kinds.contains(TokenKind.GE));
        assertTrue(kinds.contains(TokenKind.KW_WHEN));
        assertTrue(kinds.contains(TokenKind.KW_IS));
    }

    @Test
    void stringLiteralDecodesEscapes() {
        List<Token> tokens = new Lexer("let s = \"a\\nb\";", "x.yk").tokenizeAll();
        Token str = tokens.stream()
                .filter(t -> t.kind() == TokenKind.STRING_LITERAL)
                .findFirst()
                .orElseThrow();
        assertEquals("a\nb", str.lexeme());
    }

    @Test
    void unclosedStringThrows() {
        Lexer lexer = new Lexer("let s = \"oops", "x.yk");
        assertThrows(LexException.class, lexer::tokenizeAll);
    }

    @Test
    void lineCommentSkipsSlashSlash() {
        List<Token> tokens = new Lexer("a // comment\nb", "x.yk").tokenizeAll();
        assertEquals(3, tokens.size()); // IDENT a, IDENT b, EOF
        assertEquals("a", tokens.get(0).lexeme());
        assertEquals("b", tokens.get(1).lexeme());
    }

    private static String loadTestYkSource() throws IOException {
        Path root = Path.of("test.yk");
        if (Files.isRegularFile(root)) {
            return Files.readString(root);
        }
        try (InputStream in = LexerTest.class.getResourceAsStream("/test.yk")) {
            if (in == null) {
                throw new IOException("missing test.yk (cwd or /test.yk resource)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
