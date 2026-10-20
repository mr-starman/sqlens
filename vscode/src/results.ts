/**
 * Shows the latest query result in a webview, either in the "SQLens" tab of
 * the bottom panel (the default) or in an editor tab beside the SQL file, as
 * picked by the sqlens.results.location setting. Rows are drawn by
 * media/results.js, which only keeps the rows in view in the DOM.
 */
import * as vscode from 'vscode';
import { QueryResult } from './api';

export type PanelMessage =
  | { type: 'running'; datasource: string; sql: string }
  | { type: 'result'; sql: string; result: QueryResult }
  | { type: 'error'; datasource: string; sql: string; message: string };

export const RESULTS_VIEW = 'sqlens.results';

export class ResultsPanel implements vscode.WebviewViewProvider, vscode.Disposable {

  private view: vscode.WebviewView | undefined;
  private panel: vscode.WebviewPanel | undefined;
  /** The webview that has said it is ready for messages. */
  private ready: vscode.Webview | undefined;
  /** Sent again to a webview that is created or recreated. */
  private latest: PanelMessage | undefined;

  constructor(private readonly extensionUri: vscode.Uri, private readonly onExport: () => void) {}

  async show(message: PanelMessage): Promise<void> {
    this.latest = message;
    const inPanel = vscode.workspace.getConfiguration('sqlens').get<string>('results.location', 'panel') === 'panel';
    if (inPanel) {
      this.panel?.dispose();
      await this.revealView();
    } else {
      this.revealEditorTab();
    }
    const webview = inPanel ? this.view?.webview : this.panel?.webview;
    if (webview && webview === this.ready) {
      void webview.postMessage(message);
    }
  }

  /** Called by VS Code the first time the bottom panel view is shown. */
  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view;
    this.setUp(view.webview, 'in-panel');
    view.onDidDispose(() => {
      if (this.view === view) {
        this.view = undefined;
      }
    });
  }

  private async revealView(): Promise<void> {
    if (this.view) {
      this.view.show(true);
      return;
    }
    // The view is created when first shown. Its focus command is the only way
    // to show it, so give the focus back to the editor the query came from.
    const editor = vscode.window.activeTextEditor;
    await vscode.commands.executeCommand(`${RESULTS_VIEW}.focus`);
    if (editor) {
      await vscode.window.showTextDocument(editor.document, editor.viewColumn);
    }
  }

  private revealEditorTab(): void {
    if (this.panel) {
      if (!this.panel.visible) {
        this.panel.reveal(vscode.ViewColumn.Beside, true);
      }
      return;
    }
    const panel = vscode.window.createWebviewPanel(RESULTS_VIEW, 'SQLens Results',
      { viewColumn: vscode.ViewColumn.Beside, preserveFocus: true },
      { enableScripts: true, retainContextWhenHidden: true });
    this.panel = panel;
    this.setUp(panel.webview, 'in-editor');
    panel.onDidDispose(() => {
      if (this.panel === panel) {
        this.panel = undefined;
      }
    });
  }

  private setUp(webview: vscode.Webview, where: 'in-panel' | 'in-editor'): void {
    const media = vscode.Uri.joinPath(this.extensionUri, 'media');
    webview.options = { enableScripts: true, localResourceRoots: [media] };
    const nonce = [...crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join('');
    const script = webview.asWebviewUri(vscode.Uri.joinPath(media, 'results.js'));
    const style = webview.asWebviewUri(vscode.Uri.joinPath(media, 'results.css'));
    webview.html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource}; script-src 'nonce-${nonce}';">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="${style}">
</head>
<body class="${where}">
<div id="status" class="status">Run a query with Ctrl+Enter in a SQL file.</div>
<div id="grid" class="grid"></div>
<script nonce="${nonce}" src="${script}"></script>
</body>
</html>`;
    webview.onDidReceiveMessage(message => {
      if (message?.type === 'ready') {
        this.ready = webview;
        if (this.latest) {
          void webview.postMessage(this.latest);
        }
      } else if (message?.type === 'export') {
        this.onExport();
      }
    });
  }

  dispose(): void {
    this.panel?.dispose();
  }
}
