package com.k3jdr.mxdatasql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.db.Pg;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatasql.Catalog.CatalogEntry;
import com.k3jdr.mxdatasql.Catalog.CatalogKind;
import com.k3jdr.mxdatasql.Catalog.ColumnInfo;
import com.k3jdr.mxdatasql.Catalog.RoutineDefinition;
import com.k3jdr.mxdatasql.Catalog.RoutineSummary;
import com.k3jdr.mxdatasql.Catalog.TableDescription;
import com.k3jdr.mxdatasql.Catalog.TableSummary;
import com.k3jdr.mxdatasql.GridResult.CellEdit;
import com.k3jdr.mxdatasql.GridResult.CellKey;
import com.k3jdr.mxdatasql.GridFilters.GridQueryOptions;
import com.k3jdr.mxdatasql.PgService.QueryColumn;
import com.k3jdr.mxdatasql.PgService.QueryResult;

/**
 * The live, unsaved state of one workspace or table window (WorkspaceSession.swift): the connection, the table list,
 * the grid and its pending edits, the SQL box. Database work runs off the UI thread; the state changes on the UI
 * thread and listeners are told.
 */
public final class Session {
    public enum Form {
        CONNECTION("DB Connection"), TABLE_SELECTION("DB Table Selection"), TABLE("DB Table");

        public final String label;

        Form(String label) { this.label = label; }
    }

    public enum Status { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private final Control owner;
    private final List<Runnable> listeners = new ArrayList<>();
    /** Asks whether to run a statement that changes data (the confirmation dialog); true runs it. */
    public Predicate<String> confirmWrite = sql -> false;

    public Form form = Form.CONNECTION;
    public String password = "";
    public Status status = Status.DISCONNECTED;
    public String serverVersion = "";
    public String failure = "";
    /** The settings the open connection was made with. */
    public Workspace.Connection connectedSettings;

    public List<TableSummary> tables = new ArrayList<>();
    public List<RoutineSummary> routines = new ArrayList<>();
    public List<String> databases = new ArrayList<>();
    public String connectedDatabase;
    public String tableFilter = "";
    public CatalogKind catalogKind = CatalogKind.ALL;
    public String highlightedID;
    public TableDescription highlightedDescription;
    public RoutineDefinition highlightedRoutine;
    public boolean countingRows;
    public boolean openInNewWindow;

    public GridResult grid;
    public final Map<CellKey, CellEdit> edits = new LinkedHashMap<>();
    /** Bumped whenever {@code grid} is replaced or edits are discarded, so the grid view reloads. */
    public int gridVersion;

    private int busy;
    public String infoMessage;

    public String sqlText = "";
    public Path sqlFile;
    public String sqlSavedText;
    public String sqlError;
    public boolean runningSQL;
    /** The table last opened; running its unchanged default SQL reloads it as a whole table. */
    public TableDescription openedTable;

    PgService service;

    public Session(Control owner) {
        this.owner = owner;
    }

    public void addListener(Runnable r) { listeners.add(r); }

    public void changed() {
        if (owner.isDisposed()) return;
        for (Runnable r : List.copyOf(listeners)) r.run();
    }

    public Shell shell() { return owner.getShell(); }
    public boolean isBusy() { return busy > 0; }
    public boolean isConnected() { return status == Status.CONNECTED; }
    public PgService service() { return service; }

    public GridQueryOptions gridOptions() { return grid != null ? grid.options : new GridQueryOptions(); }

    public void showError(String message) {
        if (!owner.isDisposed()) ErrorDialog.show(owner.getShell(), "MxDataSQL", message);
    }

    /** Runs {@code work} off the UI thread with the busy indicator on, then {@code done} on the UI thread. */
    <T> void run(Callable<T> work, Consumer<T> done, Consumer<Throwable> failed) {
        busy++;
        changed();
        Background.run(owner, work, result -> {
            busy--;
            if (done != null) done.accept(result);
            changed();
        }, error -> {
            busy--;
            if (failed != null) failed.accept(error);
            else showError(DatabaseError.message(error));
            changed();
        });
    }

    // MARK: Catalog list

