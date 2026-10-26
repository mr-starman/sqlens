package sqlens.eclipse;

import org.eclipse.jface.preference.IPreferenceStore;

import sqlens.eclipse.api.NumberStyle;
import sqlens.eclipse.api.SqlensClient;

/** The plug-in's settings, kept in the workspace preferences. */
public final class Prefs {

    public static final String BASE_URL = "baseUrl";
    public static final String DATASOURCE = "datasource";
    /** Rows for Run Query; 0 is all. */
    public static final String MAX_ROWS = "maxRows";
    public static final String NUMBER_STYLE = "numberStyle";

    private Prefs() {
    }

    public static IPreferenceStore store() {
        return Activator.getDefault().getPreferenceStore();
    }

    public static SqlensClient client() {
        return new SqlensClient(store().getString(BASE_URL));
    }

    public static NumberStyle numberStyle() {
        return NumberStyle.of(store().getString(NUMBER_STYLE));
    }
}
