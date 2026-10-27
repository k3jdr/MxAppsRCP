package com.k3jdr.mxfilemgr.dialogs;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.ops.ConflictPolicy;

/** Copy (F5) / Move (F6): the destination, which defaults to the other pane's folder, and the conflict policy (Sheets.swift's TransferSheet). */
public final class TransferDialog extends Dialog {
    public record Result(String destination, ConflictPolicy policy) {}

    private final List<Path> sources;
    private final String initial;
    private final boolean move;
    private Text destination;
    private Combo policy;
    private Result result;

    private TransferDialog(Shell parent, List<Path> sources, String initial, boolean move) {
        super(parent);
        this.sources = sources;
        this.initial = initial;
        this.move = move;
    }

    public static Result open(Shell parent, List<Path> sources, String initial, boolean move) {
        TransferDialog d = new TransferDialog(parent, sources, initial, move);
        return d.open() == OK ? d.result : null;
    }

    private String verb() { return move ? "Move" : "Copy"; }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(verb());
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(1, 12, 8));
        Ui.label(area, sources.size() == 1 ? verb() + " “" + FileOps.name(sources.get(0)) + "” to:" : verb() + " " + sources.size() + " items to:", Ui.bold());
        destination = new Text(area, SWT.BORDER | SWT.SINGLE);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 480;
        destination.setLayoutData(gd);
        destination.setText(initial);
        destination.selectAll();
        destination.addListener(SWT.Modify, e -> {
            Button ok = getButton(IDialogConstants.OK_ID);
            if (ok != null) ok.setEnabled(!destination.getText().isBlank());
        });
        Label hint = Ui.label(area, "A folder, or for a single item a new path to " + verb().toLowerCase() + " it under another name. Relative paths and ~ work.");
        hint.setForeground(Ui.SECONDARY);
        Composite row = new Composite(area, SWT.NONE);
        row.setLayout(Ui.grid(2, 0, 8));
        Ui.label(row, "If an item already exists:");
        policy = new Combo(row, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (ConflictPolicy p : ConflictPolicy.values()) policy.add(p.title);
        policy.select(0);
        QuickHelpItem help = FileHelp.transfer(move);
        QuickHelp.help(destination, "Destination", help.detail(), help.shortcut());
        QuickHelp.help(policy, "If an item already exists", "Ask shows both items' sizes and dates and offers Replace, Skip, Keep Both or Stop, with Apply to all. "
                + "Replace if older replaces only items older than the one copied. Folders that exist are merged.");
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, verb(), true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
        result = new Result(destination.getText().trim(), ConflictPolicy.values()[Math.max(0, policy.getSelectionIndex())]);
        super.okPressed();
    }
}
