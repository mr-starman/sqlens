package sqlens.jdbc;

import sqlens.config.DataSourceConfig;

import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one SQL statement on a fresh connection and feeds the first result to a
 * {@link ResultHandler}. Connections are not pooled: every run opens and closes
 * its own, which keeps things simple for a dev tool.
 */
public final class QueryExecutor {

    /** Row limit meaning "no limit". */
    public static final int ALL_ROWS = 0;

    private final DriverRegistry drivers;

    public QueryExecutor(DriverRegistry drivers) {
        this.drivers = drivers;
    }

    /**
     * @param maxRows rows to return, or {@link #ALL_ROWS}
     */
    public void execute(DataSourceConfig ds, String sql, int maxRows, ResultHandler handler) throws Exception {
        if (ds.readOnly()) {
            ReadOnlyGuard.check(ds.name(), sql);
        }
        try (Connection connection = drivers.connect(ds);
             Statement statement = connection.createStatement()) {
            if (ds.queryTimeoutSeconds() > 0) {
                statement.setQueryTimeout(ds.queryTimeoutSeconds());
            }
            if (maxRows > 0) {
                // One extra row tells us whether the result was cut short.
                statement.setMaxRows(maxRows == Integer.MAX_VALUE ? maxRows : maxRows + 1);
            }
            statement.setFetchSize(maxRows > 0 && maxRows < 1000 ? maxRows + 1 : 1000);

            boolean hasResultSet = statement.execute(sql);
            if (!hasResultSet) {
                handler.updateCount(Math.max(0, statement.getUpdateCount()));
                handler.end(0, false);
                return;
            }
            try (ResultSet rs = statement.getResultSet()) {
                readResultSet(rs, maxRows, handler);
            }
        }
    }

    private static void readResultSet(ResultSet rs, int maxRows, ResultHandler handler) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<ColumnInfo> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            int type = meta.getColumnType(i);
            columns.add(new ColumnInfo(meta.getColumnLabel(i), jdbcTypeName(type), meta.getColumnTypeName(i), isNumeric(type)));
        }
        handler.columns(columns);

        long rows = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (maxRows > 0 && rows >= maxRows) {
                truncated = true;
                break;
            }
            Object[] values = new Object[count];
            for (int i = 0; i < count; i++) {
                values[i] = ValueConverter.read(rs, i + 1);
            }
            handler.row(values);
            rows++;
        }
        handler.end(rows, truncated);
    }

    private static String jdbcTypeName(int type) {
        try {
            return JDBCType.valueOf(type).getName();
        } catch (IllegalArgumentException e) {
            return "OTHER";
        }
    }

    private static boolean isNumeric(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> true;
            default -> false;
        };
    }

    /** Error details worth showing to the user. */
    public static String describe(Throwable e) {
        if (e instanceof SQLException sql) {
            StringBuilder sb = new StringBuilder(String.valueOf(sql.getMessage()));
            SQLException next = sql.getNextException();
            if (next != null && next.getMessage() != null && !sb.toString().contains(next.getMessage())) {
                sb.append('\n').append(next.getMessage());
            }
            return sb.toString();
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
