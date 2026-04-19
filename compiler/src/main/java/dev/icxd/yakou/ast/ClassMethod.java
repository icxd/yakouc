package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public record ClassMethod(
        List<Attribute> attributes,
        EnumSet<Modifier> modifiers,
        String name,
        List<Param> params,
        TypeRef returnType,
        FnBody body,
        Optional<SourceSpan> span) {
}
