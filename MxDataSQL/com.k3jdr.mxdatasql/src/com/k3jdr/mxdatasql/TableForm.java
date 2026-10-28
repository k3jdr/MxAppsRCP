package com.k3jdr.mxdatasql;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatasql.GridResult.CellKey;
import com.k3jdr.mxdatasql.GridFilters.GridQueryOptions;

/**
 * Form 3 — DB Table (TableGridForm.swift): the SQL box with Run SQL above the grid, the resize bar between them,
 * the grid's title and facts, the grid, and its buttons (Reload, View…, Export CSV…, Previous, Next, Discard Edits,
 * Update…). Used by workspace windows and by table windows (Open in New Window).
 */
public final class TableForm implements DataGrid.Host {
    /** Where the form's view settings live and where changes to them go. */
    public interface ViewHost {
        Workspace.ViewSettings view();
        /** Rows, columns or fetch limit changed. */
        void viewChanged();
        /** The user's column widths for {@code table} changed (null: none left). */
        void widthsChanged(String table, Map<String, Double> widths);
    }

    private static final Prefs PREFS = Prefs.of("com.k3jdr.mxdatasql");
    private static final int DEFAULT_SQL_HEIGHT = 76;
    private static final int MIN_SQL_HEIGHT = 40;

    private final Session session;
    private final ViewHost viewHost;
    private final Composite root;
    private final StackLayout stack = new StackLayout();
    private final Composite notConnected;
    private final Composite main;
    private final Composite errorBox;
    private final Text errorText;
    private final SqlEditor sql;
    private final Button runButton;
    private final Label runHint;
    private final Label fileLabel;
    private final Label caption;
    private final Sash sash;
    private final StackLayout gridStack = new StackLayout();
    private final Composite gridArea;
    private final Composite noTable;
    private final Composite gridPane;
    private final Label title;
    private final Label facts;
    private final Link clearFilters;
    private final DataGrid grid;
    private final Button reload, viewButton, exportButton, previous, next, discard, update;
    private final Label footnote;
    private int sqlHeight;

