package sqlens.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import sqlens.config.DataSourceConfig;
import sqlens.jdbc.ColumnInfo;
import sqlens.jdbc.QueryExecutor;
import sqlens.jdbc.ResultHandler;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code POST /api/query}: runs one statement and streams the result as JSON.
 *
 * <pre>
 * {"datasource": "...", "columns": [{"name", "jdbcType", "dbType", "numeric"}],
 *  "rows": [[...], ...], "rowCount": 10, "truncated": true,
 *  "updateCount": null, "elapsedMs": 12, "error": null}
 * </pre>
 *
 * Failures before any row is sent give HTTP 400 with {@code error}, {@code sqlState}
 * and {@code errorCode}. A failure while rows are streaming ends the rows and
 * sets {@code error} in the same response.
 */
@WebServlet("/api/query")
public class QueryServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(QueryServlet.class.getName());

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        App app = App.from(getServletContext());

        QueryRequest query;
        try {
            JsonNode body = Json.MAPPER.readTree(request.getInputStream());
            query = QueryRequest.parse(body);
        } catch (IOException | IllegalArgumentException e) {
            Json.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Bad request: " + e.getMessage());
            return;
        }

        Optional<DataSourceConfig> ds = app.config().find(query.datasource());
        if (ds.isEmpty()) {
            Json.sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown datasource: " + query.datasource());
            return;
        }

        JsonResultWriter writer = new JsonResultWriter(response, query.datasource());
        try {
            app.executor().execute(ds.get(), query.sql(), query.maxRows(), writer);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Query failed", e);
            if (writer.started) {
                writer.finishWithError(QueryExecutor.describe(e));
            } else {
                sendQueryError(response, e);
            }
        }
    }

    private static void sendQueryError(HttpServletResponse response, Exception e) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", QueryExecutor.describe(e));
        if (e instanceof SQLException sql) {
            body.put("sqlState", sql.getSQLState());
            body.put("errorCode", sql.getErrorCode());
        }
        Json.send(response, HttpServletResponse.SC_BAD_REQUEST, body);
    }

    /** Streams the result straight to the response as it is read. */
    private static final class JsonResultWriter implements ResultHandler {

        private final HttpServletResponse response;
        private final String datasource;
        private final long startNanos = System.nanoTime();
        private JsonGenerator gen;
        private boolean started;
        private boolean inRows;
        private boolean finished;
        private Long updateCount;

        JsonResultWriter(HttpServletResponse response, String datasource) {
            this.response = response;
            this.datasource = datasource;
        }

        private void start(List<ColumnInfo> columns) throws IOException {
            Json.prepare(response, HttpServletResponse.SC_OK);
            gen = Json.MAPPER.getFactory().createGenerator(response.getOutputStream());
            started = true;
            gen.writeStartObject();
            gen.writeStringField("datasource", datasource);
            gen.writeFieldName("columns");
            Json.MAPPER.writeValue(gen, columns);
            gen.writeArrayFieldStart("rows");
            inRows = true;
        }

        @Override
        public void columns(List<ColumnInfo> columns) throws IOException {
            start(columns);
        }

        @Override
        public void row(Object[] values) throws IOException {
            gen.writeStartArray();
            for (Object value : values) {
                Json.writeValue(gen, value);
            }
            gen.writeEndArray();
        }

        @Override
        public void updateCount(long count) throws IOException {
            start(List.of());
            closeRows();
            updateCount = count;
        }

        @Override
        public void end(long rowCount, boolean truncated) throws IOException {
            closeRows();
            gen.writeNumberField("rowCount", rowCount);
            gen.writeBooleanField("truncated", truncated);
            if (updateCount != null) {
                gen.writeNumberField("updateCount", updateCount);
            } else {
                gen.writeNullField("updateCount");
            }
            finish(null);
        }

        void finishWithError(String message) throws IOException {
            if (finished) {
                // The result was complete; the failure came while closing the connection.
                return;
            }
            closeRows();
            finish(message);
        }

        private void closeRows() throws IOException {
            if (inRows) {
                gen.writeEndArray();
                inRows = false;
            }
        }

        private void finish(String error) throws IOException {
            finished = true;
            gen.writeNumberField("elapsedMs", (System.nanoTime() - startNanos) / 1_000_000);
            gen.writeStringField("error", error);
            gen.writeEndObject();
            gen.close();
        }
    }
}
