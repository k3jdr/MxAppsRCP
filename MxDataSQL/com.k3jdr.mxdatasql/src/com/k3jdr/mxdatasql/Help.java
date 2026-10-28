package com.k3jdr.mxdatasql;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxdatasql.Catalog.ColumnInfo;
import com.k3jdr.mxdatasql.Catalog.RoutineSummary;
import com.k3jdr.mxdatasql.Catalog.TableSummary;
import com.k3jdr.mxdatasql.GridResult.CellEdit;
import com.k3jdr.mxdatasql.GridResult.CellKey;

/** What Quick Help says in MxDataSQL (SQLQuickHelp.swift), with Windows keys. */
public final class Help {
    private Help() {}

    static String n(long v) { return NumberFormat.getIntegerInstance().format(v); }

    public static QuickHelpItem about(Session.Form form) {
        return switch (form) {
            case CONNECTION -> new QuickHelpItem("DB Connection",
                    "The PostgreSQL server this workspace works with: host, port, user, database, password and SSL mode. "
                    + "The fields are saved in the workspace file; the password isn't — Save password keeps it in secure storage. "
                    + "Connect (Enter) lists the tables in DB Table Selection; once connected, the Database drop-down above switches databases.");
            case TABLE_SELECTION -> new QuickHelpItem("DB Table Selection",
                    "The database's tables with their rows (~ means estimated from the planner's statistics, until Count Rows) and sizes, "
                    + "then its views, functions and procedures. Select a table or view for its \\d description on the right — columns, keys, indexes, "
                    + "references, a view's definition; double-click it or Open Table (Enter) to open it in DB Table, or in a window of its own with "
                    + "Open in New Window (views open read-only). Select a function or procedure for its code.");
            case TABLE -> new QuickHelpItem("DB Table",
                    "An SQL box over the rows in a grid. The box holds the statement that loaded the grid: change it and Run SQL (Ctrl+Enter); queries run read-only, "
                    + "statements that change data ask before they run. Edit cells in the grid, then Update… (Ctrl+Alt+U) writes them. "
                    + "Previous / Next page through the rows; View… (Ctrl+Alt+L) sets how many show; Export CSV… (Ctrl+Shift+E) saves them.");
        };
    }

    /** The window's screen: its form (or "Not Connected"). */
    public static QuickHelpItem screen(Session s) {
        if (s.form != Session.Form.CONNECTION && !s.isConnected()) {
            return new QuickHelpItem(s.form.label + ": Not Connected", "Connect in the DB Connection form first: "
                    + (s.form == Session.Form.TABLE ? "the grid shows" : "the list shows") + " the tables of the database you connect to.");
        }
        return about(s.form);
    }

    public static QuickHelpItem tableWindowScreen() {
        QuickHelpItem item = about(Session.Form.TABLE);
        return new QuickHelpItem(item.title(), item.detail()
                + "\nThis window has its own connection, so its transactions never mix with the workspace's.");
    }

    public static final QuickHelpItem TABLE_VIEW = new QuickHelpItem("Table View",
            "How many rows and columns the grid shows and how many rows are fetched per page. "
            + "Changing the fetch limit reloads the table and discards pending edits.", "Ctrl+Alt+L");

    public static final QuickHelpItem UPDATE = new QuickHelpItem("Update",
            "The pending edits, old and new values. Each row is found by its primary key, by its physical row ID (ctid, whole-table loads), "
            + "or by the loaded values of columns you choose. Options: only update rows whose edited columns still hold the loaded values, "
            + "write empty cells as NULL, and all rows in one transaction. Apply Updates writes them; Discard Edits drops them.", "Ctrl+Alt+U");

    public static final QuickHelpItem SQL_BOX = new QuickHelpItem("SQL Box",
            "SQL to run against the database, coloured as you type. Run SQL (Ctrl+Enter) runs it: a query fills the grid, read-only; INSERT, UPDATE, DELETE, "
            + "CREATE, ALTER, DROP and CALL ask before they run and commit. Right-click to open or save .sql files.", "Ctrl+Enter");

