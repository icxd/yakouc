package dev.icxd.yakou.typeck;

/** Result of typechecking, including tables needed for codegen. */
public record TypecheckBundle(TypeResult result, TypeTable table, Unifier unifier) {
}
