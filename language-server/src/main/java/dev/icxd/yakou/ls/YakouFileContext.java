package dev.icxd.yakou.ls;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Workspace paths and document labels shared by diagnostics and analysis. */
final class YakouFileContext {

    private YakouFileContext() {
    }

    static Optional<Path> resolveSourceRoot(YakouServerState state) {
        if (state.configuredSourceRoot().isPresent()) {
            return state.configuredSourceRoot();
        }
        Optional<Path> ws = state.workspaceRoot();
        if (ws.isEmpty()) {
            return Optional.empty();
        }
        Path candidate = ws.get().resolve("src/main/yakou");
        if (Files.isDirectory(candidate)) {
            return Optional.of(candidate.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    static Optional<Path> filePath(String documentUri) {
        if (!documentUri.startsWith("file:")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(URI.create(documentUri)));
        } catch (@SuppressWarnings("unused") Exception e) {
            return Optional.empty();
        }
    }

    static String safePathLabel(String documentUri) {
        try {
            if (documentUri.startsWith("file:")) {
                return Path.of(URI.create(documentUri)).toAbsolutePath().normalize().toString();
            }
        } catch (@SuppressWarnings("unused") Exception ignored) {
            // fall through
        }
        return documentUri;
    }
}
