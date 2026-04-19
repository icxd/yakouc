package dev.icxd.yakou.ls;

import dev.icxd.yakou.ast.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Map full nominal paths ({@code a::b::C}) to {@link Item.Class} in the file.
 */
final class NominalAst {

    private NominalAst() {
    }

    /**
     * First match on the full {@code wantPath}; if none, and exactly one class in
     * the
     * file has the same simple name as the last {@code ::} segment, return that
     * class.
     * Covers cases where {@code Ty.NomTy} was built with a package prefix but the
     * editor's implicit package application differed from the typecheck layout.
     */
    static Optional<Item.Class> findClassRelaxed(List<Item> items, String wantPath) {
        Optional<Item.Class> exact = findClass(items, "", wantPath);
        if (exact.isPresent()) {
            return exact;
        }
        String simple = lastPathSegment(wantPath);
        List<Item.Class> hits = new ArrayList<>();
        collectClassesWithSimpleName(items, "", simple, hits);
        if (hits.size() == 1) {
            return Optional.of(hits.getFirst());
        }
        return Optional.empty();
    }

    static Optional<Item.Class> findClass(List<Item> items, String prefix, String wantPath) {
        for (Item it : items) {
            switch (it) {
                case Item.Class c -> {
                    String q = qualify(prefix, c.name());
                    if (q.equals(wantPath)) {
                        return Optional.of(c);
                    }
                }
                case Item.Pkg p -> {
                    String next = qualify(prefix, p.name());
                    Optional<Item.Class> inner = findClass(p.items(), next, wantPath);
                    if (inner.isPresent()) {
                        return inner;
                    }
                }
                default -> {
                }
            }
        }
        return Optional.empty();
    }

    private static void collectClassesWithSimpleName(
            List<Item> items, String prefix, String simpleName, List<Item.Class> out) {
        for (Item it : items) {
            switch (it) {
                case Item.Class c -> {
                    if (c.name().equals(simpleName)) {
                        out.add(c);
                    }
                }
                case Item.Pkg p -> {
                    String next = qualify(prefix, p.name());
                    collectClassesWithSimpleName(p.items(), next, simpleName, out);
                }
                default -> {
                }
            }
        }
    }

    private static String lastPathSegment(String path) {
        int i = path.lastIndexOf("::");
        return i < 0 ? path : path.substring(i + 2);
    }

    private static String qualify(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "::" + name;
    }
}
