package sqlens.jdbc;

import java.util.List;

/**
 * Receives a query result as it is read, so callers can stream it (JSON for the
 * grid, CSV for export) without holding every row in memory.
 *
 * <p>For a statement that returns rows: {@link #columns} once, {@link #row} per
 * row, then {@link #end}. For an update: {@link #updateCount} then {@link #end}.
 */
public interface ResultHandler {

    void columns(List<ColumnInfo> columns) throws Exception;

    /** Values are already converted by {@link ValueConverter}. */
    void row(Object[] values) throws Exception;

    void updateCount(long count) throws Exception;

    /**
     * @param rowCount  rows passed to {@link #row}
     * @param truncated true when the row limit cut the result short
     */
    void end(long rowCount, boolean truncated) throws Exception;
}
