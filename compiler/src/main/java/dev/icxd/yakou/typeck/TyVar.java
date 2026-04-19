package dev.icxd.yakou.typeck;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unification variable for Hindley–Milner inference. When {@link #solution} is
 * non-null, this variable is bound; {@link Ty#deref()} follows the chain.
 */
public final class TyVar {

    private static final AtomicInteger NEXT = new AtomicInteger();

    private final int id = NEXT.incrementAndGet();
    /** Concrete or another variable (short chain). */
    private Ty solution;

    public int id() {
        return id;
    }

    public Ty solution() {
        return solution;
    }

    public void setSolution(Ty t) {
        this.solution = t;
    }

    @Override
    public String toString() {
        return "'" + id;
    }
}
