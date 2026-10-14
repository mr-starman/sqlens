package sqlens.jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Safety net for datasources marked {@code readOnly}: rejects anything that is
 * not a plain read before it reaches the driver, since many drivers ignore
 * {@link java.sql.Connection#setReadOnly}.
 *
 * <p>This is a keyword check, not a SQL parser. It allows one statement that
 * starts with a read keyword and contains no write keyword outside strings,
 * quoted names and comments. It cannot see what a called function or
 * procedure does ({@code select my_func()}), so a read-only database user is
 * still the only real guarantee.
 */
public final class ReadOnlyGuard {

    /** SQLSTATE for "read-only SQL transaction". */
    public static final String SQL_STATE = "25006";

    private static final Set<String> READ_STARTS = Set.of(
            "SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES");

    /**
     * Words that turn an otherwise read-looking statement into a write, such as a
     * data-modifying CTE ({@code with x as (delete ...)}), {@code select ... into},
     * {@code explain analyze delete ...} or {@code select ... for update}.
     */
    private static final Set<String> WRITE_WORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "INTO",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "GRANT", "REVOKE",
            "CALL", "EXEC", "EXECUTE", "COMMIT", "ROLLBACK");

    private ReadOnlyGuard() {
    }

    /** Throws when {@code sql} is not allowed on a read-only datasource. */
    public static void check(String datasource, String sql) throws SQLException {
        String problem = problem(sql);
        if (problem != null) {
            throw new SQLException("Datasource \"" + datasource + "\" is read-only: " + problem, SQL_STATE);
        }
    }

    /** Why {@code sql} is not a plain read, or null when it is. */
    static String problem(String sql) {
        Scan scan = scan(sql);
        if (scan.words.isEmpty()) {
            return "the statement is empty.";
        }
        if (scan.statements > 1) {
            return "run one statement at a time.";
        }
        String first = scan.words.get(0);
        if (!READ_STARTS.contains(first)) {
            return first + " statements are not allowed, only SELECT, WITH, SHOW, DESCRIBE, EXPLAIN and VALUES.";
        }
        for (String word : scan.words) {
            if (WRITE_WORDS.contains(word)) {
                return "the statement contains " + word + ".";
            }
        }
        return null;
    }

    private record Scan(List<String> words, int statements) {
    }

    /**
     * Collects the upper-cased bare words outside strings, quoted identifiers and
     * comments, and counts the statements separated by semicolons. Where dialects
     * differ (backslash escapes, {@code #} comments, dollar quotes) it reads the
     * text the plain way, so a doubt shows up as more words, which only ever
     * makes it stricter.
     */
    private static Scan scan(String sql) {
        List<String> words = new ArrayList<>();
        int statements = 0;
        boolean wordsSinceSemicolon = false;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i = skipTo(sql, i + 2, "\n");
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = skipTo(sql, i + 2, "*/");
            } else if (c == '\'') {
                i = skipQuoted(sql, i + 1, '\'');
            } else if (c == '"') {
                i = skipQuoted(sql, i + 1, '"');
            } else if (c == '`') {
                i = skipQuoted(sql, i + 1, '`');
            } else if (c == '[') {
                i = skipQuoted(sql, i + 1, ']');
            } else if (c == ';') {
                if (wordsSinceSemicolon) {
                    statements++;
                    wordsSinceSemicolon = false;
                }
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }
                words.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
                wordsSinceSemicolon = true;
            } else {
                if (!Character.isWhitespace(c)) {
                    wordsSinceSemicolon = true;
                }
                i++;
            }
        }
        if (wordsSinceSemicolon) {
            statements++;
        }
        return new Scan(words, statements);
    }

    /** The end of {@code end} searched from {@code from}, or the end of the text. */
    private static int skipTo(String sql, int from, String end) {
        int at = sql.indexOf(end, from);
        return at < 0 ? sql.length() : at + end.length();
    }

    /** Skips a quoted run; a doubled quote character is an escaped quote. */
    private static int skipQuoted(String sql, int from, char quote) {
        int i = from;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }
}
