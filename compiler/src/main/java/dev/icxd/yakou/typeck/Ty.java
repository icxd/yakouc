package dev.icxd.yakou.typeck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Monomorphic types after elaboration (before solving, may contain
 * {@link TyVar}).
 */
public sealed interface Ty permits Ty.PrimTy, Ty.VarTy, Ty.FunTy, Ty.NomTy, Ty.ArrayTy {

    static Ty prim(Prim p) {
        return new PrimTy(p);
    }

    static Ty var(TyVar v) {
        return new VarTy(v);
    }

    static Ty fun(List<Ty> params, Ty ret) {
        return new FunTy(List.copyOf(params), ret);
    }

    /**
     * Nominal type: {@code path} uses {@code ::} segments joined (e.g.
     * {@code demo::User}).
     */
    static Ty nom(String path, List<Ty> typeArgs) {
        return new NomTy(path, List.copyOf(typeArgs));
    }

    /** JVM array type {@code element[]} (descriptor {@code [}…). */
    static Ty arrayOf(Ty element) {
        return new ArrayTy(element);
    }

    /**
     * Follow {@link TyVar} bindings to a non-var head (may still be under
     * construction).
     */
    static Ty deref(Ty t) {
        Ty u = t;
        while (u instanceof VarTy vv) {
            Ty s = vv.var().solution();
            if (s == null) {
                return u;
            }
            u = s;
        }
        return u;
    }

    static Set<TyVar> ftv(Ty t) {
        Set<TyVar> out = new HashSet<>();
        ftvInto(t, out);
        return out;
    }

    private static void ftvInto(Ty t, Set<TyVar> out) {
        Ty d = deref(t);
        switch (d) {
            case PrimTy ignored -> {
            }
            case VarTy v -> out.add(v.var());
            case FunTy f -> {
                for (Ty p : f.params()) {
                    ftvInto(p, out);
                }
                ftvInto(f.ret(), out);
            }
            case NomTy n -> {
                for (Ty a : n.typeArgs()) {
                    ftvInto(a, out);
                }
            }
            case ArrayTy a -> ftvInto(a.element(), out);
        }
    }

    /** Deep copy replacing only the given quantified variables. */
    static Ty subst(Ty t, java.util.Map<TyVar, Ty> map) {
        Ty d = deref(t);
        return switch (d) {
            case PrimTy p -> p;
            case VarTy v -> map.getOrDefault(v.var(), v);
            case FunTy f -> {
                List<Ty> ps = new ArrayList<>();
                for (Ty x : f.params()) {
                    ps.add(subst(x, map));
                }
                yield new FunTy(ps, subst(f.ret(), map));
            }
            case NomTy n -> {
                List<Ty> args = new ArrayList<>();
                for (Ty a : n.typeArgs()) {
                    args.add(subst(a, map));
                }
                yield new NomTy(n.path(), args);
            }
            case ArrayTy a -> new ArrayTy(subst(a.element(), map));
        };
    }

    record PrimTy(Prim prim) implements Ty {
        @Override
        public String toString() {
            return switch (prim) {
                case I8 -> "i8";
                case I16 -> "i16";
                case I32 -> "i32";
                case I64 -> "i64";
                case U16 -> "u16";
                case U32 -> "u32";
                case F32 -> "f32";
                case F64 -> "f64";
                case STR -> "str";
                case BOOL -> "bool";
                case UNIT -> "unit";
                case BYTES -> "bytes";
            };
        }
    }

    record VarTy(TyVar var) implements Ty {
        @Override
        public String toString() {
            return var.toString();
        }
    }

    record FunTy(List<Ty> params, Ty ret) implements Ty {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("(");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params.get(i));
            }
            sb.append(") -> ");
            sb.append(ret);
            return sb.toString();
        }
    }

    record NomTy(String path, List<Ty> typeArgs) implements Ty {
        @Override
        public String toString() {
            if (typeArgs.isEmpty()) {
                return path;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(path);
            sb.append("[");
            for (int i = 0; i < typeArgs.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(typeArgs.get(i));
            }
            sb.append("]");
            return sb.toString();
        }
    }

    record ArrayTy(Ty element) implements Ty {
        @Override
        public String toString() {
            return "[]" + element;
        }
    }
}
