# SQLens for VS Code

Runs SQL from VS Code against the datasources of a running SQLens webapp and
exports results to CSV. It talks to the same JSON API as the web UI, so
datasources, drivers and read-only checks are all configured on the server
in `datasources.json`.

## Use

1. Start SQLens (see the main [README](../README.md)) and check that
   <http://localhost:8080/sqlens/api/datasources> answers.
2. Open a `.sql` file. Pick a datasource from **SQLens Datasources** in the
   Explorer, or with the status bar item.
3. **Ctrl+Enter** (⌘+Enter on a Mac) runs the selection, or with nothing
   selected the statement under the cursor. Statements end at a `;` or a blank
   line, like in the web UI. The result opens in the **SQLens** tab of the
   bottom panel, next to Terminal and Problems, and the cursor stays in the
   editor.
   The row limit is in the status bar: click it, or run **SQLens: Set Row
   Limit**, to pick 10, 100, 1,000, 10,000, All or another number.
4. **Export Query to CSV…** (Alt+Shift+E, the editor title bar, or the button in
   the results panel) runs the statement again and streams all rows into a file
   you pick. Cancel it from the progress notification. If the query fails
   after rows were written, the partial file is deleted.

## Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `sqlens.baseUrl` | `http://localhost:8080/sqlens` | Where SQLens runs |
| `sqlens.results.location` | `panel` | `panel`: the SQLens tab in the bottom panel; `beside`: an editor tab beside the SQL file |
| `sqlens.maxRows` | `100` | Rows for Run Query; `0` is all, up to the server's `maxGridRows` |
| `sqlens.export.maxRows` | `0` | Rows for export; `0` is all |
| `sqlens.export.numberStyle` | `point` | `point`: `,` between fields and `1.5`; `comma`: `;` and `1,5` |

With VS Code Remote (WSL, SSH) the extension runs on the remote side, so
`localhost` is the remote machine, where Tomcat usually runs too.

## Develop

```sh
cd vscode
npm install
npm test          # compiles, then runs the unit tests
```

Open the `vscode` folder in VS Code and press F5 to start an Extension
Development Host with the extension loaded. `npm run package` builds a
`.vsix` to install with **Extensions: Install from VSIX…**.
