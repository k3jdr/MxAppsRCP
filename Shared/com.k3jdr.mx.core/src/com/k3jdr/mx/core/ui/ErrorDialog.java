package com.k3jdr.mx.core.ui;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.db.Pg;

/**
 * An error in a small dialog whose text can be selected, with Copy for all of it (MxDataHUB's ErrorDialog.swift).
 */
public final class ErrorDialog extends Dialog {
    private static final int COPY = IDialogConstants.CLIENT_ID + 1;
    private final String title;
    private final String message;

    private ErrorDialog(Shell parent, String title, String message) {
        super(parent);
        this.title = title;
        this.message = message;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    public static void show(Shell parent, String title, String message) {
        if (parent == null || parent.isDisposed()) return;
        new ErrorDialog(parent, title, message).open();
    }

    public static void show(Shell parent, String title, Throwable t) {
        show(parent, title, describe(t));
    }

    /** The message to show for a failure: psql-style for PostgreSQL errors, else the messages of its causes. */
    public static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root && (root.getMessage() == null
                || root instanceof java.util.concurrent.ExecutionException
                || root instanceof java.lang.reflect.InvocationTargetException)) {
            root = root.getCause();
        }
        if (root instanceof SQLException sql) return Pg.describe(sql);
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            StringWriter sw = new StringWriter();
            root.printStackTrace(new PrintWriter(sw));
            return sw.toString();
        }
        return msg;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(title);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Text text = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        text.setText(message == null ? "" : message);
        text.setFont(Ui.mono());
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 520;
        gd.heightHint = 160;
        text.setLayoutData(gd);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, COPY, "Copy", false);
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == COPY) {
            Clipboards.copyText(getShell().getDisplay(), message);
            return;
        }
        super.buttonPressed(buttonId);
    }

    @Override
    protected Point getInitialSize() {
        Point p = super.getInitialSize();
        return new Point(Math.max(p.x, 560), p.y);
    }
}
