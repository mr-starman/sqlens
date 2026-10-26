package sqlens.eclipse.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import sqlens.eclipse.api.QueryResult.Column;

/** Tab-separated text for pasting into Excel, the same as the web UI's Copy. */
public final class Tsv {

    private static final Pattern DECIMAL_STRING = Pattern.compile("-?\\d+\\.\\d+");

    private Tsv() {
    }

    /** A header row and the rows, with CRLF line ends. Quoting follows what Excel's paste understands. */
    public static String of(List<Column> columns, List<List<Object>> rows, NumberStyle style) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append('\t');
            }
            sb.append(field(columns.get(i).name()));
        }
        sb.append("\r\n");
        for (List<Object> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append('\t');
                }
                sb.append(value(row.get(i), i < columns.size() && columns.get(i).numeric(), style.decimalSeparator));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static String value(Object value, boolean numeric, String decimalSeparator) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof BigDecimal n) {
            return QueryResult.plain(n).replace(".", decimalSeparator);
        }
        String s = value.toString();
        if (numeric && !decimalSeparator.equals(".") && DECIMAL_STRING.matcher(s).matches()) {
            return s.replace(".", decimalSeparator);
        }
        return field(s);
    }

    private static String field(String s) {
        boolean quote = false;
        for (int i = 0; i < s.length() && !quote; i++) {
            char c = s.charAt(i);
            quote = c == '\t' || c == '\r' || c == '\n' || c == '"';
        }
        return quote ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
