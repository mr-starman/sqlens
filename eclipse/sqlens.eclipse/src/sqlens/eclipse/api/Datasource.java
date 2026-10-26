package sqlens.eclipse.api;

import java.util.List;
import java.util.Map;

/** One entry of {@code GET /api/datasources}. */
public record Datasource(String name, String description, String url, String user, boolean readOnly) {

    /** The reply of {@code GET /api/datasources}. */
    public record Listing(List<Datasource> datasources, int maxGridRows, String error) {

        public static Listing parse(String json) {
            Map<String, Object> body = Json.object(Json.parse(json));
            List<Datasource> list = Json.array(body.get("datasources")).stream()
                    .map(o -> {
                        Map<String, Object> d = Json.object(o);
                        return new Datasource(Json.string(d, "name"), Json.string(d, "description"),
                                Json.string(d, "url"), Json.string(d, "user"), Json.bool(d, "readOnly"));
                    })
                    .toList();
            return new Listing(list, (int) Json.number(body, "maxGridRows", 0), Json.string(body, "error"));
        }
    }
}
