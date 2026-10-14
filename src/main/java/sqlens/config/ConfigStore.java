package sqlens.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads {@code datasources.json} and reloads it whenever the file changes, so
 * editing the file takes effect without redeploying.
 */
public final class ConfigStore {

    private static final Logger LOG = Logger.getLogger(ConfigStore.class.getName());
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");

    /**
     * Rows the result grid gets at most, whatever row limit is asked for. The
     * browser holds the whole result in memory and draws every row, so "All"
     * on a big table would freeze the tab. Export streams and is not capped.
     */
    public static final int DEFAULT_MAX_GRID_ROWS = 50_000;

    private final Path configFile;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FileTime loadedModified;
    private List<DataSourceConfig> datasources = List.of();
    private int maxGridRows = DEFAULT_MAX_GRID_ROWS;
    private String error;

    public ConfigStore(Path configFile) {
        this.configFile = configFile;
    }

    public Path configFile() {
        return configFile;
    }

    public synchronized List<DataSourceConfig> datasources() {
        reloadIfChanged();
        return datasources;
    }

    public synchronized Optional<DataSourceConfig> find(String name) {
        return datasources().stream().filter(ds -> ds.name().equals(name)).findFirst();
    }

    /** The grid row cap from {@code settings.maxGridRows}; 0 means no cap. */
    public synchronized int maxGridRows() {
        reloadIfChanged();
        return maxGridRows;
    }

    /** The problem found while reading the config file, or null when it loaded fine. */
    public synchronized String error() {
        reloadIfChanged();
        return error;
    }

    private void reloadIfChanged() {
        if (!Files.isRegularFile(configFile)) {
            if (loadedModified != null || error == null) {
                LOG.warning("SQLens config file not found: " + configFile);
            }
            loadedModified = null;
            datasources = List.of();
            maxGridRows = DEFAULT_MAX_GRID_ROWS;
            error = "Config file not found: " + configFile;
            return;
        }
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(configFile);
        } catch (IOException e) {
            error = "Cannot read " + configFile + ": " + e.getMessage();
            return;
        }
        if (modified.equals(loadedModified)) {
            return;
        }
        loadedModified = modified;
        try {
            ConfigFile file = mapper.readValue(Files.readString(configFile), ConfigFile.class);
            datasources = toDataSources(file);
            maxGridRows = maxGridRows(file);
            error = null;
            LOG.info("Loaded " + datasources.size() + " datasource(s) from " + configFile);
        } catch (IOException | IllegalArgumentException e) {
            datasources = List.of();
            maxGridRows = DEFAULT_MAX_GRID_ROWS;
            error = "Invalid " + configFile.getFileName() + ": " + e.getMessage();
            LOG.warning(error);
        }
    }

    List<DataSourceConfig> parse(String json) throws IOException {
        return toDataSources(mapper.readValue(json, ConfigFile.class));
    }

    private static int maxGridRows(ConfigFile file) {
        if (file.settings == null || file.settings.maxGridRows == null) {
            return DEFAULT_MAX_GRID_ROWS;
        }
        if (file.settings.maxGridRows < 0) {
            throw new IllegalArgumentException("\"settings.maxGridRows\" cannot be negative");
        }
        return file.settings.maxGridRows;
    }

    private static List<DataSourceConfig> toDataSources(ConfigFile file) {
        List<DataSourceConfig> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (file.datasources == null) {
            return result;
        }
        for (RawDataSource raw : file.datasources) {
            if (raw.name == null || raw.name.isBlank()) {
                throw new IllegalArgumentException("every datasource needs a \"name\"");
            }
            if (raw.url == null || raw.url.isBlank()) {
                throw new IllegalArgumentException("datasource \"" + raw.name + "\" needs a \"url\"");
            }
            if (!names.add(raw.name)) {
                throw new IllegalArgumentException("duplicate datasource name \"" + raw.name + "\"");
            }
            Map<String, String> props = new HashMap<>();
            if (raw.properties != null) {
                raw.properties.forEach((k, v) -> props.put(k, expand(v)));
            }
            result.add(new DataSourceConfig(
                    raw.name,
                    raw.description,
                    expand(raw.url),
                    expand(raw.user),
                    expand(raw.password),
                    blankToNull(raw.driver),
                    props,
                    raw.readOnly,
                    Math.max(0, raw.queryTimeoutSeconds)));
        }
        return List.copyOf(result);
    }

    /**
     * Replaces {@code ${NAME}} with the environment variable or system property
     * of that name, so passwords can stay out of the file. Unknown names are left as is.
     */
    static String expand(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = System.getenv(key);
            if (replacement == null) {
                replacement = System.getProperty(key);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ConfigFile {
        public Settings settings;
        public List<RawDataSource> datasources;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Settings {
        public Integer maxGridRows;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawDataSource {
        public String name;
        public String description;
        public String url;
        public String user;
        public String password;
        public String driver;
        public Map<String, String> properties;
        public boolean readOnly;
        public int queryTimeoutSeconds;
    }
}
