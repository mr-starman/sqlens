package sqlens.eclipse.api;

/** Same two choices as the web UI's number style picker. */
public enum NumberStyle {
    POINT("point", ",", "."),
    COMMA("comma", ";", ",");

    public final String id;
    public final String delimiter;
    public final String decimalSeparator;

    NumberStyle(String id, String delimiter, String decimalSeparator) {
        this.id = id;
        this.delimiter = delimiter;
        this.decimalSeparator = decimalSeparator;
    }

    public static NumberStyle of(String id) {
        for (NumberStyle s : values()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return POINT;
    }
}
