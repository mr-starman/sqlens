package sqlens.config;

import java.nio.file.Path;

/**
 * Resolves the SQLens home directory, which lives outside the WAR and holds
 * {@code datasources.json} and the {@code drivers/} folder.
 *
 * <p>Lookup order: the {@code sqlens.home} system property, the
 * {@code SQLENS_HOME} environment variable, then {@code ~/.sqlens}.
 */
public record SqlensHome(Path dir) {

    public static final String PROPERTY = "sqlens.home";
    public static final String ENV = "SQLENS_HOME";

    public static SqlensHome resolve() {
        String value = System.getProperty(PROPERTY);
        if (isBlank(value)) {
            value = System.getenv(ENV);
        }
        if (isBlank(value)) {
            return new SqlensHome(Path.of(System.getProperty("user.home"), ".sqlens"));
        }
        if (value.startsWith("~")) {
            value = System.getProperty("user.home") + value.substring(1);
        }
        return new SqlensHome(Path.of(value).toAbsolutePath().normalize());
    }

    public Path configFile() {
        return dir.resolve("datasources.json");
    }

    public Path driversDir() {
        return dir.resolve("drivers");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
