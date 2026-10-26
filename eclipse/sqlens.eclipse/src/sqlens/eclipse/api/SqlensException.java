package sqlens.eclipse.api;

import java.io.IOException;

/** A failed call to SQLens, with the SQL error details when the database reported any. */
public class SqlensException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String sqlState;

    public SqlensException(String message) {
        this(message, null, null);
    }

    public SqlensException(String message, String sqlState, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    /** The message, with the SQLSTATE when there is one. */
    public String describe() {
        return sqlState == null ? getMessage() : getMessage() + " (SQLSTATE " + sqlState + ")";
    }
}
