package com.k3jdr.mxdatasql;

import java.util.Map;
import java.util.TreeMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;

/**
 * A table opened with Open in New Window (TableWindow.swift): the DB Table form in a window of its own, with its own
 * connection, so its transactions never mix with another window's. Its view settings start as a copy of the
 * workspace's; column widths set here go back to the workspace while it is open. The password is handed over in
 * memory only.
 */
public final class TableWindow implements Windows.HelpWindow {
    private final Shell shell;
    private final Session session;
    private final TableForm form;
    private final StatusBar status;
    private final Workspace.ViewSettings view;
    private final Workspace.Connection settings;
    private final WorkspaceWindow workspace;

    private TableWindow(WorkspaceWindow workspace, TableRef ref) {
        this.workspace = workspace;
        Display display = workspace.shell().getDisplay();
        settings = workspace.doc.connection.copy();
        view = workspace.doc.view.copy();
        shell = new Shell(display, SWT.SHELL_TRIM);
        shell.setImages(workspace.shell().getImages());
        shell.setText(ref.displayName() + " — " + settings.user + "@" + settings.host + "/" + settings.database);
        shell.setLayout(Ui.grid(1, 0, 0));
        shell.setMinimumSize(700, 480);
        session = new Session(shell);
        session.form = Session.Form.TABLE;
        session.password = workspace.session.password;
        form = new TableForm(shell, session, new TableForm.ViewHost() {
            @Override public Workspace.ViewSettings view() { return view; }
            @Override public void viewChanged() { }
            @Override public void widthsChanged(String table, Map<String, Double> widths) {
                if (widths == null) view.columnWidths.remove(table); else view.columnWidths.put(table, new TreeMap<>(widths));
                if (!workspace.shell().isDisposed()) workspace.setWidths(table, widths);
            }
        });
        form.control().setLayoutData(Ui.fill());
        status = new StatusBar(shell, session, () -> settings);
        buildMenus();
        session.addListener(this::refresh);
        Windows.register(this);
        shell.addListener(SWT.Dispose, e -> session.dispose());
        Rectangle b = workspace.shell().getBounds();
        shell.setBounds(b.x + 40, b.y + 40, Math.max(900, b.width - 120), Math.max(600, b.height - 120));
        shell.open();
        refresh();
        session.connect(settings, () -> {
            if (session.isConnected()) {
                session.form = Session.Form.TABLE;
                session.openTable(ref, view.fetchLimit, 0, true, null, null);
            }
        });
    }

    static TableWindow open(WorkspaceWindow workspace, TableRef ref) {
        return new TableWindow(workspace, ref);
    }

    @Override public Shell shell() { return shell; }

    private void refresh() {
        if (shell.isDisposed()) return;
        session.form = Session.Form.TABLE;
        form.refresh();
        status.refresh();
        if (session.status == Session.Status.FAILED && !failureShown) {
            failureShown = true;
            session.showError("Connection Failed\n\n" + session.failure);
        }
    }

    private boolean failureShown;

    private void buildMenus() {
        MenuBarBuilder mb = new MenuBarBuilder(shell);
        mb.menu("&File").item("&Close Window", SWT.MOD1 | 'W', shell::close);
        EditActions.addStandardItems(mb.menu("&Edit"), () -> {
            Control focus = shell.getDisplay().getFocusControl();
            return focus == form.grid() ? form.editTarget() : null;
        });
        QuickHelp.get().addMenuItems(mb.menu("&View"));
        mb.menu("&Table")
                .item("&Run SQL", SWT.MOD1 | SWT.CR, form::runSQL, () -> session.isConnected() && !session.runningSQL)
                .separator()
                .item("&View…", SWT.MOD1 | SWT.MOD3 | 'L', form::showViewDialog)
                .item("&Update…", SWT.MOD1 | SWT.MOD3 | 'U', form::showUpdateDialog, form::canUpdate)
                .separator()
                .item("&Export CSV…", SWT.MOD1 | SWT.SHIFT | 'E', form::exportCSV, () -> session.grid != null);
        mb.install();
    }

    @Override
    public QuickHelpItem screen() { return Help.tableWindowScreen(); }

    @Override
    public QuickHelpItem selection() { return Help.gridSelection(session, form.grid()); }

    WorkspaceWindow workspace() { return workspace; }
}
