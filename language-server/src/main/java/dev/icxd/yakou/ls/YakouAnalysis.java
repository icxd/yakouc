package dev.icxd.yakou.ls;

import dev.icxd.yakou.SourcePackage;
import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.parse.Parser;
import dev.icxd.yakou.resolve.NameResolver;
import dev.icxd.yakou.resolve.ResolveResult;
import dev.icxd.yakou.syntax.Lexer;
import dev.icxd.yakou.typeck.JavaInterop;
import dev.icxd.yakou.typeck.TypeChecker;
import dev.icxd.yakou.typeck.TypecheckBundle;
import dev.icxd.yakou.typeck.Ty;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the same parse → resolve → typecheck pipeline as diagnostics, optionally
 * recording per-expression types for IDE features.
 */
final class YakouAnalysis {

    private YakouAnalysis() {
    }

    record Result(
            AstFile ast,
            ResolveResult resolve,
            TypecheckBundle bundle,
            Map<Expr, Ty> exprTypes,
            JavaInterop javaInterop) {
    }

    /**
     * @return empty if lex/parse fails or resolve fails. Typecheck issues do not
     *         discard the result: expression types are still recorded (needed for
     *         completion on {@code self.} where the trailing expr fails the return
     *         check until a member is typed).
     */
    static Optional<Result> analyze(String documentUri, String text, YakouServerState state) {
        String docLabel = YakouFileContext.safePathLabel(documentUri);
        List<Path> classpath = state.effectiveClasspath();

        Optional<Path> sourceRoot = YakouFileContext.resolveSourceRoot(state);
        Optional<Path> filePath = YakouFileContext.filePath(documentUri);

        AstFile ast;
        try {
            var tokens = new Lexer(text, docLabel).tokenizeAll();
            ast = new Parser(docLabel, tokens).parseFile();
        } catch (Exception e) {
            return Optional.empty();
        }

        if (sourceRoot.isPresent() && filePath.isPresent()) {
            Path root = sourceRoot.get();
            Path path = filePath.get();
            try {
                Optional<String> prefixOpt = SourcePackage.implicitPrefix(root, path);
                ast = SourcePackage.applyImplicitPrefix(ast, prefixOpt);
            } catch (IllegalArgumentException ignored) {
                // Same as parseOnly: workspace root vs file location may disagree; keep the
                // raw AST so resolve/typecheck still run (paths match unqualified classes).
            }
        }

        var resolve = new NameResolver().resolve(ast);
        if (!resolve.issues().isEmpty()) {
            return Optional.empty();
        }

        var exprTypes = new IdentityHashMap<Expr, Ty>();
        TypecheckBundle bundle = TypeChecker.checkBundle(ast, classpath, exprTypes);
        return Optional.of(new Result(ast, resolve, bundle, exprTypes, new JavaInterop(classpath)));
    }

    /**
     * Parse tree only (no resolve), for best-effort completion when the file has
     * errors.
     */
    static Optional<AstFile> parseOnly(String documentUri, String text, YakouServerState state) {
        String docLabel = YakouFileContext.safePathLabel(documentUri);
        Optional<Path> sourceRoot = YakouFileContext.resolveSourceRoot(state);
        Optional<Path> filePath = YakouFileContext.filePath(documentUri);
        try {
            var tokens = new Lexer(text, docLabel).tokenizeAll();
            AstFile ast = new Parser(docLabel, tokens).parseFile();
            if (sourceRoot.isPresent() && filePath.isPresent()) {
                try {
                    Optional<String> prefixOpt = SourcePackage.implicitPrefix(sourceRoot.get(), filePath.get());
                    ast = SourcePackage.applyImplicitPrefix(ast, prefixOpt);
                } catch (IllegalArgumentException ignored) {
                    // Package inference failed (path vs sourceRoot); still use raw AST for
                    // completions.
                }
            }
            return Optional.of(ast);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
