package com.k3jdr.mxacctmgr.ui;

import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;

/**
 * A dialog of labelled fields whose Save runs off the UI thread (the macOS sheets): it closes when the save succeeds
 * and shows why when it doesn't (a name taken, someone changed it since), keeping what was typed.
 */
public abstract class FormDialog extends Dialog {
    private final String title;
    private final String okLabel;
    private Label error;
    private Composite grid;

    protected FormDialog(Shell parent, String title, String okLabel) {
        super(parent);
        this.title = title;
        this.okLabel = okLabel;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(title);
    }

    /** The dialog's fields, in {@code grid} (two columns: label, field). */
    protected abstract void fields(Composite grid);

    /**
     * What Save does, off the UI thread: answers null when the dialog may close, else what went wrong. Return null
     * from {@link #saveWork()} to close without saving.
     */
    protected abstract Callable<String> saveWork();

    /** Called on the UI thread after a successful save, before the dialog closes. */
    protected void saved() {}

    protected int width() { return 480; }

    /** True for a long form: its fields scroll inside the dialog. */
    protected boolean scrolls() { return false; }

    /** Lays the fields out again (a part shown or hidden). */
    protected void relayoutFields() {
        grid.layout(true, true);
        if (grid.getParent() instanceof org.eclipse.swt.custom.ScrolledComposite sc) {
            int w = sc.getClientArea().width;
            sc.setMinSize(w, grid.computeSize(w, SWT.DEFAULT).y);
        }
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(1, 14, 8));
        if (scrolls()) {
            org.eclipse.swt.custom.ScrolledComposite sc = new org.eclipse.swt.custom.ScrolledComposite(area, SWT.V_SCROLL | SWT.BORDER);
            GridData sd = new GridData(SWT.FILL, SWT.FILL, true, true);
            sd.widthHint = width() + 30;
            sd.heightHint = 560;
            sc.setLayoutData(sd);
            sc.setExpandHorizontal(true);
            sc.setExpandVertical(true);
            grid = new Composite(sc, SWT.NONE);
            grid.setLayout(Ui.grid(2, 8, 8));
            sc.setContent(grid);
            fields(grid);
            sc.addListener(SWT.Resize, e -> {
                int w = sc.getClientArea().width;
                sc.setMinSize(w, grid.computeSize(w, SWT.DEFAULT).y);
            });
            sc.getVerticalBar().setIncrement(20);
        } else {
            grid = new Composite(area, SWT.NONE);
            grid.setLayout(Ui.grid(2, 0, 8));
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            gd.widthHint = width();
            grid.setLayoutData(gd);
            fields(grid);
        }
        error = Ui.wrap(area, "");
        error.setForeground(Ui.ORANGE);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, okLabel, true);
    }

    protected void showError(String message) {
        if (error == null || error.isDisposed()) return;
        error.setText(message == null ? "" : Ui.escape(message));
        error.getParent().layout(true);
        getShell().layout(true, true);
        Point size = getShell().computeSize(getShell().getSize().x, SWT.DEFAULT);
        if (size.y > getShell().getSize().y) getShell().setSize(getShell().getSize().x, size.y);
    }

    @Override
    protected void okPressed() {
        Callable<String> work = saveWork();
        if (work == null) {
            super.okPressed();
            return;
        }
        Button ok = getButton(IDialogConstants.OK_ID);
        ok.setEnabled(false);
        showError("");
        Background.run(getShell(), work, problem -> {
            ok.setEnabled(true);
            if (problem == null) {
                saved();
                super.okPressed();
            } else {
                showError(problem);
            }
        }, e -> {
            ok.setEnabled(true);
            showError(e.getMessage() == null ? e.toString() : e.getMessage());
        });
    }

    // Fields

    protected Label label(String text) {
        Label l = Ui.label(grid, text);
        l.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        return l;
    }

    protected Text field(String label, String value, String help) {
        label(label);
        Text t = new Text(grid, SWT.BORDER);
        t.setText(value == null ? "" : value);
        t.setLayoutData(Ui.fillH());
        if (help != null) {
            t.setToolTipText(help);
            QuickHelp.help(t, label, help);
        }
        return t;
    }

    protected Text area(String label, String value, int lines) {
        Label l = label(label);
        l.setLayoutData(new GridData(SWT.END, SWT.TOP, false, false));
        Text t = new Text(grid, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        t.setText(value == null ? "" : value);
        GridData gd = Ui.fillH();
        gd.heightHint = t.getLineHeight() * lines + 6;
        t.setLayoutData(gd);
        return t;
    }

    /** A masked field (a key or password) with Copy; {@code shown} switches it to plain text. */
    protected Text secret(String label, String value, boolean shown) {
        label(label);
        Composite row = new Composite(grid, SWT.NONE);
        row.setLayout(Ui.grid(2, 0, 4));
        row.setLayoutData(Ui.fillH());
        Text t = new Text(row, SWT.BORDER | (shown ? SWT.NONE : SWT.PASSWORD));
        t.setText(value == null ? "" : value);
        t.setLayoutData(Ui.fillH());
        Button copy = Ui.button(row, "Copy", () -> Clipboards.copyText(t.getDisplay(), t.getText()));
        copy.setToolTipText("Copy the " + label.toLowerCase());
        return t;
    }

    protected Combo combo(String label, String[] items, int selected) {
        label(label);
        Combo c = new Combo(grid, SWT.READ_ONLY | SWT.DROP_DOWN);
        c.setItems(items);
        if (selected >= 0 && selected < items.length) c.select(selected);
        c.setLayoutData(Ui.fillH());
        return c;
    }

    protected Combo editableCombo(String label, String[] items, String text) {
        label(label);
        Combo c = new Combo(grid, SWT.DROP_DOWN);
        c.setItems(items);
        c.setText(text == null ? "" : text);
        c.setLayoutData(Ui.fillH());
        return c;
    }

    protected DateTime date(String label, String isoDay) {
        label(label);
        DateTime d = new DateTime(grid, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
        setDay(d, isoDay);
        return d;
    }

    protected Button check(String text, boolean on, Consumer<Boolean> changed) {
        new Label(grid, SWT.NONE);
        return Ui.check(grid, text, on, changed == null ? v -> {} : changed);
    }

    protected Label note(String text) {
        Label l = new Label(grid, SWT.WRAP);
        l.setText(Ui.escape(text));
        l.setForeground(Ui.SECONDARY);
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        gd.widthHint = 200;
        l.setLayoutData(gd);
        return l;
    }

    protected Label heading(String text) {
        Label l = Ui.label(grid, text, Ui.bold());
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        gd.verticalIndent = 6;
        l.setLayoutData(gd);
        return l;
    }

    protected Composite grid() { return grid; }

    // Dates

    public static void setDay(DateTime d, String isoDay) {
        LocalDate day;
        try {
            day = isoDay == null || isoDay.length() < 10 ? LocalDate.now() : LocalDate.parse(isoDay.substring(0, 10));
        } catch (RuntimeException e) {
            day = LocalDate.now();
        }
        d.setDate(day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth());
    }

    public static String day(DateTime d) {
        return LocalDate.of(d.getYear(), d.getMonth() + 1, d.getDay()).toString();
    }
}