    /** The list's rows: tables, views, functions, then procedures, of the chosen kind, whose schema.name has the filter. */
    public List<CatalogEntry> filteredEntries() {
        List<CatalogEntry> entries = new ArrayList<>();
        boolean all = catalogKind == CatalogKind.ALL;
        if (all || catalogKind == CatalogKind.TABLES) for (TableSummary t : tables) if (!t.isView()) entries.add(CatalogEntry.of(t));
        if (all || catalogKind == CatalogKind.VIEWS) for (TableSummary t : tables) if (t.isView()) entries.add(CatalogEntry.of(t));
        for (RoutineSummary r : routines) {
            boolean show = switch (catalogKind) {
                case ALL -> true;
                case TABLES, VIEWS -> false;
                case FUNCTIONS -> !r.isProcedure();
                case PROCEDURES -> r.isProcedure();
            };
            if (show) entries.add(CatalogEntry.of(r));
        }
        String filter = tableFilter.strip().toLowerCase();
        if (filter.isEmpty()) return entries;
        List<CatalogEntry> out = new ArrayList<>();
        for (CatalogEntry e : entries) {
            String name = e.routine() != null ? e.routine().schema() + "." + e.routine().name() : e.schema() + "." + e.name();
            if (name.toLowerCase().contains(filter)) out.add(e);
        }
        return out;
    }

    public CatalogEntry highlightedEntry() {
        if (highlightedID == null) return null;
        for (TableSummary t : tables) if (t.id().equals(highlightedID)) return CatalogEntry.of(t);
        for (RoutineSummary r : routines) if (r.id().equals(highlightedID)) return CatalogEntry.of(r);
        return null;
    }

    private String entryID(String key) {
        if (key == null) return null;
        for (TableSummary t : tables) if (CatalogEntry.of(t).key().equals(key)) return t.id();
        for (RoutineSummary r : routines) if (CatalogEntry.of(r).key().equals(key)) return r.id();
        return null;
    }

    private String firstTableID() { return tables.isEmpty() ? null : tables.get(0).id(); }

    public TableSummary table(TableRef ref) {
        for (TableSummary t : tables) if (t.ref.equals(ref)) return t;
        return null;
    }

    // MARK: Connection

    /** The password saved for these settings, when there is one and the field is empty. */
    public void loadSavedPassword(Workspace.Connection settings) {
        if (password.isEmpty() && settings.savePasswordInKeychain) {
            Pg.savedPassword(PgService.APP, settings.pg()).ifPresent(p -> password = p);
        }
    }

    private record Connected(PgService service, String version, List<String> databases, List<TableSummary> tables,
                             List<RoutineSummary> routines) {}

    private static Connected open(Workspace.Connection s, String password) throws DatabaseError {
        PgService svc = PgService.connect(s, password);
        try {
            String version = svc.serverVersion();
            List<String> dbs;
            try {
                dbs = svc.databases();
            } catch (DatabaseError e) {
                dbs = new ArrayList<>();
            }
            List<TableSummary> tables = svc.listTables();
            List<RoutineSummary> routines;
            try {
                routines = svc.listRoutines();   // servers before PostgreSQL 11 list tables only
            } catch (DatabaseError e) {
                routines = new ArrayList<>();
            }
            return new Connected(svc, version, dbs, tables, routines);
        } catch (DatabaseError e) {
            svc.close();
            throw e;
        }
    }

    public void connect(Workspace.Connection settings, Runnable after) {
        Workspace.Connection s = settings.copy();
        PgService old = service;
        clearConnection();
        status = Status.CONNECTING;
        if (password.isEmpty()) loadSavedPassword(s);
        String pw = password;
        run(() -> {
            if (old != null) old.close();
            return open(s, pw);
        }, c -> {
            adopt(c, s);
            if (s.savePasswordInKeychain && !password.isEmpty()) Pg.savePassword(PgService.APP, s.pg(), password);
            if (after != null) after.run();
        }, error -> {
            status = Status.FAILED;
            failure = DatabaseError.message(error);
        });
    }

    private void adopt(Connected c, Workspace.Connection s) {
        service = c.service();
        status = Status.CONNECTED;
        serverVersion = c.version();
        connectedSettings = s;
        connectedDatabase = s.database;
        databases = new ArrayList<>(c.databases());
        if (!databases.contains(s.database)) {
            databases.add(s.database);
            databases.sort(null);
        }
        tables = c.tables();
        routines = c.routines();
    }

