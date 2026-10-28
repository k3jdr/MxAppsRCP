package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatasql.GridResult.CellEdit;
import com.k3jdr.mxdatasql.GridResult.CellKey;
import com.k3jdr.mxdatasql.TableUpdate.RowKeyMode;

/**
 * Table ▸ Update… (UpdateDialog.swift): the changed cells, how each row is identified, the options, a preview of the
 * SQL, then Apply Updates.
 */
public final class UpdateDialog extends Dialog {
    private static final int DISCARD = IDialogConstants.CLIENT_ID + 1;
    private final Session session;
    private final int fetchLimit;
    private final TableUpdate.Options options = new TableUpdate.Options();
    private StyledText preview;
    private Composite matchRow;
    private ScrolledComposite matchScroll;
    private Table changes;

    public UpdateDialog(Shell parent, Session session, int fetchLimit) {
        super(parent);
        this.session = session;
        this.fetchLimit = fetchLimit;
        setShellStyle(getShellStyle() | SWT.RESIZE);
        GridResult g = session.grid;
        if (g != null && g.description != null && g.description.primaryKey.isEmpty()) {
            options.keyMode = g.rowIDs.isEmpty() ? RowKeyMode.COLUMNS : RowKeyMode.ROW_ID;
        }
    }

    private boolean hasEdits() { return session.grid != null && !session.edits.isEmpty(); }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Update");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(1, 16, 10));
        QuickHelp.help(area, Help.UPDATE.title(), Help.UPDATE.detail(), Help.UPDATE.shortcut());
        GridResult g = session.grid;
        if (!hasEdits()) {
            Ui.label(area, "Update Table", Ui.bold());
            Ui.wrap(area, "There are no pending edits. Edit cells in the DB Table grid, then choose Table ▸ Update.").setForeground(Ui.SECONDARY);
            return area;
        }
        Ui.label(area, "Update " + (g.ref() != null ? g.ref().displayName() : g.title), Ui.bold());

        int n = session.edits.size();
        Group changed = group(area, n + " changed cell" + (n == 1 ? "" : "s"));
        changes = new Table(changed, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        changes.setHeaderVisible(true);
        GridData td = Ui.fill();
        td.heightHint = 120;
        changes.setLayoutData(td);
        for (String t : new String[] { "Row", "Column", "Old value", "New value" }) {
            TableColumn c = new TableColumn(changes, SWT.NONE);
            c.setText(t);
            c.setWidth(t.equals("Row") ? 60 : 150);
        }
        TableCopy.addMenuItems(changes);
        QuickHelp.help(changes, "Changed Cells", "Every pending edit: its row, column, the value loaded from the table and the new value.");

        Group key = group(area, "Identify each row by");
        boolean noPk = g.description == null || g.description.primaryKey.isEmpty();
        for (RowKeyMode mode : RowKeyMode.values()) {
            if (mode == RowKeyMode.ROW_ID && g.rowIDs.isEmpty()) continue;
            String label = switch (mode) {
                case PRIMARY_KEY -> noPk ? "Primary key (none on this table)" : "Primary key (" + String.join(", ", g.description.primaryKey) + ")";
                case ROW_ID -> "Physical row ID (ctid, as loaded)";
                case COLUMNS -> "Loaded values of selected columns";
            };
            Button b = new Button(key, SWT.RADIO);
            b.setText(Ui.escape(label));
            b.setSelection(options.keyMode == mode);
            b.addListener(SWT.Selection, e -> {
                if (!b.getSelection()) return;
                options.keyMode = mode;
                Ui.setVisible(matchScroll, mode == RowKeyMode.COLUMNS);
                getShell().layout(true, true);
                updatePreview();
            });
        }
        matchScroll = new ScrolledComposite(key, SWT.H_SCROLL);
        matchScroll.setLayoutData(Ui.fillH());
        matchRow = new Composite(matchScroll, SWT.NONE);
        matchRow.setLayout(new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL));
        for (Catalog.ColumnInfo c : g.columns) {
            Button b = new Button(matchRow, SWT.CHECK);
            b.setText(Ui.escape(c.name()));
            b.addListener(SWT.Selection, e -> {
                if (b.getSelection()) options.matchColumns.add(c.name()); else options.matchColumns.remove(c.name());
                updatePreview();
            });
        }
        matchScroll.setContent(matchRow);
        Point size = matchRow.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        matchRow.setSize(size);
        ((GridData) matchScroll.getLayoutData()).heightHint = size.y + 18;
        Ui.setVisible(matchScroll, options.keyMode == RowKeyMode.COLUMNS);

