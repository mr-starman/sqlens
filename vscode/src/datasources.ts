/**
 * The datasource list from the server, the one picked for queries (kept per
 * workspace), the "SQLens Datasources" tree view and a status bar item.
 */
import * as vscode from 'vscode';
import { Datasource, DatasourceList, describe, SqlensClient } from './api';

const ACTIVE_KEY = 'sqlens.activeDatasource';

export class Datasources implements vscode.TreeDataProvider<Datasource | string>, vscode.Disposable {

  private list: DatasourceList | null = null;
  private loadError: string | null = null;
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.changed.event;
  private readonly status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);

  constructor(private readonly client: () => SqlensClient, private readonly state: vscode.Memento) {
    this.status.command = 'sqlens.pickDatasource';
    this.updateStatus();
  }

  get active(): string | undefined {
    return this.state.get<string>(ACTIVE_KEY);
  }

  async setActive(name: string): Promise<void> {
    await this.state.update(ACTIVE_KEY, name);
    this.updateStatus();
    this.changed.fire();
  }

  async reload(): Promise<void> {
    try {
      this.list = await this.client().datasources();
      this.loadError = null;
    } catch (e) {
      this.list = null;
      this.loadError = describe(e);
    }
    this.updateStatus();
    this.changed.fire();
  }

  /** The datasources, loading them first if needed. Throws when the server can't give them. */
  async all(): Promise<Datasource[]> {
    if (!this.list) {
      await this.reload();
    }
    if (!this.list) {
      throw new Error(this.loadError ?? 'Cannot load datasources');
    }
    if (this.list.error) {
      throw new Error(`${this.list.configFile}: ${this.list.error}`);
    }
    return this.list.datasources;
  }

  /** The active datasource, asking for one when none is picked yet. */
  async activeOrPick(): Promise<string | undefined> {
    const names = (await this.all()).map(d => d.name);
    const active = this.active;
    if (active && names.includes(active)) {
      return active;
    }
    return this.pick();
  }

  async pick(): Promise<string | undefined> {
    const items = (await this.all()).map(d => ({
      label: d.name,
      description: d.readOnly ? `${d.description ?? ''} (read-only)`.trim() : d.description ?? undefined,
      detail: d.url,
      picked: d.name === this.active,
    }));
    if (items.length === 0) {
      throw new Error(`No datasources in ${this.list?.configFile ?? 'datasources.json'}`);
    }
    const choice = await vscode.window.showQuickPick(items, { placeHolder: 'Datasource for SQLens queries' });
    if (choice) {
      await this.setActive(choice.label);
    }
    return choice?.label;
  }

  getTreeItem(element: Datasource | string): vscode.TreeItem {
    if (typeof element === 'string') {
      const item = new vscode.TreeItem(element);
      item.iconPath = new vscode.ThemeIcon('error');
      item.tooltip = element;
      return item;
    }
    const item = new vscode.TreeItem(element.name);
    const active = element.name === this.active;
    item.description = [element.description, element.readOnly ? 'read-only' : null].filter(Boolean).join(' · ');
    item.tooltip = new vscode.MarkdownString(
      `**${element.name}**${active ? ' (active)' : ''}\n\n\`${element.url}\`` +
      (element.user ? `\n\nUser: ${element.user}` : ''));
    item.iconPath = new vscode.ThemeIcon(active ? 'pass-filled' : 'database');
    item.contextValue = 'datasource';
    item.command = { command: 'sqlens.useDatasource', title: 'Use This Datasource', arguments: [element] };
    return item;
  }

  async getChildren(element?: Datasource | string): Promise<(Datasource | string)[]> {
    if (element) {
      return [];
    }
    if (!this.list && !this.loadError) {
      await this.reload();
    }
    if (this.loadError) {
      return [this.loadError];
    }
    if (this.list?.error) {
      return [`${this.list.configFile}: ${this.list.error}`];
    }
    return this.list?.datasources ?? [];
  }

  private updateStatus(): void {
    this.status.text = `$(database) ${this.active ?? 'No datasource'}`;
    this.status.tooltip = 'SQLens datasource. Click to change.';
    this.status.show();
  }

  dispose(): void {
    this.status.dispose();
    this.changed.dispose();
  }
}
