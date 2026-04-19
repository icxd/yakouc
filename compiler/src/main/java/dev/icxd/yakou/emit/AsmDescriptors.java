package dev.icxd.yakou.emit;

import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.ast.Param;
import dev.icxd.yakou.ast.TypeRef;

/**
 * JVM field/method descriptors from Yakou {@link TypeRef} (subset for codegen).
 */
public final class AsmDescriptors {

    private AsmDescriptors() {
    }

    public static String methodDescriptor(Item.Fn f) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (Param p : f.params()) {
            if (p.type().isEmpty()) {
                throw new CodegenException("parameter '" + p.name() + "' needs a type for codegen");
            }
            sb.append(typeRefToField(p.type().get()));
        }
        sb.append(')');
        sb.append(returnDescriptor(f.returnType()));
        return sb.toString();
    }

    private static String returnDescriptor(TypeRef tr) {
        if (isUnitType(tr)) {
            return "V";
        }
        return typeRefToField(tr);
    }

    static boolean isUnitType(TypeRef tr) {
        return tr instanceof TypeRef.Named n && "unit".equals(n.name());
    }

    public static String typeRefToField(TypeRef tr) {
        return switch (tr) {
            case TypeRef.Named n -> primitiveOrClassNamed(n.name());
            case TypeRef.Path p -> javaPathDescriptor(p.segments());
            case TypeRef.Applied a -> typeRefToField(erasureRoot(a));
            case TypeRef.Array ar -> "[" + typeRefToField(ar.element());
        };
    }

    /**
     * JVM bytecode uses erasure for generic Java types:
     * {@code java::util::List<java::lang::String>}
     * → {@code Ljava/util/List;}.
     */
    private static TypeRef erasureRoot(TypeRef.Applied a) {
        TypeRef b = a.base();
        while (b instanceof TypeRef.Applied ap) {
            b = ap.base();
        }
        return b;
    }

    private static String javaPathDescriptor(java.util.List<String> segments) {
        if (segments.isEmpty()) {
            throw new CodegenException("empty java type path");
        }
        if (!"java".equals(segments.getFirst())) {
            throw new CodegenException("only java:: paths supported for nominal types, got " + segments);
        }
        return 'L' + String.join("/", segments) + ';';
    }

    private static String primitiveOrClassNamed(String name) {
        return switch (name) {
            case "i8", "i16", "i32", "u16", "u32" -> "I";
            case "i64" -> "J";
            case "f32" -> "F";
            case "f64" -> "D";
            case "bool" -> "Z";
            case "str" -> "Ljava/lang/String;";
            case "unit" -> "V";
            case "bytes" -> "[B";
            default ->
                throw new CodegenException(
                        "unknown named type '" + name + "' (user classes not emitted in this codegen pass)");
        };
    }
}
