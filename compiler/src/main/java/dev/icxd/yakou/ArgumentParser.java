package dev.icxd.yakou;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses {@code gcc}/{@code clang}-style arguments for {@code yakouc}.
 */
final class ArgumentParser {

    private ArgumentParser() {
    }

    static ParseResult parse(String[] args) {
        boolean compileOnly = false;
        Path classOutputDir = Paths.get(".").toAbsolutePath().normalize();
        List<Path> classPath = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        boolean verbose = false;
        boolean printTokens = false;
        boolean printAst = false;
        boolean checkResolve = false;
        boolean checkTypes = false;
        boolean help = false;
        boolean version = false;
        Optional<Path> sourceRoot = Optional.empty();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--".equals(a)) {
                while (++i < args.length) {
                    sources.add(Paths.get(args[i]));
                }
                break;
            }
            if (helpOrVersion(a)) {
                if ("--help".equals(a) || "-h".equals(a) || "-?".equals(a)) {
                    help = true;
                } else if ("--version".equals(a)) {
                    version = true;
                }
                continue;
            }
            switch (a) {
                case "-c" -> compileOnly = true;
                case "-v", "--verbose" -> verbose = true;
                case "--print-tokens" -> printTokens = true;
                case "--print-ast" -> printAst = true;
                case "--resolve" -> checkResolve = true;
                case "--typecheck" -> checkTypes = true;
                case "-d" -> {
                    if (++i >= args.length) {
                        return ParseResult.error("option '-d' requires a directory argument");
                    }
                    classOutputDir = Paths.get(args[i]);
                }
                case "-cp", "-classpath", "--class-path" -> {
                    if (++i >= args.length) {
                        return ParseResult.error("option '" + a + "' requires a path argument");
                    }
                    for (String piece : args[i].split(java.io.File.pathSeparator)) {
                        if (!piece.isEmpty()) {
                            classPath.add(Paths.get(piece));
                        }
                    }
                }
                case "--source-root" -> {
                    if (++i >= args.length) {
                        return ParseResult.error("option '--source-root' requires a directory argument");
                    }
                    sourceRoot = Optional.of(Paths.get(args[i]));
                }
                default -> {
                    if (a.startsWith("-")) {
                        return ParseResult.error("unknown argument: '" + a + "'");
                    }
                    sources.add(Paths.get(a));
                }
            }
        }

        if (help || version) {
            return ParseResult.meta(help, version);
        }

        if (sources.isEmpty()) {
            return ParseResult.error("no input files");
        }

        return ParseResult.ok(
                new CompileOptions(
                        compileOnly,
                        classOutputDir,
                        classPath,
                        sources,
                        verbose,
                        printTokens,
                        printAst,
                        checkResolve,
                        checkTypes,
                        sourceRoot));
    }

    private static boolean helpOrVersion(String a) {
        return "--help".equals(a)
                || "-h".equals(a)
                || "-?".equals(a)
                || "--version".equals(a);
    }

    sealed interface ParseResult {
        static ParseResult ok(CompileOptions options) {
            return new Ok(options);
        }

        static ParseResult error(String message) {
            return new Err(message);
        }

        /** Help or version only; no compilation. */
        static ParseResult meta(boolean help, boolean version) {
            return new Meta(help, version);
        }

        record Ok(CompileOptions options) implements ParseResult {
        }

        record Err(String message) implements ParseResult {
        }

        record Meta(boolean help, boolean version) implements ParseResult {
        }
    }
}