        Group opts = group(area, "Options");
        Ui.check(opts, "Only update rows whose edited columns still have the loaded values", options.checkOriginalValues, v -> {
            options.checkOriginalValues = v;
            updatePreview();
        });
        Ui.check(opts, "Write empty cells as NULL", options.emptyAsNull, v -> {
            options.emptyAsNull = v;
            fillChanges();
            updatePreview();
        });
        Ui.check(opts, "All rows in one transaction (all or nothing)", options.singleTransaction, v -> options.singleTransaction = v);

        Group sqlGroup = group(area, "SQL");
        preview = new StyledText(sqlGroup, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        preview.setFont(Ui.mono());
        GridData pd = Ui.fill();
        pd.heightHint = 110;
        preview.setLayoutData(pd);
        QuickHelp.help(preview, "SQL", "The UPDATE statements Apply Updates runs, with their parameter values in a comment. Select text to copy it.");
        fillChanges();
        updatePreview();
        return area;
    }

    private static Group group(Composite parent, String text) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(Ui.escape(text));
        g.setLayout(Ui.grid(1, 8, 4));
        g.setLayoutData(Ui.fillH());
        return g;
    }

    private void fillChanges() {
        if (changes == null) return;
        changes.removeAll();
        GridResult g = session.grid;
        List<Map.Entry<CellKey, CellEdit>> sorted = new ArrayList<>(session.edits.entrySet());
        sorted.sort((a, b) -> a.getKey().row() != b.getKey().row() ? Integer.compare(a.getKey().row(), b.getKey().row())
                : Integer.compare(a.getKey().column(), b.getKey().column()));
        for (var e : sorted) {
            TableItem item = new TableItem(changes, SWT.NONE);
            String old = g.rows.get(e.getKey().row())[e.getKey().column()];
            item.setText(new String[] { Help.n(g.offset + e.getKey().row() + 1L), g.columns.get(e.getKey().column()).name(),
                    old == null ? "NULL" : old, displayNew(e.getValue().value()) });
            item.setForeground(2, Ui.SECONDARY);
        }
    }

    private String displayNew(String value) {
        if (value == null) return "NULL";
        if (value.isEmpty()) return options.emptyAsNull ? "NULL" : "''";
        return value;
    }

    private String planError() {
        try {
            TableUpdate.plan(session.grid, Map.copyOf(session.edits), options);
            return null;
        } catch (TableUpdate.PlanError e) {
            return e.getMessage();
        }
    }

    private void updatePreview() {
        if (preview == null) return;
        try {
            List<TableUpdate.Planned> plan = TableUpdate.plan(session.grid, Map.copyOf(session.edits), options);
            List<String> lines = new ArrayList<>();
            for (TableUpdate.Planned p : plan) lines.add(p.preview());
            preview.setText(String.join("\n", lines));
            preview.setStyleRanges(SqlHighlighter.styles(preview.getText(), 0, false).toArray(StyleRange[]::new));
        } catch (TableUpdate.PlanError e) {
            preview.setText(e.getMessage());
            preview.setStyleRange(new StyleRange(0, preview.getCharCount(), Ui.RED, null));
        }
        Button apply = getButton(IDialogConstants.OK_ID);
        if (apply != null) apply.setEnabled(planError() == null);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        if (!hasEdits()) {
            createButton(parent, IDialogConstants.CANCEL_ID, "Close", true);
            return;
        }
        createButton(parent, DISCARD, "Discard Edits", false);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        Button apply = createButton(parent, IDialogConstants.OK_ID, "Apply Updates", true);
        apply.setEnabled(planError() == null);
    }

    @Override
    protected void buttonPressed(int id) {
        if (id == DISCARD) {
            session.discardEdits();
            close();
            return;
        }
        super.buttonPressed(id);
    }

    @Override
    protected void okPressed() {
        getShell().setEnabled(false);
        session.applyUpdates(options, fetchLimit, ok -> {
            if (!getShell().isDisposed()) close();
        });
    }

    @Override
    protected Point getInitialSize() {
        Point p = super.getInitialSize();
        return new Point(Math.max(660, Math.min(p.x, 900)), Math.min(p.y, 760));
    }
}