    private void clearConnection() {
        service = null;
        status = Status.DISCONNECTED;
        connectedDatabase = null;
        tables = new ArrayList<>();
        routines = new ArrayList<>();
        highlightedDescription = null;
        highlightedRoutine = null;
        grid = null;
        edits.clear();
        gridVersion++;
        openedTable = null;
        sqlError = null;
    }

    public void disconnect() {
        PgService old = service;
        clearConnection();
        if (old != null) Background.execute(old::close);
        changed();
    }

    /** Closes the connection when the window goes away. */
    public void dispose() {
        PgService old = service;
        service = null;
        if (old != null) Background.execute(old::close);
    }

    /**
     * Reconnects to another database on the same server with the same user and password, then refreshes the list and
     * the highlighted entry (the same schema.table when the new database has it, else the first table). On failure the
     * previous connection stays and the error is shown. {@code done} hears whether the switch happened.
     */
    public void switchDatabase(String name, Workspace.Connection settings, Consumer<Boolean> done) {
        if (!isConnected() || name.equals(connectedDatabase)) {
            done.accept(false);
            return;
        }
        if (!edits.isEmpty()) {
            int n = edits.size();
            MessageDialog d = new MessageDialog(shell(), "Discard " + n + " pending edit" + (n == 1 ? "" : "s") + "?", null,
                    "Switching to database " + name + " closes the open table, and edits not applied with Update… are lost.",
                    MessageDialog.WARNING, 0, "Switch Database", "Cancel");
            if (d.open() != 0) {
                done.accept(false);
                return;
            }
        }
        String previous = highlightedEntry() == null ? null : highlightedEntry().key();
        Workspace.Connection target = settings.copy();
        target.database = name;
        String pw = password;
        PgService old = service;
        run(() -> open(target, pw), c -> {
            if (old != null) Background.execute(old::close);
            clearConnection();
            adopt(c, target);
            highlightedID = entryID(previous) != null ? entryID(previous) : firstTableID();
            describeHighlighted();
            infoMessage = "Switched to database " + name + ".";
            done.accept(true);
        }, error -> {
            showError("Could not connect to database " + name + ".\n\n" + DatabaseError.message(error));
            done.accept(false);
        });
    }

    // MARK: DB Table Selection

    private record Lists(List<TableSummary> tables, List<RoutineSummary> routines, List<String> databases) {}

    private void adoptLists(Lists l) {
        Map<String, Long> counted = new HashMap<>();
        for (TableSummary t : tables) if (t.exactRows != null) counted.put(t.oid, t.exactRows);
        for (TableSummary t : l.tables()) t.exactRows = counted.get(t.oid);
        tables = l.tables();
        routines = l.routines();
        if (l.databases() != null) {
            databases = new ArrayList<>(l.databases());
            if (connectedDatabase != null && !databases.contains(connectedDatabase)) {
                databases.add(connectedDatabase);
                databases.sort(null);
            }
        }
    }

    private static Lists lists(PgService svc, boolean withDatabases) throws DatabaseError {
        List<TableSummary> t = svc.listTables();
        List<RoutineSummary> r;
        try {
            r = svc.listRoutines();
        } catch (DatabaseError e) {
            r = new ArrayList<>();
        }
        List<String> d = null;
        if (withDatabases) {
            try {
                d = svc.databases();
            } catch (DatabaseError e) {
                d = null;
            }
        }
        return new Lists(t, r, d);
    }

    /** Lists the tables, views, functions and procedures again (and reloads what's on the right). */
    public void refreshTables(Runnable after) {
        PgService svc = service;
        if (svc == null) return;
        run(() -> lists(svc, false), l -> {
            adoptLists(l);
            if (after != null) after.run();
        }, null);
    }

    /** Loads the right panel: the highlighted table's \d description, or the function's code. */
    public void describeHighlighted() {
        PgService svc = service;
        CatalogEntry entry = highlightedEntry();
        if (svc == null || entry == null) {
            highlightedDescription = null;
            highlightedRoutine = null;
            changed();
            return;
        }
        String id = entry.id();
        if (entry.table() != null) {
            TableSummary summary = entry.table();
            run(() -> svc.describe(summary), d -> {
                if (!id.equals(highlightedID)) return;   // a slower load must not replace the entry clicked after it
                highlightedDescription = d;
                highlightedRoutine = null;
            }, null);
        } else {
            RoutineSummary r = entry.routine();
            run(() -> {
                String code = svc.routineCode(r);
                return code != null ? code : "-- " + r.displayName() + " no longer exists; Refresh lists the database's functions again.\n";
            }, code -> {
                if (!id.equals(highlightedID)) return;
                highlightedRoutine = new RoutineDefinition(r, code);
                highlightedDescription = null;
            }, null);
        }
    }

