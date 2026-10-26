package sqlens.eclipse.ui;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import sqlens.eclipse.api.Statements;

/** The SQL to run from the active editor. */
final class EditorStatement {

    private EditorStatement() {
    }

    static ITextEditor textEditor(IEditorPart part) {
        if (part instanceof ITextEditor editor) {
            return editor;
        }
        return part == null ? null : part.getAdapter(ITextEditor.class);
    }

    /** The selection, or with nothing selected the statement under the cursor; null (after telling the user) if there is none. */
    static String of(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        ITextEditor editor = textEditor(HandlerUtil.getActiveEditor(event));
        if (editor == null) {
            MessageDialog.openInformation(window.getShell(), "SQLens", "Open a SQL file to run a query.");
            return null;
        }
        IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        ITextSelection selection = (ITextSelection) editor.getSelectionProvider().getSelection();
        String sql = null;
        try {
            if (selection.getLength() > 0) {
                sql = document.get(selection.getOffset(), selection.getLength()).strip();
            } else {
                Statements.Range range = Statements.at(document.get(), selection.getOffset());
                sql = range == null ? null : document.get(range.from(), range.to() - range.from());
            }
        } catch (BadLocationException e) {
            throw new ExecutionException("Cannot read the editor text", e);
        }
        if (sql == null || sql.isEmpty()) {
            MessageDialog.openInformation(window.getShell(), "SQLens", "No SQL statement at the cursor.");
            return null;
        }
        return sql;
    }
}
