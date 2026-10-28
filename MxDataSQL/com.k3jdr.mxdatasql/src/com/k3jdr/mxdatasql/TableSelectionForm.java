package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatasql.Catalog.CatalogEntry;
import com.k3jdr.mxdatasql.Catalog.CatalogKind;
import com.k3jdr.mxdatasql.Catalog.TableSummary;

/**
 * Form 2 — DB Table Selection (TableSelectionForm.swift): tables and views with row counts and sizes, then functions
 * and procedures; on the right the highlighted table's psql {@code \d} description or the routine's code.
 */
public final class TableSelectionForm {
    private final Session session;
    private final Consumer<TableRef> onOpen;
    private final Composite root;
    private final StackLayout stack = new StackLayout();
    private final Composite notConnected;
    private final SplitPanes split;
    private final Combo kind;
    private final Text filter;
    private final Button refresh, countRows, newWindow, open;
    private final Table table;
    private final Label count;
    private final StyledText describe;
    private List<CatalogEntry> shown = new ArrayList<>();

    public TableSelectionForm(Composite parent, Session session, Consumer<TableRef> onOpen) {
        this.session = session;
        this.onOpen = onOpen;
        root = new Composite(parent, SWT.NONE);
        root.setLayout(stack);
        notConnected = new Composite(root, SWT.NONE);
        notConnected.setLayout(Ui.grid(1, 40, 8));
        new Label(notConnected, SWT.NONE).setLayoutData(new GridData(SWT.CENTER, SWT.FILL, true, true));
        Label h = Ui.label(notConnected, "Not Connected", Ui.subheading());
        h.setForeground(Ui.SECONDARY);
        h.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Label t = Ui.label(notConnected, "Connect in the DB Connection form to list tables, views, functions and procedures.");
        t.setForeground(Ui.SECONDARY);
        t.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        new Label(notConnected, SWT.NONE).setLayoutData(new GridData(SWT.CENTER, SWT.FILL, true, true));

        split = new SplitPanes(root, "mxdatasql.tableSelection.v2", 360, 1100, 600, 320);
        Composite left = split.leading();
        left.setLayout(Ui.grid(1, 10, 8));
        Composite top = new Composite(left, SWT.NONE);
        top.setLayout(Ui.grid(4, 0, 6));
        top.setLayoutData(Ui.fillH());
        kind = new Combo(top, SWT.READ_ONLY);
        for (CatalogKind k : CatalogKind.values()) kind.add(k.label);
        kind.select(0);
        kind.addListener(SWT.Selection, e -> {
            session.catalogKind = CatalogKind.values()[kind.getSelectionIndex()];
            session.changed();
        });
        QuickHelp.help(kind, "Show", "Lists the tables, views, functions and procedures, or only one of them.");
        filter = new Text(top, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
        filter.setMessage("Filter");
        filter.setLayoutData(Ui.fillH());
        filter.addListener(SWT.Modify, e -> {
            session.tableFilter = filter.getText();
            session.changed();
        });
        QuickHelp.help(filter, "Filter", "Shows only the tables, views, functions and procedures whose schema or name contains this text.");
        refresh = Ui.button(top, "↻ Refresh", () -> session.refreshTables(session::describeHighlighted));
        QuickHelp.help(refresh, "Refresh", "Lists the tables, views, functions and procedures again, with fresh row estimates and sizes, and reloads what's on the right.");
        countRows = Ui.button(top, "Count Rows", session::countAllRows);
        countRows.setToolTipText("Replace the estimated row counts with exact count(*) values");
        QuickHelp.help(countRows, "Count Rows", "Replaces the estimated row counts (~) with exact count(*) values. On big tables this takes a while. "
                + "Views aren't counted here (each would run its whole query); opening one counts its rows.");

        table = new Table(left, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(Ui.fill());
        column("Schema", 90, SWT.LEFT);
        column("Name", 180, SWT.LEFT);
        column("Type", 105, SWT.LEFT);
        column("Rows", 85, SWT.RIGHT);
        column("Size", 75, SWT.LEFT);
        table.addListener(SWT.Selection, e -> {
            int i = table.getSelectionIndex();
            if (i < 0 || i >= shown.size()) return;
            String id = shown.get(i).id();
            if (id.equals(session.highlightedID)) return;
            session.highlightedID = id;
            session.describeHighlighted();
        });
        table.addListener(SWT.DefaultSelection, e -> openHighlighted());
        TableCopy.addMenuItems(table);
        QuickHelp.provider(table, (c, p) -> {
            TableItem item = table.getItem(p);
            if (item != null && item.getData() instanceof CatalogEntry entry && entry.routine() != null) {
                return new QuickHelpItem(entry.routine().displayName(), entry.routine().facts() + "\n" + Help.TABLE_LIST.detail());
            }
            return Help.TABLE_LIST;
        });

        Composite bottom = new Composite(left, SWT.NONE);
        bottom.setLayout(Ui.grid(3, 0, 8));
        bottom.setLayoutData(Ui.fillH());
        count = new Label(bottom, SWT.NONE);
        count.setForeground(Ui.SECONDARY);
        count.setLayoutData(Ui.fillH());
        newWindow = Ui.check(bottom, "Open in New Window", false, v -> session.openInNewWindow = v);
        newWindow.setToolTipText("Open the table in its own window, with its own connection");
        QuickHelp.help(newWindow, "Open in New Window", "Opens tables in a window of their own, with their own connection, instead of in this workspace's DB Table form.");
        open = Ui.button(bottom, "Open Table", this::openHighlighted);
        open.setLayoutData(new GridData(110, SWT.DEFAULT));

        Composite right = split.trailing();
        right.setLayout(Ui.grid(1, 0, 0));
        describe = new StyledText(right, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        describe.setFont(Ui.mono());
        describe.setMargins(12, 12, 12, 12);
        describe.setCaret(null);
        describe.setLayoutData(Ui.fill());
        QuickHelp.provider(describe, (c, p) -> session.highlightedRoutine != null ? Help.ROUTINE_CODE : Help.DESCRIBE);
        stack.topControl = notConnected;
    }

    private void column(String text, int width, int align) {
        TableColumn c = new TableColumn(table, align);
        c.setText(text);
        c.setWidth(width);
    }

    public Composite control() { return root; }
    public Button defaultButton() { return open; }

    private void openHighlighted() {
        CatalogEntry e = session.highlightedEntry();
        if (e != null && e.table() != null) onOpen.accept(e.table().ref);
    }

    public void refresh() {
        if (root.isDisposed()) return;
        Composite top = session.isConnected() ? split : notConnected;
        if (stack.topControl != top) {
            stack.topControl = top;
            root.layout();
        }
        if (!session.isConnected()) return;
        if (kind.getSelectionIndex() != session.catalogKind.ordinal()) kind.select(session.catalogKind.ordinal());
        if (!filter.getText().equals(session.tableFilter)) filter.setText(session.tableFilter);
        newWindow.setSelection(session.openInNewWindow);
        countRows.setEnabled(!session.countingRows);
        fillTable();
        CatalogEntry h = session.highlightedEntry();
        boolean isView = h != null && h.table() != null && h.table().isView();
        open.setText(isView ? "Open View" : "Open Table");
        open.setEnabled(h != null && h.table() != null);
        QuickHelp.help(open, open.getText(), "Opens the selected table's rows in DB Table (or its own window), sorted by its primary key. "
                + "A view's rows open read-only, in the view's own order.", "Enter");
        count.setText(countText());
        fillDescription();
    }

    private void fillTable() {
        List<CatalogEntry> entries = session.filteredEntries();
        table.setRedraw(false);
        try {
            if (entries.size() != table.getItemCount()) {
                table.removeAll();
                for (int i = 0; i < entries.size(); i++) new TableItem(table, SWT.NONE);
            }
            int selected = -1;
            for (int i = 0; i < entries.size(); i++) {
                CatalogEntry e = entries.get(i);
                TableItem item = table.getItem(i);
                item.setData(e);
                String rows = "";
                if (e.table() != null) {
                    TableSummary t = e.table();
                    if (!(t.isPlainView() && t.exactRows == null)) rows = (t.exactRows == null ? "~" : "") + Help.n(t.displayRows());
                }
                item.setText(new String[] { e.schema(), e.name(), e.kind(), rows, e.table() != null ? e.table().totalSize : "" });
                item.setForeground(2, e.table() == null ? Ui.SECONDARY : null);
                if (e.id().equals(session.highlightedID)) selected = i;
            }
            if (selected >= 0) {
                if (table.getSelectionIndex() != selected) {
                    table.setSelection(selected);
                    table.showSelection();
                }
            } else {
                table.deselectAll();
            }
        } finally {
            table.setRedraw(true);
        }
        shown = entries;
    }

    /** "56 tables, 7 views, 29 functions, 2 procedures". */
    private String countText() {
        int tables = 0, views = 0, procedures = 0, functions = 0;
        for (CatalogEntry e : shown) {
            if (e.table() != null) {
                if (e.table().isView()) views++; else tables++;
            } else if (e.routine().isProcedure()) procedures++;
            else functions++;
        }
        List<String> parts = new ArrayList<>();
        add(parts, tables, "table");
        add(parts, views, "view");
        add(parts, functions, "function");
        add(parts, procedures, "procedure");
        return parts.isEmpty() ? "Nothing listed" : String.join(", ", parts);
    }

    private static void add(List<String> parts, int n, String word) {
        if (n > 0) parts.add(n + " " + word + (n == 1 ? "" : "s"));
    }

    private String describedKey;

    private void fillDescription() {
        String text;
        List<StyleRange> styles = new ArrayList<>();
        if (session.highlightedRoutine != null) {
            Catalog.RoutineDefinition r = session.highlightedRoutine;
            text = r.code();
            boolean sqlBody = r.summary().language().equals("sql") || r.summary().language().equals("plpgsql");
            styles.addAll(SqlHighlighter.styles(text, 0, sqlBody));
        } else if (session.highlightedDescription != null) {
            Catalog.TableDescription d = session.highlightedDescription;
            text = d.psqlSummary();
            if (d.viewDefinition != null) {
                String head = text + "View definition:\n";
                styles.addAll(SqlHighlighter.styles(d.viewDefinition, head.length(), false));
                text = head + d.viewDefinition;
            }
        } else {
            text = "Select a table or view to see its description, or a function or procedure to see its code.";
            styles.add(new StyleRange(0, text.length(), Ui.SECONDARY, null));
        }
        String key = text.hashCode() + ":" + text.length();
        if (key.equals(describedKey)) return;
        describedKey = key;
        describe.setText(text);
        describe.setStyleRanges(styles.toArray(StyleRange[]::new));
        describe.setTopIndex(0);
        describe.setHorizontalIndex(0);
    }
}
