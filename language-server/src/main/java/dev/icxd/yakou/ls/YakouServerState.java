package dev.icxd.yakou.ls;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
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

    void applyInitialize(InitializeParams params) {
        workspaceRoot = firstWorkspaceRoot(params);
        configuredSourceRoot = Optional.empty();
        classpath = List.of();

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
