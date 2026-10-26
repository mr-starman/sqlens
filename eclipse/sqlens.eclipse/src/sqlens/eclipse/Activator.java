package sqlens.eclipse;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

    public static final String ID = "sqlens.eclipse";

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        IPreferenceStore store = getPreferenceStore();
        store.setDefault(Prefs.BASE_URL, "http://localhost:8080/sqlens");
        store.setDefault(Prefs.MAX_ROWS, 100);
        store.setDefault(Prefs.NUMBER_STYLE, "point");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }
}
