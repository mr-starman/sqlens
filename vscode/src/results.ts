/**
 * The results panel: one webview that shows the latest query result. Rows are
 * drawn by media/results.js, which only keeps the rows in view in the DOM.
 */
import * as vscode from 'vscode';
import { QueryResult } from './api';

export type PanelMessage =
  | { type: 'running'; datasource: string; sql: string }
  | { type: 'result'; sql: string; result: QueryResult }
  | { type: 'error'; datasource: string; sql: string; message: string };

export class ResultsPanel implements vscode.Disposable {

  private panel: vscode.WebviewPanel | undefined;
  private ready = false;
  private pending: PanelMessage | undefined;

  constructor(private readonly extensionUri: vscode.Uri, private readonly onExport: () => void) {}

  show(message: PanelMessage): void {
    if (!this.panel) {
      this.panel = this.create();
    } else if (!this.panel.visible) {
      this.panel.reveal(vscode.ViewColumn.Beside, true);
    }
    if (this.ready) {
      void this.panel.webview.postMessage(message);
    } else {
      this.pending = message;
    }
  }

  private create(): vscode.WebviewPanel {
    const media = vscode.Uri.joinPath(this.extensionUri, 'media');
    const panel = vscode.window.createWebviewPanel('sqlens.results', 'SQLens Results',
      { viewColumn: vscode.ViewColumn.Beside, preserveFocus: true },
      { enableScripts: true, retainContextWhenHidden: true, localResourceRoots: [media] });
    const webview = panel.webview;
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
<body>
<div id="status" class="status">Run a query with Ctrl+Enter in a SQL file.</div>
<div id="grid" class="grid"></div>
<script nonce="${nonce}" src="${script}"></script>
</body>
</html>`;
    webview.onDidReceiveMessage(message => {
      if (message?.type === 'ready') {
        this.ready = true;
        if (this.pending) {
          void webview.postMessage(this.pending);
          this.pending = undefined;
        }
      } else if (message?.type === 'export') {
        this.onExport();
      }
    });
    panel.onDidDispose(() => {
      this.panel = undefined;
      this.ready = false;
    });
    return panel;
  }

  dispose(): void {
    this.panel?.dispose();
  }
}
