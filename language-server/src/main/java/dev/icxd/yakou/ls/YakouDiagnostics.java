package dev.icxd.yakou.ls;

import dev.icxd.yakou.SourcePackage;
import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.parse.ParseException;
import dev.icxd.yakou.parse.Parser;
import dev.icxd.yakou.resolve.NameResolver;
import dev.icxd.yakou.resolve.ResolveIssue;
import dev.icxd.yakou.syntax.LexException;
import dev.icxd.yakou.syntax.Lexer;
import dev.icxd.yakou.syntax.SourceSpan;
import dev.icxd.yakou.typeck.TypeChecker;
import dev.icxd.yakou.typeck.TypeIssue;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class YakouDiagnostics {

    static final String SOURCE = "yakou";

    private YakouDiagnostics() {
    }

    static List<Diagnostic> analyze(String documentUri, String text, YakouServerState state) {
        String docLabel = YakouFileContext.safePathLabel(documentUri);
        List<Path> classpath = state.effectiveClasspath();
        List<Diagnostic> out = new ArrayList<>();

        Optional<Path> sourceRoot = YakouFileContext.resolveSourceRoot(state);

        Optional<Path> filePath = YakouFileContext.filePath(documentUri);
        AstFile ast;
        try {
            var tokens = new Lexer(text, docLabel).tokenizeAll();
            ast = new Parser(docLabel, tokens).parseFile();
        } catch (LexException e) {
            out.add(
                    new Diagnostic(
                            LspPositions.pointRange(e.position()),
                            e.detailMessage(),
                            DiagnosticSeverity.Error,
                            SOURCE));
            return out;
        } catch (ParseException e) {
            out.add(
                    new Diagnostic(
                            LspPositions.pointRange(e.position()),
                            e.detailMessage(),
                            DiagnosticSeverity.Error,
                            SOURCE));
            return out;
        }

        if (sourceRoot.isPresent() && filePath.isPresent()) {
            Path root = sourceRoot.get();
            Path path = filePath.get();
            Optional<String> prefixOpt;
            try {
                prefixOpt = SourcePackage.implicitPrefix(root, path);
            } catch (IllegalArgumentException e) {
                out.add(
                        new Diagnostic(
                                LspPositions.lineStartRange(1),
                                "file is not under the configured Yakou source root (" + root + ")",
                                DiagnosticSeverity.Error,
                                SOURCE));
                return out;
            }
            ast = SourcePackage.applyImplicitPrefix(ast, prefixOpt);
        }

        var resolve = new NameResolver().resolve(ast);
        for (ResolveIssue issue : resolve.issues()) {
            out.add(resolveOrTypeDiag(issue.message(), issue.span()));
        }

        if (!resolve.issues().isEmpty()) {
            return out;
        }

        var tres = TypeChecker.check(ast, classpath);
        for (TypeIssue issue : tres.issues()) {
            out.add(resolveOrTypeDiag(issue.message(), issue.span()));
        }

        return out;
    }

    private static Diagnostic resolveOrTypeDiag(String message, Optional<SourceSpan> spanOpt) {
        if (spanOpt.isEmpty()) {
            return new Diagnostic(
                    LspPositions.lineStartRange(1),
                    message,
                    DiagnosticSeverity.Error,
                    SOURCE);
        }
        return new Diagnostic(
                LspPositions.spanToRange(spanOpt.get()),
                message,
                DiagnosticSeverity.Error,
                SOURCE);
    }
}
