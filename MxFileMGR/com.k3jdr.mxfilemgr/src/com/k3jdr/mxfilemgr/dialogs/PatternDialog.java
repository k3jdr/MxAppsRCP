package com.k3jdr.mxfilemgr.dialogs;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.Settings;

/** Select Group (Ctrl+=) / Deselect Group (Ctrl+-): a wildcard pattern, remembered (Sheets.swift's PatternSheet). */
public final class PatternDialog extends Dialog {
    public record Result(String pattern, boolean includeFolders) {}

    private final boolean select;
    private Text pattern;
    private Button folders;
    private Result result;

    private PatternDialog(Shell parent, boolean select) {
        super(parent);
        this.select = select;
    }

    public static Result open(Shell parent, boolean select) {
        PatternDialog d = new PatternDialog(parent, select);
        return d.open() == OK ? d.result : null;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(select ? "Select Group" : "Deselect Group");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(1, 12, 8));
        pattern = new Text(area, SWT.BORDER | SWT.SINGLE);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 340;
        pattern.setLayoutData(gd);
        pattern.setText(Settings.PREFS.get("lastSelectPattern", "*.*"));
        pattern.selectAll();
        Ui.label(area, "Wildcards * and ?; separate several patterns with ;  e.g.  *.jpg;*.png").setForeground(Ui.SECONDARY);
        folders = new Button(area, SWT.CHECK);
        folders.setText("Include folders");
        folders.setSelection(Settings.PREFS.getBoolean("selectPatternFolders", false));
        QuickHelp.help(pattern, FileHelp.pattern(select).title(), FileHelp.pattern(select).detail(), FileHelp.pattern(select).shortcut());
        QuickHelp.help(folders, "Include folders", "Folders whose names match are marked (or unmarked) too.");
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, select ? "Select" : "Deselect", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
        Settings.PREFS.put("lastSelectPattern", pattern.getText());
        Settings.PREFS.putBoolean("selectPatternFolders", folders.getSelection());
        result = new Result(pattern.getText(), folders.getSelection());
        super.okPressed();
    }
}
