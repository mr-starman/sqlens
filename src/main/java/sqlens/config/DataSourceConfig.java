package sqlens.config;

import java.util.Map;

/**
 * One entry from {@code datasources.json}.
 *
 * @param name                 unique name shown in the UI
 * @param description          optional free text
 * @param url                  JDBC URL
 * @param user                 optional user name
 * @param password             optional password
 * @param driver               optional driver class name; when absent the driver is found from the URL
 * @param properties           optional extra JDBC connection properties
 * @param readOnly             when true the connection is put in read-only mode
 * @param queryTimeoutSeconds  optional statement timeout, 0 or absent means no timeout
 */
public record DataSourceConfig(
        String name,
        String description,
        String url,
        String user,
        String password,
        String driver,
        Map<String, String> properties,
        boolean readOnly,
        int queryTimeoutSeconds) {

    public DataSourceConfig {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
