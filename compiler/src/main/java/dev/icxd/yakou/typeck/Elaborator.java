package dev.icxd.yakou.typeck;

import dev.icxd.yakou.ast.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Maps surface {@link TypeRef} to internal {@link Ty}. */
public final class Elaborator {

    private static final Set<String> PRIMITIVES = Set.of("i8", "i16", "i32", "i64", "u16", "u32", "f32", "f64", "str",
            "bool", "unit", "bytes");

    private Elaborator() {
    }

    public static Ty elaborate(TypeRef ref, ElabContext ctx) {
        return switch (ref) {
            case TypeRef.Named n -> elaborateNamed(n, ctx);
            case TypeRef.Path p -> Ty.nom(String.join("::", p.segments()), List.of());
            case TypeRef.Applied a -> elaborateApplied(a, ctx);
            case TypeRef.Array ar -> Ty.arrayOf(elaborate(ar.element(), ctx));
        };
    }

    private static Ty elaborateNamed(TypeRef.Named n, ElabContext ctx) {
        String name = n.name();
        if (PRIMITIVES.contains(name)) {
            return Ty.prim(primOf(name));
        }
        return ctx.typeParam(name)
                .orElseGet(() -> Ty.nom(ctx.qualifyNominal(name), List.of()));
    }

    private static Ty elaborateApplied(TypeRef.Applied a, ElabContext ctx) {
        List<Ty> args = new ArrayList<>();
        for (TypeRef arg : a.args()) {
            args.add(elaborate(arg, ctx));
        }
        String path = switch (a.base()) {
            case TypeRef.Named n -> ctx.qualifyNominal(n.name());
            case TypeRef.Path p -> String.join("::", p.segments());
            case TypeRef.Applied ignored ->
                throw new IllegalStateException("nested applied type base not supported");
            case TypeRef.Array ignored ->
                throw new IllegalStateException("array type as generic base not supported");
        };
        return Ty.nom(path, args);
    }

    private static Prim primOf(String name) {
        return switch (name) {
            case "i8" -> Prim.I8;
            case "i16" -> Prim.I16;
            case "i32" -> Prim.I32;
            case "i64" -> Prim.I64;
            case "u16" -> Prim.U16;
            case "u32" -> Prim.U32;
            case "f32" -> Prim.F32;
            case "f64" -> Prim.F64;
            case "str" -> Prim.STR;
            case "bool" -> Prim.BOOL;
            case "unit" -> Prim.UNIT;
            case "bytes" -> Prim.BYTES;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
