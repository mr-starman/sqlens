package sqlens.export;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The export formats SQLens offers, by {@link ExportFormat#id()}. */
public final class ExportFormats {

    private static final Map<String, ExportFormat> FORMATS = new LinkedHashMap<>();

    static {
        register(new CsvFormat());
    }

    private ExportFormats() {
    }

    private static void register(ExportFormat format) {
        FORMATS.put(format.id(), format);
    }

    public static Optional<ExportFormat> find(String id) {
        return Optional.ofNullable(FORMATS.get(id));
    }
}
