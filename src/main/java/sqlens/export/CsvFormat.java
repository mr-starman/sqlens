package sqlens.export;

import sqlens.jdbc.ResultHandler;

import java.io.OutputStream;

/**
 * CSV that Excel opens correctly when double-clicked: UTF-8 with a byte order
 * mark (so accented letters survive), CRLF line ends, and RFC 4180 quoting.
 * See {@link CsvWriter} for how values are written.
 */
public final class CsvFormat implements ExportFormat {

    @Override
    public String id() {
        return "csv";
    }

    @Override
    public String contentType() {
        return "text/csv; charset=UTF-8";
    }

    @Override
    public String extension() {
        return "csv";
    }

    @Override
    public ResultHandler writer(OutputStream out, ExportOptions options) {
        return new CsvWriter(out, options);
    }
}
