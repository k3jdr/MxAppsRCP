package com.k3jdr.mxacctmgr.bank;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.ui.FormDialog;

/**
 * Edit Target Account Name / Edit Service Name: names are unique whatever their case — the dialog says when another
 * one has the name and won't save it (the accounts service refuses one taken since). Save closes it.
 */
final class NameDialog extends FormDialog {
    private final String fieldLabel, current, takenFormat, footer;
    private final List<String> names;
    private final Function<String, String> save;
    private Text name;
    private Label taken;

    NameDialog(Shell parent, String title, String fieldLabel, String current, List<String> names, String takenFormat, String footer,
               Function<String, String> save) {
        super(parent, title, "Save");
        this.fieldLabel = fieldLabel;
        this.current = current;
        this.names = names;
        this.takenFormat = takenFormat;
        this.footer = footer;
        this.save = save;
    }

    @Override
    protected void fields(Composite grid) {
        name = field(fieldLabel, current, null);
        taken = note("");
        taken.setForeground(Ui.ORANGE);
        if (footer != null) note(footer);
        name.addListener(SWT.Modify, e -> check());
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        super.createButtonsForButtonBar(parent);
        check();
    }

    private String takenBy() {
        String t = name.getText().trim();
        if (t.equalsIgnoreCase(current)) return null;
        return names.stream().filter(n -> n.equalsIgnoreCase(t)).findFirst().orElse(null);
    }

    private void check() {
        String t = name.getText().trim();
        String other = takenBy();
        taken.setText(other == null ? "" : Ui.escape(String.format(takenFormat, other)));
        if (getButton(IDialogConstants.OK_ID) != null) getButton(IDialogConstants.OK_ID).setEnabled(!t.isEmpty() && !t.equals(current) && other == null);
        taken.getParent().layout(true);
    }

    @Override
    protected Callable<String> saveWork() {
        String t = name.getText().trim();
        return () -> save.apply(t);
    }
}
