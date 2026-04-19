package dev.icxd.yakou.ls;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.HashMap;
import java.util.Map;

final class YakouTextDocumentService implements TextDocumentService {

    private final YakouLanguageServer server;
    private final Map<String, String> documents = new HashMap<>();

    YakouTextDocumentService(YakouLanguageServer server) {
        this.server = server;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documents.put(uri, params.getTextDocument().getText());
        publish(uri);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        if (params.getContentChanges().isEmpty()) {
            return;
        }
        documents.put(uri, params.getContentChanges().get(0).getText());
        publish(uri);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documents.remove(uri);
        clearDiagnostics(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        publish(params.getTextDocument().getUri());
    }

    private void publish(String uri) {
        LanguageClient client = server.client();
        if (client == null) {
            return;
        }
        String text = documents.getOrDefault(uri, "");
        var diags = YakouDiagnostics.analyze(uri, text, server.state());
        PublishDiagnosticsParams p = new PublishDiagnosticsParams(uri, diags);
        client.publishDiagnostics(p);
    }

    private void clearDiagnostics(String uri) {
        LanguageClient client = server.client();
        if (client == null) {
            return;
        }
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, java.util.List.of()));
    }
}
