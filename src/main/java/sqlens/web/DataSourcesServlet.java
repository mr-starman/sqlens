package sqlens.web;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import sqlens.config.DataSourceConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/datasources}: the configured datasources (without passwords)
 * plus where the config and drivers are read from.
 */
@WebServlet("/api/datasources")
public class DataSourcesServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        App app = App.from(getServletContext());

        List<Map<String, Object>> list = new ArrayList<>();
        for (DataSourceConfig ds : app.config().datasources()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", ds.name());
            item.put("description", ds.description());
            item.put("url", ds.url());
            item.put("user", ds.user());
            item.put("readOnly", ds.readOnly());
            list.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasources", list);
        body.put("configFile", app.config().configFile().toString());
        body.put("driversDir", app.drivers().driversDir().toString());
        body.put("drivers", app.drivers().driverNames());
        body.put("error", app.config().error());
        Json.send(response, HttpServletResponse.SC_OK, body);
    }
}
