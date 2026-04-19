package dev.icxd.yakou.typeck;

import dev.icxd.yakou.parse.Parser;
import dev.icxd.yakou.syntax.Lexer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeCheckerTest {

    @Test
    void typecheckOkSample() throws Exception {
        Path p = Path.of("src/test/resources/typecheck_ok.yk").toAbsolutePath().normalize();
        String source = Files.readString(p);
        var tokens = new Lexer(source, p.toString()).tokenizeAll();
        var ast = new Parser(p.toString(), tokens).parseFile();
        TypeResult r = TypeChecker.check(ast);
        assertTrue(r.ok(), () -> String.valueOf(r.issues()));
    }

    @Test
    void sealedSubclassRegisteredAsVariant() throws Exception {
        Path p = Path.of("src/test/resources/sealed_transitive.yk").toAbsolutePath().normalize();
        String source = Files.readString(p);
        var tokens = new Lexer(source, p.toString()).tokenizeAll();
        var ast = new Parser(p.toString(), tokens).parseFile();
        TypeResult r = TypeChecker.check(ast);
        assertTrue(r.ok(), () -> String.valueOf(r.issues()));
    }

    @Test
    void whenMultibindAndExhaustive() throws Exception {
        Path p = Path.of("src/test/resources/when_multibind.yk").toAbsolutePath().normalize();
        String source = Files.readString(p);
        var tokens = new Lexer(source, p.toString()).tokenizeAll();
        var ast = new Parser(p.toString(), tokens).parseFile();
        TypeResult r = TypeChecker.check(ast);
        assertTrue(r.ok(), () -> String.valueOf(r.issues()));
    }

    @Test
    void jvmSuperclassTypechecks() throws Exception {
        Path p = Path.of("src/test/resources/jvm_super_ok.yk").toAbsolutePath().normalize();
        String source = Files.readString(p);
        var tokens = new Lexer(source, p.toString()).tokenizeAll();
        var ast = new Parser(p.toString(), tokens).parseFile();
        TypeResult r = TypeChecker.check(ast, List.of());
        assertTrue(r.ok(), () -> String.valueOf(r.issues()));
    }

    @Test
    void whenNonExhaustiveFails() throws Exception {
        Path p = Path.of("src/test/resources/when_non_exhaustive.yk").toAbsolutePath().normalize();
        String source = Files.readString(p);
        var tokens = new Lexer(source, p.toString()).tokenizeAll();
        var ast = new Parser(p.toString(), tokens).parseFile();
        TypeResult r = TypeChecker.check(ast);
        assertFalse(r.ok());
        assertTrue(
                r.issues().stream().anyMatch(i -> i.message().contains("non-exhaustive when")),
                () -> String.valueOf(r.issues()));
    }
}
