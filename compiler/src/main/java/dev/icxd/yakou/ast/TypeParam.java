package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.Optional;

public record TypeParam(String name, Optional<TypeRef> upperBound, Optional<SourceSpan> span) {
}
