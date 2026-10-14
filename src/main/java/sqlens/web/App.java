package sqlens.web;

import jakarta.servlet.ServletContext;
import sqlens.config.ConfigStore;
import sqlens.config.SqlensHome;
import sqlens.jdbc.DriverRegistry;
import sqlens.jdbc.QueryExecutor;

/** The shared services of one deployed SQLens webapp. */
public record App(SqlensHome home, ConfigStore config, DriverRegistry drivers, QueryExecutor executor) {

    static final String ATTRIBUTE = App.class.getName();

    public static App create(SqlensHome home) {
        DriverRegistry drivers = new DriverRegistry(home.driversDir());
        return new App(home, new ConfigStore(home.configFile()), drivers, new QueryExecutor(drivers));
    }

    public static App from(ServletContext context) {
        return (App) context.getAttribute(ATTRIBUTE);
    }
}
