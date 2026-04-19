package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.List;
import java.util.Optional;

/**
 * Braced block: optional {@code let} / expression-with-{@code ;} statements,
 * optional trailing
 * expression.
 */
public record Block(List<Stmt> statements, Optional<Expr> trailingExpr, Optional<SourceSpan> span) {
}
