package sqlens.export;

import org.junit.jupiter.api.Test;
import sqlens.jdbc.ColumnInfo;
import sqlens.jdbc.ResultHandler;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvWriterTest {

    private static final List<ColumnInfo> COLUMNS = List.of(
            new ColumnInfo("id", "INTEGER", "int", true),
            new ColumnInfo("name", "VARCHAR", "varchar", false));

    @Test
    void startsWithBomAndUsesCrlf() throws Exception {
        byte[] bytes = export(ExportOptions.DEFAULT, new Object[] {1, "Ada"});
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, java.util.Arrays.copyOf(bytes, 3));
        assertEquals("﻿id,name\r\n1,Ada\r\n", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void quotesOnlyWhenNeeded() throws Exception {
        String csv = text(ExportOptions.DEFAULT,
                new Object[] {1, "a,b"},
                new Object[] {2, "say \"hi\""},
                new Object[] {3, "two\nlines"},
                new Object[] {4, " padded "},
                new Object[] {5, "plain; text"},
                new Object[] {null, ""});
        assertEquals("id,name\r\n1,\"a,b\"\r\n2,\"say \"\"hi\"\"\"\r\n3,\"two\nlines\"\r\n4,\" padded \"\r\n"
                + "5,plain; text\r\n,\r\n", csv);
    }

    @Test
    void semicolonAndDecimalComma() throws Exception {
        String csv = text(ExportOptions.EUROPEAN,
                new Object[] {new BigDecimal("12.50"), "a;b"},
                new Object[] {1.5, "1.5 as text"});
        assertEquals("id;name\r\n12,50;\"a;b\"\r\n1,5;1.5 as text\r\n", csv);
    }

    @Test
    void writesNumbersInFull() {
        assertEquals("12.50", CsvWriter.formatNumber(new BigDecimal("12.50"), '.'));
        assertEquals("1000", CsvWriter.formatNumber(new BigDecimal("1E+3"), '.'));
        assertEquals("100000000000000000000", CsvWriter.formatNumber(1e20, '.'));
        assertEquals("0.00000015", CsvWriter.formatNumber(1.5e-7, '.'));
        assertEquals("3", CsvWriter.formatNumber(3.0, '.'));
        assertEquals("0.1", CsvWriter.formatNumber(0.1f, '.'));
        assertEquals("NaN", CsvWriter.formatNumber(Double.NaN, ','));
        assertEquals("123456789012345678901234", CsvWriter.formatNumber(new BigInteger("123456789012345678901234"), '.'));
        assertEquals("-0,25", CsvWriter.formatNumber(new BigDecimal("-0.25"), ','));
    }

    @Test
    void booleansAsExcelWords() throws Exception {
        assertEquals("id,name\r\nTRUE,FALSE\r\n", text(ExportOptions.DEFAULT, new Object[] {true, false}));
    }

    @Test
    void tabDelimited() throws Exception {
        assertEquals("id\tname\r\n1\ta,b\r\n", text(new ExportOptions('\t', '.'), new Object[] {1, "a,b"}));
    }

    @Test
    void rejectsClashingOptions() {
        assertThrows(IllegalArgumentException.class, () -> new ExportOptions(',', ','));
        assertThrows(IllegalArgumentException.class, () -> new ExportOptions('"', '.'));
    }

    private static String text(ExportOptions options, Object[]... rows) throws Exception {
        return new String(export(options, rows), StandardCharsets.UTF_8).substring(1);
    }

    private static byte[] export(ExportOptions options, Object[]... rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ResultHandler writer = new CsvFormat().writer(out, options);
        writer.columns(COLUMNS);
        for (Object[] row : rows) {
            writer.row(row);
        }
        writer.end(rows.length, false);
        return out.toByteArray();
    }
}
