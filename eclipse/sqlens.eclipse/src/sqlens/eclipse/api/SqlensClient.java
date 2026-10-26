package sqlens.eclipse.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Client for the SQLens JSON API:
 * <ul>
 * <li>GET  /api/datasources  the configured datasources</li>
 * <li>POST /api/query        runs a statement, returns columns and rows</li>
 * <li>POST /api/export       runs a statement, streams the result as a file</li>
 * </ul>
 * Every call takes a {@code cancelled} check, polled while waiting, and throws
 * {@link CancellationException} when it turns true.
 */
public final class SqlensClient {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** {@code maxRows} is 0 for no limit. */
    public record Query(String datasource, String sql, int maxRows) {
    }

    private final String baseUrl;

    public SqlensClient(String baseUrl) {
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }

    public Datasource.Listing datasources(BooleanSupplier cancelled) throws IOException {
        return Datasource.Listing.parse(read(send(null, "datasources", cancelled)));
    }

    public QueryResult query(Query query, BooleanSupplier cancelled) throws IOException {
        return QueryResult.parse(read(send(queryJson(query, null), "query", cancelled)));
    }

    /**
     * Streams an export into a file. The server cuts the connection off when the
     * query fails after rows were sent, so any failure removes the partial file
     * rather than leaving a CSV that looks complete.
     */
    public void exportToFile(Query query, NumberStyle style, Path file, BooleanSupplier cancelled) throws IOException {
        HttpResponse<InputStream> response = send(queryJson(query, style), "export", cancelled);
        boolean done = false;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException();
                }
                out.write(buffer, 0, n);
            }
            done = true;
        } catch (IOException e) {
            throw new SqlensException("Export failed after rows were written, so the file was removed: " + e.getMessage(),
                    null, e);
        } finally {
            if (!done) {
                Files.deleteIfExists(file);
            }
        }
    }

    static String queryJson(Query q, NumberStyle exportStyle) {
        StringBuilder sb = new StringBuilder()
                .append("{\"datasource\":").append(Json.quote(q.datasource()))
                .append(",\"sql\":").append(Json.quote(q.sql()))
                .append(",\"maxRows\":").append(q.maxRows());
        if (exportStyle != null) {
            sb.append(",\"format\":\"csv\",\"delimiter\":").append(Json.quote(exportStyle.delimiter))
                    .append(",\"decimalSeparator\":").append(Json.quote(exportStyle.decimalSeparator));
        }
        return sb.append('}').toString();
    }

    private HttpResponse<InputStream> send(String json, String path, BooleanSupplier cancelled) throws IOException {
        URI uri;
        HttpRequest.Builder request;
        try {
            uri = URI.create(baseUrl + "/api/" + path);
            request = HttpRequest.newBuilder(uri);
        } catch (IllegalArgumentException e) {
            throw new SqlensException("Not a valid SQLens URL: " + baseUrl);
        }
        if (json == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        }

        CompletableFuture<HttpResponse<InputStream>> future =
                HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            while (true) {
                try {
                    response = future.get(150, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException e) {
                    if (cancelled.getAsBoolean()) {
                        future.cancel(true);
                        throw new CancellationException();
                    }
                }
            }
        } catch (ExecutionException e) {
            throw new SqlensException("Cannot reach SQLens at " + uri + ": " + describe(e.getCause()), null, e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
        if (response.statusCode() / 100 != 2) {
            throw apiError(response);
        }
        return response;
    }

    private static String read(HttpResponse<InputStream> response) throws IOException {
        try (InputStream in = response.body()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SqlensException apiError(HttpResponse<InputStream> response) throws IOException {
        String text = read(response);
        try {
            Map<String, Object> body = Json.object(Json.parse(text));
            String error = Json.string(body, "error");
            if (error != null) {
                return new SqlensException(error, Json.string(body, "sqlState"), null);
            }
        } catch (IllegalArgumentException e) {
            // Not JSON, e.g. a Tomcat error page.
        }
        return new SqlensException("HTTP " + response.statusCode());
    }

    private static String describe(Throwable e) {
        Throwable cause = e.getCause();
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return cause != null && cause.getMessage() != null ? message + " (" + cause.getMessage() + ")" : message;
    }
}
