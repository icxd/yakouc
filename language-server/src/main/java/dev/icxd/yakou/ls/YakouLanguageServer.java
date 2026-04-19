package dev.icxd.yakou.ls;

import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class YakouLanguageServer implements LanguageServer, LanguageClientAware {

    private final YakouServerState state = new YakouServerState();
    private final YakouTextDocumentService textDocuments = new YakouTextDocumentService(this);
    private final YakouWorkspaceService workspace = new YakouWorkspaceService();
    private volatile LanguageClient client;

    YakouServerState state() {
        return state;
    }

    LanguageClient client() {
        return client;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        state.applyInitialize(params);
        ServerCapabilities caps = new ServerCapabilities();
        caps.setTextDocumentSync(TextDocumentSyncKind.Full);
        CompletionOptions comp = new CompletionOptions();
        comp.setResolveProvider(false);
        comp.setTriggerCharacters(List.of(".", ":"));
        caps.setCompletionProvider(comp);
        caps.setHoverProvider(true);
        caps.setInlayHintProvider(true);
        InitializeResult result = new InitializeResult();
        result.setCapabilities(caps);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocuments;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspace;
    }
}
