package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.Optional;

/**
 * Parameter; type omitted for bare {@code self} in methods like
 * {@code fn m(self): T}.
 */
public record Param(String name, Optional<TypeRef> type, Optional<SourceSpan> span) {
}
