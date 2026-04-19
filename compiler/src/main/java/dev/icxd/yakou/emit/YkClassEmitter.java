package dev.icxd.yakou.emit;

import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.typeck.Ty;
import dev.icxd.yakou.typeck.TypeTable;
import dev.icxd.yakou.typeck.Unifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Thin alias for {@link YkProgramEmitter#emit}. */
public final class YkClassEmitter {

    private YkClassEmitter() {
    }

    public static void emit(
            AstFile ast,
            Path sourcePath,
            Path outputDir,
            Map<Expr, Ty> exprTypes,
            TypeTable table,
            Unifier u,
            List<Path> classpath)
            throws IOException {
        YkProgramEmitter.emit(ast, sourcePath, outputDir, exprTypes, table, u, classpath);
    }
}
