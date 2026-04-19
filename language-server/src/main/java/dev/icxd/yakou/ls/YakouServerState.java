package dev.icxd.yakou.ls;

import dev.icxd.yakou.cp.ClasspathIndex;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workspace and client options gathered at {@code initialize}.
 */
final class YakouServerState {

    private volatile Optional<Path> workspaceRoot = Optional.empty();
    /** Explicit {@code sourceRoot} from {@code initializationOptions}. */
    private volatile Optional<Path> configuredSourceRoot = Optional.empty();
    private volatile List<Path> classpath = List.of();
    /**
     * File whose contents are a platform
     * {@link java.io.File#pathSeparator}-separated classpath.
     */
    private volatile Optional<Path> classpathFile = Optional.empty();

    private volatile ClasspathIndex classpathIndexCache;
    private volatile List<Path> classpathIndexKey = List.of();

    void applyInitialize(InitializeParams params) {
        workspaceRoot = firstWorkspaceRoot(params);
        configuredSourceRoot = Optional.empty();
        classpath = List.of();
        classpathFile = Optional.empty();
        classpathIndexCache = null;
        classpathIndexKey = List.of();

        Object raw = params.getInitializationOptions();
        if (raw instanceof Map<?, ?> map) {
            Object sr = map.get("sourceRoot");
            if (sr instanceof String s && !s.isBlank()) {
                configuredSourceRoot = Optional.of(Path.of(s).toAbsolutePath().normalize());
            }
            Object cp = map.get("classpath");
            if (cp instanceof List<?> list) {
                List<Path> paths = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof String s && !s.isBlank()) {
                        paths.add(Path.of(s));
                    }
                }
                classpath = List.copyOf(paths);
            }
            Object cf = map.get("classpathFile");
            if (cf instanceof String s && !s.isBlank()) {
                classpathFile = Optional.of(Path.of(s).toAbsolutePath().normalize());
            }
        }
    }

    Optional<Path> workspaceRoot() {
        return workspaceRoot;
    }

    Optional<Path> configuredSourceRoot() {
        return configuredSourceRoot;
    }

    List<Path> classpath() {
        return classpath;
    }

    /**
     * Classpath entries from {@code initializationOptions.classpath} plus any paths
     * parsed from {@code classpathFile} (if the file exists), in stable order.
     */
    List<Path> effectiveClasspath() {
        LinkedHashSet<Path> merged = new LinkedHashSet<>();
        merged.addAll(classpath);
        classpathFile.ifPresent(f -> merged.addAll(readClasspathFile(f)));
        return List.copyOf(merged);
    }

    ClasspathIndex classpathIndex() {
        List<Path> cp = effectiveClasspath();
        if (cp.equals(classpathIndexKey) && classpathIndexCache != null) {
            return classpathIndexCache;
        }
        synchronized (this) {
            if (cp.equals(classpathIndexKey) && classpathIndexCache != null) {
                return classpathIndexCache;
            }
            classpathIndexCache = ClasspathIndex.build(cp);
            classpathIndexKey = List.copyOf(cp);
            return classpathIndexCache;
        }
    }

    private static List<Path> readClasspathFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            String raw = Files.readString(file).trim();
            if (raw.isEmpty()) {
                return List.of();
            }
            List<Path> out = new ArrayList<>();
            String sep = java.util.regex.Pattern.quote(java.io.File.pathSeparator);
            for (String part : raw.split(sep)) {
                if (!part.isBlank()) {
                    out.add(Path.of(part.trim()));
                }
            }
            return out;
        } catch (@SuppressWarnings("unused") IOException e) {
            return List.of();
        }
    }

    private static Optional<Path> firstWorkspaceRoot(InitializeParams params) {
        List<WorkspaceFolder> folders = params.getWorkspaceFolders();
        if (folders != null && !folders.isEmpty()) {
            return Optional.of(uriToPath(folders.get(0).getUri()));
        }
        String rootUri = params.getRootUri();
        if (rootUri != null && !rootUri.isBlank()) {
            return Optional.of(uriToPath(rootUri));
        }
        String rootPath = params.getRootPath();
        if (rootPath != null && !rootPath.isBlank()) {
            return Optional.of(Path.of(rootPath).toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    private static Path uriToPath(String uri) {
        return Path.of(URI.create(uri)).toAbsolutePath().normalize();
    }
}
