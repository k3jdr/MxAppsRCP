package com.k3jdr.mxdatahub;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Db.DistinctValue;

/**
 * A column's filter, as Excel's (Shared/GridFilters' ColumnFilterPopover): tick the values to keep — the column's
 * values the other filters leave, with how many rows have each — or give a condition. The database does the work.
 */
final class FilterDialog extends Dialog {
    private static final int CLEAR = IDialogConstants.CLIENT_ID + 1;
    private final Hub hub;
    private final String column;
    private final GridOptions.Filter current;
    private final Callable<List<DistinctValue>> values;
    private Button byValues, byCondition;
    private Table list;
    private Text search;
    private Combo op;
    private Text value;
    private Label status;
    private List<DistinctValue> loaded = List.of();
    /** The filter chosen; null with {@link #cleared} for none. */
    GridOptions.Filter result;
    boolean cleared;

    FilterDialog(Shell parent, Hub hub, String column, GridOptions.Filter current, Callable<List<DistinctValue>> values) {
        super(parent);
        this.hub = hub;
        this.column = column;
        this.current = current;
        this.values = values;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Filter " + column);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(1, 12, 8));
        byValues = new Button(area, SWT.RADIO);
        byValues.setText("Show rows whose " + column + " is one of the values ticked");
        search = new Text(area, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        search.setMessage("Find a value");
        search.setLayoutData(Ui.fillH());
        list = new Table(area, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.V_SCROLL);
        list.setHeaderVisible(true);
        new TableColumn(list, SWT.LEFT).setText("Value");
        new TableColumn(list, SWT.RIGHT).setText("Rows");
        list.getColumn(0).setWidth(260);
        list.getColumn(1).setWidth(70);
        GridData lg = Ui.fill();
        lg.heightHint = 260;
        list.setLayoutData(lg);
        QuickHelp.help(list, "Values", "The column's values on every page that the search and the other filters leave, with how many rows have each. Tick the ones to show.");
        Composite allNone = new Composite(area, SWT.NONE);
        allNone.setLayout(Ui.grid(3, 0, 6));
        Ui.button(allNone, "Select All", () -> check(true));
        Ui.button(allNone, "Select None", () -> check(false));
        status = Ui.label(allNone, "Loading values…");
        status.setForeground(Ui.SECONDARY);

        byCondition = new Button(area, SWT.RADIO);
        byCondition.setText("Show rows whose " + column + " …");
        Composite cond = new Composite(area, SWT.NONE);
        cond.setLayout(Ui.grid(2, 0, 6));
        cond.setLayoutData(Ui.fillH());
        op = new Combo(cond, SWT.READ_ONLY);
        for (GridOptions.Op o : GridOptions.Op.values()) op.add(o.title);
        op.select(0);
        value = new Text(cond, SWT.BORDER);
        value.setLayoutData(Ui.fillH());
        op.addListener(SWT.Selection, e -> {
            value.setEnabled(GridOptions.Op.values()[op.getSelectionIndex()].takesValue());
            byCondition.setSelection(true);
            byValues.setSelection(false);
        });
        value.addListener(SWT.Modify, e -> {
            byCondition.setSelection(true);
            byValues.setSelection(false);
        });
        list.addListener(SWT.Selection, e -> {
            byValues.setSelection(true);
            byCondition.setSelection(false);
        });
        search.addListener(SWT.Modify, e -> showValues());

        if (current instanceof GridOptions.Condition c) {
            byCondition.setSelection(true);
            op.select(c.op().ordinal());
            value.setText(c.value() == null ? "" : c.value());
            value.setEnabled(c.op().takesValue());
        } else {
            byValues.setSelection(true);
        }
        hub.run(list, values, v -> {
            loaded = v;
            status.setText(v.size() >= 1000 ? "The first 1,000 values" : Fmt.count(v.size()) + " values");
            status.getParent().layout(true);
            showValues();
        });
        return area;
    }

    private Set<String> ticked() {
        Set<String> out = new HashSet<>();
        for (TableItem i : list.getItems()) if (i.getChecked()) out.add(key((DistinctValue) i.getData()));
        return out;
    }

    private static String key(DistinctValue v) { return v.value() == null ? "\u0000null" : v.value(); }

    private Set<String> initiallyTicked;

    private void showValues() {
        Set<String> keep = list.getItemCount() > 0 ? ticked() : null;
        if (keep == null) {
            keep = new HashSet<>();
            if (current instanceof GridOptions.Values v) {
                keep.addAll(v.values());
                if (v.includeNull()) keep.add("\u0000null");
            } else {
                for (DistinctValue d : loaded) keep.add(key(d));
            }
            initiallyTicked = keep;
        }
        String f = search.getText().trim().toLowerCase();
        list.removeAll();
        for (DistinctValue d : loaded) {
            String shown = d.value() == null ? "(empty)" : d.value();
            if (!f.isEmpty() && !shown.toLowerCase().contains(f)) continue;
            TableItem item = new TableItem(list, SWT.NONE);
            item.setText(new String[] { shown, Fmt.count(d.count()) });
            item.setData(d);
            item.setChecked(keep.contains(key(d)));
        }
    }

    private void check(boolean on) {
        for (TableItem i : list.getItems()) i.setChecked(on);
        byValues.setSelection(true);
        byCondition.setSelection(false);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, CLEAR, "Clear Filter", false);
        createButton(parent, IDialogConstants.OK_ID, "Filter", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void buttonPressed(int id) {
        if (id == CLEAR) {
            cleared = true;
            result = null;
            setReturnCode(OK);
            close();
            return;
        }
        if (id == IDialogConstants.OK_ID) {
            if (byCondition.getSelection()) {
                GridOptions.Op o = GridOptions.Op.values()[Math.max(0, op.getSelectionIndex())];
                result = new GridOptions.Condition(o, value.getText());
            } else {
                Set<String> keys = ticked();
                boolean all = list.getItemCount() == loaded.size() && keys.size() == loaded.size() && search.getText().isBlank();
                if (all && !(current instanceof GridOptions.Values)) {
                    cleared = true;
                    result = null;
                } else {
                    Set<String> vals = new HashSet<>();
                    boolean nul = false;
                    for (String k : keys) {
                        if (k.equals("\u0000null")) nul = true;
                        else vals.add(k);
                    }
                    result = new GridOptions.Values(vals, nul);
                }
            }
        }
        super.buttonPressed(id);
    }

    @Override
    protected Point getInitialSize() {
        Point p = super.getInitialSize();
        return new Point(Math.max(p.x, 420), Math.min(Math.max(p.y, 520), 700));
    }
}
