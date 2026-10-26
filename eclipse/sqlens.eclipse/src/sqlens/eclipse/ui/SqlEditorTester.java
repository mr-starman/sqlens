package sqlens.eclipse.ui;

import java.util.Locale;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * {@code sqlens:isSqlEditor}: a text editor that edits SQL, which is the Data
 * Tools SQL editor, any editor whose id mentions SQL, or any text editor open
 * on a {@code .sql} file.
 */
public class SqlEditorTester extends PropertyTester {

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        if (!(receiver instanceof IEditorPart part) || EditorStatement.textEditor(part) == null) {
            return false;
        }
        ITextEditor editor = EditorStatement.textEditor(part);
        String id = part.getSite() == null ? "" : String.valueOf(part.getSite().getId());
        String name = editor.getEditorInput() == null ? "" : editor.getEditorInput().getName();
        return id.toLowerCase(Locale.ROOT).contains("sql") || name.toLowerCase(Locale.ROOT).endsWith(".sql");
    }
}
