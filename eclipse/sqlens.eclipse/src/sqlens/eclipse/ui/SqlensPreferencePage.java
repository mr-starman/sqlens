package sqlens.eclipse.ui;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.RadioGroupFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import sqlens.eclipse.Prefs;

public class SqlensPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public SqlensPreferencePage() {
        super(GRID);
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Prefs.store());
        setDescription("Datasources, drivers and read-only checks are configured on the SQLens server.");
    }

    @Override
    protected void createFieldEditors() {
        addField(new StringFieldEditor(Prefs.BASE_URL, "SQLens URL:", getFieldEditorParent()));
        addField(new RadioGroupFieldEditor(Prefs.NUMBER_STYLE, "Number style for Copy and Export", 1, new String[][] {
                {"Decimal point (1.5), comma between fields", "point"},
                {"Decimal comma (1,5), semicolon between fields", "comma"},
        }, getFieldEditorParent(), true));
    }
}
