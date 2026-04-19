package dev.icxd.yakou;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Options for one {@code yakouc} invocation, after argument parsing.
 *
 * @param compileOnly    When {@code true}, emit class files only (no link step;
 *                       no {@code yk/Main} launcher).
 * @param classOutputDir Root directory for generated {@code .class} files
 *                       ({@code -d}).
 * @param classPath      Entries for resolution against bytecode on the
 *                       classpath ({@code -cp} / {@code -classpath}).
 * @param sources        Source paths ({@code .yk}) in command-line order.
 * @param verbose        Emit extra diagnostics ({@code -v}).
 * @param printTokens    Lex input and print tokens to stdout
 *                       ({@code --print-tokens}); skips codegen.
 * @param printAst       Parse and print AST ({@code --print-ast}); skips
 *                       codegen.
 * @param checkResolve   Run name resolution ({@code --resolve}); skips codegen.
 * @param checkTypes     Run Hindley–Milner style typecheck
 *                       ({@code --typecheck}).
 * @param sourceRoot     When present, parent path of each {@code .yk} file
 *                       relative to this root becomes the implicit package
 *                       ({@code dir1/dir2/foo.yk} → {@code dir1::dir2}).
 */
public record CompileOptions(
        boolean compileOnly,
        Path classOutputDir,
        List<Path> classPath,
        List<Path> sources,
        boolean verbose,
        boolean printTokens,
        boolean printAst,
        boolean checkResolve,
        boolean checkTypes,
        Optional<Path> sourceRoot) {

    public CompileOptions {
        classOutputDir = classOutputDir.toAbsolutePath().normalize();
        classPath = List.copyOf(classPath);
        sources = List.copyOf(sources);
        sourceRoot = sourceRoot.map(r -> r.toAbsolutePath().normalize());
    }
}
