package dev.icxd.yakou;

import dev.icxd.yakou.ArgumentParser.ParseResult;
import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.AstPrinter;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.diagnostics.DiagnosticFormatter;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.Modifier;
import dev.icxd.yakou.emit.CodegenException;
import dev.icxd.yakou.emit.JvmClassNames;
import dev.icxd.yakou.emit.YkLinker;
import dev.icxd.yakou.emit.YkProgramEmitter;
import dev.icxd.yakou.parse.ParseException;
import dev.icxd.yakou.parse.Parser;
import dev.icxd.yakou.resolve.NameResolver;
import dev.icxd.yakou.typeck.Ty;
import dev.icxd.yakou.typeck.TypeChecker;
import dev.icxd.yakou.typeck.TypecheckBundle;
import dev.icxd.yakou.syntax.LexException;
import dev.icxd.yakou.syntax.Lexer;
import dev.icxd.yakou.syntax.SourceSpan;
import dev.icxd.yakou.syntax.TokenDebug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Yakou compiler driver, invoked as {@code yakouc}. Interface is modeled
 * after {@code gcc} and {@code clang}:
 * source files as arguments, {@code -c} for compile-only (no link step),
 * {@code -d} for class output, {@code -cp} for the classpath.
 */
public final class Yakouc {

    private record SourceUnit(Path abs, String source, AstFile ast) {
    }

    private record BundleResult(int exit, List<String> ykMainModules) {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Runs the compiler driver and returns a process exit code (0 = success).
     */
    public static int run(String[] args) {
        ParseResult pr = ArgumentParser.parse(args);
        if (pr instanceof ParseResult.Err e) {
            DiagnosticFormatter.stderr().printError(e.message());
            usageHint();
            return 1;
        }
        if (pr instanceof ParseResult.Meta m) {
            if (m.help()) {
                printHelp(System.out);
                return 0;
            }
            if (m.version()) {
                System.out.println(programName() + " " + version());
                return 0;
            }
            return 0;
        }
        if (pr instanceof ParseResult.Ok ok) {
            return compile(ok.options());
        }
        throw new IllegalStateException("unhandled parse result");
    }

    private static int compile(CompileOptions options) {
        if (options.verbose()) {
            System.err.println(
                    programName()
                            + ": compile: "
                            + options.sources().size()
                            + " file(s) -> "
                            + options.classOutputDir());
            if (!options.classPath().isEmpty()) {
                System.err.println(programName() + ": classpath: " + options.classPath());
            }
            options.sourceRoot().ifPresent(r -> System.err.println(programName() + ": source root: " + r));
        }
        if (options.printTokens() || options.printAst() || options.checkResolve() || options.checkTypes()) {
            return lexParseDebug(options);
        }
        List<SourceUnit> units = new ArrayList<>();
        int exit = 0;
        for (Path p : options.sources()) {
            Path abs = p.toAbsolutePath().normalize();
            String source;
            try {
                source = Files.readString(abs);
            } catch (IOException e) {
                DiagnosticFormatter.stderr().printError("could not read '" + p + "': " + e.getMessage());
                exit = 1;
                continue;
            }
            try {
                var tokens = new Lexer(source, abs.toString()).tokenizeAll();
                AstFile ast = new Parser(abs.toString(), tokens).parseFile();
                try {
                    ast = applySourceLayout(ast, abs, options);
                } catch (IllegalArgumentException e) {
                    DiagnosticFormatter.stderr().printError(abs + ": " + e.getMessage());
                    exit = 1;
                    continue;
                }
                units.add(new SourceUnit(abs, source, ast));
            } catch (LexException e) {
                DiagnosticFormatter.stderr().printLex(e);
                exit = 1;
            } catch (ParseException e) {
                DiagnosticFormatter.stderr().printParse(abs.toString(), source, e);
                exit = 1;
            }
        }
        if (units.size() != options.sources().size()) {
            return exit;
        }
        if (units.isEmpty()) {
            return exit;
        }

        AstFile program;
        if (units.size() == 1) {
            program = units.get(0).ast();
        } else {
            try {
                program = mergeSourcesForCompile(
                        units.stream().map(SourceUnit::abs).toList(),
                        units.stream().map(SourceUnit::ast).toList(),
                        options);
            } catch (IllegalArgumentException e) {
                DiagnosticFormatter.stderr().printError(e.getMessage());
                return 1;
            }
        }
        BundleResult br = compileProgramBundle(program, units, options);
        exit = br.exit();
        List<String> ykMainModules = br.ykMainModules();
        if (exit == 0 && !options.compileOnly()) {
            if (ykMainModules.isEmpty()) {
                DiagnosticFormatter.stderr()
                        .printError("link: no module defines top-level fn main (expected exactly one)");
                exit = 1;
            } else if (ykMainModules.size() > 1) {
                DiagnosticFormatter.stderr().printError("link: multiple modules define fn main: " + ykMainModules);
                exit = 1;
            } else {
                try {
                    YkLinker.emitLauncher(options.classOutputDir(), ykMainModules.get(0));
                } catch (IOException e) {
                    DiagnosticFormatter.stderr().printError("link: could not write launcher: " + e.getMessage());
                    exit = 1;
                }
            }
        }
        return exit;
    }

