package com.k3jdr.mxfilemgr.dialogs;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.Settings;

/**
 * Settings (SettingsView.swift): hidden files, folders first, the function key bar, asking before the Recycle Bin,
 * and the programs Edit (F4) and Open Terminal Here use.
 */
public final class SettingsDialog extends Dialog {
    private static final String[][] TERMINALS = { { "wt", "Windows Terminal" }, { "powershell", "Windows PowerShell" },
            { "pwsh", "PowerShell 7 (pwsh)" }, { "cmd", "Command Prompt" } };

    private Button hidden, foldersFirst, keyBar, confirm;
    private Text editor;
    private Combo terminal;

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
        area.setLayout(Ui.grid(1, 14, 10));
        Settings s = Settings.get();
        Group lists = new Group(area, SWT.NONE);
        lists.setText("File Lists");
        lists.setLayout(Ui.grid(1, 10, 6));
        lists.setLayoutData(Ui.fillH());
        hidden = check(lists, "Show hidden files", s.showHidden(), "Shows hidden and system items in every pane; they appear dimmed.");
        foldersFirst = check(lists, "Folders before files", s.foldersFirst(), "Lists folders before files whatever the sort column.");
        keyBar = check(lists, "Show function key bar", s.showFunctionKeyBar(), "The F2–F8 buttons at the bottom of the window.");
        confirm = check(lists, "Ask before moving items to the Recycle Bin", s.confirmTrash(),
                "F8 and Delete ask first. Drives without a Recycle Bin (network, USB) always ask, as the items are deleted for good.");

        Group apps = new Group(area, SWT.NONE);
        apps.setText("Applications");
        apps.setLayout(Ui.grid(3, 10, 6));
        apps.setLayoutData(Ui.fillH());
        Ui.label(apps, "Editor (F4):");
        editor = new Text(apps, SWT.BORDER);
        GridData eg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        eg.widthHint = 300;
        editor.setLayoutData(eg);
        editor.setText(s.editorPath());
        Button choose = new Button(apps, SWT.PUSH);
        choose.setText("Choose…");
        choose.addListener(SWT.Selection, e -> {
            FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
            fd.setFilterExtensions(new String[] { "*.exe;*.cmd;*.bat", "*.*" });
            fd.setFilterNames(new String[] { "Programs", "All files" });
            String chosen = fd.open();
            if (chosen != null) editor.setText(chosen);
        });
        QuickHelp.help(editor, "Editor", "The program Edit (F4) and New File (Shift+F4) open files with: notepad.exe unless you choose another (VS Code, Notepad++…).", "F4");
        QuickHelp.help(choose, "Choose Editor", "Picks the editor's .exe.");
        Ui.label(apps, "Terminal:");
        terminal = new Combo(apps, SWT.DROP_DOWN);
        terminal.setLayoutData(Ui.fillH());
        for (String[] t : TERMINALS) terminal.add(t[1]);
        String current = s.terminal();
        int idx = -1;
        for (int i = 0; i < TERMINALS.length; i++) if (TERMINALS[i][0].equalsIgnoreCase(current)) idx = i;
        if (idx >= 0) terminal.select(idx);
        else terminal.setText(current);
        Button chooseTerm = new Button(apps, SWT.PUSH);
        chooseTerm.setText("Choose…");
        chooseTerm.addListener(SWT.Selection, e -> {
            FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
            fd.setFilterExtensions(new String[] { "*.exe", "*.*" });
            String chosen = fd.open();
            if (chosen != null) terminal.setText(chosen);
        });
        QuickHelp.help(terminal, "Terminal", "What Open Terminal Here (Ctrl+Alt+T) starts in the folder: Windows Terminal, PowerShell, Command Prompt, or any program.", "Ctrl+Alt+T");
        QuickHelp.help(chooseTerm, "Choose Terminal", "Picks another terminal program.");
        return area;
    }

    private static Button check(Composite parent, String text, boolean value, String help) {
        Button b = new Button(parent, SWT.CHECK);
        b.setText(text);
        b.setSelection(value);
        QuickHelp.help(b, text, help);
        return b;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
        Settings s = Settings.get();
        if (hidden.getSelection() != s.showHidden()) s.setShowHidden(hidden.getSelection());
        if (foldersFirst.getSelection() != s.foldersFirst()) s.setFoldersFirst(foldersFirst.getSelection());
        if (keyBar.getSelection() != s.showFunctionKeyBar()) s.setShowFunctionKeyBar(keyBar.getSelection());
        s.setConfirmTrash(confirm.getSelection());
        s.setEditorPath(editor.getText());
        int i = terminal.getSelectionIndex();
        String text = terminal.getText();
        String chosen = text;
        for (String[] t : TERMINALS) if (t[1].equals(text)) chosen = t[0];
        if (i >= 0 && i < TERMINALS.length && TERMINALS[i][1].equals(text)) chosen = TERMINALS[i][0];
        s.setTerminal(chosen);
        super.okPressed();
    }
}
