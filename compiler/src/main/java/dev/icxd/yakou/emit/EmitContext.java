package dev.icxd.yakou.emit;

import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.typeck.JavaInterop;
import dev.icxd.yakou.typeck.TypeTable;
import dev.icxd.yakou.typeck.Ty;
import dev.icxd.yakou.typeck.Unifier;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Shared state for expression / method bytecode emission. */
final class EmitContext {

    final Path outputDir;
    /** Source file name (e.g. {@code foo.yk}) for {@code SourceDebugExtension}. */
    final String sourceFileName;
    final String moduleInternalName;
    final Map<Expr, Ty> exprTypes;
    final TypeTable table;
    final Unifier unifier;
    final JavaInterop javaInterop;
    /** Top-level {@link Item.Fn} qualified names → AST (module class methods). */
    final Map<String, Item.Fn> moduleFunctions;
    /** Qualified paths of Yakou classes emitted in this compilation unit. */
    final Set<String> yakouClasses;
    /**
     * Class AST by qualified path (constructor field order for {@code when}, etc.).
     */
    final Map<String, Item.Class> yakouClassAst;

    EmitContext(
            Path outputDir,
            String sourceFileName,
            String moduleInternalName,
            Map<Expr, Ty> exprTypes,
            TypeTable table,
            Unifier unifier,
            JavaInterop javaInterop,
            Map<String, Item.Fn> moduleFunctions,
            Set<String> yakouClasses,
            Map<String, Item.Class> yakouClassAst) {
        this.outputDir = outputDir;
        this.sourceFileName = sourceFileName;
        this.moduleInternalName = moduleInternalName;
        this.exprTypes = exprTypes;
        this.table = table;
        this.unifier = unifier;
        this.javaInterop = javaInterop;
        this.moduleFunctions = moduleFunctions;
        this.yakouClasses = yakouClasses;
        this.yakouClassAst = yakouClassAst;
    }
}