    /**
     * When multiple sources share a {@code --source-root}, unwrap each laid-out AST
     * (strip synthetic {@code pkg} wrappers for that file’s path), then rebuild one
     * nested package tree so directories at different depths (e.g.
     * {@code dev/icxd} and {@code dev/icxd/commands}) merge into a single program.
     */
    static AstFile mergeSourcesForCompile(List<Path> paths, List<AstFile> laidOut, CompileOptions options) {
        Objects.requireNonNull(paths);
        Objects.requireNonNull(laidOut);
        if (paths.size() != laidOut.size()) {
            throw new IllegalArgumentException("paths and ASTs size mismatch");
        }
        if (laidOut.isEmpty()) {
            throw new IllegalArgumentException("empty merge");
        }
        if (laidOut.size() == 1) {
            return laidOut.get(0);
        }
        Optional<Path> rootOpt = options.sourceRoot();
        List<Item> mergedRoot = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            AstFile af = laidOut.get(i);
            Optional<String> prefixOpt = rootOpt.flatMap(root -> SourcePackage.implicitPrefix(root, path));
            List<Item> inner = stripImplicitPackageChain(af.items(), prefixOpt);
            List<String> segments = new ArrayList<>();
            if (prefixOpt.isPresent()) {
                String pfx = prefixOpt.get();
                if (!pfx.isEmpty()) {
                    segments.addAll(Arrays.asList(pfx.split("::")));
                }
            }
            insertPackagePath(mergedRoot, segments, 0, inner);
        }
        String moduleFileName = paths.getFirst().getFileName().toString();
        return new AstFile(moduleFileName, mergedRoot);
    }

    /**
     * Inserts {@code leafItems} under nested {@code pkg} segments; merges siblings
     * with the same segment name.
     */
    private static void insertPackagePath(
            List<Item> siblings, List<String> segments, int idx, List<Item> leafItems) {
        if (idx >= segments.size()) {
            siblings.addAll(leafItems);
            return;
        }
        List<Item> inner = navigateOrCreatePkg(siblings, segments.get(idx));
        insertPackagePath(inner, segments, idx + 1, leafItems);
    }

    private static List<Item> navigateOrCreatePkg(List<Item> siblings, String segment) {
        for (int i = 0; i < siblings.size(); i++) {
            Item it = siblings.get(i);
            if (it instanceof Item.Pkg p && p.name().equals(segment)) {
                List<Item> mut = new ArrayList<>(p.items());
                siblings.set(
                        i,
                        new Item.Pkg(p.attributes(), p.modifiers(), p.name(), mut, p.span()));
                return mut;
            }
        }
        List<Item> inner = new ArrayList<>();
        siblings.add(new Item.Pkg(List.of(), EnumSet.noneOf(Modifier.class), segment, inner, Optional.empty()));
        return inner;
    }

    private static List<Item> stripImplicitPackageChain(List<Item> items, Optional<String> prefixOpt) {
        if (prefixOpt.isEmpty()) {
            return items;
        }
        String prefix = prefixOpt.get();
        if (prefix.isEmpty()) {
            return items;
        }
        String[] segs = prefix.split("::");
        List<Item> cur = items;
        for (String seg : segs) {
            if (cur.size() != 1) {
                throw new IllegalArgumentException(
                        "cannot merge: expected a single implicit package segment '"
                                + seg
                                + "', found "
                                + cur.size()
                                + " top-level items (check --source-root layout)");
            }
            Item only = cur.get(0);
            if (!(only instanceof Item.Pkg p)) {
                throw new IllegalArgumentException(
                        "cannot merge: expected pkg segment '" + seg + "', got " + only);
            }
            if (!p.name().equals(seg)) {
                throw new IllegalArgumentException(
                        "cannot merge: expected pkg '" + seg + "', found pkg '" + p.name() + "'");
            }
            cur = p.items();
        }
        return cur;
    }

    private record DiagnosticAttach(Path abs, String sourceText) {
    }

