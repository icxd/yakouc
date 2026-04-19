package dev.icxd.yakou.resolve;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.icxd.yakou.parse.Parser;
import dev.icxd.yakou.syntax.Lexer;

import org.junit.jupiter.api.Test;

class NameResolverTest {

    @Test
    void minimalFileHasNoIssues() {
        String src = "fn f(): unit {}\n";
        var ast = new Parser("m.yk", new Lexer(src, "m.yk").tokenizeAll()).parseFile();
        ResolveResult r = new NameResolver().resolve(ast);
        assertTrue(r.ok(), r.issues().toString());
    }

    @Test
    void duplicateTopLevelName() {
        String src = """
                fn a(): unit {}
                fn a(): unit {}
                """;
        var ast = new Parser("m.yk", new Lexer(src, "m.yk").tokenizeAll()).parseFile();
        ResolveResult r = new NameResolver().resolve(ast);
        assertFalse(r.ok());
        assertTrue(r.issues().stream().anyMatch(i -> i.kind() == ResolveIssue.Kind.DUPLICATE));
    }

    @Test
    void unresolvedTypeInSignature() {
        String src = "fn f(): UnknownType {}\n";
        var ast = new Parser("m.yk", new Lexer(src, "m.yk").tokenizeAll()).parseFile();
        ResolveResult r = new NameResolver().resolve(ast);
        assertFalse(r.ok());
        assertTrue(r.issues().stream().anyMatch(i -> i.message().contains("UnknownType")));
    }
}
