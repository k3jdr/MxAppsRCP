package com.k3jdr.mxacctmgr;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.bank.Kinds;

/**
 * File ▸ Settings… (Ctrl+,; the Mac's MxAcctMGR ▸ Settings…): Text — the headings' and the tables' size, a point at a
 * time; Banking — what the checking register's New entry starts with; Guidance — how much of the teaching shows.
 * Each change applies at once.
 */
public final class SettingsDialog extends Dialog {
    public SettingsDialog(Shell parent) {
        super(parent);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Settings");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        CTabFolder tabs = new CTabFolder(area, SWT.BORDER | SWT.FLAT);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 560;
        gd.heightHint = 330;
        tabs.setLayoutData(gd);
        tab(tabs, "Text", this::text);
        tab(tabs, "Banking", this::banking);
        tab(tabs, "Guidance", this::guidance);
        tabs.setSelection(0);
        return area;
    }

    private interface Builder { void build(Composite c); }

    private static void tab(CTabFolder tabs, String title, Builder b) {
        CTabItem item = new CTabItem(tabs, SWT.NONE);
        item.setText(title);
        Composite c = new Composite(tabs, SWT.NONE);
        c.setLayout(Ui.grid(1, 14, 10));
        b.build(c);
        item.setControl(c);
    }

    private void text(Composite c) {
        section(c, "Headings", "Page titles, page introductions, headings, subheadings, names, figures.",
                Settings::headingDelta, Settings::setHeadingDelta);
        section(c, "Numbers and Tables", "Registers and tables (their rows follow their text's height), IDs and codes, calculation results.",
                Settings::numbersDelta, Settings::setNumbersDelta);
    }

    private void section(Composite parent, String title, String detail, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
        Ui.label(parent, title, Ui.bold());
        Label d = Ui.wrap(parent, detail);
        d.setForeground(Ui.SECONDARY);
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayout(Ui.grid(4, 0, 8));
        Label size = Ui.label(row, "");
        Runnable show = () -> size.setText(get.getAsInt() == 0 ? "Standard size" : (get.getAsInt() > 0 ? "+" : "") + get.getAsInt() + " pt");
        show.run();
        size.setLayoutData(new GridData(120, SWT.DEFAULT));
        QuickHelp.help(Ui.button(row, "Step Down", () -> { set.accept(get.getAsInt() - 1); show.run(); }), "Step Down", "Every kind of this text a point smaller.");
        QuickHelp.help(Ui.button(row, "Step Up", () -> { set.accept(get.getAsInt() + 1); show.run(); }), "Step Up", "Every kind of this text a point larger.");
        QuickHelp.help(Ui.button(row, "Restore Defaults", () -> { set.accept(0); show.run(); }), "Restore Defaults", "Back to the standard sizes.");
    }

    private void banking(Composite c) {
        Ui.label(c, "Checking Accounts ▸ New entry", Ui.bold());
        Composite g = new Composite(c, SWT.NONE);
        g.setLayout(Ui.grid(2, 0, 8));
        Ui.label(g, "Kind");
        Combo kind = new Combo(g, SWT.READ_ONLY);
        for (String[] k : Kinds.KINDS) kind.add(k[1]);
        kind.select(Math.max(0, Kinds.index(Settings.newEntryKind())));
        kind.addListener(SWT.Selection, e -> Settings.setNewEntryKind(Kinds.KINDS[kind.getSelectionIndex()][0]));
        Ui.label(g, "Check number");
        Combo check = new Combo(g, SWT.READ_ONLY);
        check.setItems(Settings.CHECK_EMPTY, Settings.CHECK_NEXT);
        check.select(Settings.CHECK_NEXT.equals(Settings.newEntryCheckNumber()) ? 1 : 0);
        check.addListener(SWT.Selection, e -> Settings.setNewEntryCheckNumber(check.getText()));
        Ui.label(g, "Date");
        Combo date = new Combo(g, SWT.READ_ONLY);
        date.setItems(Settings.DATE_PREVIOUS, Settings.DATE_TODAY);
        date.select(Settings.DATE_TODAY.equals(Settings.newEntryDate()) ? 1 : 0);
        date.addListener(SWT.Selection, e -> Settings.setNewEntryDate(date.getText()));
        Label f = Ui.wrap(c, "What the New entry line above the checking register starts with, and starts again with after each entry. The check number is filled in only for a check; the date of the entry before keeps a month's entries from the paper register on their day.");
        f.setForeground(Ui.SECONDARY);
    }

    private void guidance(Composite c) {
        Ui.label(c, "Instruction level", Ui.bold());
        for (Settings.Guidance g : Settings.Guidance.values()) {
            Button b = new Button(c, SWT.RADIO);
            b.setText(g.title);
            b.setSelection(Settings.guidance() == g);
            b.addListener(SWT.Selection, e -> {
                if (b.getSelection() && Settings.guidance() != g) Settings.setGuidance(g);
            });
            Label d = Ui.wrap(c, g.detail);
            d.setForeground(Ui.SECONDARY);
            ((GridData) d.getLayoutData()).horizontalIndent = 20;
        }
        Label f = Ui.wrap(c, "A hidden lesson comes back at Full. The educational notice in the status bar always shows.");
        f.setForeground(Ui.SECONDARY);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Done", true);
    }

    @Override
    protected boolean isResizable() { return true; }
}
