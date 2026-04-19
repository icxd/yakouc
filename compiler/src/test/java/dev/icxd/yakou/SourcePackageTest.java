package dev.icxd.yakou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.Item;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

class SourcePackageTest {

    @Test
    void implicitPrefixFromNestedDirectories(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("yk");
        Files.createDirectories(root.resolve("dev/icxd/foo"));
        Path src = root.resolve("dev/icxd/foo/bar.yk");
        Files.writeString(src, "//\n");
        assertEquals(Optional.of("dev::icxd::foo"), SourcePackage.implicitPrefix(root, src));
    }

    @Test
    void implicitPrefixEmptyWhenFileAtRoot(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("yk");
        Files.createDirectories(root);
        Path src = root.resolve("only.yk");
        Files.writeString(src, "//\n");
        assertTrue(SourcePackage.implicitPrefix(root, src).isEmpty());
    }

    @Test
    void implicitPrefixRejectsFileOutsideRoot(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("yk");
        Files.createDirectories(root);
        Path other = dir.resolve("other/out.yk");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "//\n");
        assertThrows(IllegalArgumentException.class, () -> SourcePackage.implicitPrefix(root, other));
    }

    @Test
    void applyImplicitPrefixWrapsPkgChain() {
        AstFile ast = new AstFile("f.yk", List.of(new Item.Use(List.of("java", "lang", "System"), Optional.empty())));
        AstFile wrapped = SourcePackage.applyImplicitPrefix(ast, Optional.of("p::q"));
        Item.Pkg outer = (Item.Pkg) wrapped.items().getFirst();
        assertEquals("p", outer.name());
        Item.Pkg inner = (Item.Pkg) outer.items().getFirst();
        assertEquals("q", inner.name());
        assertTrue(inner.items().getFirst() instanceof Item.Use);
    }
}