    /**
     * Maps an issue span to the original {@link SourceUnit}: merged compilation
     * uses
     * one AST but spans retain per-file offsets.
     */
    static DiagnosticAttach attachDiagnosticSource(
            List<SourceUnit> units, Optional<SourceSpan> span, String message) {
        SourceUnit primary = units.getFirst();
        if (units.size() <= 1) {
            return new DiagnosticAttach(primary.abs(), primary.source());
        }
        if (span.isEmpty()) {
            return new DiagnosticAttach(primary.abs(), primary.source());
        }
        int off = span.get().start().offset();
        Optional<String> quotedLexeme = firstSingleQuotedToken(message);

        List<SourceUnit> byOffset = new ArrayList<>();
        for (SourceUnit u : units) {
            String s = u.source();
            if (off >= 0 && off < s.length()) {
                byOffset.add(u);
            }
        }
        if (byOffset.size() == 1) {
            SourceUnit u = byOffset.getFirst();
            return new DiagnosticAttach(u.abs(), u.source());
        }
        if (quotedLexeme.isPresent() && !byOffset.isEmpty()) {
            String hint = quotedLexeme.get();
            for (SourceUnit u : byOffset) {
                String s = u.source();
                if (off + hint.length() <= s.length() && s.startsWith(hint, off)) {
                    return new DiagnosticAttach(u.abs(), u.source());
                }
            }
        }
        return new DiagnosticAttach(primary.abs(), primary.source());
    }

    private static Optional<String> firstSingleQuotedToken(String message) {
        int q = message.indexOf('\'');
        if (q < 0) {
            return Optional.empty();
        }
        int end = message.indexOf('\'', q + 1);
        if (end <= q) {
            return Optional.empty();
        }
        return Optional.of(message.substring(q + 1, end));
    }

    private static BundleResult compileProgramBundle(AstFile ast, List<SourceUnit> units, CompileOptions options) {
        Path primaryAbs = units.getFirst().abs();
        List<String> ykMainModules = new ArrayList<>();
        var resolved = new NameResolver().resolve(ast);
        if (!resolved.issues().isEmpty()) {
            for (var issue : resolved.issues()) {
                DiagnosticAttach da = attachDiagnosticSource(units, issue.span(), issue.message());
                DiagnosticFormatter.stderr().printResolve(da.abs().toString(), da.sourceText(), issue);
            }
            return new BundleResult(1, ykMainModules);
        }
        var exprTypes = new IdentityHashMap<Expr, Ty>();
        TypecheckBundle bundle = TypeChecker.checkBundle(ast, List.copyOf(options.classPath()), exprTypes);
        if (!bundle.result().ok()) {
            for (var issue : bundle.result().issues()) {
                DiagnosticAttach da = attachDiagnosticSource(units, issue.span(), issue.message());
                DiagnosticFormatter.stderr().printTypeIssue(da.abs().toString(), da.sourceText(), issue);
            }
            return new BundleResult(1, ykMainModules);
        }
        try {
            YkProgramEmitter.emit(
                    ast,
                    primaryAbs,
                    options.classOutputDir(),
                    exprTypes,
                    bundle.table(),
                    bundle.unifier(),
                    List.copyOf(options.classPath()));
            if (YkProgramEmitter.moduleDefinesYkMain(ast)) {
                ykMainModules.add(JvmClassNames.internalNameFromSource(primaryAbs));
            }
        } catch (CodegenException e) {
            DiagnosticFormatter.stderr().printError(primaryAbs + ": codegen: " + e.getMessage());
            return new BundleResult(1, ykMainModules);
        } catch (IOException e) {
            DiagnosticFormatter.stderr()
                    .printError(primaryAbs + ": could not write class file: " + e.getMessage());
            return new BundleResult(1, ykMainModules);
        }
        return new BundleResult(0, ykMainModules);
    }

    /**
     * Wraps the file in synthetic {@code pkg} nodes when {@code --source-root} is
     * set (directory → {@code seg1::seg2::...}).
     */
    private static AstFile applySourceLayout(AstFile parsed, Path absSourceFile, CompileOptions options) {
        if (options.sourceRoot().isEmpty()) {
            return parsed;
        }
        Optional<String> prefix = SourcePackage.implicitPrefix(options.sourceRoot().get(), absSourceFile);
        return SourcePackage.applyImplicitPrefix(parsed, prefix);
    }

