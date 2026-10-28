package com.k3jdr.mxdatasql;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;

/** Table ▸ View… (ViewSettingsDialog.swift): rows and columns shown in the grid, and how many rows are fetched. */
public final class ViewSettingsDialog extends Dialog {
    private final Workspace.ViewSettings settings;
    private final int pendingEdits;
    private Spinner rows, columns, fetch;
    private Label warning;
    private Workspace.ViewSettings result;

    public ViewSettingsDialog(Shell parent, Workspace.ViewSettings settings, int pendingEdits) {
        super(parent);
        this.settings = settings;
        this.pendingEdits = pendingEdits;
    }

    public Workspace.ViewSettings result() { return result; }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Table View");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(2, 16, 10));
        QuickHelp.help(area, Help.TABLE_VIEW.title(), Help.TABLE_VIEW.detail(), Help.TABLE_VIEW.shortcut());
        Label heading = Ui.label(area, "Table View", Ui.bold());
        heading.setLayoutData(Ui.span(2));
        rows = row(area, "Rows to display", settings.visibleRows, 1, 200, 1);
        QuickHelp.help(rows, "Rows to display", "How many rows the grid keeps visible at least.");
        columns = row(area, "Columns to display", settings.visibleColumns, 1, 50, 1);
        QuickHelp.help(columns, "Columns to display", "How many columns span the grid's width; columns you resized keep their width.");
        fetch = row(area, "Rows to fetch", settings.fetchLimit, 1, 1_000_000, 100);
        QuickHelp.help(fetch, "Rows to fetch", "How many rows a page holds; Previous and Next load the pages before and after.");
        warning = new Label(area, SWT.WRAP);
        warning.setForeground(Ui.ORANGE);
        GridData wd = Ui.span(2);
        wd.widthHint = 340;
        warning.setLayoutData(wd);
        fetch.addListener(SWT.Modify, e -> updateWarning());
        updateWarning();
        return area;
    }

    private static Spinner row(Composite parent, String label, int value, int min, int max, int step) {
        Ui.label(parent, label);
        Spinner s = new Spinner(parent, SWT.BORDER);
        s.setValues(Math.max(min, Math.min(max, value)), min, max, 0, step, step * 10);
        GridData gd = new GridData(SWT.END, SWT.CENTER, true, false);
        gd.widthHint = 90;
        s.setLayoutData(gd);
        return s;
    }

    private void updateWarning() {
        boolean show = fetch.getSelection() != settings.fetchLimit && pendingEdits > 0;
        warning.setText(show ? "⚠ Changing the fetch limit reloads the table and discards " + pendingEdits + " pending edits." : "");
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, "Apply", true);
    }

    @Override
    protected void okPressed() {
        result = settings.copy();
        result.visibleRows = Math.max(1, Math.min(200, rows.getSelection()));
        result.visibleColumns = Math.max(1, Math.min(50, columns.getSelection()));
        result.fetchLimit = Math.max(1, Math.min(1_000_000, fetch.getSelection()));
        super.okPressed();
    }
}
