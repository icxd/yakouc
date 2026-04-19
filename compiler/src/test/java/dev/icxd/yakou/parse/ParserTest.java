package dev.icxd.yakou.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.syntax.Lexer;
import dev.icxd.yakou.syntax.TokenKind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ParserTest {

    @Test
    void parsesTestYkFixture() throws IOException {
        String src = loadTestYk();
        var tokens = new Lexer(src, "test.yk").tokenizeAll();
        AstFile ast = new Parser("test.yk", tokens).parseFile();

        assertEquals(11, ast.items().size());
        assertInstanceOf(Item.Use.class, ast.items().get(0));
        assertInstanceOf(Item.Trait.class, ast.items().get(2));
        assertInstanceOf(Item.Class.class, ast.items().get(3));
        assertInstanceOf(Item.Trait.class, ast.items().get(4));
        Item.Trait result = (Item.Trait) ast.items().get(4);
        assertTrue(result.modifiers().contains(dev.icxd.yakou.ast.Modifier.SEALED));
        assertInstanceOf(Item.Pkg.class, ast.items().get(7));
        assertInstanceOf(Item.Fn.class, ast.items().get(9));
        assertInstanceOf(Item.Fn.class, ast.items().get(10));

        Item.Pkg demo = (Item.Pkg) ast.items().get(7);
        assertEquals("demo", demo.name());
        assertEquals(3, demo.items().size()); // User, Animal, Dog

        Item.Fn main = (Item.Fn) ast.items().get(10);
        assertEquals("main", main.name());
    }

    @Test
    void parseMinimalFn() {
        String src = "fn f(): unit {}\n";
        var toks = new Lexer(src, "m.yk").tokenizeAll();
        AstFile ast = new Parser("m.yk", toks).parseFile();
        assertEquals(1, ast.items().size());
        assertEquals(TokenKind.EOF, toks.get(toks.size() - 1).kind());
    }

    @Test
    void parseHashBracesAttribute() {
        String src = """
                use java::lang::Object;
                #{ MyPlugin("a", 1) }
                class C(): Object;
                """;
        var toks = new Lexer(src, "a.yk").tokenizeAll();
        AstFile ast = new Parser("a.yk", toks).parseFile();
        Item.Class c = (Item.Class) ast.items().get(1);
        assertEquals(1, c.attributes().size());
        assertInstanceOf(Expr.Call.class, c.attributes().getFirst().invocation());
    }

    @Test
    void parseAttributeOnClassMethod() {
        String src = """
                use java::lang::Object;
                class L(): Object {
                  #{ Foo() }
                  fn m(self): unit {}
                }
                """;
        var toks = new Lexer(src, "a.yk").tokenizeAll();
        AstFile ast = new Parser("a.yk", toks).parseFile();
        Item.Class c = (Item.Class) ast.items().get(1);
        ClassMethod m = c.methods().getFirst();
        assertEquals(1, m.attributes().size());
        assertInstanceOf(Expr.Call.class, m.attributes().getFirst().invocation());
    }

    private static String loadTestYk() throws IOException {
        Path root = Path.of("..", "test.yk").normalize();
        if (Files.isRegularFile(root)) {
            return Files.readString(root);
        }
        try (InputStream in = ParserTest.class.getResourceAsStream("/test.yk")) {
            if (in == null) {
                throw new IOException("test.yk not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
