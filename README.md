# SQLens

A small SQL query tool for development boxes. A Java webapp that runs on Tomcat
and can query any database that has a JDBC driver.

This is the backend: datasource config, driver loading and a JSON API. The web
UI and CSV export come next.

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

To redeploy a new build, copy the WAR over the old one; Tomcat reloads it.

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
- Values are `null`, booleans, numbers or strings. Numbers JavaScript cannot
  hold exactly (very large integers, high-precision decimals) come back as
  strings. Dates look like `2024-05-01` and timestamps like
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
  `ResultHandler`, so other outputs such as CSV export can stream the same
  result.
- `sqlens.web`: servlets for the JSON API
- `src/main/webapp`: static web content
