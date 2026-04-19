package dev.icxd.yakou.typeck;

/** Helpers for grouping {@link Prim} variants for typing rules. */
public final class PrimSets {

    private PrimSets() {
    }

    /** Fixed-width signed/unsigned integers used with literal widening rules. */
    public static boolean isIntegral(Prim p) {
        return switch (p) {
            case I8, I16, I32, I64, U16, U32 -> true;
            default -> false;
        };
    }
}
