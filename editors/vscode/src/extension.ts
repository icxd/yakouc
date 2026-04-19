import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from "vscode-languageclient/node";

let client: LanguageClient | undefined;
let outputChannel: vscode.OutputChannel | undefined;

function workspaceFolderPath(): string | undefined {
  return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
}

function substituteWorkspace(value: string): string {
  const root = workspaceFolderPath() ?? "";
  return value.replaceAll("${workspaceFolder}", root);
}

function resolveJarPath(context: vscode.ExtensionContext): string | undefined {
  const configured = substituteWorkspace(
    vscode.workspace.getConfiguration("yakou").get<string>("languageServerJar", "").trim()
  );
  if (configured.length > 0) {
    const abs = path.normalize(configured);
    if (fs.existsSync(abs)) {
      return abs;
    }
    void vscode.window.showWarningMessage(
      `Yakou: yakou.languageServerJar not found: ${abs}`
    );
    return undefined;
  }

  const fromEnv = process.env.YAKOU_LS_JAR?.trim();
  if (fromEnv && fs.existsSync(fromEnv)) {
    return path.normalize(fromEnv);
  }

  const dev = path.join(
    context.extensionPath,
    "..",
    "..",
    "language-server",
    "target",
    "yakou-ls.jar"
  );
  if (fs.existsSync(dev)) {
    return path.normalize(dev);
  }

  return undefined;
}

function buildInitializationOptions(): Record<string, unknown> {
  const c = vscode.workspace.getConfiguration("yakou");
  const out: Record<string, unknown> = {};
  const sr = substituteWorkspace(c.get<string>("sourceRoot", "").trim());
  if (sr.length > 0) {
    out.sourceRoot = sr;
  }
  const cp = c.get<string[]>("classpath", []);
  const resolved = (cp ?? []).map((p) => substituteWorkspace(p)).filter((s) => s.length > 0);
  if (resolved.length > 0) {
    out.classpath = resolved;
  }
  return out;
}

function createClient(context: vscode.ExtensionContext, jarPath: string): LanguageClient {
  if (!outputChannel) {
    outputChannel = vscode.window.createOutputChannel("Yakou Language Server");
    context.subscriptions.push(outputChannel);
  }

  const javaPath = vscode.workspace.getConfiguration("yakou").get<string>("javaPath", "java");

  const serverOptions: ServerOptions = {
    run: { command: javaPath, args: ["-jar", jarPath] },
    debug: { command: javaPath, args: ["-jar", jarPath] },
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: "file", language: "yakou" },
      { scheme: "untitled", language: "yakou" },
    ],
    outputChannel,
    initializationOptions: buildInitializationOptions(),
  };

  return new LanguageClient(
    "yakouLanguageServer",
    "Yakou Language Server",
    serverOptions,
    clientOptions
  );
}

async function stopClient(): Promise<void> {
  if (client) {
    await client.stop();
    client = undefined;
  }
}

async function startClient(context: vscode.ExtensionContext): Promise<void> {
  await stopClient();

  const jar = resolveJarPath(context);
  if (!jar) {
    void vscode.window.showErrorMessage(
      "Yakou: could not find yakou-ls.jar. Build with `mvn -pl language-server package`, set yakou.languageServerJar, or set YAKOU_LS_JAR."
    );
    return;
  }

  client = createClient(context, jar);
  try {
    await client.start();
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    void vscode.window.showErrorMessage(`Yakou: failed to start language server: ${msg}`);
  }
}

export function activate(context: vscode.ExtensionContext): void {
  void startClient(context);

  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration(async (e) => {
      if (e.affectsConfiguration("yakou")) {
        await startClient(context);
      }
    }),
    { dispose: () => void stopClient() }
  );
}

export function deactivate(): Promise<void> | undefined {
  return stopClient();
}