    /** Replaces the estimates in the table list with exact count(*) values, table by table. */
    public void countAllRows() {
        PgService svc = service;
        if (svc == null || countingRows) return;
        countingRows = true;
        changed();
        List<TableSummary> list = List.copyOf(tables);
        Background.execute(() -> {
            try {
                for (TableSummary t : list) {
                    if (t.isPlainView()) continue;   // a view would run its whole query; it is counted when opened
                    try {
                        long n = svc.exactRowCount(t.ref);
                        Ui.async(owner, () -> {
                            t.exactRows = n;
                            changed();
                        });
                    } catch (DatabaseError e) {
                        Ui.async(owner, () -> showError(t.ref.displayName() + ": " + e.getMessage()));
                        return;
                    }
                }
            } finally {
                Ui.async(owner, () -> {
                    countingRows = false;
                    changed();
                });
            }
        });
    }

    // MARK: DB Table

    private record Loaded(TableDescription description, GridResult grid, Lists lists) {}

    /**
     * Loads the table into the grid, one page of {@code limit} rows from {@code offset}; {@code resetSQL} puts the
     * table's default SELECT in the SQL box. {@code info} is shown after it loads.
     */
    public void openTable(TableRef ref, int limit, int offset, boolean resetSQL, GridQueryOptions options, String info) {
        PgService svc = service;
        if (svc == null) return;
        if (resetSQL && !confirmReplacingSQL()) return;
        GridQueryOptions o = options != null ? options : (resetSQL ? new GridQueryOptions() : gridOptions());
        boolean needList = tables.isEmpty();
        TableSummary known = table(ref);
        run(() -> {
            Lists l = needList ? lists(svc, false) : null;
            TableSummary summary = known;
            if (l != null) for (TableSummary t : l.tables()) if (t.ref.equals(ref)) summary = t;
            if (summary == null) throw new DatabaseError("Table " + ref.displayName() + " was not found.");
            TableDescription d = svc.describe(summary);
            return new Loaded(d, svc.fetchGrid(d, limit, offset, o), l);
        }, loaded -> {
            if (loaded.lists() != null) adoptLists(loaded.lists());
            setGrid(loaded.grid());
            openedTable = loaded.description();
            infoMessage = info;
            if (resetSQL) {
                sqlText = loaded.description().defaultSQL;
                sqlFile = null;
                sqlSavedText = null;
                sqlError = null;
            }
            form = Form.TABLE;
        }, null);
    }

    /** Loads the current page again. */
    public void reloadGrid(int limit) {
        if (grid == null) return;
        if (grid.customSQL != null) runSQL(grid.customSQL, limit, grid.offset, false, null);
        else if (grid.ref() != null) openTable(grid.ref(), limit, grid.offset, false, null, null);
    }

    /** Next or Previous: the neighbouring page of {@code limit} rows. */
    public void showPage(boolean next, int limit) {
        if (grid == null || !(next ? grid.hasNextPage() : grid.hasPreviousPage())) return;
        if (!edits.isEmpty()) {
            showError(pending() + " on this page. Apply with Update… or use Discard Edits before changing page.");
            return;
        }
        int offset = next ? grid.offset + grid.rows.size() : Math.max(0, grid.offset - limit);
        if (grid.customSQL != null) runSQL(grid.customSQL, limit, offset, false, null);
        else if (grid.ref() != null) openTable(grid.ref(), limit, offset, false, null, null);
    }

    /** New sorting / filters for the grid: reloads it from its first page. */
    public void applyGridOptions(GridQueryOptions options, int limit) {
        if (grid == null || options.equals(gridOptions())) return;
        if (!edits.isEmpty()) {
            showError(pending() + ". Apply with Update… or use Discard Edits before sorting or filtering.");
            return;
        }
        if (grid.customSQL != null) runSQL(grid.customSQL, limit, 0, false, options);
        else if (grid.ref() != null) openTable(grid.ref(), limit, 0, false, options, null);
    }

