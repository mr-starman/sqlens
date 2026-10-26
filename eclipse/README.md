# SQLens for Eclipse

Runs SQL from an Eclipse SQL editor against the datasources of a running SQLens
webapp, shows the result in a grid, copies it to the clipboard for Excel and
exports it to CSV. It talks to the same JSON API as the web UI and the
[VS Code extension](../vscode), so datasources, drivers and read-only checks are
all configured on the server in `datasources.json`.

Built for Eclipse 2023-09 (4.29), the platform under iSCOBOL Evolve IDE 2025R1.
It needs Java 17, which that Eclipse requires anyway.

## Install

Build it (see below), then copy `sqlens.eclipse/target/sqlens.eclipse-*.jar` into the
`dropins` folder of the IDE and start the IDE once with `-clean`
(`evolve -clean`, or add `-clean` as the first line of its `.ini` file). If the
IDE has no `dropins` folder, create it next to `plugins`.

**Window > Show View > Other… > SQLens > SQLens Results** shows the view, and
**Window > Preferences > SQLens** has the settings. If neither is there, the
bundle did not load: start the IDE with `-consoleLog` and look for `sqlens.eclipse`.

## Use

1. Start SQLens (see the main [README](../README.md)) and check that
   <http://localhost:8080/sqlens/api/datasources> answers.
2. Open a `.sql` file in the SQL editor. Results show in the **SQLens Results** view, which opens in the bottom panel as a tab next to Problems. The first run asks for a datasource; the
   **SQLens Results** view has a datasource and a row limit picker.
3. **Ctrl+Enter** (⌘+Enter on a Mac) runs the selection, or with nothing selected
   the statement under the cursor. Statements end at a `;` or a blank line, like
   in the web UI. The cursor stays in the editor.
4. **Copy** in the view's toolbar puts all rows on the clipboard as tab-separated
   text with headers, which pastes into Excel as cells. **Ctrl+C** in the grid,
   or the context menu, copies the selected rows.
5. **Export CSV…** (the view's toolbar, or **Alt+Shift+E** in the editor) runs the
   statement again and streams all rows into a file. Cancel it from the
   progress view. If the query fails after rows were written, the partial file is
   deleted.

The shortcuts and the SQLens menu work in editors the plug-in takes for SQL
editors: the Data Tools SQL editor, any editor whose id mentions SQL, and any text
editor open on a `.sql` file.

## Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| SQLens URL | `http://localhost:8080/sqlens` | Where SQLens runs |
| Number style | decimal point | Point: `,` between fields and `1.5`; comma: `;` and `1,5`. Used by Copy and Export |

The row limit is in the results view (10, 100, 1,000, 10,000 or all).

## Develop

```sh
cd eclipse
mvn verify        # builds the bundle and runs the unit tests
```

This is a [Tycho](https://tycho.eclipseprojects.io) build that resolves Eclipse
2023-09 from the Eclipse p2 repositories, so the first build downloads a few
hundred MB. The tests are JUnit 4 in `sqlens.eclipse.tests`, a fragment of the
bundle; they cover the parts without UI (statement splitting, TSV, the API
replies).

To try the bundle from Eclipse, import both folders as existing projects into an
Eclipse with the *Plug-in Development Environment* and run
`sqlens.eclipse` as an **Eclipse Application**.
