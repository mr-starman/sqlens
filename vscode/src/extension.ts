import * as path from 'node:path';
import * as vscode from 'vscode';
import { ApiError, Datasource, describe, SqlensClient } from './api';
import { Datasources } from './datasources';
import { RESULTS_VIEW, ResultsPanel } from './results';
import { statementAt } from './statement';

/** Same choices as the web UI's number style picker. */
const NUMBER_STYLES = {
  point: { delimiter: ',', decimalSeparator: '.' },
  comma: { delimiter: ';', decimalSeparator: ',' },
} as const;

/** The web UI's row limits; 0 is all. */
const ROW_LIMITS = [10, 100, 1000, 10000, 0];

interface Statement {
  datasource: string;
  sql: string;
}

export function activate(context: vscode.ExtensionContext): void {
  const config = () => vscode.workspace.getConfiguration('sqlens');
  const client = () => new SqlensClient(config().get<string>('baseUrl', 'http://localhost:8080/sqlens'));
  const datasources = new Datasources(client, context.workspaceState);
  let lastQuery: Statement | undefined;
  const flash = vscode.window.createTextEditorDecorationType({
    backgroundColor: new vscode.ThemeColor('editor.findMatchHighlightBackground'),
  });
  const results = new ResultsPanel(context.extensionUri, () => {
    if (lastQuery) {
      void exportCsv(lastQuery);
    }
  });

  /** The selection, or with nothing selected the statement under the cursor. */
  function editorStatement(): string | undefined {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      void vscode.window.showWarningMessage('Open a SQL file to run a query.');
      return undefined;
    }
    const document = editor.document;
    if (!editor.selection.isEmpty) {
      return document.getText(editor.selection).trim() || undefined;
    }
    const range = statementAt(document.getText(), document.offsetAt(editor.selection.active));
    if (!range) {
      void vscode.window.showWarningMessage('No SQL statement at the cursor.');
      return undefined;
    }
    // Flash the statement rather than selecting it: a selection would put Vim
    // users into Visual mode and move the cursor.
    const statementRange = new vscode.Range(document.positionAt(range.from), document.positionAt(range.to));
    editor.setDecorations(flash, [statementRange]);
    setTimeout(() => editor.setDecorations(flash, []), 500);
    return document.getText(statementRange);
  }

  async function statement(): Promise<Statement | undefined> {
    const sql = editorStatement();
    if (!sql) {
      return undefined;
    }
    const datasource = await datasources.activeOrPick();
    return datasource ? { datasource, sql } : undefined;
  }

  async function runQuery(): Promise<void> {
    const query = await statement();
    if (!query) {
      return;
    }
    lastQuery = query;
    await results.show({ type: 'running', ...query });
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Window, title: `SQLens: running on ${query.datasource}`, cancellable: true },
      async (_progress, token) => {
        const abort = new AbortController();
        token.onCancellationRequested(() => abort.abort());
        try {
          const result = await client().query({ ...query, maxRows: config().get<number>('maxRows', 100) }, abort.signal);
          await results.show({ type: 'result', sql: query.sql, result });
        } catch (e) {
          const message = abort.signal.aborted ? 'Cancelled. The database may keep running the query until it finishes.' : errorText(e);
          await results.show({ type: 'error', ...query, message });
        }
      });
  }

  async function exportCsv(query?: Statement): Promise<void> {
    query ??= await statement();
    if (!query) {
      return;
    }
    const folder = vscode.workspace.workspaceFolders?.[0]?.uri;
    const fileName = `${query.datasource.replace(/[^A-Za-z0-9._-]+/g, '_')}.csv`;
    const target = await vscode.window.showSaveDialog({
      defaultUri: folder ? vscode.Uri.joinPath(folder, fileName) : undefined,
      filters: { CSV: ['csv'] },
      title: `Export from ${query.datasource}`,
    });
    if (!target) {
      return;
    }
    if (target.scheme !== 'file') {
      void vscode.window.showErrorMessage(`SQLens can only export to a local file, not ${target.toString()}`);
      return;
    }
    const style = NUMBER_STYLES[config().get<keyof typeof NUMBER_STYLES>('export.numberStyle', 'point')] ?? NUMBER_STYLES.point;
    const request = { ...query, maxRows: config().get<number>('export.maxRows', 0), format: 'csv', ...style };

    const exported = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: `SQLens: exporting from ${query.datasource}`, cancellable: true },
      async (_progress, token) => {
        const abort = new AbortController();
        token.onCancellationRequested(() => abort.abort());
        try {
          await client().exportToFile(request, target.fsPath, abort.signal);
          return true;
        } catch (e) {
          if (abort.signal.aborted) {
            void vscode.window.showInformationMessage('Export cancelled.');
          } else {
            void vscode.window.showErrorMessage(`Export failed: ${errorText(e)}`);
          }
          return false;
        }
      });
    // Outside withProgress: the progress notification stays until its callback
    // returns, and this message waits for the user.
    if (exported) {
      const open = 'Open';
      const choice = await vscode.window.showInformationMessage(`Exported to ${path.basename(target.fsPath)}`, open);
      if (choice === open) {
        await vscode.commands.executeCommand('vscode.open', target);
      }
    }
  }

  const rowsStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 49);
  rowsStatus.command = 'sqlens.setMaxRows';
  rowsStatus.tooltip = 'SQLens row limit for Run Query. Click to change.';
  const updateRowsStatus = () => {
    rowsStatus.text = `$(list-flat) ${rowsText(config().get<number>('maxRows', 100))}`;
    rowsStatus.show();
  };
  updateRowsStatus();

  async function setMaxRows(): Promise<void> {
    const current = config().get<number>('maxRows', 100);
    const custom = 'Other…';
    const items = [
      ...ROW_LIMITS.map(n => ({ label: rowsText(n), rows: n, description: n === current ? 'current' : undefined })),
      { label: custom, rows: -1, description: undefined },
    ];
    const choice = await vscode.window.showQuickPick(items, { placeHolder: 'Rows to fetch for Run Query' });
    if (!choice) {
      return;
    }
    let rows = choice.rows;
    if (choice.label === custom) {
      const input = await vscode.window.showInputBox({
        prompt: 'Rows to fetch for Run Query (0 for all)',
        value: String(current),
        validateInput: s => (/^\d+$/.test(s.trim()) ? null : 'Enter a whole number, 0 for all'),
      });
      if (input === undefined) {
        return;
      }
      rows = Number(input.trim());
    }
    // Write where the setting comes from now, so a workspace value isn't left shadowing a new user value.
    const inspected = config().inspect<number>('maxRows');
    const target = inspected?.workspaceFolderValue !== undefined ? vscode.ConfigurationTarget.WorkspaceFolder
      : inspected?.workspaceValue !== undefined ? vscode.ConfigurationTarget.Workspace
        : vscode.ConfigurationTarget.Global;
    await config().update('maxRows', rows, target);
  }

  context.subscriptions.push(
    datasources,
    results,
    rowsStatus,
    flash,
    vscode.commands.registerCommand('sqlens.setMaxRows', () => setMaxRows().catch(showError)),
    vscode.window.registerTreeDataProvider('sqlens.datasources', datasources),
    vscode.window.registerWebviewViewProvider(RESULTS_VIEW, results, { webviewOptions: { retainContextWhenHidden: true } }),
    vscode.commands.registerCommand('sqlens.runQuery', () => runQuery().catch(showError)),
    vscode.commands.registerCommand('sqlens.exportCsv', () => exportCsv().catch(showError)),
    vscode.commands.registerCommand('sqlens.pickDatasource', () => datasources.pick().catch(showError)),
    vscode.commands.registerCommand('sqlens.useDatasource', (ds: Datasource) => datasources.setActive(ds.name)),
    vscode.commands.registerCommand('sqlens.refreshDatasources', () => datasources.reload()),
    vscode.workspace.onDidChangeConfiguration(e => {
      if (e.affectsConfiguration('sqlens.baseUrl')) {
        void datasources.reload();
      }
      if (e.affectsConfiguration('sqlens.maxRows')) {
        updateRowsStatus();
      }
    }),
  );
}

export function deactivate(): void {}

function rowsText(rows: number): string {
  return rows === 0 ? 'All rows' : `${rows.toLocaleString()} rows`;
}

function errorText(e: unknown): string {
  if (e instanceof ApiError && e.sqlState) {
    return `${e.message} (SQLSTATE ${e.sqlState})`;
  }
  return describe(e);
}

function showError(e: unknown): void {
  void vscode.window.showErrorMessage(`SQLens: ${errorText(e)}`);
}
