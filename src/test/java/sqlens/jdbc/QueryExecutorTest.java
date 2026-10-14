package sqlens.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sqlens.config.DataSourceConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against H2 loaded from a drivers folder, through a class loader that
 * cannot see the test classpath, the way Tomcat sees a driver dropped in the folder.
 */
class QueryExecutorTest {

    @TempDir
    Path home;

    private DriverRegistry registry;
    private QueryExecutor executor;
    private DataSourceConfig ds;

    @BeforeEach
    void setUp() throws Exception {
        Path drivers = Files.createDirectories(home.resolve("drivers"));
        Path h2Jar = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.copy(h2Jar, drivers.resolve(h2Jar.getFileName()));

        registry = new DriverRegistry(drivers, ClassLoader.getPlatformClassLoader());
        executor = new QueryExecutor(registry);
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ds = new DataSourceConfig("h2", null, url, "sa", "", null, Map.of(), false, 0);

        run("create table t (id bigint primary key, name varchar(20), born date, seen timestamp, "
                + "amount decimal(10,2), data blob, notes clob, ok boolean)", 0);
        run("insert into t select x, 'name ' || x, date '2024-05-01', timestamp '2024-05-01 13:45:00', "
                + "x * 1.5, X'CAFE', 'long text', x % 2 = 0 from system_range(1, 25)", 0);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void loadsDriverFromFolder() {
        assertTrue(registry.driverNames().contains("org.h2.Driver"), registry.driverNames().toString());
    }

    @Test
    void reportsJarsThatCannotBeOpened() throws Exception {
        Files.writeString(registry.driversDir().resolve("broken.jar"), "not a zip");
        assertEquals(1, registry.jarProblems().size(), registry.jarProblems().toString());
        assertTrue(registry.jarProblems().get(0).startsWith("broken.jar"));
        assertTrue(registry.driverNames().contains("org.h2.Driver"));
        SQLException e = assertThrows(SQLException.class, () -> executor.execute(
                new DataSourceConfig("x", null, "jdbc:x:y", null, null, "com.example.Missing", Map.of(), false, 0),
                "select 1", 10, new Collected()));
        assertTrue(e.getMessage().contains("broken.jar"), e.getMessage());
    }

    @Test
    void reloadsWhenJarBecomesReadable() throws Exception {
        Path jar;
        try (var files = Files.list(registry.driversDir())) {
            jar = files.findFirst().orElseThrow();
        }
        registry.close();
        assertTrue(jar.toFile().setReadable(false, false));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(jar), "running as root, cannot make the jar unreadable");
            assertFalse(registry.driverNames().contains("org.h2.Driver"));
        } finally {
            assertTrue(jar.toFile().setReadable(true, false));
        }
        assertTrue(registry.driverNames().contains("org.h2.Driver"));
    }

    @Test
    void limitsRowsAndReportsTruncation() throws Exception {
        Collected r = run("select id, name from t order by id", 10);
        assertEquals(List.of("ID", "NAME"), r.columns.stream().map(ColumnInfo::name).toList());
        assertTrue(r.columns.get(0).numeric());
        assertFalse(r.columns.get(1).numeric());
        assertEquals(10, r.rows.size());
        assertEquals(10, r.rowCount);
        assertTrue(r.truncated);
    }

    @Test
    void exactLimitIsNotTruncated() throws Exception {
        Collected r = run("select id from t", 25);
        assertEquals(25, r.rows.size());
        assertFalse(r.truncated);
    }

    @Test
    void allRows() throws Exception {
        Collected r = run("select id from t", QueryExecutor.ALL_ROWS);
        assertEquals(25, r.rows.size());
        assertFalse(r.truncated);
    }

    @Test
    void convertsValues() throws Exception {
        Collected r = run("select id, name, born, seen, amount, data, notes, ok, cast(null as int) n from t where id = 2", 10);
        Object[] row = r.rows.get(0);
        assertEquals(2L, row[0]);
        assertEquals("name 2", row[1]);
        assertEquals("2024-05-01", row[2]);
        assertEquals("2024-05-01 13:45:00", row[3]);
        assertEquals(new java.math.BigDecimal("3.00"), row[4]);
        assertEquals("0xCAFE", row[5]);
        assertEquals("long text", row[6]);
        assertEquals(Boolean.TRUE, row[7]);
        assertNull(row[8]);
    }

    @Test
    void reportsUpdateCount() throws Exception {
        Collected r = run("update t set name = 'x' where id <= 3", 10);
        assertEquals(3L, r.updateCount);
        assertTrue(r.columns == null);
    }

    @Test
    void usesExplicitDriverClass() throws Exception {
        DataSourceConfig explicit = new DataSourceConfig("h2", null, ds.url(), "sa", "", "org.h2.Driver", Map.of(), true, 5);
        Collected r = new Collected();
        executor.execute(explicit, "select count(*) from t", 10, r);
        assertArrayEquals(new Object[]{25L}, r.rows.get(0));
    }

    @Test
    void readOnlyDatasourceRejectsWritesBeforeRunningThem() throws Exception {
        DataSourceConfig readOnly = new DataSourceConfig("ro", null, ds.url(), "sa", "", null, Map.of(), true, 0);
        SQLException e = assertThrows(SQLException.class,
                () -> executor.execute(readOnly, "delete from t", 10, new Collected()));
        assertEquals(ReadOnlyGuard.SQL_STATE, e.getSQLState());
        assertEquals(25L, run("select count(*) from t", 10).rows.get(0)[0]);

        Collected r = new Collected();
        executor.execute(readOnly, "select count(*) from t", 10, r);
        assertEquals(25L, r.rows.get(0)[0]);
    }

    @Test
    void sqlErrorsSurface() {
        SQLException e = assertThrows(SQLException.class, () -> run("select nope from t", 10));
        assertTrue(QueryExecutor.describe(e).contains("NOPE"), QueryExecutor.describe(e));
    }

    private Collected run(String sql, int maxRows) throws Exception {
        Collected r = new Collected();
        executor.execute(ds, sql, maxRows, r);
        return r;
    }

    private static final class Collected implements ResultHandler {
        List<ColumnInfo> columns;
        final List<Object[]> rows = new ArrayList<>();
        Long updateCount;
        long rowCount;
        boolean truncated;

        @Override
        public void columns(List<ColumnInfo> columns) {
            this.columns = columns;
        }

        @Override
        public void row(Object[] values) {
            rows.add(values);
        }

        @Override
        public void updateCount(long count) {
            updateCount = count;
        }

        @Override
        public void end(long rowCount, boolean truncated) {
            this.rowCount = rowCount;
            this.truncated = truncated;
        }
    }
}