    private String pending() {
        return edits.size() + " pending edit" + (edits.size() == 1 ? "" : "s");
    }

    private void setGrid(GridResult data) {
        grid = data;
        edits.clear();
        gridVersion++;
        if (data.totalRows != null && data.ref() != null) {
            TableSummary t = table(data.ref());
            if (t != null) t.exactRows = data.unfilteredTotal != null ? data.unfilteredTotal : data.totalRows;
        }
    }

    /** Records an edit; editing a cell back to its loaded value removes the edit. */
    public void setCell(int row, int column, String value) {
        if (grid == null || row < 0 || row >= grid.rows.size() || column < 0 || column >= grid.columns.size()) return;
        CellKey key = new CellKey(row, column);
        String loaded = grid.rows.get(row)[column];
        if (java.util.Objects.equals(loaded, value)) edits.remove(key);
        else edits.put(key, new CellEdit(value));
    }

    /** The value a cell shows: its pending edit, else the loaded value. */
    public String displayed(int row, int column) {
        CellEdit e = edits.get(new CellKey(row, column));
        if (e != null) return e.value();
        return grid.rows.get(row)[column];
    }

    public void discardEdits() {
        edits.clear();
        gridVersion++;
        changed();
    }

    /** Writes the pending edits, then reloads the grid; {@code done} hears whether every row was updated. */
    public void applyUpdates(TableUpdate.Options options, int limit, Consumer<Boolean> done) {
        PgService svc = service;
        if (svc == null || grid == null || grid.ref() == null) return;
        TableRef table = grid.ref();
        List<TableUpdate.Planned> planned;
        try {
            planned = TableUpdate.plan(grid, Map.copyOf(edits), options);
        } catch (TableUpdate.PlanError e) {
            showError(e.getMessage());
            done.accept(false);
            return;
        }
        run(() -> svc.apply(planned, options.singleTransaction), outcome -> {
            edits.clear();
            String message = null;
            boolean ok = outcome.failures().isEmpty();
            if (ok) {
                message = "Updated " + outcome.applied() + " row" + (outcome.applied() == 1 ? "" : "s") + " in " + table.displayName() + ".";
            } else {
                showError("Updated " + outcome.applied() + " of " + planned.size() + " rows.\n\n" + String.join("\n", outcome.failures()));
            }
            reloadAfterUpdate(limit, message);
            done.accept(ok);
        }, error -> {
            showError(DatabaseError.message(error));
            done.accept(false);
        });
    }

    private void reloadAfterUpdate(int limit, String message) {
        if (grid == null) return;
        if (grid.customSQL != null) {
            runSQL(grid.customSQL, limit, grid.offset, false, null);
            infoMessage = message;
        } else if (grid.ref() != null) {
            openTable(grid.ref(), limit, grid.offset, false, null, message);
        }
    }

    // MARK: Run SQL

    private enum Outcome { CONFIRM, CALLED, DDL, EXECUTED, RESULT, ERROR }

    private record SqlOutcome(Outcome kind, String text, GridResult grid) {}

