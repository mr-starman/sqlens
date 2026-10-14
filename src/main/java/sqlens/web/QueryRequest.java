package sqlens.web;

import com.fasterxml.jackson.databind.JsonNode;
import sqlens.jdbc.QueryExecutor;

import java.util.Locale;

/**
 * Body of {@code POST /api/query}:
 * {@code {"datasource": "name", "sql": "select ...", "maxRows": 10}}.
 * {@code maxRows} is a positive number, or {@code 0} / {@code "all"} for no limit,
 * and defaults to {@value #DEFAULT_MAX_ROWS}.
 */
record QueryRequest(String datasource, String sql, int maxRows) {

    static final int DEFAULT_MAX_ROWS = 10;

    static QueryRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        String datasource = text(body, "datasource");
        String sql = text(body, "sql");
        if (datasource == null || datasource.isBlank()) {
            throw new IllegalArgumentException("\"datasource\" is required");
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("\"sql\" is required");
        }
        return new QueryRequest(datasource, cleanSql(sql), maxRows(body.get("maxRows")));
    }

    private static int maxRows(JsonNode node) {
        if (node == null || node.isNull()) {
            return DEFAULT_MAX_ROWS;
        }
        if (node.isTextual()) {
            String s = node.asText().trim().toLowerCase(Locale.ROOT);
            if (s.equals("all") || s.isEmpty()) {
                return QueryExecutor.ALL_ROWS;
            }
            try {
                return checkRows(Long.parseLong(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"maxRows\" must be a number or \"all\"");
            }
        }
        if (node.isIntegralNumber()) {
            return checkRows(node.asLong());
        }
        throw new IllegalArgumentException("\"maxRows\" must be a number or \"all\"");
    }

    private static int checkRows(long rows) {
        if (rows < 0) {
            throw new IllegalArgumentException("\"maxRows\" cannot be negative");
        }
        return (int) Math.min(rows, Integer.MAX_VALUE);
    }

    /**
     * Trims the statement and drops a trailing semicolon, which many drivers
     * reject. A semicolon after END is kept, since PL/SQL blocks need it.
     */
    static String cleanSql(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            String rest = s.substring(0, s.length() - 1).stripTrailing();
            if (rest.toUpperCase(Locale.ROOT).matches("(?s).*\\bEND(\\s+\\w+)?")) {
                break;
            }
            s = rest;
        }
        return s;
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
