package com.k3jdr.mxfilemgr.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.dialogs.Dialogs;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.ops.ConflictPolicy;
import com.k3jdr.mxfilemgr.ops.TransferManager;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * Synchronize Folders (Ctrl+Alt+Y; SyncView.swift): compares two folder trees by date and size, or by content,
 * proposes a copy direction for each difference — change it per row (click the Action cell) or for the selection
 * (right-click) — then copies, replacing older files.
 */
public final class SyncWindow {
    private final Workspace ws;
    private final Shell shell;
    private final Display display;
    private Text leftPath, rightPath;
    private Button recursive, hidden, byContent, showEqual, compare, synchronize;
    private Label progress, summary;
    private Table table;
    private List<SyncEngine.Entry> entries = new ArrayList<>();
    private List<SyncEngine.Entry> visible = new ArrayList<>();
    private boolean compared;
    private AtomicBoolean cancel;

    private SyncWindow(Workspace ws, Path left, Path right) {
        this.ws = ws;
        this.display = ws.display();
        shell = ToolWindows.create(ws, "sync", "Synchronize Folders", 900, 580, () -> FileHelp.SYNC, this::selectionHelp);
        shell.setLayout(Ui.grid(1, 0, 0));
        buildForm(left, right);
        buildTable();
        buildFooter();
        shell.addListener(SWT.Dispose, e -> {
            if (cancel != null) cancel.set(true);
        });
        shell.open();
        compare.setFocus();
    }

    public static void open(Workspace ws, Path left, Path right) {
        new SyncWindow(ws, left, right);
    }

    private void buildForm(Path left, Path right) {
        Composite form = new Composite(shell, SWT.NONE);
        form.setLayout(Ui.grid(1, 12, 8));
        form.setLayoutData(Ui.fillH());
        Composite paths = new Composite(form, SWT.NONE);
        paths.setLayout(Ui.grid(3, 0, 6));
        paths.setLayoutData(Ui.fillH());
        leftPath = new Text(paths, SWT.BORDER | SWT.SINGLE);
        leftPath.setText(left == null ? "" : left.toString());
        leftPath.setMessage("Left folder");
        leftPath.setLayoutData(Ui.fillH());
        QuickHelp.help(leftPath, "Left Folder", "The folder on the left; it starts as the left pane's folder.");
        Button swap = Ui.button(paths, "⇄", () -> {
            String l = leftPath.getText();
            leftPath.setText(rightPath.getText());
            rightPath.setText(l);
            clear();
        });
        swap.setToolTipText("Swap sides");
        QuickHelp.help(swap, "Swap Sides", "Exchanges the left and right folders.");
        rightPath = new Text(paths, SWT.BORDER | SWT.SINGLE);
        rightPath.setText(right == null ? "" : right.toString());
        rightPath.setMessage("Right folder");
        rightPath.setLayoutData(Ui.fillH());
        QuickHelp.help(rightPath, "Right Folder", "The folder on the right; it starts as the right pane's folder.");

        Composite options = new Composite(form, SWT.NONE);
        options.setLayout(Ui.grid(7, 0, 12));
        options.setLayoutData(Ui.fillH());
        recursive = check(options, "Subfolders", true, true, "Subfolders", "Compares the folders inside, all the way down.");
        hidden = check(options, "Hidden items", false, true, "Hidden items", "Includes hidden files and folders.");
        byContent = check(options, "Compare content", false, true, "Compare content",
                "Files of the same size are also compared byte for byte (slower); the dates then only decide the direction.");
        showEqual = check(options, "Show equal", false, false, "Show equal", "Lists the files that are the same on both sides too.");
        showEqual.addListener(SWT.Selection, e -> refill());
        Label spacer = new Label(options, SWT.NONE);
        spacer.setLayoutData(Ui.fillH());
        progress = new Label(options, SWT.NONE);
        progress.setForeground(Ui.SECONDARY);
        QuickHelp.help(progress, "Progress", "Shows Comparing… while the folders are being compared.");
        compare = Ui.button(options, "Compare", this::compare);
        GridData gd = new GridData(SWT.END, SWT.CENTER, false, false);
        gd.widthHint = 90;
        compare.setLayoutData(gd);
        QuickHelp.help(compare, "Compare", "Lists what differs between the two folder trees, with a proposed direction for each.", "Enter");
        shell.setDefaultButton(compare);
    }

