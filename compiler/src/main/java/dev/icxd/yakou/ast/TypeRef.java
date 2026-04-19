package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.List;
import java.util.Optional;

/**
 * Type expressions: {@code T}, {@code a::b::C}, {@code Result[T, E]},
 * {@code []T}.
 */
public sealed interface TypeRef permits TypeRef.Named, TypeRef.Path, TypeRef.Applied, TypeRef.Array {

    record Named(String name, Optional<SourceSpan> span) implements TypeRef {
    }

    /** Path like {@code java::lang::String} (no type args on the path itself). */
    record Path(List<String> segments, Optional<SourceSpan> span) implements TypeRef {
    }

    /** {@code Base[A, B]} — {@code base} is usually {@link Named}. */
    record Applied(TypeRef base, List<TypeRef> args, Optional<SourceSpan> span) implements TypeRef {
    }

    /** Array type: {@code []element} (nested: {@code [][]i32}). */
    record Array(TypeRef element, Optional<SourceSpan> span) implements TypeRef {
    }
}
