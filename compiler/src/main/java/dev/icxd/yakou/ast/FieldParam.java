package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.EnumSet;
import java.util.Optional;

public record FieldParam(
        EnumSet<Modifier> modifiers, String name, TypeRef type, Optional<SourceSpan> span) {
}
