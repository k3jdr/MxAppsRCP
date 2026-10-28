package com.k3jdr.mxfilemgr.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/** The row of Total Commander function keys at the bottom (MainView.swift's FunctionKeyBar). */
public final class FunctionKeyBar {
    private final Composite root;

    public FunctionKeyBar(Composite parent, Workspace ws) {
        root = new Composite(parent, SWT.NONE);
        root.setLayout(Ui.grid(8, 2, 2));
        ((org.eclipse.swt.layout.GridLayout) root.getLayout()).makeColumnsEqualWidth = true;
        root.setLayoutData(Ui.fillH());
        key("F2", "Rename", ws::rename);
        key("F3", "View", ws::view);
        key("F4", "Edit", ws::edit);
        key("F5", "Copy", () -> ws.copyOrMoveToOtherPane(false));
        key("F6", "Move", () -> ws.copyOrMoveToOtherPane(true));
        key("F7", "New Folder", ws::newFolder);
        key("F8", "Recycle Bin", ws::moveToTrash);
        key("Alt+F7", "Find", ws::search);
        QuickHelp.help(root, FileHelp.FUNCTION_KEYS.title(), FileHelp.FUNCTION_KEYS.detail());
    }

    public Composite control() { return root; }

    private void key(String name, String title, Runnable action) {
        Button b = new Button(root, SWT.PUSH | SWT.FLAT);
        b.setText(name + "  " + title);
        b.setLayoutData(Ui.fillH());
        b.addListener(SWT.Selection, e -> action.run());
        QuickHelp.help(b, title, title + " the marked items (" + name + "). " + FileHelp.FUNCTION_KEYS.detail(), name);
    }
}
