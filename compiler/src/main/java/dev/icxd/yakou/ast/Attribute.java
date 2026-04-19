package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.Optional;

/**
 * Declaration metadata: {@code #{ expr }} — typically a path or call such as
 * {@code MyAttr} or {@code pkg::Attr(1, "x")}.
 */
public record Attribute(Expr invocation, Optional<SourceSpan> span) {
}