    /**
     * Pre-flights and runs {@code sqlOrNull} (null: the SQL box), then shows its rows in the grid. A statement that
     * changes data asks first ({@link #confirmWrite}) and then runs again with {@code confirmedWrite}.
     */
    public void runSQL(String sqlOrNull, int limit, int offset, boolean confirmedWrite, GridQueryOptions optionsOrNull) {
        GridQueryOptions options = optionsOrNull != null ? optionsOrNull
                : (sqlOrNull == null || confirmedWrite) ? new GridQueryOptions() : gridOptions();
        String sql = Sql.statementBody(sqlOrNull != null ? sqlOrNull : sqlText);
        sqlError = null;
        infoMessage = null;
        PgService svc = service;
        if (svc == null) {
            sqlError = "Not connected. Connect in the DB Connection form first.";
            changed();
            return;
        }
        String problem = Sql.localPreflight(sql);
        if (problem == null && !edits.isEmpty()) {
            problem = pending() + " in the grid. Apply with Update… or use Discard Edits before running SQL.";
        }
        if (problem == null && Sql.hasSeveralStatements(sql)) {
            problem = "ERROR:  Only one statement can be run at a time. Run each statement separately.";
        }
        if (problem != null) {
            sqlError = problem;
            changed();
            return;
        }
        if (openedTable != null && Sql.normalized(sql).equals(Sql.normalized(openedTable.defaultSQL))) {
            openTable(openedTable.summary.ref, limit, offset, false, options, null);
            return;
        }
        List<ColumnInfo> gridColumns = grid != null ? grid.columns : List.of();
        List<TableSummary> snapshot = List.copyOf(tables);
        runningSQL = true;
        run(() -> sqlWork(svc, sql, limit, offset, confirmedWrite, options, gridColumns, snapshot), outcome -> {
            runningSQL = false;
            switch (outcome.kind()) {
                case CONFIRM -> Ui.async(owner, () -> {
                    if (confirmWrite.test(outcome.text())) runSQL(outcome.text(), limit, 0, true, null);
                });
                case CALLED -> afterWrite(limit, "Procedure executed.");
                case EXECUTED -> afterWrite(limit, "Statement executed and committed.");
                case DDL -> afterSchemaChange(limit, outcome.text() + " statement completed.");
                case RESULT -> {
                    setGrid(outcome.grid());
                    form = Form.TABLE;
                    infoMessage = outcome.text();
                }
                case ERROR -> sqlError = outcome.text();
            }
        }, error -> {
            runningSQL = false;
            sqlError = Sql.describeError(DatabaseError.from(error), sql);
        });
    }

    private void afterWrite(int limit, String message) {
        infoMessage = message;
        if (openedTable != null) openTable(openedTable.summary.ref, limit, 0, false, null, message);
    }

    private static SqlOutcome sqlWork(PgService svc, String sql, int limit, int offset, boolean confirmedWrite,
                                      GridQueryOptions options, List<ColumnInfo> gridColumns, List<TableSummary> tables) {
        try {
            // PREPARE can't take a CALL, CREATE, ALTER or DROP: confirm, then run it as sent.
            if (Sql.isCall(sql) || Sql.isDDL(sql)) {
                if (!confirmedWrite) return new SqlOutcome(Outcome.CONFIRM, sql, null);
                svc.call(sql);
                return Sql.isCall(sql) ? new SqlOutcome(Outcome.CALLED, null, null)
                        : new SqlOutcome(Outcome.DDL, Sql.leadingKeyword(sql).toUpperCase(), null);
            }
            List<String> types = svc.preflight(sql);
            if (types.isEmpty()) {
                if (!confirmedWrite) return new SqlOutcome(Outcome.CONFIRM, sql, null);
                svc.execute(sql);
                return new SqlOutcome(Outcome.EXECUTED, null, null);
            }
            QueryResult result = null;
            List<String> rowIDs = null;
            try {
                // "SELECT * ..." runs with each row's ctid too, so a result with the table's full column layout can be
                // edited even when the table has no primary key. Where PostgreSQL rejects the ctid, it runs as written.
                String withRowID = confirmedWrite ? null : Sql.addingRowID(sql);
                if (withRowID != null) {
                    try {
                        QueryResult r = svc.runQuery(withRowID, limit, offset, false, options, gridColumns);
                        if (!r.columns().isEmpty() && r.columns().get(0).name().equals(Sql.ROW_ID_COLUMN)) {
                            rowIDs = new ArrayList<>();
                            List<String[]> rows = new ArrayList<>();
                            for (String[] row : r.rows()) {
                                rowIDs.add(row[0] == null ? "" : row[0]);
                                rows.add(java.util.Arrays.copyOfRange(row, 1, row.length));
                            }
                            result = new QueryResult(r.columns().subList(1, r.columns().size()), rows, r.hasMore());
                        }
                    } catch (DatabaseError | PgService.WriteNotAllowed ignored) {
                        // run as written below
                    }
                }
                if (result == null) result = svc.runQuery(sql, limit, offset, confirmedWrite, options, gridColumns);
            } catch (PgService.WriteNotAllowed w) {
                return new SqlOutcome(Outcome.CONFIRM, sql, null);
            }
            GridResult g = queryGrid(svc, result, sql, rowIDs, tables);
            g.offset = offset;
            g.isWriteResult = confirmedWrite;
            g.options = options;
            int n = result.rows().size();
            NumberFormat f = NumberFormat.getIntegerInstance();
            String info = "Query returned " + f.format(n) + " row" + (n == 1 ? "" : "s")
                    + (offset > 0 ? " from row " + f.format(offset + 1) : "")
                    + (result.hasMore() ? " (more on the next page)." : ".");
            return new SqlOutcome(Outcome.RESULT, info, g);
        } catch (DatabaseError e) {
            DatabaseError error = e;
            if (error.getMessage().contains("cannot insert multiple commands into a prepared statement")) {
                error = error.withMessage("Only one statement can be run at a time. Run each statement separately.");
            }
            return new SqlOutcome(Outcome.ERROR, Sql.describeError(error, sql), null);
        }
    }

