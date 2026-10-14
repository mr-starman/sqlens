package sqlens.web;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import sqlens.config.SqlensHome;

import java.util.logging.Logger;

@WebListener
public class AppListener implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger(AppListener.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
        SqlensHome home = SqlensHome.resolve();
        LOG.info("SQLens home: " + home.dir() + " (config " + home.configFile() + ", drivers " + home.driversDir() + ")");
        event.getServletContext().setAttribute(App.ATTRIBUTE, App.create(home));
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        App app = App.from(event.getServletContext());
        if (app != null) {
            app.drivers().close();
        }
    }
}
