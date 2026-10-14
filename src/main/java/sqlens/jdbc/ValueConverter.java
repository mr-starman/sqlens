package sqlens.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.HexFormat;

/**
 * Turns JDBC values into plain Java values: {@code null}, {@link Boolean},
 * {@link Number} or {@link String}. Dates use ISO order with a space before the
 * time ({@code 2024-05-01 13:45:00}), which Excel recognises.
 */
public final class ValueConverter {

    /** Large text and binary values are cut to this many characters. */
    public static final int MAX_LOB_CHARS = 64 * 1024;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSSSSS]");

    private ValueConverter() {
    }

    public static Object read(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null || rs.wasNull()) {
            return null;
        }
        return convert(value);
    }

    static Object convert(Object value) throws SQLException {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Timestamp ts) {
            return formatDateTime(ts.toLocalDateTime());
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof Time t) {
            return t.toLocalTime().toString();
        }
        if (value instanceof LocalDateTime ldt) {
            return formatDateTime(ldt);
        }
        if (value instanceof TemporalAccessor) {
            return value.toString().replace('T', ' ');
        }
        if (value instanceof java.util.Date d) {
            return formatDateTime(new Timestamp(d.getTime()).toLocalDateTime());
        }
        if (value instanceof byte[] bytes) {
            return hex(bytes);
        }
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                return readChars(reader);
            } catch (IOException e) {
                throw new SQLException(e);
            } finally {
                freeQuietly(clob);
            }
        }
        if (value instanceof Blob blob) {
            try (InputStream in = blob.getBinaryStream()) {
                return hex(in.readNBytes(MAX_LOB_CHARS / 2));
            } catch (IOException e) {
                throw new SQLException(e);
            } finally {
                freeQuietly(blob);
            }
        }
        if (value instanceof SQLXML xml) {
            return xml.getString();
        }
        if (value instanceof Array array) {
            Object[] items = (Object[]) array.getArray();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Object item = items[i] == null ? null : convert(items[i]);
                sb.append(item);
            }
            return sb.append(']').toString();
        }
        if (value instanceof char[] chars) {
            return new String(chars);
        }
        return value.toString();
    }

    private static String formatDateTime(LocalDateTime ldt) {
        String s = DATE_TIME.format(ldt);
        int dot = s.indexOf('.');
        if (dot < 0) {
            return s;
        }
        // Drop trailing zeros from the fraction, and the dot when nothing is left.
        int end = s.length();
        while (end > dot + 1 && s.charAt(end - 1) == '0') {
            end--;
        }
        return end == dot + 1 ? s.substring(0, dot) : s.substring(0, end);
    }

    private static String hex(byte[] bytes) {
        return "0x" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static String readChars(Reader reader) throws IOException {
        char[] buffer = new char[MAX_LOB_CHARS];
        int total = 0;
        int n;
        while (total < buffer.length && (n = reader.read(buffer, total, buffer.length - total)) > 0) {
            total += n;
        }
        return new String(buffer, 0, total);
    }

    private static void freeQuietly(Clob clob) {
        try {
            clob.free();
        } catch (SQLException | AbstractMethodError ignored) {
            // some drivers do not implement free()
        }
    }

    private static void freeQuietly(Blob blob) {
        try {
            blob.free();
        } catch (SQLException | AbstractMethodError ignored) {
            // some drivers do not implement free()
        }
    }
}
