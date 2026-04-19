package dev.icxd.yakou.typeck;

import java.util.ArrayList;
import java.util.List;

/** Unification with occurs check; binds {@link TyVar} solutions in place. */
public final class Unifier {

    public TyVar fresh() {
        return new TyVar();
    }

    public Ty applyFully(Ty t) {
        Ty d = Ty.deref(t);
        return switch (d) {
            case Ty.PrimTy p -> p;
            case Ty.VarTy v -> d;
            case Ty.FunTy f -> {
                List<Ty> ps = new ArrayList<>();
                for (Ty p : f.params()) {
                    ps.add(applyFully(p));
                }
                yield Ty.fun(ps, applyFully(f.ret()));
            }
            case Ty.NomTy n -> {
                List<Ty> args = new ArrayList<>();
                for (Ty a : n.typeArgs()) {
                    args.add(applyFully(a));
                }
                yield Ty.nom(n.path(), args);
            }
            case Ty.ArrayTy a -> Ty.arrayOf(applyFully(a.element()));
        };
    }

    /**
     * Unifies two types. On failure, returns false (caller records a diagnostic).
     */
    public boolean unify(Ty a, Ty b) {
        Ty x = Ty.deref(a);
        Ty y = Ty.deref(b);
        if (x instanceof Ty.VarTy vx && y instanceof Ty.VarTy vy) {
            if (vx.var() == vy.var()) {
                return true;
            }
            if (occurs(vx.var(), y)) {
                return false;
            }
            vx.var().setSolution(y);
            return true;
        }
        if (x instanceof Ty.VarTy vx) {
            if (occurs(vx.var(), y)) {
                return false;
            }
            vx.var().setSolution(y);
            return true;
        }
        if (y instanceof Ty.VarTy vy) {
            if (occurs(vy.var(), x)) {
                return false;
            }
            vy.var().setSolution(x);
            return true;
        }
        if (x instanceof Ty.PrimTy px && y instanceof Ty.PrimTy py) {
            if (px.prim() == py.prim()) {
                return true;
            }
            return compatibleIntPrim(px.prim(), py.prim());
        }
        if (x instanceof Ty.FunTy fx && y instanceof Ty.FunTy fy) {
            if (fx.params().size() != fy.params().size()) {
                return false;
            }
            for (int i = 0; i < fx.params().size(); i++) {
                if (!unify(fx.params().get(i), fy.params().get(i))) {
                    return false;
                }
            }
            return unify(fx.ret(), fy.ret());
        }
        if (x instanceof Ty.NomTy nx && y instanceof Ty.NomTy ny) {
            if (!nx.path().equals(ny.path())) {
                return false;
            }
            if (nx.typeArgs().size() != ny.typeArgs().size()) {
                return false;
            }
            for (int i = 0; i < nx.typeArgs().size(); i++) {
                if (!unify(nx.typeArgs().get(i), ny.typeArgs().get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (x instanceof Ty.ArrayTy ax && y instanceof Ty.ArrayTy ay) {
            return unify(ax.element(), ay.element());
        }
        // Interop: Yakou str literals / str type ↔ java.lang.String nominal (erased to
        // same JVM type).
        if (x instanceof Ty.PrimTy px
                && px.prim() == Prim.STR
                && y instanceof Ty.NomTy ny
                && "java::lang::String".equals(ny.path())
                && ny.typeArgs().isEmpty()) {
            return true;
        }
        if (y instanceof Ty.PrimTy py
                && py.prim() == Prim.STR
                && x instanceof Ty.NomTy nx
                && "java::lang::String".equals(nx.path())
                && nx.typeArgs().isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * Allow mixed integer primitives to unify (widening/narrowing at check time).
     */
    private static boolean compatibleIntPrim(Prim a, Prim b) {
        return PrimSets.isIntegral(a) && PrimSets.isIntegral(b);
    }

    private static boolean occurs(TyVar v, Ty t) {
        Ty d = Ty.deref(t);
        return switch (d) {
            case Ty.PrimTy ignored -> false;
            case Ty.VarTy vv -> {
                if (vv.var() == v) {
                    yield true;
                }
                Ty s = vv.var().solution();
                if (s != null) {
                    yield occurs(v, s);
                }
                yield false;
            }
            case Ty.FunTy f -> {
                boolean any = false;
                for (Ty p : f.params()) {
                    any = any || occurs(v, p);
                }
                yield any || occurs(v, f.ret());
            }
            case Ty.NomTy n -> {
                boolean any = false;
                for (Ty a : n.typeArgs()) {
                    any = any || occurs(v, a);
                }
                yield any;
            }
            case Ty.ArrayTy a -> occurs(v, a.element());
        };
    }
}
