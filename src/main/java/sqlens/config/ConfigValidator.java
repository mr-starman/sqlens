package sqlens.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Finds keys in {@code datasources.json} that SQLens does not know, such as
 * {@code readonly} for {@code readOnly}, which would otherwise be ignored
 * without a word. Suggests the field that was probably meant.
 */
final class ConfigValidator {

    private static final List<String> ROOT_FIELDS = fieldNames(ConfigStore.ConfigFile.class);
    private static final List<String> SETTINGS_FIELDS = fieldNames(ConfigStore.Settings.class);
    private static final List<String> DATASOURCE_FIELDS = fieldNames(ConfigStore.RawDataSource.class);

    private ConfigValidator() {
    }

    /** One line per unknown key; empty when every key is known. */
    static List<String> unknownKeys(JsonNode root) {
        List<String> problems = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return problems;
        }
        check(root, "", ROOT_FIELDS, problems);
        JsonNode settings = root.get("settings");
        if (settings != null && settings.isObject()) {
            check(settings, "in \"settings\"", SETTINGS_FIELDS, problems);
        }
        JsonNode datasources = root.get("datasources");
        if (datasources != null && datasources.isArray()) {
            for (int i = 0; i < datasources.size(); i++) {
                JsonNode ds = datasources.get(i);
                if (ds.isObject()) {
                    JsonNode name = ds.get("name");
                    String where = name != null && name.isTextual()
                            ? "in datasource \"" + name.asText() + "\""
                            : "in datasource #" + (i + 1);
                    check(ds, where, DATASOURCE_FIELDS, problems);
                }
            }
        }
        return problems;
    }

    private static void check(JsonNode node, String where, List<String> known, List<String> problems) {
        Iterator<String> keys = node.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (known.contains(key)) {
                continue;
            }
            StringBuilder sb = new StringBuilder("unknown field \"").append(key).append('"');
            if (!where.isEmpty()) {
                sb.append(' ').append(where);
            }
            String guess = closest(key, known);
            if (guess != null) {
                sb.append(", did you mean \"").append(guess).append("\"?");
            } else {
                sb.append(" (known fields: ").append(String.join(", ", known)).append(')');
            }
            problems.add(sb.toString());
        }
    }

    /** The known field that differs only in case, or by at most two edits. */
    static String closest(String key, List<String> known) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            if (candidate.equalsIgnoreCase(key)) {
                return candidate;
            }
            int d = distance(key.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return bestDistance <= 2 ? best : null;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static List<String> fieldNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())) {
                names.add(f.getName());
            }
        }
        return List.copyOf(names);
    }
}
