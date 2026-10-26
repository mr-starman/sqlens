package sqlens.eclipse.api;

import static org.junit.Assert.assertEquals;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import sqlens.eclipse.api.QueryResult.Column;

public class TsvTest {

    private static final List<Column> COLUMNS = List.of(
            new Column("id", "INTEGER", "int", true),
            new Column("price", "DECIMAL", "decimal", true),
            new Column("note", "VARCHAR", "text", false));

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    @Test
    public void writesHeadersAndRowsWithCrLf() {
        String tsv = Tsv.of(COLUMNS, List.of(row(new BigDecimal(1), "9.50", "plain")), NumberStyle.POINT);
        assertEquals("id\tprice\tnote\r\n1\t9.50\tplain\r\n", tsv);
    }

    @Test
    public void quotesTabsNewlinesAndQuotes() {
        String tsv = Tsv.of(COLUMNS, List.of(row(null, null, "a\tb\n\"c\"")), NumberStyle.POINT);
        assertEquals("id\tprice\tnote\r\n\t\t\"a\tb\n\"\"c\"\"\"\r\n", tsv);
    }

    @Test
    public void decimalCommaForNumbersAndDecimalStrings() {
        String tsv = Tsv.of(COLUMNS, List.of(row(new BigDecimal("1.5"), "9.50", "1.5"), row(true, "7", false)),
                NumberStyle.COMMA);
        assertEquals("id\tprice\tnote\r\n1,5\t9,50\t1.5\r\nTRUE\t7\tFALSE\r\n", tsv);
    }

    @Test
    public void numbersAreNeverInExponentForm() {
        String tsv = Tsv.of(COLUMNS, List.of(row(new BigDecimal("1E+3"), new BigDecimal("2.50"), "")), NumberStyle.POINT);
        assertEquals("id\tprice\tnote\r\n1000\t2.5\t\r\n", tsv);
    }
}
