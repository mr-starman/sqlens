# SQLens

A small SQL query tool for development boxes. A Java webapp that runs on Tomcat
and can query any database that has a JDBC driver.

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

```json
{
  "datasources": [
    {
      "name": "local-postgres",
      "description": "App database on the devbox",
      "url": "jdbc:postgresql://localhost:5432/app",
      "user": "app",
      "password": "${PGPASSWORD}"
    }
  ]
}
```

| Field                 | Required | Meaning |
|-----------------------|----------|---------|
| `name`                | yes      | Unique name shown in the UI |
| `url`                 | yes      | JDBC URL |
| `description`         | no       | Free text shown next to the name |
| `user`, `password`    | no       | Credentials |
| `driver`              | no       | Driver class name. Usually not needed: the driver is picked from the URL |
| `properties`          | no       | Extra JDBC connection properties, as string pairs |
| `readOnly`            | no       | `true` opens the connection in read-only mode (support depends on the driver) |
| `queryTimeoutSeconds` | no       | Statement timeout; absent or `0` means none |

`${NAME}` in `url`, `user`, `password` and `properties` is replaced with the
environment variable or Java system property of that name, so secrets can stay
out of the file.

The file is re-read whenever it changes, so there is no need to restart Tomcat
after editing it. If it is missing or invalid, the API reports why in the
`error` field.

### JDBC drivers

Drop driver jars into `~/.sqlens/drivers/`. Jars added, removed or replaced
there are picked up on the next query, without a restart. A driver jar can
also go into Tomcat's own `lib/` folder; SQLens falls back to drivers it finds
there.

Where to get them (download the jar from Maven Central):

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

## Deploy on Tomcat in WSL

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
# put driver jars in ~/.sqlens/drivers

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

If you use the distro's `tomcat9` package instead, it runs as the `tomcat`
user, so the default home becomes that user's home rather than yours. Set
`SQLENS_HOME` in the service's environment (for example
`/etc/default/tomcat9`) to a folder the `tomcat` user can read. That package
runs as a systemd service, which WSL only supports when systemd is turned on,
so the tarball setup above is usually easier.

To redeploy a new build, copy the WAR over the old one; Tomcat reloads it. The
browser picks up the new UI on the next page load, since SQLens tells it to
check for changed files every time.

## Using the UI

Open <http://localhost:8080/sqlens/>.

- Pick a datasource at the top. Its description and JDBC URL are shown next to
  it, and the editor's keyword highlighting follows the database type in the
  URL (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, SQLite, else standard
  SQL). The reload button re-reads the list after you edit `datasources.json`.
- **Ctrl+Enter** (⌘+Enter on a Mac) or **Run** runs the selected text. With
  nothing selected it runs the statement under the cursor: statements end at a
  `;` or a blank line, so a scratchpad of queries works without semicolons.
  Select a block explicitly to run something that contains blank lines or
  inner semicolons, such as a PL/SQL block.
- **Rows** limits the result: 10 (the default), 100, 1000, 10000 or All. The
  status bar says when more rows were available.
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
    { "name": "local-postgres", "description": "App database on the devbox",
      "url": "jdbc:postgresql://localhost:5432/app", "user": "app", "readOnly": false }
  ],
  "configFile": "/home/me/.sqlens/datasources.json",
  "driversDir": "/home/me/.sqlens/drivers",
  "drivers": ["org.postgresql.Driver"],
  "driverJarErrors": [],
  "error": null
}
```

Passwords are never returned. `error` explains a missing or broken config file, and
`driverJarErrors` lists driver jars Java could not open.

### `POST /api/query`

Request:

```json
{ "datasource": "local-postgres", "sql": "select * from customer", "maxRows": 10 }
```

`maxRows` is the row limit: any positive number (the UI offers 10, 100, 1000
and 10000), or `0` / `"all"` for no limit. It defaults to 10. One statement is
run per request; a trailing `;` is removed (but kept after `END`, for PL/SQL
blocks).

Response for a query:

```json
{
  "datasource": "local-postgres",
  "columns": [
    { "name": "id", "jdbcType": "INTEGER", "dbType": "int4", "numeric": true },
    { "name": "name", "jdbcType": "VARCHAR", "dbType": "varchar", "numeric": false }
  ],
  "rows": [[1, "Ada"], [2, "Linus"]],
  "rowCount": 2,
  "truncated": true,
  "updateCount": null,
  "elapsedMs": 12,
  "error": null
}
```

- `truncated` is true when there were more rows than `maxRows`.
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
{ "error": "Column \"NOPE\" not found ...", "sqlState": "42S22", "errorCode": 42122 }
```

If a failure happens while rows are streaming, the response is still HTTP 200,
the rows received so far are kept, and `error` holds the message.

### `POST /api/export`

Runs one statement like `/api/query` and returns the result as a file
download (`Content-Disposition: attachment`).

```json
{ "datasource": "local-postgres", "sql": "select * from customer", "maxRows": "all",
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
