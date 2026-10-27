package com.k3jdr.mxjobsmgr.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.engine.Validator;

/**
 * Problems (Ctrl+Shift+K; the Mac app's popover): the configuration checks — errors, warnings, notes — of the front
 * jobspace. Click one to go to its step or subscription; Escape or a click elsewhere closes it.
 */
public final class ProblemsPopup {
    private ProblemsPopup() {}

    private static Shell open;

    public static void show(Editor editor) {
        if (open != null && !open.isDisposed()) {
            open.close();
            return;
        }
        Shell parent = editor.root.getShell();
        Shell shell = new Shell(parent, SWT.TOOL | SWT.RESIZE | SWT.CLOSE | SWT.TITLE);
        open = shell;
        shell.setText(editor.issues.isEmpty() ? "No Problems" : editor.issues.size() + " Problems");
        shell.setLayout(Ui.grid(1, 6, 6));
        Table table = new Table(shell, SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        table.setLayoutData(Ui.fill());
        TableColumn icon = new TableColumn(table, SWT.CENTER);
        icon.setWidth(28);
        TableColumn msg = new TableColumn(table, SWT.LEFT);
        msg.setWidth(520);
        if (editor.issues.isEmpty()) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[] { "✔", "No problems found" });
            item.setForeground(Ui.GREEN);
        }
        for (Validator.Issue issue : editor.issues) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setData(issue);
            item.setText(new String[] { issue.severity().symbol, issue.message() });
            item.setForeground(0, Colors.of(issue.severity().rgb));
        }
        table.addListener(SWT.Selection, e -> {
            if (e.item != null && e.item.getData() instanceof Validator.Issue issue) {
                shell.close();
                editor.main.goToIssue(editor, issue);
            }
        });
        TableCopy.addMenuItems(table);
        QuickHelp.help(table, "Problems", "Configuration checks: missing subscriptions or keys, unlinked steps, empty settings. Click one to go to the step or subscription.", "Ctrl+Shift+K");
        shell.addListener(SWT.Deactivate, e -> shell.getDisplay().asyncExec(() -> {
            if (!shell.isDisposed()) shell.close();
        }));
        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) shell.close();
        });
        Point anchor = editor.runBar.problemsAnchor();
        int h = Math.min(420, Math.max(1, editor.issues.size()) * 26 + 70);
        Rectangle screen = parent.getMonitor().getClientArea();
        int x = Math.max(screen.x, Math.min(anchor.x - 200, screen.x + screen.width - 600));
        shell.setBounds(x, anchor.y + 2, 600, h);
        shell.open();
    }
}
