package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.Optional;

public sealed interface Stmt permits Stmt.Let, Stmt.ExprSemi {

    record Let(
            String name, Optional<TypeRef> annotation, Expr value, Optional<SourceSpan> span)
            implements Stmt {
    }

    /** Expression followed by {@code ;} inside a block. */
    record ExprSemi(Expr expr, Optional<SourceSpan> span) implements Stmt {
    }
}
