package dev.icxd.yakou.typeck;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Polymorphic type: ∀ quantifiers . body (HM let-generalization). */
public final class Scheme {

    private final Set<TyVar> quantified;
    private final Ty body;

    public Scheme(Set<TyVar> quantified, Ty body) {
        this.quantified = Set.copyOf(quantified);
        this.body = body;
    }

    public Set<TyVar> quantified() {
        return quantified;
    }

    public Ty body() {
        return body;
    }

    /** Free type variables in the scheme (in the body, excluding quantified). */
    public static Set<TyVar> ftv(Scheme s) {
        Set<TyVar> t = Ty.ftv(s.body());
        t.removeAll(s.quantified());
        return t;
    }

    /** Instantiate with fresh unification variables for each quantifier. */
    public Ty instantiate(Unifier u) {
        Map<TyVar, Ty> subst = new HashMap<>();
        for (TyVar v : quantified) {
            subst.put(v, Ty.var(u.fresh()));
        }
        return Ty.subst(body, subst);
    }

    public static Scheme mono(Ty t) {
        return new Scheme(Set.of(), t);
    }

    /** HM generalize: ∀ (ftv(τ) \ ftv(env)) . τ */
    public static Scheme generalize(Ty tau, Env env, Unifier u) {
        Ty applied = u.applyFully(tau);
        Set<TyVar> freeInTau = Ty.ftv(applied);
        Set<TyVar> freeInEnv = env.ftv();
        Set<TyVar> quant = new HashSet<>(freeInTau);
        quant.removeAll(freeInEnv);
        return new Scheme(quant, applied);
    }

    @Override
    public String toString() {
        if (quantified.isEmpty()) {
            return body.toString();
        }
        return "∀" + quantified + ". " + body;
    }
}
