package sqlens.web;

import com.fasterxml.jackson.databind.JsonNode;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import sqlens.config.DataSourceConfig;
import sqlens.export.ExportFormat;
import sqlens.export.ExportFormats;
import sqlens.export.ExportOptions;
import sqlens.jdbc.ColumnInfo;
import sqlens.jdbc.QueryExecutor;
import sqlens.jdbc.ResultHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code POST /api/export}: runs one statement like {@code /api/query} and
 * streams the result as a file download.
 *
 * <pre>
 * {"datasource": "...", "sql": "...", "maxRows": 10,
 *  "format": "csv", "delimiter": ",", "decimalSeparator": "."}
 * </pre>
 *
 * {@code format}, {@code delimiter} and {@code decimalSeparator} are optional
 * and default to CSV with a comma and a decimal point. Errors before the first
 * row give the same JSON error as {@code /api/query}. A failure after rows were
 * sent cuts the download off, so the browser reports it as failed rather than
 * saving a partial file.
 */
@WebServlet("/api/export")
public class ExportServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ExportServlet.class.getName());
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        App app = App.from(getServletContext());

        QueryRequest query;
        ExportFormat format;
        ExportOptions options;
        try {
            JsonNode body = Json.MAPPER.readTree(request.getInputStream());
            query = QueryRequest.parse(body);
            String formatId = text(body, "format", "csv");
            format = ExportFormats.find(formatId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown format: " + formatId));
            options = new ExportOptions(
                    character(body, "delimiter", ExportOptions.DEFAULT.delimiter()),
                    character(body, "decimalSeparator", ExportOptions.DEFAULT.decimalSeparator()));
        } catch (IOException | IllegalArgumentException e) {
            Json.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Bad request: " + e.getMessage());
            return;
        }

        Optional<DataSourceConfig> ds = app.config().find(query.datasource());
        if (ds.isEmpty()) {
            Json.sendError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown datasource: " + query.datasource());
            return;
        }

        DownloadHandler handler = new DownloadHandler(response, format, options, fileName(query.datasource(), format));
        try {
            app.executor().execute(ds.get(), query.sql(), query.maxRows(), handler);
        } catch (Exception e) {
            if (handler.finished) {
                // The file is complete; the failure came while closing the connection.
                LOG.log(Level.FINE, "Export closed with an error", e);
                return;
            }
            if (!response.isCommitted()) {
                response.reset();
                sendQueryError(response, e);
                return;
            }
            // Rows were already sent. Ending without a complete response makes
            // the browser treat the download as failed instead of saving part of it.
            LOG.log(Level.WARNING, "Export of " + query.datasource() + " failed after rows were sent", e);
            throw new IOException("Export failed: " + QueryExecutor.describe(e), e);
        }
    }

    static String fileName(String datasource, ExportFormat format) {
        String safe = datasource.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe + "-" + FILE_TIME.format(LocalDateTime.now()) + "." + format.extension();
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

    private static String text(JsonNode body, String field, String fallback) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? fallback : node.asText();
    }

    private static char character(JsonNode body, String field, char fallback) {
        String s = text(body, field, null);
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        if (s.equals("\\t") || s.equalsIgnoreCase("tab")) {
            return '\t';
        }
        if (s.length() != 1) {
            throw new IllegalArgumentException("\"" + field + "\" must be a single character");
        }
        return s.charAt(0);
    }

    /** Sets the download headers when the first row arrives, then hands the rows to the format. */
    private static final class DownloadHandler implements ResultHandler {

        private final HttpServletResponse response;
        private final ExportFormat format;
        private final ExportOptions options;
        private final String fileName;
        private ResultHandler writer;
        private boolean finished;

        DownloadHandler(HttpServletResponse response, ExportFormat format, ExportOptions options, String fileName) {
            this.response = response;
            this.format = format;
            this.options = options;
            this.fileName = fileName;
        }

        @Override
        public void columns(List<ColumnInfo> columns) throws Exception {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(format.contentType());
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            response.setHeader("Cache-Control", "no-store");
            OutputStream out = response.getOutputStream();
            writer = format.writer(out, options);
            writer.columns(columns);
        }

        @Override
        public void row(Object[] values) throws Exception {
            writer.row(values);
        }

        @Override
        public void updateCount(long count) throws Exception {
            throw new SQLException("The statement did not return rows (" + count
                    + " rows affected), so there is nothing to export.");
        }

        @Override
        public void end(long rowCount, boolean truncated) throws Exception {
            writer.end(rowCount, truncated);
            finished = true;
        }
    }
}