    /**
     * The grid for a query result: editable when every column is a plain column of one table (not a view) and either
     * the table's primary key is among them, or the result has the table's full column layout and row IDs.
     */
    private static GridResult queryGrid(PgService svc, QueryResult result, String sql, List<String> rowIDs,
                                      List<TableSummary> tables) throws DatabaseError {
        GridResult g = new GridResult();
        g.title = "Query result";
        g.columns = new ArrayList<>();
        for (QueryColumn c : result.columns()) g.columns.add(new ColumnInfo(c.name(), c.type().toLowerCase()));
        g.rows = result.rows();
        g.hasMore = result.hasMore();
        g.customSQL = sql;
        Set<String> bases = new HashSet<>();
        for (QueryColumn c : result.columns()) {
            if (c.baseTable().isEmpty() || c.baseColumn().isEmpty()) return g;
            bases.add(c.baseSchema() + "\u0000" + c.baseTable());
        }
        if (bases.size() != 1) return g;
        QueryColumn any = result.columns().get(0);
        TableSummary summary = null;
        for (TableSummary t : tables) if (t.schema().equals(any.baseSchema()) && t.name().equals(any.baseTable())) summary = t;
        if (summary == null || summary.isView()) return g;
        TableDescription d = svc.describe(summary);
        List<ColumnInfo> columns = new ArrayList<>();
        for (QueryColumn c : result.columns()) {
            ColumnInfo info = d.column(c.baseColumn());
            if (info == null) return g;
            columns.add(info);
        }
        boolean hasKey = !d.primaryKey.isEmpty();
        for (String key : d.primaryKey) {
            boolean found = false;
            for (ColumnInfo c : columns) if (c.name().equals(key)) found = true;
            hasKey &= found;
        }
        boolean fullLayout = columns.size() == d.columns.size();
        for (int i = 0; fullLayout && i < columns.size(); i++) fullLayout = columns.get(i).attnum() == d.columns.get(i).attnum();
        if (fullLayout && rowIDs != null && rowIDs.size() == result.rows().size()) g.rowIDs = rowIDs;
        if (!hasKey && g.rowIDs.isEmpty()) return g;
        g.description = d;
        g.columns = columns;
        g.title = summary.ref.displayName() + " (query)";
        return g;
    }

    /**
     * After CREATE / ALTER / DROP: reloads the database list, the catalog list and the highlighted entry; reloads the
     * open table, or closes it when it no longer exists.
     */
    private void afterSchemaChange(int limit, String message) {
        PgService svc = service;
        if (svc == null) return;
        String highlighted = highlightedEntry() == null ? null : highlightedEntry().key();
        run(() -> lists(svc, true), l -> {
            adoptLists(l);
            highlightedID = entryID(highlighted);
            describeHighlighted();
            infoMessage = message;
            if (openedTable == null) return;
            TableRef ref = openedTable.summary.ref;
            if (table(ref) != null) {
                if (grid == null || grid.customSQL == null) openTable(ref, limit, 0, false, null, message);
            } else {
                openedTable = null;
                if (grid != null && (ref.equals(grid.ref()) || grid.customSQL == null)) {
                    grid = null;
                    edits.clear();
                    gridVersion++;
                }
            }
        }, null);
    }

    // MARK: Export CSV