    public static final QuickHelpItem RESIZE_BAR = new QuickHelpItem("Resize Bar",
            "Drag to give the SQL box more or less room; double-click to reset it.");

    public static final QuickHelpItem TABLE_LIST = new QuickHelpItem("Tables, Views, Functions and Procedures",
            "The tables (partitioned ones too) by schema with their rows and size, then the views (materialized ones too), then the functions "
            + "and procedures with their arguments (not aggregates, nor the functions extensions install). Select a table or view for its "
            + "description, a function or procedure for its code; double-click a table or view to open it.");

    public static final QuickHelpItem DESCRIBE = new QuickHelpItem("Description",
            "The highlighted table as psql's \\d shows it: columns with types, collation, nullability and defaults, then indexes, checks, foreign keys and the tables that reference it. "
            + "For a view, its columns and its definition (psql's \\d+), coloured like the SQL box. Select text to copy it.");

    public static final QuickHelpItem ROUTINE_CODE = new QuickHelpItem("Code",
            "The highlighted function or procedure as psql's \\sf shows it: its CREATE OR REPLACE statement, coloured like the SQL box. "
            + "Select text to copy it; paste it in DB Table's SQL box to change the function.");

    public static final QuickHelpItem STATUS = new QuickHelpItem("Status",
            "The connection — user, server, database and PostgreSQL version — and the last action's message.");

    /** What's selected: the highlighted table, or the grid's table or query. */
    public static QuickHelpItem selection(Session s, DataGrid grid) {
        if (!s.isConnected()) return null;
        switch (s.form) {
            case TABLE_SELECTION -> {
                Catalog.CatalogEntry e = s.highlightedEntry();
                if (e == null) return null;
                if (e.routine() != null) {
                    RoutineSummary r = e.routine();
                    return new QuickHelpItem(r.displayName(), r.facts() + " Its code is on the right"
                            + (r.isProcedure() ? "; run it with CALL in DB Table's SQL box." : "."));
                }
                TableSummary t = e.table();
                String kind = Character.toUpperCase(t.kind.charAt(0)) + t.kind.substring(1);
                if (t.isView()) {
                    String rows = t.exactRows != null ? n(t.exactRows) + " rows"
                            : t.isPlainView() ? "rows not counted yet" : "about " + n(t.displayRows()) + " rows";
                    String size = t.totalSize.isEmpty() ? "" : ", " + t.totalSize;
                    return new QuickHelpItem(t.schema() + "." + t.name(), kind + ", " + rows + size
                            + ". Its columns and definition are on the right; double-click or Open View (Enter) opens its rows, read-only.");
                }
                String rows = (t.exactRows == null ? "about " : "") + n(t.displayRows()) + " rows";
                String key = s.highlightedDescription == null ? ""
                        : s.highlightedDescription.primaryKey.isEmpty() ? " No primary key."
                        : " Key: " + String.join(", ", s.highlightedDescription.primaryKey) + ".";
                return new QuickHelpItem(t.schema() + "." + t.name(), kind + ", " + rows + ", " + t.totalSize + "." + key
                        + " Its description is on the right; double-click or Open Table (Enter) opens it.");
            }
            case TABLE -> {
                return gridSelection(s, grid);
            }
            default -> {
                return null;
            }
        }
    }