    private Button check(Composite parent, String text, boolean value, boolean clears, String title, String help) {
        Button b = new Button(parent, SWT.CHECK);
        b.setText(text);
        b.setSelection(value);
        if (clears) b.addListener(SWT.Selection, e -> clear());
        QuickHelp.help(b, title, help);
        return b;
    }

    private void buildTable() {
        table = new Table(shell, SWT.VIRTUAL | SWT.MULTI | SWT.FULL_SELECTION | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLayoutData(Ui.fill());
        String[] titles = { "Path", "Left", "Action", "Right", "Status" };
        int[] widths = { 300, 180, 60, 180, 100 };
        for (int i = 0; i < titles.length; i++) {
            TableColumn c = new TableColumn(table, i == 2 ? SWT.CENTER : SWT.LEFT);
            c.setText(titles[i]);
            c.setWidth(widths[i]);
        }
        table.addListener(SWT.SetData, e -> {
            TableItem item = (TableItem) e.item;
            int index = table.indexOf(item);
            if (index < 0 || index >= visible.size()) return;
            SyncEngine.Entry en = visible.get(index);
            item.setText(new String[] { (en.isDirectory ? "📁 " : "") + en.path, info(en.left, en.isDirectory), en.action.symbol,
                    info(en.right, en.isDirectory), en.status.title });
            item.setForeground(1, Ui.SECONDARY);
            item.setForeground(3, Ui.SECONDARY);
            item.setForeground(4, color(en.status));
            item.setFont(2, Ui.bold());
            item.setData(en);
        });
        table.addListener(SWT.MouseDown, e -> {
            if (e.button != 1) return;
            Point pt = new Point(e.x, e.y);
            TableItem item = table.getItem(pt);
            if (item == null || !(item.getData() instanceof SyncEngine.Entry en)) return;
            if (item.getBounds(2).contains(pt)) {
                en.action = en.action.next();
                table.clear(table.indexOf(item));
                updateSummary();
            }
        });
        QuickHelp.provider(table, (c, p) -> {
            if (p.y >= 0 && p.y < table.getHeaderHeight()) {
                return new QuickHelpItem("Differences", "Path, both sides' size and date, the direction to copy and how the items differ.");
            }
            TableItem item = table.getItem(p);
            if (item != null && item.getData() instanceof SyncEngine.Entry en) {
                if (item.getBounds(2).contains(p)) {
                    return new QuickHelpItem("Action", "→ copies left to right, ← right to left, – skips this row. Click to change it; "
                            + "right-click to set it for all selected rows.");
                }
                return new QuickHelpItem(en.path, en.status.title.substring(0, 1).toUpperCase() + en.status.title.substring(1)
                        + ". " + (en.action == SyncEngine.Action.SKIP ? "Skipped." : "Copied " + (en.action == SyncEngine.Action.TO_RIGHT ? "to the right" : "to the left") + " by Synchronize."));
            }
            return new QuickHelpItem("Differences", "Press Compare to list what differs between the two folders.");
        });
        Menu menu = new Menu(table);
        table.setMenu(menu);
        menuItem(menu, "Copy → Right", SyncEngine.Action.TO_RIGHT);
        menuItem(menu, "Copy ← Left", SyncEngine.Action.TO_LEFT);
        menuItem(menu, "Skip", SyncEngine.Action.SKIP);
        TableCopy.addMenuItems(table);
    }

    private void menuItem(Menu menu, String text, SyncEngine.Action action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(text);
        mi.addListener(SWT.Selection, e -> {
            for (int i : table.getSelectionIndices()) {
                if (i >= 0 && i < visible.size()) visible.get(i).action = action;
            }
            table.clearAll();
            updateSummary();
        });
        menu.addListener(SWT.Show, e -> mi.setEnabled(table.getSelectionCount() > 0));
    }

    private void buildFooter() {
        Composite footer = new Composite(shell, SWT.NONE);
        footer.setLayout(Ui.grid(2, 10, 8));
        footer.setLayoutData(Ui.fillH());
        summary = new Label(footer, SWT.NONE);
        summary.setForeground(Ui.SECONDARY);
        summary.setLayoutData(Ui.fillH());
        QuickHelp.help(summary, "Summary", "How many items Synchronize would copy each way, and how many are equal.");
        synchronize = Ui.button(footer, "Synchronize", this::synchronize);
        QuickHelp.help(synchronize, "Synchronize", "Copies every row with a direction (→ or ←) after asking, replacing older files at the destination.");
        updateSummary();
    }

    private QuickHelpItem selectionHelp() {
        if (table == null || table.isDisposed() || table.getSelectionCount() == 0) return null;
        return new QuickHelpItem(Fmt.count(table.getSelectionCount(), "Row") + " Selected",
                "Right-click to copy them to the right, to the left, or skip them.");
    }

    private static String info(SyncEngine.Side side, boolean dir) {
        if (side == null) return "";
        return dir ? "folder" : Fmt.size(side.size()) + "  " + Fmt.date(side.modified());
    }

    private static Color color(SyncEngine.Status s) {
        return switch (s) {
            case EQUAL -> Ui.SECONDARY;
            case DIFFERS -> Ui.RED;
            case LEFT_ONLY, LEFT_NEWER -> Ui.ACCENT;
            case RIGHT_ONLY, RIGHT_NEWER -> Ui.GREEN;
        };
    }

    private Path path(Text t) {
        String s = t.getText().trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("~")) s = System.getProperty("user.home") + s.substring(1);
        try {
            Path p = Path.of(s).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void clear() {
        if (cancel != null) {
            cancel.set(true);
            cancel = null;
        }
        entries = new ArrayList<>();
        compared = false;
        refill();
    }

    private void compare() {
        Path l = path(leftPath), r = path(rightPath);
        if (l == null || r == null) {
            Dialogs.message(shell, "Choose two folders to compare.", (l == null ? "The left" : "The right") + " folder can't be found.");
            return;
        }
        if (cancel != null) cancel.set(true);
        AtomicBoolean flag = new AtomicBoolean();
        cancel = flag;
        boolean rec = recursive.getSelection(), hid = hidden.getSelection(), content = byContent.getSelection();
        compare.setEnabled(false);
        progress.setText("Comparing…");
        progress.getParent().layout();
        Thread t = new Thread(() -> {
            List<SyncEngine.Entry> result = SyncEngine.compare(l, r, rec, hid, content, flag);
            ToolWindows.ui(display, () -> {
                if (shell.isDisposed() || flag != cancel) return;
                cancel = null;
                entries = result;
                compared = true;
                compare.setEnabled(true);
                progress.setText("");
                refill();
            });
        }, "MxFileMGR Synchronize");
        t.setDaemon(true);
        t.start();
    }

    private void refill() {
        if (table.isDisposed()) return;
        visible = new ArrayList<>();
        for (SyncEngine.Entry e : entries) if (showEqual.getSelection() || e.status != SyncEngine.Status.EQUAL) visible.add(e);
        table.setItemCount(visible.size());
        table.clearAll();
        if (cancel == null) {
            compare.setEnabled(true);
            progress.setText("");
        }
        updateSummary();
    }

    private void updateSummary() {
        if (summary == null || summary.isDisposed()) return;
        int toRight = 0, toLeft = 0, equal = 0;
        for (SyncEngine.Entry e : entries) {
            if (e.action == SyncEngine.Action.TO_RIGHT) toRight++;
            else if (e.action == SyncEngine.Action.TO_LEFT) toLeft++;
            if (e.status == SyncEngine.Status.EQUAL) equal++;
        }
        summary.setText(compared ? toRight + " to copy right, " + toLeft + " to copy left, " + equal + " equal" : "Press Compare.");
        synchronize.setEnabled(toRight + toLeft > 0);
    }

    private void synchronize() {
        Path l = path(leftPath), r = path(rightPath);
        if (l == null || r == null) return;
        List<Path[]> pairs = new ArrayList<>();
        for (SyncEngine.Entry e : entries) {
            switch (e.action) {
                case TO_RIGHT -> pairs.add(new Path[] { l.resolve(e.path), r.resolve(e.path) });
                case TO_LEFT -> pairs.add(new Path[] { r.resolve(e.path), l.resolve(e.path) });
                default -> { }
            }
        }
        if (pairs.isEmpty()) return;
        if (!Dialogs.confirm(shell, "Copy " + Fmt.count(pairs.size(), "item") + "?", "Existing files at the destination are replaced.",
                "Synchronize", false)) return;
        TransferManager.get().copy(pairs, false, ConflictPolicy.OVERWRITE);
        entries = new ArrayList<>();
        compared = false;
        refill();
    }
}