    /** Saves the loaded rows (with pending edits), or all rows by running the grid's query again, read-only. */
    public void exportCSV(Path file, boolean allRows) {
        GridResult g = grid;
        if (g == null) return;
        PgService svc = service;
        Map<CellKey, CellEdit> pendingEdits = Map.copyOf(edits);
        run(() -> {
            if (allRows) {
                String sql = g.customSQL != null ? g.customSQL : g.description != null ? g.description.defaultSQL : null;
                if (sql == null) return 0L;
                if (svc == null) throw new DatabaseError("Not connected.");
                try {
                    return svc.exportCSV(sql, file, g.options, g.columns);
                } catch (PgService.WriteNotAllowed w) {
                    throw new DatabaseError("This statement changes data, so it is not run again. Export the loaded rows instead.");
                }
            }
            StringBuilder sb = new StringBuilder();
            List<String> names = new ArrayList<>();
            for (ColumnInfo c : g.columns) names.add(c.name());
            sb.append(TextFormats.csvLine(names));
            for (int r = 0; r < g.rows.size(); r++) {
                List<String> fields = new ArrayList<>();
                for (int c = 0; c < g.columns.size(); c++) {
                    CellEdit e = pendingEdits.get(new CellKey(r, c));
                    fields.add(e != null ? e.value() : g.rows.get(r)[c]);
                }
                sb.append(TextFormats.csvLine(fields));
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            return (long) g.rows.size();
        }, count -> infoMessage = "Exported " + NumberFormat.getIntegerInstance().format(count) + " row" + (count == 1 ? "" : "s")
                + " to " + file.getFileName() + ".",
                error -> showError("CSV export failed.\n\n" + DatabaseError.message(error)));
    }

    // MARK: SQL files

    public String sqlFileName() { return sqlFile == null ? null : sqlFile.getFileName().toString(); }

    public boolean sqlIsEdited() { return sqlFile != null && !sqlText.equals(sqlSavedText); }

    /** Before the SQL box is replaced: offers to save unsaved changes to its file. False cancels. */
    public boolean confirmReplacingSQL() {
        if (!sqlIsEdited()) return true;
        MessageDialog d = new MessageDialog(shell(), "MxDataSQL", null,
                "Save changes to " + sqlFileName() + "?\n\nYour changes to the SQL will be lost if you don't save them.",
                MessageDialog.QUESTION, 0, "Save", "Don't Save", "Cancel");
        return switch (d.open()) {
            case 0 -> saveSQL();
            case 1 -> true;
            default -> false;
        };
    }

    public void newSQL() {
        if (!confirmReplacingSQL()) return;
        sqlText = "";
        sqlFile = null;
        sqlSavedText = null;
        sqlError = null;
        changed();
    }

    public void openSQLFile(Path file) {
        if (!confirmReplacingSQL()) return;
        Path f = file != null ? file : SqlLibrary.openDialog(shell());
        if (f == null) return;
        try {
            String text = SqlLibrary.read(f);
            sqlText = text;
            sqlSavedText = text;
            sqlFile = f;
            sqlError = null;
            SqlLibrary.noteRecent(f);
            infoMessage = "Opened " + f.getFileName() + ".";
        } catch (IOException e) {
            showError("Could not open " + f.getFileName() + ".\n\n" + e.getMessage());
        }
        changed();
    }

    public boolean saveSQL() {
        return sqlFile == null ? saveSQLAs() : writeSQL(sqlFile);
    }

    public boolean saveSQLAs() {
        String suggested = sqlFileName() != null ? sqlFileName()
                : (grid != null && grid.ref() != null ? grid.ref().name() : "query") + ".sql";
        Path f = SqlLibrary.saveDialog(shell(), suggested);
        return f != null && writeSQL(f);
    }

    private boolean writeSQL(Path f) {
        try {
            SqlLibrary.write(sqlText, f);
            sqlFile = f;
            sqlSavedText = sqlText;
            SqlLibrary.noteRecent(f);
            infoMessage = "Saved " + f.getFileName() + ".";
            changed();
            return true;
        } catch (IOException e) {
            showError("Could not save " + f.getFileName() + ".\n\n" + e.getMessage());
            return false;
        }
    }

    public void revertSQL() {
        if (sqlFile == null) return;
        try {
            String text = SqlLibrary.read(sqlFile);
            sqlText = text;
            sqlSavedText = text;
        } catch (IOException e) {
            showError("Could not read " + sqlFile.getFileName() + ".\n\n" + e.getMessage());
        }
        changed();
    }
}
