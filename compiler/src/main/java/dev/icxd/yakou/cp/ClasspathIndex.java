package dev.icxd.yakou.cp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Indexes {@code .class} resources from the JDK module image ({@code jrt:/})
 * plus
 * jars and directories on the compile classpath so tools can complete
 * {@code a::b::c}-style paths without loading every class. Use with
 * {@link dev.icxd.yakou.typeck.JavaInterop#internalNameToYkPath(String)} to
 * present names in Yakou form.
 */
public final class ClasspathIndex {

    private final ConcurrentHashMap<String, Set<String>> childNamesByParentInternal = new ConcurrentHashMap<>();

    private ClasspathIndex() {
    }

    /**
     * Build an index by scanning the runtime JDK ({@code jrt:/}) plus jars and
     * directories from the given classpath (same semantics as
     * {@link dev.icxd.yakou.typeck.JavaInterop} for the explicit entries).
     */
    public static ClasspathIndex build(List<Path> classpath) {
        ClasspathIndex ix = new ClasspathIndex();
        ix.indexJrtModules();
        if (classpath == null || classpath.isEmpty()) {
            return ix;
        }
        for (Path p : classpath) {
            if (!Files.exists(p)) {
                continue;
            }
            try {
                if (Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    ix.indexJar(p);
                } else if (Files.isDirectory(p)) {
                    ix.indexDirectory(p);
                }
            } catch (@SuppressWarnings("unused") IOException ignored) {
                // skip unreadable entries
            }
        }
        return ix;
    }

    /** Register every {@code .class} under each {@code jrt:/modules/*} tree. */
    private void indexJrtModules() {
        try {
            FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path modulesDir = fs.getPath("modules");
            if (!Files.isDirectory(modulesDir)) {
                return;
            }
            try (Stream<Path> mods = Files.list(modulesDir)) {
                mods.filter(Files::isDirectory).forEach(this::indexModuleRoot);
            }
        } catch (@SuppressWarnings("unused") IOException | UnsupportedOperationException ignored) {
            // jrt not available (e.g. non-modular JDK in tests)
        }
    }

    private void indexModuleRoot(Path moduleRoot) {
        try {
            Files.walkFileTree(
                    moduleRoot,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String s = file.toString();
                            if (!s.endsWith(".class")) {
                                return FileVisitResult.CONTINUE;
                            }
                            Path rel = moduleRoot.relativize(file);
                            String sep = moduleRoot.getFileSystem().getSeparator();
                            String noClass = rel.toString().replace(sep, "/");
                            if (noClass.endsWith(".class")) {
                                noClass = noClass.substring(0, noClass.length() - ".class".length());
                            }
                            if (!noClass.isEmpty()) {
                                registerClassInternal(noClass);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (@SuppressWarnings("unused") IOException ignored) {
            // unreadable module tree
        }
    }

    /**
     * Immediate child package/type names under this internal-name prefix (slashes,
     * e.g. {@code org/bukkit}), suitable for completing the next {@code ::} segment
     * after {@link JavaInterop#internalNameToYkPath(String)} conversion.
     */
    public List<String> completeInternalPrefix(String parentInternalPrefix) {
        Set<String> names = childNamesByParentInternal.get(normalizeParent(parentInternalPrefix));
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(names);
    }

    /**
     * Completes the next segment for a Yakou-style path prefix (segments joined
     * with {@code ::}), e.g. {@code "org::bukkit"} → child names.
     */
    public List<String> completeYkPathPrefix(String ykPrefix) {
        if (ykPrefix.isBlank()) {
            return completeInternalPrefix("");
        }
        String normalized = ykPrefix.replace("::", "/");
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return completeInternalPrefix(normalized);
    }

    private static String normalizeParent(String parent) {
        if (parent == null || parent.isEmpty()) {
            return "";
        }
        String p = parent.replace('\\', '/');
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private void indexJar(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry je = entries.nextElement();
                String path = je.getName();
                if (!path.endsWith(".class")) {
                    continue;
                }
                String noClass = path.substring(0, path.length() - ".class".length());
                if (noClass.endsWith("/")) {
                    continue;
                }
                registerClassInternal(noClass);
            }
        }
    }

    private void indexDirectory(Path root) throws IOException {
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.toString();
                        if (!name.endsWith(".class")) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path rel = root.relativize(file);
                        String noClass = rel.toString().replace(root.getFileSystem().getSeparator(), "/");
                        if (noClass.endsWith(".class")) {
                            noClass = noClass.substring(0, noClass.length() - ".class".length());
                        }
                        if (!noClass.isEmpty()) {
                            registerClassInternal(noClass);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private void registerClassInternal(String internalNoSuffix) {
        int slash = internalNoSuffix.lastIndexOf('/');
        if (slash < 0) {
            addChild("", internalNoSuffix);
            return;
        }
        String parent = internalNoSuffix.substring(0, slash);
        String simple = internalNoSuffix.substring(slash + 1);
        addChild(parent, simple);
    }

    private void addChild(String parentInternal, String childName) {
        childNamesByParentInternal
                .computeIfAbsent(normalizeParent(parentInternal),
                        k -> Collections.synchronizedSortedSet(new TreeSet<>()))
                .add(childName);
    }
}