    public TableForm(Composite parent, Session session, ViewHost viewHost) {
        this.session = session;
        this.viewHost = viewHost;
        root = new Composite(parent, SWT.NONE);
        root.setLayout(stack);

        notConnected = placeholder(root, "Not Connected", "Connect in the DB Connection form first.");

        main = new Composite(root, SWT.NONE);
        main.setLayout(Ui.grid(1, 14, 6));

        errorBox = new Composite(main, SWT.BORDER);
        errorBox.setLayout(Ui.grid(1, 6, 0));
        errorBox.setBackground(new org.eclipse.swt.graphics.Color(new org.eclipse.swt.graphics.RGB(253, 236, 236)));
        errorBox.setLayoutData(Ui.fillH());
        errorText = new Text(errorBox, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        errorText.setFont(Ui.mono());
        errorText.setForeground(Ui.RED);
        errorText.setBackground(errorBox.getBackground());
        errorText.setLayoutData(Ui.fill());
        QuickHelp.help(errorText, "SQL Error", "Why the SQL didn't run, psql style: the message, then the line with a caret under the error. Select text to copy it.");

        Composite sqlRow = new Composite(main, SWT.NONE);
        sqlRow.setLayout(Ui.grid(2, 0, 8));
        sqlRow.setLayoutData(Ui.fillH());
        sql = new SqlEditor(sqlRow, SWT.NONE, this::sqlMenu, text -> {
            session.sqlText = text;
            refreshCaption();
        });
        sql.control().addVerifyKeyListener(e -> {
            if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.MODIFIER_MASK) == SWT.MOD1) {
                e.doit = false;
                runSQL();
            }
        });
        sqlHeight = PREFS.getInt("sqlEditorHeight", DEFAULT_SQL_HEIGHT);
        GridData sqlData = new GridData(SWT.FILL, SWT.FILL, true, false);
        sqlData.heightHint = sqlHeight;
        sql.control().setLayoutData(sqlData);
        QuickHelp.help(sql.control(), Help.SQL_BOX.title(), Help.SQL_BOX.detail(), Help.SQL_BOX.shortcut());
        Composite runColumn = new Composite(sqlRow, SWT.NONE);
        runColumn.setLayout(Ui.grid(1, 0, 4));
        runColumn.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, false, false));
        runButton = Ui.button(runColumn, "▶ Run SQL", this::runSQL);
        runButton.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        QuickHelp.help(runButton, "Run SQL", "Runs the SQL box. A query fills the grid (read-only; editable when it's SELECT * from one table or includes its key); a statement that changes data asks first.", "Ctrl+Enter");
        runHint = Ui.label(runColumn, "Ctrl+Enter", Ui.small());
        runHint.setForeground(Ui.SECONDARY);
        runHint.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, false, false));

        Composite captionRow = new Composite(main, SWT.NONE);
        captionRow.setLayout(Ui.grid(2, 0, 6));
        captionRow.setLayoutData(Ui.fillH());
        fileLabel = Ui.label(captionRow, "", Ui.small());
        fileLabel.setForeground(Ui.ORANGE);
        caption = new Label(captionRow, SWT.WRAP);
        caption.setFont(Ui.small());
        caption.setForeground(Ui.SECONDARY);
        GridData cd = Ui.fillH();
        cd.widthHint = 300;
        caption.setLayoutData(cd);

        sash = new Sash(main, SWT.HORIZONTAL);
        GridData sd = Ui.fillH();
        sd.heightHint = 6;
        sash.setLayoutData(sd);
        sash.setToolTipText("Drag to give the SQL box more or less room; double-click to reset");
        QuickHelp.help(sash, Help.RESIZE_BAR.title(), Help.RESIZE_BAR.detail());
        sash.addListener(SWT.Selection, e -> {
            int sashY = sash.getBounds().y;
            int current = sql.control().getSize().y;
            int clamped = clampSQLHeight(current + (e.y - sashY));
            e.y = sashY + (clamped - current);
            if (e.detail != SWT.DRAG) {
                sqlHeight = clamped;
                PREFS.putInt("sqlEditorHeight", sqlHeight);
                applySQLHeight();
            }
        });
        sash.addListener(SWT.MouseDoubleClick, e -> {
            sqlHeight = DEFAULT_SQL_HEIGHT;
            PREFS.putInt("sqlEditorHeight", sqlHeight);
            applySQLHeight();
        });

        gridArea = new Composite(main, SWT.NONE);
        gridArea.setLayout(gridStack);
        gridArea.setLayoutData(Ui.fill());
        noTable = placeholder(gridArea, "No Table Open", "Choose a table in the DB Table Selection form, or type a query above and click Run SQL.");
        gridPane = new Composite(gridArea, SWT.NONE);
        gridPane.setLayout(Ui.grid(1, 0, 4));

        title = Ui.label(gridPane, "", Ui.subheading());
        title.setLayoutData(Ui.fillH());
        Composite factsRow = new Composite(gridPane, SWT.NONE);
        factsRow.setLayout(Ui.grid(2, 0, 10));
        factsRow.setLayoutData(Ui.fillH());
        facts = new Label(factsRow, SWT.NONE);
        facts.setForeground(Ui.SECONDARY);
        facts.setLayoutData(Ui.fillH());
        clearFilters = new Link(factsRow, SWT.NONE);
        clearFilters.setText("<a>Clear Filters</a>");
        clearFilters.addListener(SWT.Selection, e -> {
            if (session.grid == null) return;
            GridQueryOptions o = session.grid.options.copy();
            o.filters.clear();
            applyOptions(o);
        });
        QuickHelp.help(clearFilters, "Clear Filters", "Removes every column's filter and loads the rows again from the first page; the sort stays.");

        grid = new DataGrid(gridPane, session);
        grid.setHost(this);
        GridData gd = Ui.fill();
        gd.minimumHeight = 120;
        grid.setLayoutData(gd);

        Composite buttons = new Composite(gridPane, SWT.NONE);
        buttons.setLayout(Ui.grid(8, 0, 6));
        buttons.setLayoutData(Ui.fillH());
        reload = Ui.button(buttons, "↻ Reload", () -> session.reloadGrid(limit()));
        QuickHelp.help(reload, "Reload", "Loads this page of rows again from the database.");
        viewButton = Ui.button(buttons, "View…", this::showViewDialog);
        QuickHelp.help(viewButton, "View", Help.TABLE_VIEW.detail(), "Ctrl+Alt+L");
        exportButton = Ui.button(buttons, "Export CSV…", this::exportCSV);
        QuickHelp.help(exportButton, "Export CSV", "Saves the rows as a CSV file.", "Ctrl+Shift+E");
        Label spacer = new Label(buttons, SWT.NONE);
        spacer.setLayoutData(Ui.fillH());
        previous = Ui.button(buttons, "‹ Previous", () -> session.showPage(false, limit()));
        next = Ui.button(buttons, "Next ›", () -> session.showPage(true, limit()));
        discard = Ui.button(buttons, "Discard Edits", session::discardEdits);
        QuickHelp.help(discard, "Discard Edits", "Drops every pending edit; the cells show the loaded values again.");
        update = Ui.button(buttons, "Update…", this::showUpdateDialog);
        QuickHelp.help(update, "Update", "Reviews the pending edits and writes them to the table: " + Help.UPDATE.detail(), "Ctrl+Alt+U");

        footnote = new Label(gridPane, SWT.WRAP);
        footnote.setFont(Ui.small());
        footnote.setForeground(Ui.SECONDARY);
        GridData fd = Ui.fillH();
        fd.widthHint = 300;
        footnote.setLayoutData(fd);

        session.confirmWrite = this::confirmWrite;
        Ui.setVisible(errorBox, false);
        stack.topControl = notConnected;
        gridStack.topControl = noTable;
    }

    private static Composite placeholder(Composite parent, String heading, String text) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(Ui.grid(1, 40, 8));
        Label filler = new Label(c, SWT.NONE);
        filler.setLayoutData(new GridData(SWT.CENTER, SWT.FILL, true, true));
        Label h = Ui.label(c, heading, Ui.subheading());
        h.setForeground(Ui.SECONDARY);
        h.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Label t = Ui.label(c, text);
        t.setForeground(Ui.SECONDARY);
        t.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Label filler2 = new Label(c, SWT.NONE);
        filler2.setLayoutData(new GridData(SWT.CENTER, SWT.FILL, true, true));
        return c;
    }

    public Composite control() { return root; }
    public DataGrid grid() { return grid; }
    public SqlEditor sqlEditor() { return sql; }

    int limit() { return viewHost.view().fetchLimit; }

    // MARK: Refresh

    public void refresh() {
        if (root.isDisposed()) return;
        Composite top = session.isConnected() ? main : notConnected;
        if (stack.topControl != top) {
            stack.topControl = top;
            root.layout();
        }
        if (!session.isConnected()) return;
        if (!sql.getText().equals(session.sqlText)) sql.setText(session.sqlText);
        boolean showError = session.sqlError != null;
        if (showError) errorText.setText(session.sqlError);
        Ui.setVisible(errorBox, showError);
        if (showError) {
            GridData ed = (GridData) errorBox.getLayoutData();
            int lines = Math.min(8, session.sqlError.split("\n").length);
            ed.heightHint = lines * errorText.getLineHeight() + 12;
        }
        runButton.setEnabled(!session.runningSQL && !session.isBusy());
        runHint.setText(session.runningSQL ? "Running…" : "Ctrl+Enter");
        refreshCaption();

        GridResult g = session.grid;
        Composite gridTop = g == null ? noTable : gridPane;
        if (gridStack.topControl != gridTop) {
            gridStack.topControl = gridTop;
            gridArea.layout();
        }
        Workspace.ViewSettings view = viewHost.view();
        if (g != null) {
            title.setText(Ui.escape(g.title));
            facts.setText(Ui.escape(factsText(g)));
            boolean filtered = !g.options.filters.isEmpty();
            Ui.setVisible(clearFilters, filtered);
            clearFilters.setEnabled(!session.isBusy());
            footnote.setText(g.isEditable()
                    ? "Click, shift-click or drag to select cells; Ctrl+C copies and Ctrl+V pastes. Double-click a cell or press Enter to edit it. Right-click for NULL and Revert."
                    : g.description != null && g.description.summary.isView()
                    ? "Read-only view: select cells and press Ctrl+C to copy. To change its rows, edit the tables it reads from."
                    : "Read-only result: select cells and press Ctrl+C to copy. To edit rows, query one table with SELECT * (any WHERE / ORDER BY), or include its primary key columns.");
            boolean idle = !session.isBusy() && !session.runningSQL;
            String n = Help.n(view.fetchLimit);
            previous.setEnabled(g.hasPreviousPage() && idle);
            next.setEnabled(g.hasNextPage() && idle);
            previous.setToolTipText("Load the previous " + n + " rows");
            next.setToolTipText("Load the next " + n + " rows");
            QuickHelp.help(previous, "Previous", "Loads the previous " + n + " rows (View… sets how many).");
            QuickHelp.help(next, "Next", "Loads the next " + n + " rows (View… sets how many).");
            exportButton.setEnabled(!session.isBusy());
            discard.setEnabled(!session.edits.isEmpty());
            update.setEnabled(!session.edits.isEmpty());
            reload.setEnabled(idle);
        }
        GridData gridData = (GridData) grid.getLayoutData();
        // At least View ▸ rows are visible (up to 10, so the window still fits on a small screen); a taller window shows more.
        gridData.minimumHeight = grid.heightFor(Math.min(view.visibleRows, 10));
        gridData.heightHint = Math.min(grid.heightFor(view.visibleRows), 600);
        grid.refresh(view.visibleColumns, g == null ? null : view.columnWidths.get(g.widthKey()));
        main.layout(true, true);
    }

    private void refreshCaption() {
        String name = session.sqlFileName();
        fileLabel.setText(name == null ? "" : Ui.escape("📄 " + name + (session.sqlIsEdited() ? " — Edited" : "") + "  ·"));
        fileLabel.setForeground(session.sqlIsEdited() ? Ui.ORANGE : Ui.SECONDARY);
        fileLabel.setToolTipText(session.sqlFile == null ? null : session.sqlFile.toString());
        Ui.setVisible(fileLabel, name != null);
        caption.setText("Right-click the SQL to open or save .sql files. Fetches up to " + Help.n(viewHost.view().fetchLimit)
                + " rows (Table ▸ View…); queries run read-only, and statements that change data ask before they run.");
        fileLabel.getParent().layout();
    }

    private String factsText(GridResult g) {
        List<String> parts = new ArrayList<>();
        parts.add(rowsText(g));
        parts.add(g.columns.size() + " columns");
        if (g.description != null) {
            parts.add(g.description.summary.isView() ? g.description.summary.kind
                    : g.description.primaryKey.isEmpty() ? "no primary key" : "key: " + String.join(", ", g.description.primaryKey));
        }
        if (!g.isEditable()) parts.add("read-only");
        if (!session.edits.isEmpty()) parts.add(session.edits.size() + " pending edit" + (session.edits.size() == 1 ? "" : "s"));
        if (g.options.sort != null) parts.add((g.options.sort.ascending() ? "↑ " : "↓ ") + "sorted by " + g.options.sort.column());
        if (!g.options.filters.isEmpty()) parts.add("filtered: " + String.join(", ", g.options.filters.keySet()));
        return String.join("     ", parts);
    }

    /** "Rows 1,001–2,000 of 5,432"; without a known total, "(more on the next page)". */
    static String rowsText(GridResult g) {
        if (g.rows.isEmpty()) return g.offset > 0 ? "No rows after row " + Help.n(g.offset) : "No rows";
        String range = "Rows " + Help.n(g.offset + 1L) + "–" + Help.n((long) g.offset + g.rows.size());
        if (g.totalRows != null && g.unfilteredTotal != null) {
            return range + " of " + Help.n(g.totalRows) + " (filtered from " + Help.n(g.unfilteredTotal) + ")";
        }
        if (g.totalRows != null) return range + " of " + Help.n(g.totalRows);
        return range + (g.hasMore ? " (more on the next page)" : "");
    }

    // MARK: SQL height

    private int clampSQLHeight(int wanted) {
        int formHeight = main.getClientArea().height;
        int max = formHeight > 0 ? Math.max(MIN_SQL_HEIGHT, formHeight - 320 - (session.sqlError == null ? 0 : 90)) : 400;
        return Math.max(MIN_SQL_HEIGHT, Math.min(max, wanted));
    }

    private void applySQLHeight() {
        GridData d = (GridData) sql.control().getLayoutData();
        d.heightHint = clampSQLHeight(sqlHeight);
        main.layout(true, true);
    }

    // MARK: Actions

    public void runSQL() {
        if (!session.isConnected() || session.runningSQL) return;
        session.sqlText = sql.getText();
        session.runSQL(null, limit(), 0, false, null);
    }

    private boolean confirmWrite(String text) {
        String db = session.connectedDatabase != null ? session.connectedDatabase : "the database";
        String heading;
        String message;
        if (Sql.isDrop(text)) {
            heading = "Permanently drop?";
            message = "This DROP statement permanently deletes database objects, and the data in them, on " + db + ". It can't be undone.";
        } else if (Sql.isAlter(text)) {
            heading = "Run this ALTER statement?";
            message = "This statement changes the definition of database objects on " + db + ". Some changes, such as dropping a column, delete data and can't be undone. It runs as its own transaction and is committed if it succeeds.";
        } else if (Sql.isDDL(text)) {
            heading = "Run this CREATE statement?";
            message = "This statement creates database objects on " + db + ". It runs as its own transaction and is committed if it succeeds.";
        } else if (Sql.isCall(text)) {
            heading = "Run a statement that changes data?";
            message = "This calls a stored procedure, which can change data. Its work is committed if it succeeds.";
        } else {
            heading = "Run a statement that changes data?";
            message = "This statement can insert, update or delete rows. It runs in its own transaction, which is committed if it succeeds.";
        }
        String shown = text.length() > 400 ? text.substring(0, 400) : text;
        MessageDialog d = new MessageDialog(root.getShell(), heading, null, message + "\n\n" + shown,
                Sql.isDrop(text) ? MessageDialog.WARNING : MessageDialog.QUESTION, 1,
                Sql.isDrop(text) ? "Drop" : "Run and Commit", "Cancel");
        return d.open() == 0;
    }

    /** The SQL box's own right-click items (SQLFiles.swift sqlMenuItems). */
    private void sqlMenu(Menu menu) {
        SqlEditor.item(menu, "Run SQL\tCtrl+Enter", session.isConnected() && !session.runningSQL, this::runSQL);
        new MenuItem(menu, SWT.SEPARATOR);
        SqlEditor.item(menu, "New SQL", true, session::newSQL);
        SqlEditor.item(menu, "Open SQL File…", true, () -> session.openSQLFile(null));
        MenuItem recent = new MenuItem(menu, SWT.CASCADE);
        recent.setText("Open Recent");
        Menu recentMenu = new Menu(recent);
        recent.setMenu(recentMenu);
        List<Path> recentFiles = SqlLibrary.recentFiles();
        for (Path p : recentFiles) SqlEditor.item(recentMenu, Ui.escape(p.getFileName().toString()), true, () -> session.openSQLFile(p));
        if (recentFiles.isEmpty()) SqlEditor.item(recentMenu, "No Recent Files", false, () -> { });
        new MenuItem(recentMenu, SWT.SEPARATOR);
        SqlEditor.item(recentMenu, "Clear Menu", !recentFiles.isEmpty(), SqlLibrary::clearRecent);

        MenuItem folder = new MenuItem(menu, SWT.CASCADE);
        folder.setText("SQL Folder");
        Menu folderMenu = new Menu(folder);
        folder.setMenu(folderMenu);
        Path dir = SqlLibrary.folder();
        if (dir != null) {
            SqlEditor.item(folderMenu, Ui.escape(dir.getFileName() == null ? dir.toString() : dir.getFileName().toString()), false, () -> { });
            List<Path> files = SqlLibrary.folderFiles();
            for (Path p : files) SqlEditor.item(folderMenu, Ui.escape(p.getFileName().toString()), true, () -> session.openSQLFile(p));
            if (files.isEmpty()) SqlEditor.item(folderMenu, "No .sql Files", false, () -> { });
            new MenuItem(folderMenu, SWT.SEPARATOR);
            SqlEditor.item(folderMenu, "Show SQL Folder in Explorer", true, () -> Explorer.show(dir));
        }
        SqlEditor.item(folderMenu, dir == null ? "Choose SQL Folder…" : "Change SQL Folder…", true,
                () -> SqlLibrary.chooseFolder(root.getShell()));

        new MenuItem(menu, SWT.SEPARATOR);
        boolean hasFile = session.sqlFile != null;
        SqlEditor.item(menu, hasFile ? "Save SQL" : "Save SQL…", hasFile ? session.sqlIsEdited() : !sql.getText().isEmpty(), session::saveSQL);
        SqlEditor.item(menu, "Save SQL As…", !sql.getText().isEmpty(), session::saveSQLAs);
        SqlEditor.item(menu, "Revert to Saved", session.sqlIsEdited(), session::revertSQL);
        if (hasFile) SqlEditor.item(menu, "Show in Explorer", true, () -> Explorer.select(session.sqlFile));
        new MenuItem(menu, SWT.SEPARATOR);
    }

    public void showViewDialog() {
        Workspace.ViewSettings view = viewHost.view();
        ViewSettingsDialog d = new ViewSettingsDialog(root.getShell(), view, session.edits.size());
        if (d.open() != ViewSettingsDialog.OK) return;
        Workspace.ViewSettings draft = d.result();
        boolean reload = draft.fetchLimit != view.fetchLimit && session.grid != null;
        boolean changed = draft.fetchLimit != view.fetchLimit || draft.visibleRows != view.visibleRows
                || draft.visibleColumns != view.visibleColumns;
        view.fetchLimit = draft.fetchLimit;
        view.visibleRows = draft.visibleRows;
        view.visibleColumns = draft.visibleColumns;
        if (changed) viewHost.viewChanged();
        if (reload) session.reloadGrid(view.fetchLimit);
        session.changed();
    }

    public void showUpdateDialog() {
        new UpdateDialog(root.getShell(), session, limit()).open();
    }

    public void exportCSV() {
        CsvExport.begin(root.getShell(), session);
    }

    public boolean canUpdate() { return session.grid != null && session.grid.isEditable(); }

    /** The Edit menu's target while the focus is in the grid. */
    public EditActions.Target editTarget() {
        return new EditActions.Target() {
            @Override public boolean canCopy() { return grid.canCopy(); }
            @Override public void copy() { grid.copy(false); }
            @Override public boolean canPaste() { return grid.canPaste(); }
            @Override public void paste() { grid.paste(); }
            @Override public boolean canSelectAll() { return grid.canSelectAll(); }
            @Override public void selectAll() { grid.selectAll(); }
        };
    }

    // MARK: DataGrid.Host

    @Override
    public void applyOptions(GridQueryOptions options) {
        session.applyGridOptions(options, limit());
    }

    @Override
    public void widthsChanged(Map<String, Double> widths) {
        if (session.grid == null) return;
        viewHost.widthsChanged(session.grid.widthKey(), widths.isEmpty() ? null : widths);
    }

    @Override
    public void viewJSON(CellKey key) {
        GridResult g = session.grid;
        if (g == null || key == null) return;
        Catalog.ColumnInfo column = g.columns.get(key.column());
        String value = session.displayed(key.row(), key.column());
        if (value == null) return;
        String subtitle = g.title + " · row " + Help.n(g.offset + key.row() + 1L) + " · " + column.type();
        new JsonViewerDialog(root.getShell(), column.name(), subtitle, value,
                g.isEditable() ? text -> grid.applyCell(key, text) : null).open();
    }

    @Override
    public void openFilter(int column, org.eclipse.swt.graphics.Rectangle header) {
        GridResult g = session.grid;
        if (g == null || !g.canFilter(column)) return;
        new FilterPopup(root.getShell(), session, column, header, this::applyOptions).open();
    }

    @Override
    public void editsChanged() {
        session.changed();
    }

    /** Opens the folder or selects the file in Windows Explorer. */
    static final class Explorer {
        private Explorer() {}

        static void show(Path dir) {
            org.eclipse.swt.program.Program.launch(dir.toString());
        }

        static void select(Path file) {
            try {
                new ProcessBuilder("explorer.exe", "/select," + file.toString()).start();
            } catch (Exception e) {
                // Explorer isn't there
            }
        }
    }
}
