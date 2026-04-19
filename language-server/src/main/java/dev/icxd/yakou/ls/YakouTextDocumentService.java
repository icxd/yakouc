package dev.icxd.yakou.ls;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            CompletionParams params) {
        String uri = params.getTextDocument().getUri();
        String doc = documents.get(uri);
        if (doc == null) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return CompletableFuture.completedFuture(YakouCompletions.complete(params, doc, server.state()));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        String doc = documents.get(uri);
        if (doc == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(
                YakouHover.hover(uri, doc, params.getPosition(), server.state()));
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        String uri = params.getTextDocument().getUri();
        String doc = documents.get(uri);
        if (doc == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.completedFuture(YakouInlayHints.hints(params, doc, server.state()));
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
