package sqlens.web;

import org.junit.jupiter.api.Test;
import sqlens.jdbc.QueryExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryServletTest {

    @Test
    void capsGridRows() {
        assertEquals(10, QueryServlet.gridRows(10, 50_000));
        assertEquals(50_000, QueryServlet.gridRows(QueryExecutor.ALL_ROWS, 50_000));
        assertEquals(50_000, QueryServlet.gridRows(100_000, 50_000));
        assertEquals(QueryExecutor.ALL_ROWS, QueryServlet.gridRows(QueryExecutor.ALL_ROWS, 0));
        assertEquals(100_000, QueryServlet.gridRows(100_000, 0));
    }
}
