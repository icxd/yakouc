package dev.icxd.yakou;

import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.ast.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Maps directory layout under a {@linkplain #implicitPrefix(Path, Path) source
 * root} to an implicit Yakou package prefix (like Java). Nested {@code pkg}
 * blocks in source are only for further nesting within that file.
 */
public final class SourcePackage {

    private SourcePackage() {
    }

    /**
     * Directory of {@code sourceFile} relative to {@code sourceRoot}, as
     * {@code seg1::seg2::...}. Empty when the file sits directly under the root.
     *
     * @throws IllegalArgumentException when the file’s parent directory is not
     *                                  under {@code sourceRoot}
     */
    public static Optional<String> implicitPrefix(Path sourceRoot, Path sourceFile) {
        Path root = sourceRoot.toAbsolutePath().normalize();
        Path parent = sourceFile.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return Optional.empty();
        }
        if (parent.equals(root)) {
            return Optional.empty();
        }
        try {
            if (Files.exists(root)) {
                root = root.toRealPath();
            }
            if (Files.exists(parent)) {
                parent = parent.toRealPath();
            }
        } catch (IOException ignored) {
            // keep absolute normalized paths
        }
        if (parent.equals(root)) {
            return Optional.empty();
        }
        if (!parent.startsWith(root)) {
            throw new IllegalArgumentException(
                    "source file must live under --source-root ("
                            + root
                            + "), but parent of "
                            + sourceFile
                            + " is not");
        }
        Path rel = root.relativize(parent).normalize();
        if (rel.getNameCount() == 0) {
            return Optional.empty();
        }
        List<String> segs = new ArrayList<>();
        for (int i = 0; i < rel.getNameCount(); i++) {
            segs.add(rel.getName(i).toString());
        }
        return Optional.of(String.join("::", segs));
    }

    /**
     * Wraps top-level items in nested {@link Item.Pkg} nodes so name resolution
     * sees the same prefix as handwritten {@code pkg a { pkg b { ... }}}.
     */
    public static AstFile applyImplicitPrefix(AstFile file, Optional<String> prefixOpt) {
        if (prefixOpt.isEmpty()) {
            return file;
        }
        String prefix = prefixOpt.get();
        if (prefix.isEmpty()) {
            return file;
        }
        String[] segs = prefix.split("::");
        List<Item> items = new ArrayList<>(file.items());
        // Outermost pkg first in path (seg[0]): wrap innermost last.
        for (int i = segs.length - 1; i >= 0; i--) {
            String seg = segs[i];
            items = List.of(new Item.Pkg(List.of(), EnumSet.noneOf(Modifier.class), seg, items, Optional.empty()));
        }
        return new AstFile(file.fileName(), items);
    }
}
