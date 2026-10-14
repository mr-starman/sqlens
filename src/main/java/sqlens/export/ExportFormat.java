package sqlens.export;

import sqlens.jdbc.ResultHandler;

import java.io.OutputStream;

/**
 * A file format a query result can be exported to. A format turns the streamed
 * result into bytes, so exports of any size never sit in memory. Register new
 * formats in {@link ExportFormats}.
 */
public interface ExportFormat {

    /** Short name used in requests, such as {@code csv}. */
    String id();

    /** Value for the Content-Type header. */
    String contentType();

    /** File name extension without the dot. */
    String extension();

    /**
     * A handler that writes the result to {@code out}. Nothing is written before
     * {@link ResultHandler#columns} is called, so errors that happen before the
     * first row can still be reported as a normal error response.
     */
    ResultHandler writer(OutputStream out, ExportOptions options);
}
