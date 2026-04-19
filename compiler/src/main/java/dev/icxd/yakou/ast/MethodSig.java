package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.List;
import java.util.Optional;

/** Trait / interface method signature ending with {@code ;}. */
public record MethodSig(
        String name, List<Param> params, TypeRef returnType, Optional<SourceSpan> span) {
}
