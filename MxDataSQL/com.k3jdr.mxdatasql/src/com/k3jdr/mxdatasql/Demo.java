package com.k3jdr.mxdatasql;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.dbml.DiagramWindow;
import com.k3jdr.mxdatasql.Catalog.ColumnInfo;
import com.k3jdr.mxdatasql.Catalog.RoutineSummary;
import com.k3jdr.mxdatasql.Catalog.TableDescription;
import com.k3jdr.mxdatasql.Catalog.TableSummary;
import com.k3jdr.mxdatasql.GridFilters.GridSort;
import com.k3jdr.mxdatasql.GridFilters.ValuesFilter;
import com.k3jdr.mxdatasql.GridResult.CellEdit;
import com.k3jdr.mxdatasql.GridResult.CellKey;

/**
 * For checking the screens without a database (like the macOS app's DEBUG DiagramDemo): with the environment variable
 * MXDATASQL_DEMO set to {@code grid}, {@code tables} or {@code error}, the first workspace shows made-up rows, a made-up
 * table list, or a psql-style SQL error. Nothing is connected; the actions that need the database say so.
 */
final class Demo {
    private Demo() {}

    static void apply(Session s) {
        String mode = System.getenv("MXDATASQL_DEMO");
        if (mode == null || mode.isBlank()) return;
        s.status = Session.Status.CONNECTED;
        s.serverVersion = "17.6 (demo)";
        s.connectedDatabase = "demo";
        s.databases = new ArrayList<>(List.of("demo", "postgres"));
        Workspace.Connection c = new Workspace.Connection();
        c.database = "demo";
        s.connectedSettings = c;
        TableSummary customers = new TableSummary("16401", new TableRef("public", "customers"), "table", 5432, "1208 kB");
        s.tables = new ArrayList<>(List.of(customers,
                new TableSummary("16410", new TableRef("public", "orders"), "table", 120_000, "14 MB"),
                new TableSummary("16420", new TableRef("sales", "regions"), "partitioned table", 12, "48 kB"),
                new TableSummary("16430", new TableRef("public", "active_customers"), "view", 0, ""),
                new TableSummary("16440", new TableRef("public", "monthly_totals"), "materialized view", 36, "16 kB")));
        s.tables.get(1).exactRows = 119_874L;
        s.routines = new ArrayList<>(List.of(
                new RoutineSummary("17001", "public", "customer_balance", "function", "p_id integer", "numeric", "sql"),
                new RoutineSummary("17002", "public", "refresh_totals", "procedure", "", "", "plpgsql")));
        List<ColumnInfo> columns = List.of(
                new ColumnInfo("id", "integer", "", true, "nextval('customers_id_seq'::regclass)", 1),
                new ColumnInfo("name", "character varying(80)", "", true, "", 2),
                new ColumnInfo("balance", "numeric(10,2)", "", false, "0", 3),
                new ColumnInfo("settings", "jsonb", "", false, "", 4),
                new ColumnInfo("created", "timestamp with time zone", "", true, "now()", 5));
        TableDescription d = new TableDescription(customers, columns, List.of("id"),
                List.of("\"customers_pkey\" PRIMARY KEY, btree (id)", "\"customers_name_idx\" btree (lower(name::text))"),
                List.of("\"customers_balance_check\" CHECK (balance >= 0::numeric)"), List.of(),
                List.of("TABLE \"orders\" CONSTRAINT \"orders_customer_id_fkey\" FOREIGN KEY (customer_id) REFERENCES customers(id)"),
                "SELECT * FROM customers ORDER BY id", null);
        s.highlightedID = customers.id();
        s.highlightedDescription = d;
        if (mode.equals("tables")) {
            s.form = Session.Form.TABLE_SELECTION;
            return;
        }
        GridResult g = new GridResult();
        g.description = d;
        g.title = "public.customers";
        g.columns = columns;
        g.rows = new ArrayList<>();
        String[] names = { "Ada Lovelace", "Grace Hopper", "Alan Turing", "Edsger Dijkstra", "Barbara Liskov", "Donald Knuth" };
        for (int i = 0; i < 1000; i++) {
            g.rows.add(new String[] { Integer.toString(1001 + i), names[i % names.length] + " " + (i / names.length + 1),
                    i % 7 == 3 ? null : String.format("%d.%02d", (i * 37) % 5000, i % 100),
                    i % 5 == 0 ? "{\"theme\": \"dark\", \"tags\": [\"a\", \"b\"], \"limit\": " + i + "}" : null,
                    "2026-0" + (1 + i % 9) + "-1" + (i % 9) + " 10:" + (10 + i % 50) + ":00+01" });
            g.rowIDs.add("(" + (i / 50) + "," + (i % 50 + 1) + ")");
        }
        g.totalRows = 5432L;
        g.unfilteredTotal = 6000L;
        g.offset = 1000;
        g.customSQL = null;
        g.options.sort = new GridSort("name", true);
        Set<String> keep = new HashSet<>();
        keep.add("Ada Lovelace 1");
        g.options.filters.put("balance", new ValuesFilter(keep));
        s.grid = g;
        s.gridVersion++;
        s.edits.put(new CellKey(1, 1), new CellEdit("Grace B. Hopper"));
        s.edits.put(new CellKey(3, 2), new CellEdit(null));
        s.openedTable = d;
        s.sqlText = "SELECT * FROM customers\nWHERE balance > 20 -- big ones\nORDER BY id";
        s.infoMessage = "Demo rows: nothing is connected.";
        if (mode.equals("error")) {
            s.sqlText = "SELECT id, nam\nFROM customers";
            s.sqlError = Sql.describeError(new DatabaseError("column \"nam\" does not exist\nHint: Perhaps you meant to reference the column \"customers.name\".\n(SQLSTATE 42703)", "42703", 12), s.sqlText);
        }
        s.form = Session.Form.TABLE;
    }