    private static int lexParseDebug(CompileOptions options) {
        int exit = 0;
        for (Path p : options.sources()) {
            Path abs = p.toAbsolutePath().normalize();
            try {
                String source = Files.readString(abs);
                var tokens = new Lexer(source, abs.toString()).tokenizeAll();
                if (options.printTokens()) {
                    TokenDebug.printTokens(System.out, abs, tokens);
                }
            } catch (IOException e) {
                DiagnosticFormatter.stderr()
                        .printError("could not read '" + p + "': " + e.getMessage());
                exit = 1;
            } catch (LexException e) {
                DiagnosticFormatter.stderr().printLex(e);
                exit = 1;
            }
        }

        if (!options.printAst() && !options.checkResolve() && !options.checkTypes()) {
            return exit;
        }

        List<SourceUnit> units = new ArrayList<>();
        for (Path p : options.sources()) {
            Path abs = p.toAbsolutePath().normalize();
            String source = "";
            try {
                source = Files.readString(abs);
                var tokens = new Lexer(source, abs.toString()).tokenizeAll();
                AstFile parsed = new Parser(abs.toString(), tokens).parseFile();
                AstFile ast;
                try {
                    ast = applySourceLayout(parsed, abs, options);
                } catch (IllegalArgumentException e) {
                    DiagnosticFormatter.stderr().printError(abs + ": " + e.getMessage());
                    exit = 1;
                    continue;
                }
                units.add(new SourceUnit(abs, source, ast));
            } catch (IOException e) {
                DiagnosticFormatter.stderr()
                        .printError("could not read '" + p + "': " + e.getMessage());
                exit = 1;
            } catch (LexException e) {
                DiagnosticFormatter.stderr().printLex(e);
                exit = 1;
            } catch (ParseException e) {
                DiagnosticFormatter.stderr().printParse(abs.toString(), source, e);
                exit = 1;
            }
        }
        if (units.size() != options.sources().size()) {
            return exit;
        }
        if (units.isEmpty()) {
            return exit;
        }
        AstFile program;
        if (units.size() == 1) {
            program = units.get(0).ast();
        } else {
            try {
                program = mergeSourcesForCompile(
                        units.stream().map(SourceUnit::abs).toList(),
                        units.stream().map(SourceUnit::ast).toList(),
                        options);
            } catch (IllegalArgumentException e) {
                DiagnosticFormatter.stderr().printError(e.getMessage());
                return 1;
            }
        }
        if (options.printAst()) {
            System.out.println(AstPrinter.print(program));
        }
        if (options.checkResolve()) {
            var res = new NameResolver().resolve(program);
            if (!res.issues().isEmpty()) {
                for (var issue : res.issues()) {
                    DiagnosticAttach da = attachDiagnosticSource(units, issue.span(), issue.message());
                    DiagnosticFormatter.stderr()
                            .printResolve(da.abs().toString(), da.sourceText(), issue);
                }
                exit = 1;
            }
        }
        if (options.checkTypes()) {
            var tres = TypeChecker.check(program, List.copyOf(options.classPath()));
            if (!tres.ok()) {
                for (var issue : tres.issues()) {
                    DiagnosticAttach da = attachDiagnosticSource(units, issue.span(), issue.message());
                    DiagnosticFormatter.stderr()
                            .printTypeIssue(da.abs().toString(), da.sourceText(), issue);
                }
                exit = 1;
            }
        }
        return exit;
    }

    static void printHelp(java.io.PrintStream out) {
        String n = programName();
        out.println("Usage: " + n + " [options] file.yk [file.yk ...]");
        out.println();
        out.println("Options:");
        out.println("  -h, -?, --help     Display this information.");
        out.println("  --version          Display compiler version.");
        out.println("  -c                 Compile only; emit class files (no link step).");
        out.println("  -d <dir>           Place class files under <dir> (default: current directory).");
        out.println("  -cp <path>         Class search path (" + java.io.File.pathSeparator + "-separated).");
        out.println("  -classpath <path>  Same as -cp.");
        out.println("  --class-path <path> Same as -cp.");
        out.println("  -v, --verbose       Print extra diagnostics.");
        out.println(
                "  --source-root <dir> Directory root for implicit package from path (parent of each");
        out.println(
                "                      .yk file relative to this dir → pkg seg1::seg2::…). Optional.");
        out.println("  --print-tokens      Lex each input file and print tokens (stdout); no codegen.");
        out.println("  --print-ast         Parse and print a summary AST (stdout); no codegen.");
        out.println("  --resolve           Parse and run name resolution; print issues to stderr.");
        out.println("  --typecheck         Parse and run HM-style type inference; print issues to stderr.");
        out.println("  (default)           Compile all sources, then link: emit yk/Main entry if exactly");
        out.println("                      one file defines top-level fn main.");
        out.println("  --                 Stop processing options.");
    }

    private static void usageHint() {
        System.err.println("Try '" + programName() + " --help' for more information.");
    }

    static String programName() {
        return "yakouc";
    }

    static String version() {
        Package p = Yakouc.class.getPackage();
        String v = p != null ? p.getImplementationVersion() : null;
        return v != null ? v : "0.1.0-SNAPSHOT";
    }
}
