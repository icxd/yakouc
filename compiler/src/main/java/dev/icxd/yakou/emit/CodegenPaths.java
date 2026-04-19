package dev.icxd.yakou.emit;

import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.typeck.ElabContext;

final class CodegenPaths {

    private CodegenPaths() {
    }

    static String qualifyExprPath(Expr.Path p, ElabContext ctx) {
        if (p.segments().size() == 1) {
            return ctx.qualifyNominal(p.segments().getFirst());
        }
        return String.join("::", p.segments());
    }
}