    /**
     * With MXDATASQL_DEMO_SHOTS set to a folder (and MXDATASQL_DEMO=grid), opens the dialogs one after another without
     * blocking, prints each into a PNG there and closes it; with MXDATASQL_DEMO_DBML set to a .dbml file, also its
     * diagram window. A self-test for the screens no window screenshot reaches; it sends no input.
     */
    static void shots(WorkspaceWindow w, TableForm form) {
        String dir = System.getenv("MXDATASQL_DEMO_SHOTS");
        if (dir == null || dir.isBlank() || w.session.grid == null) return;
        Display d = w.shell().getDisplay();
        Session s = w.session;
        List<Runnable> steps = new ArrayList<>();
        steps.add(() -> {
            UpdateDialog u = new UpdateDialog(w.shell(), s, 1000);
            u.setBlockOnOpen(false);
            u.open();
            later(d, () -> shot(u.getShell(), dir, "update.png", u::close));
        });
        steps.add(() -> {
            JsonViewerDialog j = new JsonViewerDialog(w.shell(), "settings", "public.customers · row 1,001 · jsonb",
                    s.grid.rows.get(0)[3], v -> { });
            j.setBlockOnOpen(false);
            j.open();
            later(d, () -> shot(j.getShell(), dir, "json.png", j::close));
        });
        steps.add(() -> {
            ViewSettingsDialog v = new ViewSettingsDialog(w.shell(), w.doc.view, s.edits.size());
            v.setBlockOnOpen(false);
            v.open();
            later(d, () -> shot(v.getShell(), dir, "view.png", v::close));
        });
        steps.add(() -> {
            Rectangle r = w.shell().getBounds();
            FilterPopup f = new FilterPopup(w.shell(), s, 1, new Rectangle(r.x + 300, r.y + 300, 300, 28), o -> { });
            f.open();
            later(d, () -> shot(f.shell(), dir, "filter.png", () -> f.shell().dispose()));
        });
        String dbml = System.getenv("MXDATASQL_DEMO_DBML");
        if (dbml != null && !dbml.isBlank()) {
            steps.add(() -> {
                DiagramWindow dw = DiagramWindow.open(d, Path.of(dbml));
                if (dw != null) {
                    dw.shell().setSize(1300, 860);
                    later(d, () -> shot(dw.shell(), dir, "diagram.png", () -> dw.shell().dispose()));
                }
            });
        }
        for (int i = 0; i < steps.size(); i++) {
            Runnable step = steps.get(i);
            d.timerExec(3000 + i * 2500, () -> {
                if (!w.shell().isDisposed()) step.run();
            });
        }
    }

    private static void later(Display d, Runnable r) {
        d.timerExec(1500, r);
    }

    private static void shot(Shell shell, String dir, String name, Runnable close) {
        if (shell == null || shell.isDisposed()) return;
        Rectangle b = shell.getBounds();
        Image image = new Image(shell.getDisplay(), b.width, b.height);
        GC gc = new GC(image);
        try {
            shell.print(gc);
        } finally {
            gc.dispose();
        }
        ImageData data = image.getImageData();
        image.dispose();
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { data };
        loader.save(Path.of(dir, name).toString(), SWT.IMAGE_PNG);
        close.run();
    }

    static boolean unused(Dialog d) { return d == null; }
}
