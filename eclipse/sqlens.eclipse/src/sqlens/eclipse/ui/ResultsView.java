package sqlens.eclipse.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.part.ViewPart;

import sqlens.eclipse.Activator;
import sqlens.eclipse.Prefs;
import sqlens.eclipse.api.Datasource;
import sqlens.eclipse.api.NumberStyle;
import sqlens.eclipse.api.QueryResult;
import sqlens.eclipse.api.SqlensClient;
import sqlens.eclipse.api.SqlensException;
import sqlens.eclipse.api.Tsv;

/**
 * The SQLens Results view: picks the datasource and row limit, shows the
 * latest result in a grid, and copies or exports it.
 */
public class ResultsView extends ViewPart {

    public static final String ID = "sqlens.eclipse.resultsView";

    /** The web UI's row limits; 0 is all. */
    private static final int[] ROW_LIMITS = {10, 100, 1000, 10000, 0};
    private static final int MAX_COLUMN_WIDTH = 400;

    private CCombo datasourceCombo;
    private CCombo rowsCombo;
    private Label statusLabel;
    private Table table;
    private Action cancelAction;
    private Action copyAction;
    private Action exportAction;

    private List<Datasource> datasources = List.of();
    private SqlensClient.Query last;
    private QueryResult shown;
    private Job running;
    /** The text the status line returns to after a short note. */
    private String summary = "Run a query with Ctrl+Enter in a SQL editor.";
    private boolean summaryIsError;
    private int noteSerial;

    private final IPropertyChangeListener prefListener = e -> {
        if (Prefs.BASE_URL.equals(e.getProperty())) {
            ui(() -> loadDatasources(null));
        }
    };

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        Composite bar = new Composite(root, SWT.NONE);
        GridLayout barLayout = new GridLayout(4, false);
        barLayout.marginWidth = 0;
        barLayout.marginHeight = 0;
        bar.setLayout(barLayout);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(bar, SWT.NONE).setText("Datasource:");
        // CCombo rather than Combo: its list is an ordinary shell, where the native GTK popup follows the IDE theme and can be see-through.
        datasourceCombo = new CCombo(bar, SWT.READ_ONLY | SWT.BORDER);
        datasourceCombo.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        datasourceCombo.setToolTipText("The datasource Run Query uses");
        datasourceCombo.addListener(SWT.Selection, e -> {
            int i = datasourceCombo.getSelectionIndex();
            if (i >= 0 && i < datasources.size()) {
                Prefs.store().setValue(Prefs.DATASOURCE, datasources.get(i).name());
            }
        });

        new Label(bar, SWT.NONE).setText("Rows:");
        rowsCombo = new CCombo(bar, SWT.READ_ONLY | SWT.BORDER);
        rowsCombo.setToolTipText("Rows to fetch for Run Query");
        int current = Prefs.store().getInt(Prefs.MAX_ROWS);
        for (int n : ROW_LIMITS) {
            rowsCombo.add(rowsText(n));
        }
        int selected = indexOfLimit(current);
        if (selected < 0) {
            rowsCombo.add(rowsText(current));
            selected = rowsCombo.getItemCount() - 1;
        }
        rowsCombo.select(selected);
        rowsCombo.addListener(SWT.Selection, e -> Prefs.store().setValue(Prefs.MAX_ROWS, selectedMaxRows()));

        statusLabel = new Label(root, SWT.WRAP);
        GridData statusData = new GridData(SWT.FILL, SWT.TOP, true, false);
        // A small width hint lets the label wrap to the view's width instead of growing it.
        statusData.widthHint = 100;
        statusLabel.setLayoutData(statusData);

