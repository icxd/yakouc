package dev.icxd.yakou.ls;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

/** Placeholder for future workspace features (configuration, symbol, etc.). */
final class YakouWorkspaceService implements WorkspaceService {

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // not used in v1
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // not used in v1 (no file watchers registered)
    }
}