    /** The selected cell or cells, else the grid. */
    public static QuickHelpItem gridSelection(Session s, DataGrid view) {
        GridResult g = s.grid;
        if (g == null) return null;
        if (view != null && view.selection() != null) {
            DataGrid.Selection sel = view.selection();
            if (!sel.anchor().equals(sel.cursor())) {
                long count = (long) sel.rowCount() * sel.columnCount();
                return new QuickHelpItem(n(count) + " cells selected",
                        "Ctrl+C copies them (Copy with Column Names from the right-click menu adds a header)"
                        + (g.isEditable() ? "; Ctrl+V pastes over them, Delete clears them as pending edits." : "."));
            }
            QuickHelpItem cell = cell(s, sel.cursor());
            if (cell != null) return cell;
        }
        List<String> facts = new ArrayList<>(List.of(n(g.rows.size()) + " rows loaded", g.columns.size() + " columns"));
        if (!s.edits.isEmpty()) facts.add(s.edits.size() + " pending edit" + (s.edits.size() == 1 ? "" : "s") + " — Update… writes them");
        return new QuickHelpItem(g.title, String.join(" · ", facts) + ".\n"
                + (g.isEditable() ? "Editable: double-click a cell or press Enter to change it."
                : g.description != null && g.description.summary.isView() ? "Read-only: a view's rows change with the tables it reads from."
                : "Read-only: query one table with SELECT * (any WHERE / ORDER BY), or include its primary key columns, to edit rows."));
    }

    static String columnFacts(ColumnInfo c) {
        List<String> facts = new ArrayList<>(List.of(c.type()));
        if (c.notNull()) facts.add("not null");
        if (!c.defaultExpr().isEmpty()) facts.add("default " + c.defaultExpr());
        if (!c.collation().isEmpty()) facts.add("collation " + c.collation());
        if (c.attnum() == 0) facts.add("computed by the query");
        return String.join(", ", facts);
    }

    static String preview(String value) {
        if (value == null) return "NULL";
        if (value.isEmpty()) return "empty";
        String line = value.replace('\n', ' ');
        return "“" + (line.length() > 80 ? line.substring(0, 80) + "…" : line) + "”";
    }

    /** A grid cell in words. */
    public static QuickHelpItem cell(Session s, CellKey key) {
        GridResult g = s.grid;
        if (g == null || key.column() < 0 || key.column() >= g.columns.size() || key.row() < 0 || key.row() >= g.rows.size()) return null;
        ColumnInfo c = g.columns.get(key.column());
        List<String> facts = new ArrayList<>(List.of("Row " + n(g.offset + key.row() + 1L), columnFacts(c)));
        CellEdit edit = s.edits.get(key);
        String loaded = g.rows.get(key.row())[key.column()];
        if (edit != null) facts.add("edited: " + preview(loaded) + " → " + preview(edit.value()) + " (Update… writes it)");
        else facts.add(preview(loaded));
        String how = g.isEditable()
                ? "Double-click or Enter to edit; right-click for View JSON…, Copy, Paste, Set to NULL and Revert."
                : "Read-only result: Ctrl+C copies; right-click for View JSON….";
        return new QuickHelpItem(c.name(), String.join(" · ", facts) + ".\n" + how);
    }

    public static QuickHelpItem rowNumber(Session s, int row) {
        long r = (s.grid == null ? 0 : s.grid.offset) + row + 1L;
        return new QuickHelpItem("Row " + n(r), "Click to select the whole row, shift-click to extend; Ctrl+C copies it as tab-separated values.");
    }

    public static final QuickHelpItem ROW_NUMBERS = new QuickHelpItem("Row Numbers", "Click a row number to select the row.");

    public static QuickHelpItem filterButton(ColumnInfo c) {
        return new QuickHelpItem("Sort & Filter " + c.name(),
                "Sort ascending or descending, or filter by values (a checklist with counts) or by a condition, as in Excel. "
                + "The database does the sorting and filtering, so it covers every row, not just this page.");
    }

    public static QuickHelpItem header(Session s, int column) {
        GridResult g = s.grid;
        ColumnInfo c = g.columns.get(column);
        StringBuilder d = new StringBuilder(columnFacts(c)).append('.');
        if (g.description != null && g.description.primaryKey.contains(c.name())) d.append(" Part of the primary key.");
        if (g.options.sort != null && g.options.sort.column().equals(c.name())) {
            d.append(" The rows are sorted by it, ").append(g.options.sort.ascending() ? "ascending" : "descending").append('.');
        }
        if (g.options.filters.containsKey(c.name())) d.append(" Filtered.");
        d.append("\nClick to select the column; drag its edge to resize it (the width is saved in the workspace).");
        return new QuickHelpItem(c.name(), d.toString());
    }
}
