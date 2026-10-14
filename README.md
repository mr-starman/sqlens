# SQLens

A small SQL query tool for development boxes. A Java webapp that runs on Tomcat
and is used mainly with FairCom **c-treeRTG**, but it should work with any
database that has a JDBC driver. The examples below use c-treeRTG; the
[JDBC drivers](#jdbc-drivers) section lists other databases.

It has a web UI with a SQL editor, a result grid, CSV export and copy to
clipboard, backed by a small JSON API.

> No authentication. SQLens is meant for a local devbox; don't expose it to a network you don't trust.

## Requirements

- Java 17 or newer
- Tomcat 9 (the app uses the Servlet 4.0 `javax.servlet` API). To run it on Tomcat 10 or newer, drop the WAR in
  `webapps-javaee/` instead of `webapps/`, and Tomcat converts it to `jakarta.servlet` on deploy
- Maven 3.8+ to build

## Build

```sh
mvn package
```

This produces `target/sqlens.war`.

## Configure

Everything SQLens reads lives in one folder outside the WAR, the **SQLens home**:

```
~/.sqlens/
├── datasources.json   # your connections
└── drivers/           # JDBC driver jars
```

The home folder is found in this order:

1. the `sqlens.home` Java system property (`-Dsqlens.home=/path`)
2. the `SQLENS_HOME` environment variable
3. `~/.sqlens` of the user running Tomcat

The resolved paths are logged at startup and shown by `GET /api/datasources`.

### Datasources

`datasources.json` holds a list of connections. Copy
[`examples/datasources.json`](examples/datasources.json) as a starting point.
A c-treeRTG database on the same machine looks like this:

```json
{
  "datasources": [
    {
      "name": "chinook",
      "description": "c-treeRTG on localhost, database chinook",
      "url": "jdbc:ctree:6597@localhost:chinook",
      "driver": "ctree.jdbc.ctreeDriver",
      "user": "ADMIN",
      "password": "ADMIN"
    }
  ]
}
```

The c-tree URL is `jdbc:ctree:<port>@<host>:<database>`. 6597 is the default
SQL port of a c-treeRTG server and `ADMIN`/`ADMIN` the default login; use your
server's if they differ. The `driver` line is optional, since the c-tree jar
registers its driver, but it gives a clearer error when the jar is missing.
Other databases only need their own JDBC URL, for example
`jdbc:postgresql://localhost:5432/app`.

| Field                 | Required | Meaning |
|-----------------------|----------|---------|
| `name`                | yes      | Unique name shown in the UI |
| `url`                 | yes      | JDBC URL |
| `description`         | no       | Free text shown next to the name |
| `user`, `password`    | no       | Credentials |
| `driver`              | no       | Driver class name. Usually not needed: the driver is picked from the URL |
| `properties`          | no       | Extra JDBC connection properties, as string pairs |
| `readOnly`            | no       | `true` blocks writes, see [Read-only datasources](#read-only-datasources) |
| `queryTimeoutSeconds` | no       | Statement timeout; absent or `0` means none |

`${NAME}` in `url`, `user`, `password` and `properties` is replaced with the
environment variable or Java system property of that name, so secrets can stay
out of the file.

An optional top-level `settings` object holds app-wide settings:

```json
{
  "settings": { "maxGridRows": 50000 },
  "datasources": [ ... ]
}
```

| Setting       | Default | Meaning |
|---------------|---------|---------|
| `maxGridRows` | 50000   | Most rows the result grid gets, even with **Rows** set to All. The browser keeps the whole grid in memory, so this stops a big "All" from freezing the tab. Export is not capped. `0` turns the cap off |

The file is re-read whenever it changes, so there is no need to restart Tomcat
after editing it. If it is missing or invalid, the API reports why in the
`error` field.

### Read-only datasources

With `"readOnly": true`, SQLens does two things:

1. It checks each statement before running it and rejects anything that is
   not a plain read. A statement must start with `SELECT`, `WITH`, `SHOW`,
   `DESCRIBE`, `EXPLAIN` or `VALUES`, must be a single statement, and must not
   contain `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `INTO`, `CREATE`, `ALTER`,
   `DROP`, `TRUNCATE`, `GRANT`, `REVOKE`, `CALL`, `EXEC` and similar words
   outside strings, quoted names and comments. That also blocks
   `select ... for update`, `select ... into` and data-modifying `WITH`
   clauses. The error has SQLSTATE `25006`.
2. It asks the driver for a read-only connection. Some drivers enforce this
   (MySQL, MariaDB), others ignore it (most others). Whether the c-tree
   driver enforces it is untested, so count on the check in step 1, or try
   an `update` on a test table to find out. For PostgreSQL add
   `"properties": { "readOnlyMode": "always" }` to make it stick, since SQLens
   runs with auto-commit.

The check is a keyword scan, not a SQL parser, so it can be wrong in both
directions. It cannot see what a function or procedure called from a
`SELECT` does, and a MySQL string with a backslash-escaped quote
(`'don\'t delete'`) can be rejected. The only real guarantee is a database
user that can only read. On c-treeRTG, that means a user who has been
granted only `SELECT` on the tables.

### JDBC drivers

Drop driver jars into `~/.sqlens/drivers/`. Jars added, removed or replaced
there are picked up on the next query, without a restart. A driver jar can
also go into Tomcat's own `lib/` folder; SQLens falls back to drivers it finds
there.

The jar must be readable by the user Tomcat runs as. Copy it rather than
symlinking it, since a link into a folder that user can't read looks like a
missing jar. `GET /api/datasources` lists the drivers found under `drivers`,
and any jar Java could not open under `driverJarErrors`.

#### c-treeRTG

The c-tree JDBC driver is `ctreeJDBC.jar`. It ships with c-treeRTG and is not
on Maven Central. Copy it from the `sql.jdbc` folder of your c-treeRTG
install (the exact path depends on the version):

```sh
mkdir -p ~/.sqlens/drivers
cp /path/to/c-treeRTG/sql.jdbc/ctreeJDBC.jar ~/.sqlens/drivers/
chmod o+r ~/.sqlens/drivers/ctreeJDBC.jar
```

#### Other databases

Any database with a JDBC driver should work the same way: put its jar in
`drivers/` and add a datasource with its JDBC URL. Common ones (download the
jar from Maven Central):

| Database   | Jar |
|------------|-----|
| PostgreSQL | `org.postgresql:postgresql` |
| MySQL      | `com.mysql:mysql-connector-j` |
| MariaDB    | `org.mariadb.jdbc:mariadb-java-client` |
| SQL Server | `com.microsoft.sqlserver:mssql-jdbc` (the `jre11` build) |
| Oracle     | `com.oracle.database.jdbc:ojdbc11` |
| H2         | `com.h2database:h2` |
| SQLite     | `org.xerial:sqlite-jdbc` |

For example:

```sh
mkdir -p ~/.sqlens/drivers
curl -L -o ~/.sqlens/drivers/postgresql-42.7.4.jar \
  https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar
```

## Deploy on Tomcat

Two setups are described: Tomcat unpacked in your home folder (simplest, and
the one to use on WSL), and the Arch Linux `tomcat9` package running under
systemd.

### Tomcat in your home folder (WSL)

The simplest setup on a WSL devbox is a Tomcat unpacked in your home folder,
running as your own user, so `~/.sqlens` is your home's.

```sh
# Java and Tomcat
sudo apt install openjdk-17-jdk
curl -LO https://archive.apache.org/dist/tomcat/tomcat-9/v9.0.96/bin/apache-tomcat-9.0.96.tar.gz
tar xzf apache-tomcat-9.0.96.tar.gz && mv apache-tomcat-9.0.96 ~/tomcat

# SQLens home
mkdir -p ~/.sqlens/drivers
cp examples/datasources.json ~/.sqlens/datasources.json   # then edit it
cp /path/to/c-treeRTG/sql.jdbc/ctreeJDBC.jar ~/.sqlens/drivers/   # or other driver jars

# Deploy and start
cp target/sqlens.war ~/tomcat/webapps/
~/tomcat/bin/startup.sh
```

Open <http://localhost:8080/sqlens/> in a browser on Windows; WSL forwards
`localhost` to the Linux side. Logs are in `~/tomcat/logs/catalina.out`.

To keep the SQLens home somewhere else, set it in `~/tomcat/bin/setenv.sh`:

```sh
export SQLENS_HOME=/path/to/sqlens-home
# or: export CATALINA_OPTS="$CATALINA_OPTS -Dsqlens.home=/path/to/sqlens-home"
```

### Arch Linux `tomcat9` package (systemd)

The package runs Tomcat as the `tomcat9` user through `jsvc`, deploys from
`/var/lib/tomcat9/webapps` and logs to the systemd journal. Java's home
folder is set to `/usr/share/tomcat9`, so SQLens needs to be told where your
`.sqlens` folder is. This setup keeps the SQLens home in your own home folder:

1. Point SQLens at your folder and let the service read your home. Run
   `sudo systemctl edit tomcat9` and add:

   ```ini
   [Service]
   Environment=SQLENS_HOME=/home/you/.sqlens
   ProtectHome=read-only
   ```

   `ProtectHome=read-only` lets the sandboxed service read `/home`. Don't set
   `User=`: `jsvc` must start as root and switches to `tomcat9` itself.

2. Let `tomcat9` through your home folder without letting it list or read
   anything else there:

   ```sh
   sudo setfacl -m u:tomcat9:x /home/you
   ```

   `~/.sqlens`, `datasources.json` and the driver jars must be readable by
   others (`chmod -R o+rX ~/.sqlens`).

3. Deploy and restart:

   ```sh
   sudo cp target/sqlens.war /var/lib/tomcat9/webapps/
   sudo systemctl restart tomcat9
   ```

4. Check <http://localhost:8080/sqlens/api/datasources>: `configFile` should
   be `/home/you/.sqlens/datasources.json` and `drivers` should list
   `ctree.jdbc.ctreeDriver`.

Logs: `journalctl -u tomcat9 -f`. If the service fails to start,
`journalctl -u tomcat9 -n 50` names the path or user it is stuck on.

Other distributions' packages differ in user name (`tomcat` on Debian and
Ubuntu) and paths, but the same idea applies: set `SQLENS_HOME` in the
service's environment and make the folder readable by the Tomcat user.

To redeploy a new build, copy the WAR over the old one; Tomcat reloads it. The
browser picks up the new UI on the next page load, since SQLens tells it to
check for changed files every time.

## Using the UI

Open <http://localhost:8080/sqlens/>.

- Pick a datasource at the top. Its description and JDBC URL are shown next to
  it, and the editor's keyword highlighting follows the database type in the
  URL (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, SQLite, else standard
  SQL, which is what c-tree datasources get). The reload button re-reads the list after you edit `datasources.json`.
- **Ctrl+Enter** (⌘+Enter on a Mac) or **Run** runs the selected text. With
  nothing selected it runs the statement under the cursor: statements end at a
  `;` or a blank line, so a scratchpad of queries works without semicolons.
  Select a block explicitly to run something that contains blank lines or
  inner semicolons, such as a PL/SQL block.
- **Rows** limits the result: 10 (the default), 100, 1000, 10000 or All. The
  status bar says when more rows were available. All still stops at
  `maxGridRows` (50,000 by default) in the grid; use Export for more.
- **Cancel** stops waiting for a running query. The database may keep working
  on it until it finishes or hits the datasource's `queryTimeoutSeconds`.
- The sun/moon button switches between the Catppuccin Latte (light) and Mocha
  (dark) themes. The first visit follows the system's light or dark setting.
- Drag the bar between the editor and the results to resize them, and drag a
  column header's right edge to resize a column.

- **Copy** puts the rows in the grid on the clipboard as tab-separated text,
  with a header row, so they paste into Excel as cells.
- **Export CSV** downloads the result as a CSV file. It runs the query that
  filled the grid again, with the row limit currently picked under **Rows**.
  So you can look at 10 rows, switch to All and export everything. Big
  exports stream from the database, and **Cancel export** stops one.
- Shortcuts: **Alt+Shift+C** copies and **Alt+Shift+E** exports (⌥⇧C and
  ⌥⇧E on a Mac). They work anywhere on the page, including in the editor.
- The picker next to them sets the number style for Excel, used by both Copy
  and Export:
  - **Comma, 1.5**: fields separated by commas, decimal point. For Excel with
    US or UK regional settings.
  - **Semicolon, 1,5**: fields separated by semicolons, decimal comma. For
    Excel with most European regional settings (Swedish, German, French...).

  The first visit picks the one that matches the browser's language. If Excel
  puts a whole row in one column, switch to the other style.

The editor text, the chosen datasource, the theme, the editor height and the
number style are remembered in the browser's local storage.

### The CSV file

The CSV is made to open in Excel with a double-click:

- UTF-8 with a byte order mark, so Excel reads accented letters correctly.
- CRLF line ends. Fields holding the separator, a quote, a line break or
  leading or trailing spaces are quoted, with quotes doubled.
- A header row with the column names. NULL is an empty field.
- Numbers are written in full, never in exponent form, and decimals keep the
  scale the database returned (`12.50`). Dates are `2024-05-01` and timestamps
  `2024-05-01 13:45:00`, which Excel recognises. Booleans are `TRUE`/`FALSE`.

Excel still applies its own guesses when it opens a CSV: text such as
`00123` loses its leading zeros, and numbers longer than 15 digits lose
precision. Use Excel's *Data → From Text/CSV* import to choose column types
when that matters.

The editor font is JetBrains Mono, bundled in the WAR. If the browser can't
load it, it falls back to Cascadia Code, Fira Code, Source Code Pro, SF Mono,
Menlo, Consolas, DejaVu Sans Mono and finally the default monospace font.

## API

All endpoints return JSON. The UI and export features build on these.

### `GET /api/datasources`

```json
{
  "datasources": [
    { "name": "chinook", "description": "c-treeRTG on localhost, database chinook",
      "url": "jdbc:ctree:6597@localhost:chinook", "user": "ADMIN", "readOnly": false }
  ],
  "configFile": "/home/me/.sqlens/datasources.json",
  "driversDir": "/home/me/.sqlens/drivers",
  "drivers": ["ctree.jdbc.ctreeDriver"],
  "driverJarErrors": [],
  "maxGridRows": 50000,
  "error": null
}
```

Passwords are never returned. `error` explains a missing or broken config file, and
`driverJarErrors` lists driver jars Java could not open.

### `POST /api/query`

Request:

```json
{ "datasource": "chinook", "sql": "select * from artist", "maxRows": 5 }
```

`maxRows` is the row limit: any positive number (the UI offers 10, 100, 1000
and 10000), or `0` / `"all"` for no limit. It defaults to 10. Either way it is
lowered to `maxGridRows` when that is set. One statement is
run per request; a trailing `;` is removed (but kept after `END`, for PL/SQL
blocks).

Response for a query:

```json
{
  "datasource": "chinook",
  "columns": [
    { "name": "artistid", "jdbcType": "INTEGER", "dbType": "integer", "numeric": true },
    { "name": "name", "jdbcType": "VARCHAR", "dbType": "varchar", "numeric": false }
  ],
  "rows": [[1, "AC/DC"], [2, "Accept"], [3, "Aerosmith"], [4, "Alanis Morissette"], [5, "Alice In Chains"]],
  "rowCount": 5,
  "truncated": true,
  "cappedAt": null,
  "updateCount": null,
  "elapsedMs": 12,
  "error": null
}
```

- `truncated` is true when there were more rows than `maxRows`.
- `cappedAt` is set to `maxGridRows` when that cap, rather than the requested
  `maxRows`, cut the result short.
- Values are `null`, booleans, numbers or strings. Integers too large for a
  JavaScript number, and all `DECIMAL`/`NUMERIC` values, come back as
  strings, so `12.50` keeps its trailing zero. Dates look like `2024-05-01` and timestamps like
  `2024-05-01 13:45:00`. Binary values are hex (`0xCAFE`), and large text and
  binary values are cut at 64K characters.
- Rows are streamed as they are read, so `"all"` on a big table does not need
  to fit in server memory.

For `insert`, `update`, DDL and similar, `columns` and `rows` are empty and
`updateCount` holds the affected row count. Statements run with auto-commit
on, so changes are committed immediately.

Errors before any row is sent return HTTP 400 (404 for an unknown datasource):

```json
{ "error": "<the driver's message>", "sqlState": "42S22", "errorCode": 0 }
```

If a failure happens while rows are streaming, the response is still HTTP 200,
the rows received so far are kept, and `error` holds the message.

### `POST /api/export`

Runs one statement like `/api/query` and returns the result as a file
download (`Content-Disposition: attachment`).

```json
{ "datasource": "chinook", "sql": "select * from track", "maxRows": "all",
  "format": "csv", "delimiter": ";", "decimalSeparator": "," }
```

`datasource`, `sql` and `maxRows` work as for `/api/query`. `format` is `csv`
(the only format so far), `delimiter` is any single character or `tab`, and
`decimalSeparator` is `.` or `,`. Those three are optional and default to CSV
with a comma and a decimal point.

Errors before the file starts return the same JSON errors as `/api/query`,
including for a statement that returns no rows. Note that such a statement
has already run by then. If the query fails after rows were sent, the
response is cut off so the download fails instead of saving part of the
result.

## Development

```sh
mvn test
```

Tests run against H2 loaded from a temporary drivers folder, the same way
Tomcat loads a dropped-in driver.

### Layout

- `sqlens.config`: home folder lookup and `datasources.json` loading
- `sqlens.jdbc`: driver loading (`DriverRegistry`), query execution
  (`QueryExecutor`) and value conversion. Results are pushed to a
  `ResultHandler`, so the JSON API and exports stream the same result.
- `sqlens.export`: export formats. An `ExportFormat` turns a result into a
  file by returning a `ResultHandler`; `CsvFormat` is the only one so far. To
  add a format, implement `ExportFormat` and register it in `ExportFormats`,
  then offer it in `js/export.js`.
- `sqlens.web`: servlets for the JSON API and export
- `src/main/webapp`: the web UI, plain ES modules with no build step:
  - `js/app.js`: toolbar, running queries, status bar
  - `js/editor.js`: the CodeMirror editor, its theme and syntax colours
  - `js/statement.js`: finds the statement under the cursor
  - `js/grid.js`: the result grid; only the rows in view are in the DOM
  - `js/results.js`: the latest request and response, with an `onResult`
    listener, for features that act on the result
  - `js/export.js`: Copy, Export CSV and the number style picker
  - `css/sqlens.css`: layout and the Catppuccin Latte and Mocha palettes
  - `vendor/`: generated, see below

Buttons that act on the current result belong in `#result-actions` in the
status bar, which stays hidden while empty.

### Vendored frontend libraries

The UI loads nothing from the internet. CodeMirror is bundled into
`src/main/webapp/vendor/codemirror.js` and the JetBrains Mono font is copied
next to it; both are committed, so `mvn package` needs no Node.js. To update
them, change the versions in `frontend/package.json`, then:

```sh
cd frontend
npm install
npm run build
```

and commit the changed files in `src/main/webapp/vendor`. To use another
CodeMirror export in the UI, add it to `frontend/codemirror-entry.js` first.
