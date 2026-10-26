package sqlens.eclipse.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class QueryResultTest {

    @Test
    public void parsesAQueryReply() {
        QueryResult r = QueryResult.parse("""
                {"datasource":"chinook","columns":[{"name":"id","jdbcType":"INTEGER","dbType":"int","numeric":true},
                 {"name":"name","jdbcType":"VARCHAR","dbType":null,"numeric":false}],
                 "rows":[[1,"A\\u00e5\\n"],[2.5,null]],"rowCount":2,"truncated":true,"cappedAt":1000,
                 "updateCount":null,"elapsedMs":12,"error":null}""");
        assertEquals("chinook", r.datasource());
        assertEquals(2, r.columns().size());
        assertTrue(r.columns().get(0).numeric());
        assertNull(r.columns().get(1).dbType());
        assertEquals(Arrays.asList(new BigDecimal("2.5"), null), r.rows().get(1));
        assertEquals("Aå\n", r.rows().get(0).get(1));
        assertTrue(r.truncated());
        assertEquals(Integer.valueOf(1000), r.cappedAt());
        assertNull(r.updateCount());
        assertEquals(12, r.elapsedMs());
    }

    @Test
    public void parsesAnUpdateCount() {
        QueryResult r = QueryResult.parse("{\"datasource\":\"d\",\"columns\":[],\"rows\":[],\"rowCount\":0,"
                + "\"truncated\":false,\"updateCount\":3,\"elapsedMs\":1}");
        assertTrue(r.columns().isEmpty());
        assertEquals(Long.valueOf(3), r.updateCount());
        assertFalse(r.truncated());
    }

    @Test
    public void parsesDatasources() {
        Datasource.Listing l = Datasource.Listing.parse("""
                {"datasources":[{"name":"a","description":null,"url":"jdbc:x","user":"u","readOnly":true}],
                 "maxGridRows":50000,"error":null}""");
        assertEquals(List.of(new Datasource("a", null, "jdbc:x", "u", true)), l.datasources());
        assertEquals(50000, l.maxGridRows());
        assertNull(l.error());
    }

    @Test
    public void displaysCells() {
        assertEquals("NULL", QueryResult.display(null));
        assertEquals("1", QueryResult.display(new BigDecimal("1.0")));
        assertEquals("1000", QueryResult.display(new BigDecimal("1E+3")));
        assertEquals("0", QueryResult.display(new BigDecimal("0.00")));
        assertEquals("true", QueryResult.display(Boolean.TRUE));
    }

    @Test
    public void rejectsBrokenJson() {
        assertThrows(IllegalArgumentException.class, () -> QueryResult.parse("<html>Tomcat</html>"));
        assertThrows(IllegalArgumentException.class, () -> QueryResult.parse("{\"a\":"));
    }

    @Test
    public void buildsRequestBodies() {
        SqlensClient.Query q = new SqlensClient.Query("db", "select \"x\"\n", 10);
        assertEquals("{\"datasource\":\"db\",\"sql\":\"select \\\"x\\\"\\n\",\"maxRows\":10}",
                SqlensClient.queryJson(q, null));
        assertEquals("{\"datasource\":\"db\",\"sql\":\"select \\\"x\\\"\\n\",\"maxRows\":10,"
                + "\"format\":\"csv\",\"delimiter\":\";\",\"decimalSeparator\":\",\"}",
                SqlensClient.queryJson(q, NumberStyle.COMMA));
    }
}