        table = new Table(root, SWT.VIRTUAL | SWT.MULTI | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.addListener(SWT.SetData, e -> fillItem((TableItem) e.item, table.indexOf((TableItem) e.item)));
        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if ((e.stateMask & SWT.MOD1) != 0 && e.keyCode == 'c') {
                    copyRows(true);
                }
            }
        });

        MenuManager menu = new MenuManager();
        menu.add(new Action("Copy Selected Rows") {
            @Override
            public void run() {
                copyRows(true);
            }
        });
        menu.add(new Action("Copy All Rows") {
            @Override
            public void run() {
                copyRows(false);
            }
        });
        table.setMenu(menu.createContextMenu(table));

        createActions();
        Prefs.store().addPropertyChangeListener(prefListener);
        showSummary();
        updateActions();
        loadDatasources(null);
    }

    private void createActions() {
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        Action refresh = new Action("Refresh Datasources", images.getImageDescriptor(ISharedImages.IMG_ELCL_SYNCED)) {
            @Override
            public void run() {
                loadDatasources(null);
            }
        };
        cancelAction = new Action("Cancel", images.getImageDescriptor(ISharedImages.IMG_ELCL_STOP)) {
            @Override
            public void run() {
                if (running != null) {
                    running.cancel();
                }
            }
        };
        copyAction = new Action("Copy Result", images.getImageDescriptor(ISharedImages.IMG_TOOL_COPY)) {
            @Override
            public void run() {
                copyRows(false);
            }
        };
        copyAction.setToolTipText("Copy the rows as tab-separated text with headers, for pasting into Excel");
        exportAction = new Action("Export CSV…", images.getImageDescriptor(ISharedImages.IMG_ETOOL_SAVEAS_EDIT)) {
            @Override
            public void run() {
                if (last != null) {
                    export(last);
                }
            }
        };
        exportAction.setToolTipText("Run the query again and save the whole result as CSV");
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        toolBar.add(cancelAction);
        toolBar.add(copyAction);
        toolBar.add(exportAction);
        toolBar.add(refresh);
        getViewSite().getActionBars().updateActionBars();
    }

    @Override
    public void setFocus() {
        table.setFocus();
    }

    @Override
    public void dispose() {
        Prefs.store().removePropertyChangeListener(prefListener);
        if (running != null) {
            running.cancel();
        }
        super.dispose();
    }

    // Entry points for the editor commands.

    public void runQuery(String sql) {
        withDatasource(ds -> {
            SqlensClient.Query query = new SqlensClient.Query(ds, sql, selectedMaxRows());
            last = query;
            if (running != null) {
                running.cancel();
            }
            setSummary("Running on " + ds + "…", false);
            Job job = new Job("SQLens: running on " + ds) {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    try {
                        QueryResult result = Prefs.client().query(query, monitor::isCanceled);
                        ui(() -> showResult(result));
                    } catch (CancellationException e) {
                        ui(() -> showFailure("Cancelled. The database may keep running the query until it finishes."));
                        return Status.CANCEL_STATUS;
                    } catch (IOException e) {
                        ui(() -> showFailure(errorText(e)));
                    }
                    return Status.OK_STATUS;
                }
            };
            running = job;
            updateActions();
            job.schedule();
        });
    }

    public void exportStatement(String sql) {
        withDatasource(ds -> export(new SqlensClient.Query(ds, sql, 0)));
    }

    private void export(SqlensClient.Query query) {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Export from " + query.datasource());
        dialog.setFilterExtensions(new String[] {"*.csv", "*.*"});
        dialog.setFileName(query.datasource().replaceAll("[^A-Za-z0-9._-]+", "_") + ".csv");
        dialog.setOverwrite(true);
        String chosen = dialog.open();
        if (chosen == null) {
            return;
        }
        Path file = Path.of(chosen);
        NumberStyle style = Prefs.numberStyle();
        // The export takes all rows, not the row limit of Run Query.
        SqlensClient.Query all = new SqlensClient.Query(query.datasource(), query.sql(), 0);
        setNote("Exporting to " + file.getFileName() + "…");
        Job job = new Job("SQLens: exporting from " + query.datasource()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    Prefs.client().exportToFile(all, style, file, monitor::isCanceled);
                    ui(() -> setNote("Exported to " + file.getFileName()));
                } catch (CancellationException e) {
                    ui(() -> setNote("Export cancelled."));
                    return Status.CANCEL_STATUS;
                } catch (IOException e) {
                    ui(() -> setSummary("Export failed: " + errorText(e), true));
                }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    // Datasources.

    /** Runs {@code then} with the selected datasource, asking first if none is selected. */
    private void withDatasource(Consumer<String> then) {
        int i = datasourceCombo.getSelectionIndex();
        if (i >= 0 && i < datasources.size()) {
            then.accept(datasources.get(i).name());
        } else if (datasources.isEmpty()) {
            loadDatasources(() -> pickDatasource(then));
        } else {
            pickDatasource(then);
        }
    }

    private void pickDatasource(Consumer<String> then) {
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(getSite().getShell(), new LabelProvider() {
            @Override
            public String getText(Object element) {
                Datasource d = (Datasource) element;
                return d.description() == null || d.description().isBlank() ? d.name() : d.name() + " - " + d.description();
            }
        });
        dialog.setTitle("SQLens");
        dialog.setMessage("Run the query on which datasource?");
        dialog.setElements(datasources.toArray());
        dialog.setMultipleSelection(false);
        if (dialog.open() == ElementListSelectionDialog.OK && dialog.getFirstResult() instanceof Datasource d) {
            select(d.name());
            Prefs.store().setValue(Prefs.DATASOURCE, d.name());
            then.accept(d.name());
        }
    }

    /** Reloads the datasource list; {@code onLoaded} runs if there is at least one. */
    private void loadDatasources(Runnable onLoaded) {
        setSummary("Loading datasources…", false);
        Job job = new Job("SQLens: loading datasources") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    Datasource.Listing listing = Prefs.client().datasources(monitor::isCanceled);
                    ui(() -> {
                        datasources = listing.datasources();
                        datasourceCombo.removeAll();
                        for (Datasource d : datasources) {
                            datasourceCombo.add(d.readOnly() ? d.name() + " (read-only)" : d.name());
                        }
                        select(Prefs.store().getString(Prefs.DATASOURCE));
                        datasourceCombo.getParent().layout();
                        if (listing.error() != null) {
                            setSummary("datasources.json: " + listing.error(), true);
                        } else if (datasources.isEmpty()) {
                            setSummary("SQLens has no datasources. Add some to datasources.json.", true);
                        } else {
                            setSummary(datasources.size() + " datasource" + (datasources.size() == 1 ? "" : "s")
                                    + ". Run a query with Ctrl+Enter in a SQL editor.", false);
                            if (onLoaded != null) {
                                onLoaded.run();
                            }
                        }
                    });
                } catch (CancellationException e) {
                    return Status.CANCEL_STATUS;
                } catch (IOException e) {
                    ui(() -> setSummary(errorText(e), true));
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private void select(String name) {
        for (int i = 0; i < datasources.size(); i++) {
            if (datasources.get(i).name().equals(name)) {
                datasourceCombo.select(i);
                return;
            }
        }
    }

    // Result grid.

    private void showResult(QueryResult result) {
        running = null;
        shown = result;
        table.setRedraw(false);
        try {
            table.setItemCount(0);
            for (TableColumn c : table.getColumns()) {
                c.dispose();
            }
            if (!result.columns().isEmpty()) {
                TableColumn number = new TableColumn(table, SWT.RIGHT);
                number.setText("#");
                for (QueryResult.Column c : result.columns()) {
                    TableColumn column = new TableColumn(table, c.numeric() ? SWT.RIGHT : SWT.LEFT);
                    column.setText(c.name());
                    column.setToolTipText(c.name() + "\n" + (c.dbType() == null ? "" : c.dbType() + " ") + "(" + c.jdbcType() + ")");
                }
                table.setItemCount(result.rows().size());
                sizeColumns(result);
            }
        } finally {
            table.setRedraw(true);
        }
        List<String> parts = new ArrayList<>();
        parts.add(result.datasource());
        if (result.updateCount() != null && result.columns().isEmpty()) {
            parts.add(plural(result.updateCount(), "row") + " affected");
        } else {
            parts.add(plural(result.rowCount(), "row"));
            if (result.truncated()) {
                parts.add(result.cappedAt() != null
                        ? "more available (grid limit " + String.format("%,d", result.cappedAt()) + "; export for all)"
                        : "more available");
            }
        }
        parts.add(String.format("%,d ms", result.elapsedMs()));
        if (result.error() != null) {
            setSummary(String.join(" · ", parts) + " · stopped: " + result.error(), true);
        } else {
            setSummary(String.join(" · ", parts), false);
        }
        updateActions();
    }

    private void showFailure(String message) {
        running = null;
        shown = null;
        table.setItemCount(0);
        for (TableColumn c : table.getColumns()) {
            c.dispose();
        }
        setSummary(message, true);
        updateActions();
    }

    private void fillItem(TableItem item, int index) {
        if (shown == null || index < 0 || index >= shown.rows().size()) {
            return;
        }
        List<Object> row = shown.rows().get(index);
        String[] text = new String[row.size() + 1];
        text[0] = String.valueOf(index + 1);
        for (int i = 0; i < row.size(); i++) {
            text[i + 1] = QueryResult.display(row.get(i));
            if (row.get(i) == null) {
                item.setForeground(i + 1, item.getDisplay().getSystemColor(SWT.COLOR_GRAY));
            }
        }
        item.setText(text);
    }

    /** Widths from the header and the first rows; a virtual table has nothing else to measure yet. */
    private void sizeColumns(QueryResult result) {
        GC gc = new GC(table);
        try {
            TableColumn[] columns = table.getColumns();
            for (int c = 0; c < columns.length; c++) {
                int width = gc.textExtent(columns[c].getText()).x;
                for (int r = 0; r < Math.min(result.rows().size(), 100); r++) {
                    String text = c == 0 ? String.valueOf(r + 1) : QueryResult.display(result.rows().get(r).get(c - 1));
                    Point extent = gc.textExtent(text);
                    width = Math.max(width, extent.x);
                }
                columns[c].setWidth(Math.min(MAX_COLUMN_WIDTH, Math.max(40, width + 24)));
            }
        } finally {
            gc.dispose();
        }
    }

    // Copy.

    /** Puts the rows (the selected ones, or all) on the clipboard as tab-separated text with headers. */
    private void copyRows(boolean selectionOnly) {
        if (shown == null || shown.columns().isEmpty()) {
            return;
        }
        List<List<Object>> rows = shown.rows();
        if (selectionOnly && table.getSelectionCount() > 0) {
            rows = new ArrayList<>();
            for (int i : table.getSelectionIndices()) {
                rows.add(shown.rows().get(i));
            }
        }
        Clipboard clipboard = new Clipboard(table.getDisplay());
        try {
            clipboard.setContents(new Object[] {Tsv.of(shown.columns(), rows, Prefs.numberStyle())},
                    new Transfer[] {TextTransfer.getInstance()});
            setNote("Copied " + plural(rows.size(), "row") + " with headers to clipboard");
        } catch (RuntimeException e) {
            setSummary("Could not copy to the clipboard: " + e.getMessage(), true);
        } finally {
            clipboard.dispose();
        }
    }

    // Status line.

    private void setSummary(String text, boolean error) {
        summary = text;
        summaryIsError = error;
        noteSerial++;
        showSummary();
    }

    /** Shows {@code text} for a few seconds, then the summary again. */
    private void setNote(String text) {
        int serial = ++noteSerial;
        statusLabel.setForeground(null);
        showStatus(text);
        table.getDisplay().timerExec(4000, () -> {
            if (!statusLabel.isDisposed() && serial == noteSerial) {
                showSummary();
            }
        });
    }

    private void showSummary() {
        statusLabel.setForeground(summaryIsError ? statusLabel.getDisplay().getSystemColor(SWT.COLOR_RED) : null);
        showStatus(summary);
    }

    private void showStatus(String text) {
        statusLabel.setText(text);
        statusLabel.setToolTipText(text);
        statusLabel.getParent().layout(true, true);
    }

    private void updateActions() {
        cancelAction.setEnabled(running != null);
        copyAction.setEnabled(shown != null && !shown.columns().isEmpty());
        exportAction.setEnabled(last != null);
    }

    // Helpers.

    private int selectedMaxRows() {
        int i = rowsCombo.getSelectionIndex();
        return i >= 0 && i < ROW_LIMITS.length ? ROW_LIMITS[i] : Prefs.store().getInt(Prefs.MAX_ROWS);
    }

    private static int indexOfLimit(int rows) {
        for (int i = 0; i < ROW_LIMITS.length; i++) {
            if (ROW_LIMITS[i] == rows) {
                return i;
            }
        }
        return -1;
    }

    private static String rowsText(int rows) {
        return rows == 0 ? "All rows" : String.format("%,d rows", rows);
    }

    private static String plural(long n, String noun) {
        return String.format("%,d %s%s", n, noun, n == 1 ? "" : "s");
    }

    private static String errorText(IOException e) {
        return e instanceof SqlensException s ? s.describe() : String.valueOf(e.getMessage());
    }

    /** Runs on the UI thread, unless the view is gone by then. */
    private void ui(Runnable runnable) {
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() -> {
            if (table != null && !table.isDisposed()) {
                runnable.run();
            }
        });
    }
}
