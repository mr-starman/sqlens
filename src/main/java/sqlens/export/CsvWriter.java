package sqlens.export;

import sqlens.jdbc.ColumnInfo;
import sqlens.jdbc.ResultHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes a result as CSV: a header row with the column names, then one line
 * per row. NULL is an empty field. Numbers are written in full, never in
 * exponent form, keeping the scale the database returned ({@code 12.50}), with
 * the configured decimal separator. Fields holding the delimiter, a quote, a
 * line break or leading/trailing spaces are quoted, with quotes doubled.
 */
final class CsvWriter implements ResultHandler {

    static final char BOM = '﻿';
    private static final String LINE_END = "\r\n";

    private final OutputStream out;
    private final ExportOptions options;
    private Writer writer;

    CsvWriter(OutputStream out, ExportOptions options) {
        this.out = out;
        this.options = options;
    }

    @Override
    public void columns(List<ColumnInfo> columns) throws IOException {
        writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
        writer.write(BOM);
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                writer.write(options.delimiter());
            }
            writeText(columns.get(i).name());
        }
        writer.write(LINE_END);
    }

    @Override
    public void row(Object[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                writer.write(options.delimiter());
            }
            Object value = values[i];
            if (value == null) {
                continue;
            }
            if (value instanceof Number n) {
                writeText(formatNumber(n, options.decimalSeparator()));
            } else if (value instanceof Boolean b) {
                writer.write(b ? "TRUE" : "FALSE");
            } else {
                writeText(value.toString());
            }
        }
        writer.write(LINE_END);
    }

    @Override
    public void updateCount(long count) throws IOException {
        throw new IOException("The statement did not return rows (" + count + " rows affected), so there is nothing to export.");
    }

    @Override
    public void end(long rowCount, boolean truncated) throws IOException {
        writer.flush();
    }

    private void writeText(String s) throws IOException {
        if (needsQuotes(s)) {
            writer.write('"');
            writer.write(s.replace("\"", "\"\""));
            writer.write('"');
        } else {
            writer.write(s);
        }
    }

    private boolean needsQuotes(String s) {
        if (s.isEmpty()) {
            return false;
        }
        if (s.charAt(0) == ' ' || s.charAt(s.length() - 1) == ' ') {
            return true;
        }
        char delimiter = options.delimiter();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == delimiter || c == '"' || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    static String formatNumber(Number n, char decimalSeparator) {
        String s;
        if (n instanceof BigDecimal bd) {
            s = bd.toPlainString();
        } else if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                return n.toString();
            }
            // Float.toString gives the shortest text that reads back as the same float.
            s = new BigDecimal(n.toString()).toPlainString();
            if (s.endsWith(".0")) {
                s = s.substring(0, s.length() - 2);
            }
        } else {
            s = n.toString();
        }
        return decimalSeparator == '.' ? s : s.replace('.', decimalSeparator);
    }
}
