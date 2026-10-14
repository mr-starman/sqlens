package sqlens.jdbc;

import sqlens.config.DataSourceConfig;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Loads JDBC driver jars from an external folder and opens connections with them.
 *
 * <p>{@link DriverManager} refuses drivers that were not loaded by the caller's
 * class loader, so drivers from the folder are called directly instead. The
 * folder is rescanned whenever a jar is added, removed or replaced. Drivers on
 * Tomcat's own classpath ({@code $CATALINA_BASE/lib}) also work, through the
 * {@link DriverManager} fallback.
 */
public final class DriverRegistry implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DriverRegistry.class.getName());

    private final Path driversDir;
    private final ClassLoader parent;

    private String loadedSignature;
    private URLClassLoader loader;
    private List<Driver> drivers = List.of();
    private List<String> jarProblems = List.of();

    public DriverRegistry(Path driversDir) {
        this(driversDir, DriverRegistry.class.getClassLoader());
    }

    public DriverRegistry(Path driversDir, ClassLoader parent) {
        this.driversDir = driversDir;
        this.parent = parent;
    }

    public Path driversDir() {
        return driversDir;
    }

    public Connection connect(DataSourceConfig ds) throws SQLException {
        Properties props = new Properties();
        props.putAll(ds.properties());
        if (ds.user() != null) {
            props.setProperty("user", ds.user());
        }
        if (ds.password() != null) {
            props.setProperty("password", ds.password());
        }

        Connection connection = null;
        if (ds.driver() != null) {
            connection = explicitDriver(ds.driver()).connect(ds.url(), props);
        } else {
            for (Driver driver : drivers()) {
                if (acceptsUrl(driver, ds.url())) {
                    connection = driver.connect(ds.url(), props);
                    break;
                }
            }
            if (connection == null) {
                connection = DriverManager.getConnection(ds.url(), props);
            }
        }
        if (connection == null) {
            throw new SQLException("Driver " + ds.driver() + " does not accept URL " + ds.url());
        }
        if (ds.readOnly()) {
            connection.setReadOnly(true);
        }
        return connection;
    }

    /** Driver jars that could not be opened, with the reason. */
    public synchronized List<String> jarProblems() {
        drivers();
        return jarProblems;
    }

    /** Names of the driver classes found, for display and troubleshooting. */
    public synchronized List<String> driverNames() {
        return drivers().stream().map(d -> d.getClass().getName()).distinct().sorted().toList();
    }

    private Driver explicitDriver(String className) throws SQLException {
        ClassLoader cl;
        synchronized (this) {
            drivers();
            cl = loader != null ? loader : parent;
        }
        try {
            Class<?> type = Class.forName(className, true, cl);
            return (Driver) type.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            String problems;
            synchronized (this) {
                problems = jarProblems.isEmpty() ? "" : " Jars that could not be opened: " + String.join("; ", jarProblems);
            }
            throw new SQLException("Driver class " + className + " not found. Put its jar in " + driversDir + "." + problems, e);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new SQLException("Cannot load driver class " + className + ": " + e, e);
        }
    }

    private synchronized List<Driver> drivers() {
        String signature = signature();
        if (!signature.equals(loadedSignature)) {
            reload(signature);
        }
        return drivers;
    }

    private void reload(String signature) {
        closeLoader();
        // The class loader skips jars it cannot open without a word, so check them first.
        List<Path> jars = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path jar : jars()) {
            try (JarFile ignored = new JarFile(jar.toFile())) {
                jars.add(jar);
            } catch (IOException | SecurityException e) {
                problems.add(jar.getFileName() + " (" + e + ")");
                LOG.warning("Cannot open driver jar " + jar + ": " + e);
            }
        }
        jarProblems = List.copyOf(problems);
        URL[] urls = jars.stream().map(DriverRegistry::toUrl).toArray(URL[]::new);
        loader = new URLClassLoader("sqlens-drivers", urls, parent);
        List<Driver> found = new ArrayList<>();
        ServiceLoader<Driver> services = ServiceLoader.load(Driver.class, loader);
        var it = services.iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                LOG.log(Level.WARNING, "Skipping JDBC driver that failed to load", e);
            }
        }
        drivers = List.copyOf(found);
        loadedSignature = signature;
        LOG.info("Loaded " + jars.size() + " jar(s) from " + driversDir + ", drivers: "
                + drivers.stream().map(d -> d.getClass().getName()).toList());
    }

    private List<Path> jars() {
        if (!Files.isDirectory(driversDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(driversDir)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot list " + driversDir, e);
            return List.of();
        }
    }

    /** Changes when a jar in the folder is added, removed, rewritten or becomes (un)readable. */
    private String signature() {
        StringBuilder sb = new StringBuilder();
        for (Path jar : jars()) {
            sb.append(jar.getFileName()).append(':');
            try {
                sb.append(Files.size(jar)).append(':').append(Files.getLastModifiedTime(jar).toMillis())
                        .append(':').append(Files.isReadable(jar));
            } catch (IOException e) {
                sb.append('?');
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private static boolean acceptsUrl(Driver driver, String url) {
        try {
            return driver.acceptsURL(url);
        } catch (SQLException e) {
            return false;
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void closeLoader() {
        if (loader == null) {
            return;
        }
        // Drivers also register themselves with DriverManager when loaded. DriverManager hides those
        // entries from this class loader, so they cannot be removed here; the old loader may linger
        // until the JVM restarts, which is fine for a dev tool.
        try {
            loader.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Cannot close driver class loader", e);
        }
        loader = null;
        drivers = List.of();
        jarProblems = List.of();
        loadedSignature = null;
    }

    @Override
    public synchronized void close() {
        closeLoader();
    }
}
