package sqlens.eclipse.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The reply of {@code POST /api/query}. Cells are null, Boolean, String or
 * BigDecimal; the server sends big integers and decimals as strings.
 */
public record QueryResult(String datasource, List<Column> columns, List<List<Object>> rows, int rowCount,
                          boolean truncated, Integer cappedAt, Long updateCount, long elapsedMs, String error) {

    public record Column(String name, String jdbcType, String dbType, boolean numeric) {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static QueryResult parse(String json) {
        Map<String, Object> m = Json.object(Json.parse(json));
        List<Column> columns = Json.array(m.get("columns")).stream()
                .map(o -> {
                    Map<String, Object> c = Json.object(o);
                    return new Column(Json.string(c, "name"), Json.string(c, "jdbcType"), Json.string(c, "dbType"),
                            Json.bool(c, "numeric"));
                })
                .toList();
        List<List<Object>> rows = (List) Json.array(m.get("rows"));
        return new QueryResult(Json.string(m, "datasource"), columns, rows,
                (int) Json.number(m, "rowCount", rows.size()), Json.bool(m, "truncated"),
                m.get("cappedAt") instanceof BigDecimal n ? n.intValue() : null,
                m.get("updateCount") instanceof BigDecimal n ? n.longValue() : null,
                Json.number(m, "elapsedMs", 0), Json.string(m, "error"));
    }

    /** How a cell is shown in the grid. */
    public static String display(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof BigDecimal n) {
            return plain(n);
        }
        return value.toString();
    }

    /** Digits only, never 1E+21 style, and 1.0 as 1 like the web UI. */
    static String plain(BigDecimal n) {
        BigDecimal stripped = n.signum() == 0 ? BigDecimal.ZERO : n.stripTrailingZeros();
        return stripped.toPlainString();
    }
}
