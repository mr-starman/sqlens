package sqlens.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import sqlens.jdbc.QueryExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryRequestTest {

    @Test
    void parsesRowLimits() throws Exception {
        assertEquals(10, parse("{\"datasource\":\"a\",\"sql\":\"select 1\"}").maxRows());
        assertEquals(1000, parse("{\"datasource\":\"a\",\"sql\":\"select 1\",\"maxRows\":1000}").maxRows());
        assertEquals(100, parse("{\"datasource\":\"a\",\"sql\":\"select 1\",\"maxRows\":\"100\"}").maxRows());
        assertEquals(QueryExecutor.ALL_ROWS, parse("{\"datasource\":\"a\",\"sql\":\"select 1\",\"maxRows\":\"All\"}").maxRows());
        assertEquals(QueryExecutor.ALL_ROWS, parse("{\"datasource\":\"a\",\"sql\":\"select 1\",\"maxRows\":0}").maxRows());
        assertThrows(IllegalArgumentException.class, () -> parse("{\"datasource\":\"a\",\"sql\":\"select 1\",\"maxRows\":-1}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"datasource\":\"a\",\"sql\":\" \"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"sql\":\"select 1\"}"));
    }

    @Test
    void stripsTrailingSemicolonsButKeepsPlsqlEnd() {
        assertEquals("select 1", QueryRequest.cleanSql("  select 1 ;; \n"));
        assertEquals("begin null; end;", QueryRequest.cleanSql("begin null; end;"));
        assertEquals("select 'a;b' from dual", QueryRequest.cleanSql("select 'a;b' from dual;"));
        assertEquals("select weekend from t", QueryRequest.cleanSql("select weekend from t;"));
    }

    private static QueryRequest parse(String json) throws Exception {
        JsonNode node = Json.MAPPER.readTree(json);
        return QueryRequest.parse(node);
    }
}
