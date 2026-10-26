package sqlens.eclipse.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

public class ExportCsvHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        String sql = EditorStatement.of(event);
        if (sql != null) {
            RunQueryHandler.resultsView(event).exportStatement(sql);
        }
        return null;
    }
}
