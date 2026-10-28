package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatasql.Catalog.ColumnInfo;
import com.k3jdr.mxdatasql.GridFilters.ColumnFilter;
import com.k3jdr.mxdatasql.GridFilters.ConditionFilter;
import com.k3jdr.mxdatasql.GridFilters.DistinctValue;
import com.k3jdr.mxdatasql.GridFilters.FilterOperator;
import com.k3jdr.mxdatasql.GridFilters.GridQueryOptions;
import com.k3jdr.mxdatasql.GridFilters.GridSort;
import com.k3jdr.mxdatasql.GridFilters.ValuesFilter;

/**
 * The panel behind a column header's ▾ button (ColumnFilterPopover.swift), laid out like Excel's: sort ascending /
 * descending, then a filter by values (a checklist of the column's distinct values with counts and a search box) or by
 * a condition. It closes when it loses the focus.
 */
public final class FilterPopup {
    private static final int LIMIT = 1000;

    private final Shell shell;
    private final Session session;
    private final String name;
    private final String type;
    private final GridQueryOptions current;
    private final int pendingEdits;
    private final Consumer<GridQueryOptions> apply;
    private final Rectangle anchor;

    private final StackLayout modes = new StackLayout();
    private Composite valuesPane, conditionPane;
    private Button valuesMode, conditionMode, ok;
    private Text search, value1, value2;
    private Table list;
    private Label status, truncatedNote;
    private Combo op;
    private Label value2Label;
    private List<DistinctValue> values = new ArrayList<>();
    private final Set<String> checked = new HashSet<>();
    private boolean loading = true;
    private String loadError;
    private boolean done;

    public FilterPopup(Shell parent, Session session, int column, Rectangle header, Consumer<GridQueryOptions> apply) {
        this.session = session;
        GridResult g = session.grid;
        ColumnInfo c = g.columns.get(column);
        this.name = c.name();
        this.type = c.type();
        this.current = g.options.copy();
        this.pendingEdits = session.edits.size();
        this.apply = apply;
        this.anchor = header;
        shell = new Shell(parent, SWT.NO_TRIM | SWT.ON_TOP);
        shell.setLayout(Ui.grid(1, 1, 0));
        shell.setBackground(Ui.BORDER);
        build(g, column);
    }

    private GridSort sortedHere() { return current.sort != null && current.sort.column().equals(name) ? current.sort : null; }

    private ColumnFilter filteredHere() { return current.filters.get(name); }

    private void build(GridResult g, int column) {
        Composite root = new Composite(shell, SWT.NONE);
        root.setLayout(Ui.grid(1, 12, 6));
        root.setLayoutData(Ui.fill());
        Ui.label(root, name, Ui.bold());
        if (pendingEdits > 0) {
            Label warn = Ui.wrap(root, "⚠ Apply or discard " + pendingEdits + " pending edit" + (pendingEdits == 1 ? "" : "s")
                    + " before sorting or filtering.");
            warn.setForeground(Ui.ORANGE);
        }
        String[] labels = GridFilters.sortLabels(type);
        sortButton(root, "↑  " + labels[0], true);
        sortButton(root, "↓  " + labels[1], false);
        if (sortedHere() != null) {
            Button clear = flat(root, "✕  Clear Sort");
            clear.addListener(SWT.Selection, e -> {
                GridQueryOptions o = current.copy();
                o.sort = null;
                finish(o);
            });
        }
        separator(root);

        Composite modeRow = new Composite(root, SWT.NONE);
        modeRow.setLayout(Ui.grid(3, 0, 10));
        Ui.label(modeRow, "Filter by");
        valuesMode = new Button(modeRow, SWT.RADIO);
        valuesMode.setText("Values");
        conditionMode = new Button(modeRow, SWT.RADIO);
        conditionMode.setText("Condition");
        QuickHelp.help(modeRow, "Filter by", "Values: tick the values to keep, as in Excel. Condition: equals, contains, greater than, between, is empty and the like.");

        Composite panes = new Composite(root, SWT.NONE);
        panes.setLayout(modes);
        GridData pd = Ui.fill();
        pd.heightHint = 270;
        pd.widthHint = 300;
        panes.setLayoutData(pd);
        buildValues(panes);
        buildCondition(panes);
        valuesMode.addListener(SWT.Selection, e -> setMode(true));
        conditionMode.addListener(SWT.Selection, e -> setMode(false));

        separator(root);
        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(Ui.grid(4, 0, 6));
        buttons.setLayoutData(Ui.fillH());
        Button clearFilter = Ui.button(buttons, "Clear Filter", () -> {
            GridQueryOptions o = current.copy();
            o.filters.remove(name);
            finish(o);
        });
        clearFilter.setEnabled(filteredHere() != null && pendingEdits == 0);
        new Label(buttons, SWT.NONE).setLayoutData(Ui.fillH());
        Ui.button(buttons, "Cancel", this::close);
        ok = Ui.button(buttons, "OK", () -> {
            if (canApply()) finish(optionsWithFilter());
        });
        shell.setDefaultButton(ok);
        Ui.background(root, shell.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));

