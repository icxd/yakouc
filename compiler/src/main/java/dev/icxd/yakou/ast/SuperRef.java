package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.List;
import java.util.Optional;

/**
 * Class extends / implements clause: {@code : Display} or
 * {@code : Animal(name)}.
 */
public record SuperRef(TypeRef type, List<Expr> args, Optional<SourceSpan> span) {
}
