package com.k3jdr.mxdatasql;

import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;

/** The status bar (ContentView.swift StatusBar): a coloured dot, the connection, the last message, a busy bar. */
public final class StatusBar {
    private static final Color YELLOW = new Color(new RGB(230, 180, 0));
    private final Session session;
    private final Supplier<Workspace.Connection> settings;
    private final Composite root;
    private final Canvas dot;
    private final Label text;
    private final Label info;
    private final ProgressBar busy;

    public StatusBar(Composite parent, Session session, Supplier<Workspace.Connection> settings) {
        this.session = session;
        this.settings = settings;
        Label line = new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL);
        line.setLayoutData(Ui.fillH());
        root = new Composite(parent, SWT.NONE);
        root.setLayout(Ui.grid(4, 0, 8));
        GridData rd = Ui.fillH();
        root.setLayoutData(rd);
        ((org.eclipse.swt.layout.GridLayout) root.getLayout()).marginWidth = 12;
        ((org.eclipse.swt.layout.GridLayout) root.getLayout()).marginHeight = 4;
        dot = new Canvas(root, SWT.NONE);
        GridData dd = new GridData(10, 10);
        dot.setLayoutData(dd);
        dot.addListener(SWT.Paint, e -> {
            e.gc.setAntialias(SWT.ON);
            e.gc.setBackground(color());
            e.gc.fillOval(1, 1, 8, 8);
        });
        text = new Label(root, SWT.NONE);
        text.setLayoutData(Ui.fillH());
        info = new Label(root, SWT.RIGHT);
        info.setForeground(Ui.SECONDARY);
        info.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        busy = new ProgressBar(root, SWT.INDETERMINATE);
        GridData bd = new GridData(60, 12);
        busy.setLayoutData(bd);
        QuickHelp.help(root, Help.STATUS.title(), Help.STATUS.detail());
        for (var c : root.getChildren()) QuickHelp.help(c, Help.STATUS.title(), Help.STATUS.detail());
    }

    private Color color() {
        return switch (session.status) {
            case CONNECTED -> Ui.GREEN;
            case CONNECTING -> YELLOW;
            case FAILED -> Ui.RED;
            case DISCONNECTED -> Ui.SECONDARY;
        };
    }

    public void refresh() {
        if (root.isDisposed()) return;
        Workspace.Connection s = session.connectedSettings != null && session.isConnected() ? session.connectedSettings : settings.get();
        text.setText(Ui.escape(switch (session.status) {
            case DISCONNECTED -> "Not connected";
            case CONNECTING -> "Connecting to " + s.host + "…";
            case CONNECTED -> s.user + "@" + s.host + ":" + s.port + "/" + s.database + " — PostgreSQL " + session.serverVersion;
            case FAILED -> "Connection failed";
        }));
        info.setText(Ui.escape(session.infoMessage == null ? "" : session.infoMessage));
        boolean working = session.isBusy() || session.status == Session.Status.CONNECTING || session.countingRows || session.runningSQL;
        Ui.setVisible(busy, working);
        dot.redraw();
        root.layout(true);
    }
}
