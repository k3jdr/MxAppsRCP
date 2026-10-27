package com.k3jdr.mxfilemgr.dialogs;

import java.util.function.Function;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;

/** The small alerts and prompts (WorkspaceActions.swift's Alerts and Sheets.swift's NamePromptSheet). */
public final class Dialogs {
    private Dialogs() {}

    public static void message(Shell parent, String text, String info) {
        MessageDialog.openInformation(parent, "MxFileMGR", info == null || info.isBlank() ? text : text + "\n\n" + info);
    }

    public static void warning(Shell parent, String text, String info) {
        MessageDialog.openWarning(parent, "MxFileMGR", info == null || info.isBlank() ? text : text + "\n\n" + info);
    }

    public static void error(Shell parent, String title, Throwable t) {
        ErrorDialog.show(parent, title, t);
    }

    /** Asks before doing something; true for {@code ok}. Destructive questions default to Cancel. */
    public static boolean confirm(Shell parent, String text, String info, String ok, boolean destructive) {
        MessageDialog d = new MessageDialog(parent, "MxFileMGR", null,
                info == null || info.isBlank() ? text : text + "\n\n" + info,
                destructive ? MessageDialog.WARNING : MessageDialog.QUESTION, destructive ? 1 : 0, ok, "Cancel");
        return d.open() == 0;
    }

    /**
     * Asks for one line of text (New Folder, Rename, Go to Folder). With {@code selectStem} the name is selected
     * without its extension, as Explorer does when renaming. {@code problem} says why a value can't be used (null
     * when it can). Returns the trimmed text, or null when cancelled.
     */
    public static String prompt(Shell parent, String title, String message, String initial, String ok, boolean selectStem,
            Function<String, String> problem, String help) {
        PromptDialog d = new PromptDialog(parent, title, message, initial, ok, selectStem, problem, help);
        return d.open() == Dialog.OK ? d.value : null;
    }

    private static final class PromptDialog extends Dialog {
        private final String title, message, initial, ok, help;
        private final boolean selectStem;
        private final Function<String, String> problem;
        private Text text;
        private Label note;
        String value;

        PromptDialog(Shell parent, String title, String message, String initial, String ok, boolean selectStem,
                Function<String, String> problem, String help) {
            super(parent);
            this.title = title;
            this.message = message;
            this.initial = initial == null ? "" : initial;
            this.ok = ok;
            this.selectStem = selectStem;
            this.problem = problem;
            this.help = help;
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText(title);
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            if (message != null && !message.isBlank()) Ui.label(area, message);
            text = new Text(area, SWT.BORDER | SWT.SINGLE);
            GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            gd.widthHint = 420;
            text.setLayoutData(gd);
            text.setText(initial);
            int dot = initial.lastIndexOf('.');
            if (selectStem && dot > 0) text.setSelection(0, dot);
            else text.selectAll();
            text.addListener(SWT.Modify, e -> validate());
            note = new Label(area, SWT.WRAP);
            note.setForeground(Ui.RED);
            note.setLayoutData(Ui.fillH());
            QuickHelp.help(text, title, help != null ? help : (message == null || message.isBlank() ? "Type a name, then press Enter." : message));
            return area;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.OK_ID, ok, true);
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            validate();
        }

        private void validate() {
            String v = text.getText().trim();
            String p = v.isEmpty() ? "" : problem != null ? problem.apply(v) : null;
            note.setText(p == null ? "" : p);
            Button b = getButton(IDialogConstants.OK_ID);
            if (b != null) b.setEnabled(!v.isEmpty() && p == null);
        }

        @Override
        protected void okPressed() {
            value = text.getText().trim();
            super.okPressed();
        }

        @Override
        protected Point getInitialSize() {
            Point p = super.getInitialSize();
            return new Point(Math.max(p.x, 460), p.y);
        }
    }
}