        if (filteredHere() instanceof ConditionFilter cf) {
            op.select(cf.op().ordinal());
            value1.setText(cf.value1());
            value2.setText(cf.value2());
            setMode(false);
        } else {
            setMode(true);
        }
        updateCondition();
        load(g);
    }

    private Button flat(Composite parent, String text) {
        Button b = new Button(parent, SWT.PUSH | SWT.FLAT);
        b.setText(text);
        b.setAlignment(SWT.LEFT);
        b.setLayoutData(Ui.fillH());
        return b;
    }

    private void sortButton(Composite parent, String text, boolean ascending) {
        Button b = flat(parent, text);
        if (sortedHere() != null && sortedHere().ascending() == ascending) b.setFont(Ui.bold());
        b.setEnabled(pendingEdits == 0);
        b.addListener(SWT.Selection, e -> {
            GridQueryOptions o = current.copy();
            o.sort = new GridSort(name, ascending);
            finish(o);
        });
        QuickHelp.help(b, ascending ? "Sort Ascending" : "Sort Descending",
                "Sorts every row of the table or query by " + name + " on the server, then shows the first page.");
    }

    private static void separator(Composite parent) {
        new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());
    }

    private void buildValues(Composite parent) {
        valuesPane = new Composite(parent, SWT.NONE);
        valuesPane.setLayout(Ui.grid(1, 0, 4));
        search = new Text(valuesPane, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
        search.setMessage("Search");
        search.setLayoutData(Ui.fillH());
        search.addListener(SWT.Modify, e -> fillList());
        QuickHelp.help(search, "Search", "Shows only the values that contain this text; OK then keeps the ticked ones among them.");
        status = new Label(valuesPane, SWT.WRAP);
        status.setLayoutData(Ui.fillH());
        list = new Table(valuesPane, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION);
        list.setLayoutData(Ui.fill());
        new TableColumn(list, SWT.NONE).setWidth(200);
        TableColumn count = new TableColumn(list, SWT.RIGHT);
        count.setWidth(70);
        list.addListener(SWT.Selection, e -> {
            if (e.detail != SWT.CHECK || !(e.item instanceof TableItem item)) return;
            Object data = item.getData();
            if (data == SELECT_ALL) {
                for (DistinctValue v : visible()) {
                    if (item.getChecked()) checked.add(v.value()); else checked.remove(v.value());
                }
                fillList();
            } else if (data instanceof DistinctValue v) {
                if (item.getChecked()) checked.add(v.value()); else checked.remove(v.value());
                list.getItem(0).setChecked(allVisibleChecked());
            }
            updateOk();
        });
        QuickHelp.help(list, "Values", "The column's distinct values with their row counts, honouring the other columns' filters (the first "
                + Help.n(LIMIT) + "). Untick the values to hide; (Select All) ticks or unticks every value shown.");
        truncatedNote = new Label(valuesPane, SWT.WRAP);
        truncatedNote.setFont(Ui.small());
        truncatedNote.setForeground(Ui.SECONDARY);
        truncatedNote.setLayoutData(Ui.fillH());
    }

    private static final Object SELECT_ALL = new Object();

    private void buildCondition(Composite parent) {
        conditionPane = new Composite(parent, SWT.NONE);
        conditionPane.setLayout(Ui.grid(1, 0, 6));
        Ui.label(conditionPane, "Show rows where " + name);
        op = new Combo(conditionPane, SWT.READ_ONLY);
        for (FilterOperator o : FilterOperator.values()) op.add(o.label);
        op.select(FilterOperator.CONTAINS.ordinal());
        op.setLayoutData(Ui.fillH());
        op.addListener(SWT.Selection, e -> updateCondition());
        value1 = new Text(conditionPane, SWT.BORDER);
        value1.setMessage("Value");
        value1.setLayoutData(Ui.fillH());
        value1.addListener(SWT.Modify, e -> updateOk());
        value2Label = Ui.label(conditionPane, "and");
        value2 = new Text(conditionPane, SWT.BORDER);
        value2.setMessage("and");
        value2.setLayoutData(Ui.fillH());
        value2.addListener(SWT.Modify, e -> updateOk());
        Label hint = Ui.wrap(conditionPane, "Text conditions ignore case. Greater, less and between compare as " + type + ".");
        hint.setFont(Ui.small());
        hint.setForeground(Ui.SECONDARY);
        QuickHelp.help(op, "Condition", "Text conditions ignore case. Greater, less and between compare as the column's type (numbers as numbers, dates as dates).");
    }

    private FilterOperator operator() { return FilterOperator.values()[Math.max(0, op.getSelectionIndex())]; }

    private void updateCondition() {
        FilterOperator o = operator();
        Ui.setVisible(value1, o.needsValue());
        Ui.setVisible(value2, o.needsSecondValue());
        Ui.setVisible(value2Label, o.needsSecondValue());
        conditionPane.layout(true, true);
        updateOk();
    }

    private void setMode(boolean values) {
        valuesMode.setSelection(values);
        conditionMode.setSelection(!values);
        modes.topControl = values ? valuesPane : conditionPane;
        valuesPane.getParent().layout(true, true);
        updateOk();
    }

    private void load(GridResult g) {
        PgService svc = session.service();
        if (svc == null) {
            loading = false;
            loadError = "Not connected.";
            fillList();
            return;
        }
        GridQueryOptions options = current.copy();
        Background.run(shell, () -> svc.distinctValues(g, name, options, LIMIT), result -> {
            values = result.values();
            loading = false;
            if (filteredHere() instanceof ValuesFilter vf) checked.addAll(vf.values());
            else for (DistinctValue v : values) checked.add(v.value());
            truncatedNote.setText(result.truncated() ? "Showing the first " + Help.n(values.size()) + " values. Use Condition for others." : "");
            fillList();
            updateOk();
        }, error -> {
            loading = false;
            loadError = DatabaseError.message(error);
            fillList();
            updateOk();
        });
        fillList();
    }

    private List<DistinctValue> visible() {
        String s = search.getText().strip().toLowerCase();
        if (s.isEmpty()) return values;
        List<DistinctValue> out = new ArrayList<>();
        for (DistinctValue v : values) if ((v.value() == null ? "(null)" : v.value().toLowerCase()).contains(s)) out.add(v);
        return out;
    }

    private boolean allVisibleChecked() {
        for (DistinctValue v : visible()) if (!checked.contains(v.value())) return false;
        return true;
    }

    private void fillList() {
        if (list.isDisposed()) return;
        status.setText(loading ? "Loading values…" : loadError != null ? loadError : "");
        status.setForeground(loadError != null ? Ui.RED : Ui.SECONDARY);
        Ui.setVisible(status, loading || loadError != null);
        list.setRedraw(false);
        list.removeAll();
        if (!loading && loadError == null) {
            TableItem all = new TableItem(list, SWT.NONE);
            all.setText("(Select All)");
            all.setData(SELECT_ALL);
            all.setChecked(allVisibleChecked());
            for (DistinctValue v : visible()) {
                TableItem item = new TableItem(list, SWT.NONE);
                item.setText(0, v.value() == null ? "(NULL)" : v.value().replace('\n', ' '));
                item.setText(1, Help.n(v.count()));
                item.setForeground(1, Ui.SECONDARY);
                if (v.value() == null) item.setForeground(0, Ui.SECONDARY);
                item.setData(v);
                item.setChecked(checked.contains(v.value()));
            }
        }
        list.setRedraw(true);
        valuesPane.layout(true, true);
    }

    private boolean canApply() {
        if (pendingEdits > 0) return false;
        if (valuesMode.getSelection()) return !loading && loadError == null;
        FilterOperator o = operator();
        return (!o.needsValue() || !value1.getText().isEmpty()) && (!o.needsSecondValue() || !value2.getText().isEmpty());
    }

    private void updateOk() {
        if (ok != null && !ok.isDisposed()) ok.setEnabled(canApply());
    }

    /** The options with this column's filter as set here (no filter when every value is ticked). */
    private GridQueryOptions optionsWithFilter() {
        GridQueryOptions o = current.copy();
        if (valuesMode.getSelection()) {
            boolean searching = !search.getText().isBlank();
            Set<String> keep = new HashSet<>();
            if (searching) {
                for (DistinctValue v : visible()) if (checked.contains(v.value())) keep.add(v.value());
            } else {
                keep.addAll(checked);
            }
            Set<String> all = new HashSet<>();
            for (DistinctValue v : values) all.add(v.value());
            if (!searching && keep.containsAll(all)) o.filters.remove(name);
            else o.filters.put(name, new ValuesFilter(keep));
        } else {
            FilterOperator f = operator();
            o.filters.put(name, new ConditionFilter(f, value1.getText(), f.needsSecondValue() ? value2.getText() : ""));
        }
        return o;
    }

    private void finish(GridQueryOptions options) {
        close();
        if (!options.equals(current)) apply.accept(options);
    }

    private void close() {
        done = true;
        if (!shell.isDisposed()) shell.dispose();
    }

    public Shell shell() { return shell; }

    public void open() {
        shell.pack();
        Point size = shell.getSize();
        Rectangle screen = shell.getDisplay().getClientArea();
        int x = Math.max(screen.x, Math.min(anchor.x + anchor.width - size.x, screen.x + screen.width - size.x));
        int y = anchor.y;
        if (y + size.y > screen.y + screen.height) y = Math.max(screen.y, anchor.y - anchor.height - size.y);
        shell.setLocation(x, y);
        shell.addListener(SWT.Deactivate, e -> shell.getDisplay().asyncExec(() -> {
            if (!done && !shell.isDisposed() && shell.getDisplay().getActiveShell() != shell) close();
        }));
        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) close();
        });
        shell.open();
        if (valuesMode.getSelection()) search.setFocus(); else value1.setFocus();
    }
}
