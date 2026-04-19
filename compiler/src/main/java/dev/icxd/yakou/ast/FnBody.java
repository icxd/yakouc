package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.Optional;

/** Function body: block or {@code = expr}. */
public sealed interface FnBody permits FnBody.BlockBody, FnBody.ExprEquals {

    record BlockBody(Block block) implements FnBody {
    }

    record ExprEquals(Expr expr, Optional<SourceSpan> span) implements FnBody {
    }
}
