package sqlens.export;

/**
 * How values are written. The defaults suit Excel with US or UK regional
 * settings; {@link #EUROPEAN} suits the many locales (Swedish, German, French...)
 * where Excel expects a semicolon between fields and a decimal comma.
 *
 * @param delimiter        character between fields
 * @param decimalSeparator character written in place of the dot in numbers
 */
public record ExportOptions(char delimiter, char decimalSeparator) {

    public static final ExportOptions DEFAULT = new ExportOptions(',', '.');
    public static final ExportOptions EUROPEAN = new ExportOptions(';', ',');

    public ExportOptions {
        if (delimiter == '"' || delimiter == '\r' || delimiter == '\n') {
            throw new IllegalArgumentException("Invalid delimiter: " + delimiter);
        }
        if (decimalSeparator != '.' && decimalSeparator != ',') {
            throw new IllegalArgumentException("Decimal separator must be . or ,");
        }
        if (delimiter == decimalSeparator) {
            throw new IllegalArgumentException("Delimiter and decimal separator must differ");
        }
    }
}
