package dev.icxd.yakou.ls;

import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.concurrent.Future;

/**
 * Stdio entrypoint for editors: {@code java -jar yakou-ls.jar}.
 */
public final class YakouLanguageServerLauncher {

    private YakouLanguageServerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        YakouLanguageServer server = new YakouLanguageServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        Future<?> listening = launcher.startListening();
        listening.get();
    }
}
