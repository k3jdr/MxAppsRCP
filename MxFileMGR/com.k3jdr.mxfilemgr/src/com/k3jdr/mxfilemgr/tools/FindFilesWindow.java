package com.k3jdr.mxfilemgr.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.model.NameMatcher;
import com.k3jdr.mxfilemgr.model.SortOrder;
import com.k3jdr.mxfilemgr.win.ShellInfo;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * Find Files (Ctrl+F, Alt+F7 as in Total Commander; SearchView.swift): by name (wildcards or a regular
 * expression), by text the file contains, by size and age, optionally into subfolders and hidden items. Results
 * stream in while the search runs; double-click one to show it in the active pane.
 */
public final class FindFilesWindow {
    static final int MAX_HITS = 20_000;
    static final long CONTENT_LIMIT = 200L * 1024 * 1024;

    record Hit(Path path, String name, String folder, long size, long modified, boolean isDirectory) {}

    record Criteria(Path root, NameMatcher names, String contains, boolean caseSensitive, boolean recursive,
            boolean includeHidden, boolean includeFolders, Long minSize, Long maxSize, Long modifiedAfter) {}

    private final Workspace ws;
    private final Shell shell;
    private final Display display;
    private Text root, name, contains, minKB, maxKB, days;
    private Button regex, caseSensitive, subfolders, hidden, folders, find, showInPane, copyPaths;
    private Table table;
    private Label status;
    private final List<Hit> results = new ArrayList<>();
    private int sortColumn = -1;
    private boolean ascending = true;
    private int scanned;
    private boolean running;
    private AtomicBoolean cancel;

    private FindFilesWindow(Workspace ws, Path start) {
        this.ws = ws;
        this.display = ws.display();
        shell = ToolWindows.create(ws, "find", "Find Files", 820, 560, () -> FileHelp.SEARCH, this::selectionHelp);
        shell.setLayout(Ui.grid(1, 0, 0));
        buildForm(start);
        buildTable();
        buildFooter();
        shell.addListener(SWT.Dispose, e -> stop());
        shell.open();
        name.setFocus();
        name.selectAll();
    }

    public static void open(Workspace ws, Path root) {
        new FindFilesWindow(ws, root);
    }

    // MARK: Form

    private void buildForm(Path start) {
        Composite form = new Composite(shell, SWT.NONE);
        form.setLayout(Ui.grid(2, 12, 8));
        form.setLayoutData(Ui.fillH());

        Ui.label(form, "Search in:");
        Composite r1 = row(form, 2);
        root = text(r1, start == null ? "" : start.toString(), true);
        QuickHelp.help(root, "Search in", "The folder the search starts from; its subfolders are searched too when Subfolders is on. ~ is your user folder.");
        Button choose = Ui.button(r1, "Choose…", this::chooseFolder);
        QuickHelp.help(choose, "Choose", "Picks the folder to search in.");

        Ui.label(form, "Name:");
        Composite r2 = row(form, 2);
        name = text(r2, "*", true);
        name.setMessage("*.txt;*.md");
        QuickHelp.help(name, "Name", "Wildcards such as *.java;*.md (* matches anything, ? one character; separate several with ;), "
                + "or a regular expression when that is on. Press Enter to search.", "Enter");
        regex = check(r2, "Regular expression", "Regular expression", "Treats Name as a regular expression (Java syntax), matched anywhere in the name.");

        Ui.label(form, "Containing text:");
        Composite r3 = row(form, 2);
        contains = text(r3, "", true);
        contains.setMessage("Optional");
        QuickHelp.help(contains, "Containing text", "Finds only files whose contents include this text (folders are then left out). "
                + "Files over 200 MB are skipped. Press Enter to search.", "Enter");
        caseSensitive = check(r3, "Case sensitive", "Case sensitive", "Matches upper and lower case exactly, in the name pattern and the text.");

        Ui.label(form, "Limits:");
        Composite r4 = row(form, 10);
        minKB = small(r4, "min KB", "Minimum size", "Only files of at least this many KB (folders are then left out).");
        maxKB = small(r4, "max KB", "Maximum size", "Only files of at most this many KB (folders are then left out).");
        days = small(r4, "days", "Modified within", "Only items modified within this many days.");
        subfolders = check(r4, "Subfolders", "Subfolders", "Searches the folders inside the starting folder too, all the way down.");
        subfolders.setSelection(true);
        hidden = check(r4, "Hidden items", "Hidden items", "Includes hidden files and folders (and searches inside hidden folders).");
        folders = check(r4, "Folders", "Folders", "Lists folders whose names match, too.");
        folders.setSelection(true);
        Label spacer = new Label(r4, SWT.NONE);
        spacer.setLayoutData(Ui.fillH());
        find = new Button(r4, SWT.PUSH);
        find.setText("Find");
        GridData fd = new GridData(SWT.END, SWT.CENTER, false, false);
        fd.widthHint = 80;
        find.setLayoutData(fd);
        find.addListener(SWT.Selection, e -> {
            if (running) stop();
            else start();
        });
        QuickHelp.help(find, "Find", "Starts the search; results appear as they are found. While it runs this button stops it.", "Enter");
    }

    private static Composite row(Composite parent, int columns) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(Ui.grid(columns, 0, 8));
        c.setLayoutData(Ui.fillH());
        return c;
    }

    private Text text(Composite parent, String value, boolean fill) {
        Text t = new Text(parent, SWT.BORDER | SWT.SINGLE);
        t.setText(value);
        if (fill) t.setLayoutData(Ui.fillH());
        t.addListener(SWT.DefaultSelection, e -> {
            if (!running) start();
        });
        return t;
    }

    private Text small(Composite parent, String hint, String title, String help) {
        Text t = new Text(parent, SWT.BORDER | SWT.SINGLE);
        t.setMessage(hint);
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.widthHint = 60;
        t.setLayoutData(gd);
        t.addListener(SWT.DefaultSelection, e -> {
            if (!running) start();
        });
        QuickHelp.help(t, title, help);
        return t;
    }

    private static Button check(Composite parent, String text, String title, String help) {
        Button b = new Button(parent, SWT.CHECK);
        b.setText(text);
        QuickHelp.help(b, title, help);
        return b;
    }

    private void chooseFolder() {
        DirectoryDialog d = new DirectoryDialog(shell);
        d.setText("Search in");
        d.setFilterPath(root.getText());
        String p = d.open();
        if (p != null) root.setText(p);
    }

    // MARK: Results

    private void buildTable() {
        table = new Table(shell, SWT.VIRTUAL | SWT.MULTI | SWT.FULL_SELECTION | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLinesVisible(false);
        table.setLayoutData(Ui.fill());
        String[] titles = { "Name", "Folder", "Size", "Modified" };
        int[] widths = { 220, 330, 90, 140 };
        for (int i = 0; i < titles.length; i++) {
            TableColumn c = new TableColumn(table, i == 2 ? SWT.RIGHT : SWT.LEFT);
            c.setText(titles[i]);
            c.setWidth(widths[i]);
            c.setMoveable(true);
            int col = i;
            c.addListener(SWT.Selection, e -> sortBy(col));
        }
        table.addListener(SWT.SetData, e -> {
            TableItem item = (TableItem) e.item;
            int index = table.indexOf(item);
            if (index < 0 || index >= results.size()) return;
            Hit h = results.get(index);
            item.setText(new String[] { h.name(), h.folder(), h.isDirectory() ? "" : Fmt.size(h.size()), Fmt.date(h.modified()) });
            item.setForeground(1, Ui.SECONDARY);
            item.setData(h);
        });
        table.addListener(SWT.DefaultSelection, e -> showFirstInPane());
        table.addListener(SWT.Selection, e -> updateButtons());
        QuickHelp.provider(table, (c, p) -> {
            if (p.y >= 0 && p.y < table.getHeaderHeight()) {
                return new QuickHelpItem("Results", "Click a column title to sort by it; drag to reorder.");
            }
            TableItem item = table.getItem(p);
            if (item != null && item.getData() instanceof Hit h) {
                return new QuickHelpItem(h.name(), (h.isDirectory() ? "A folder" : Fmt.size(h.size())) + " in " + h.folder()
                        + (h.modified() > 0 ? ", modified " + Fmt.date(h.modified()) : "")
                        + ". Double-click shows it in the active pane; right-click to open it, show it in Explorer or copy paths.");
            }
            return new QuickHelpItem("Results", "What the search found, as it finds it. Double-click a result to show it in the active pane.");
        });

        Menu menu = new Menu(table);
        table.setMenu(menu);
        List<MenuItem> mine = List.of(
                item(menu, "Show in Pane", this::showFirstInPane),
                item(menu, "Open", () -> selectedHits().forEach(h -> ShellInfo.open(h.path()))),
                item(menu, "Show in Explorer", () -> {
                    List<Hit> s = selectedHits();
                    if (!s.isEmpty()) ShellInfo.showInExplorer(s.get(0).path(), true);
                }),
                item(menu, "Copy Paths", this::copyPaths));
        menu.addListener(SWT.Show, e -> {
            boolean any = table.getSelectionCount() > 0;
            for (MenuItem mi : mine) mi.setEnabled(any);
        });
        TableCopy.addMenuItems(table);
    }

    private static MenuItem item(Menu menu, String text, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(text);
        mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    private void buildFooter() {
        Composite footer = new Composite(shell, SWT.NONE);
        footer.setLayout(Ui.grid(3, 10, 8));
        footer.setLayoutData(Ui.fillH());
        status = new Label(footer, SWT.NONE);
        status.setForeground(Ui.SECONDARY);
        status.setLayoutData(Ui.fillH());
        status.setText("Press Find.");
        QuickHelp.help(status, "Results", "How many items match and how many were looked at so far.");
        showInPane = Ui.button(footer, "Show in Pane", this::showFirstInPane);
        QuickHelp.help(showInPane, "Show in Pane", "Shows the selected result in the active pane, in its folder, selected.");
        copyPaths = Ui.button(footer, "Copy Paths", this::copyPaths);
        QuickHelp.help(copyPaths, "Copy Paths", "Copies the selected results' full paths, one per line.");
        updateButtons();
    }

    private void updateButtons() {
        if (showInPane == null || showInPane.isDisposed()) return;
        int n = table.getSelectionCount();
        showInPane.setEnabled(n == 1);
        copyPaths.setEnabled(n > 0);
    }

    private List<Hit> selectedHits() {
        List<Hit> out = new ArrayList<>();
        for (int i : table.getSelectionIndices()) if (i >= 0 && i < results.size()) out.add(results.get(i));
        return out;
    }

    private void showFirstInPane() {
        List<Hit> s = selectedHits();
        if (!s.isEmpty()) ws.reveal(s.get(0).path());
    }

    private void copyPaths() {
        List<String> paths = new ArrayList<>();
        for (Hit h : selectedHits()) paths.add(h.path().toString());
        paths.sort(null);
        if (!paths.isEmpty()) Clipboards.copyText(display, String.join("\r\n", paths));
    }

    private QuickHelpItem selectionHelp() {
        if (table == null || table.isDisposed()) return null;
        int n = table.getSelectionCount();
        if (n == 0) return null;
        return new QuickHelpItem(Fmt.count(n, "Result") + " Selected", "Show in Pane reveals the first one in the active pane; Copy Paths copies their paths.");
    }

    private void sortBy(int column) {
        if (sortColumn == column) ascending = !ascending;
        else {
            sortColumn = column;
            ascending = true;
        }
        table.setSortColumn(table.getColumn(column));
        table.setSortDirection(ascending ? SWT.UP : SWT.DOWN);
        resort();
    }

    private void resort() {
        if (sortColumn < 0) return;
        Comparator<Hit> c = switch (sortColumn) {
            case 0 -> (a, b) -> SortOrder.NATURAL.compare(a.name(), b.name());
            case 1 -> (a, b) -> SortOrder.NATURAL.compare(a.folder(), b.folder());
            case 2 -> Comparator.comparingLong(Hit::size);
            default -> Comparator.comparingLong(Hit::modified);
        };
        List<Hit> selected = selectedHits();
        results.sort(ascending ? c : c.reversed());
        table.deselectAll();
        table.clearAll();
        if (!selected.isEmpty()) {
            java.util.Set<Hit> keep = new java.util.HashSet<>(selected);
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) if (keep.contains(results.get(i))) idx.add(i);
            table.select(idx.stream().mapToInt(Integer::intValue).toArray());
        }
        updateButtons();
    }

    // MARK: Searching

    private void start() {
        stop();
        Path start;
        try {
            String t = root.getText().trim();
            if (t.startsWith("~")) t = System.getProperty("user.home") + t.substring(1);
            start = Path.of(t);
        } catch (RuntimeException e) {
            status.setText("“" + root.getText() + "” isn't a folder.");
            return;
        }
        if (!Files.isDirectory(start)) {
            status.setText("“" + start + "” isn't a folder.");
            return;
        }
        String pattern = name.getText().trim();
        NameMatcher matcher = new NameMatcher(pattern.isEmpty() ? "*" : pattern, false, regex.getSelection(), caseSensitive.getSelection());
        if (matcher.isInvalid()) {
            status.setText("The regular expression isn't valid.");
            return;
        }
        Criteria c = new Criteria(start, matcher, contains.getText(), caseSensitive.getSelection(), subfolders.getSelection(),
                hidden.getSelection(), folders.getSelection(), kb(minKB), kb(maxKB), daysAgo());
        results.clear();
        table.setItemCount(0);
        table.clearAll();
        scanned = 0;
        running = true;
        find.setText("Stop");
        status.setText("Searching…");
        updateButtons();
        AtomicBoolean flag = new AtomicBoolean();
        cancel = flag;
        Thread t = new Thread(() -> search(c, flag), "MxFileMGR Find Files");
        t.setDaemon(true);
        t.start();
    }

    private void stop() {
        if (cancel != null) cancel.set(true);
        cancel = null;
        if (running) {
            running = false;
            if (!shell.isDisposed()) {
                find.setText("Find");
                showStatus();
            }
        }
    }

    private static Long kb(Text t) {
        try {
            String s = t.getText().trim();
            return s.isEmpty() ? null : Long.parseLong(s) * 1024;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long daysAgo() {
        try {
            String s = days.getText().trim();
            return s.isEmpty() ? null : System.currentTimeMillis() - (long) (Double.parseDouble(s) * 86_400_000L);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void receive(List<Hit> hits, int scannedCount, boolean done, AtomicBoolean flag) {
        results.addAll(hits);
        scanned = scannedCount;
        if (sortColumn >= 0 && !hits.isEmpty()) resort();
        table.setItemCount(results.size());
        if (done) {
            running = false;
            cancel = null;
            find.setText("Find");
        }
        showStatus();
    }

    private void showStatus() {
        if (status.isDisposed()) return;
        status.setText(Fmt.count(results.size(), "match", "matches") + " · " + Fmt.count(scanned, "item") + " scanned"
                + (running ? "…" : "") + (results.size() >= MAX_HITS ? " (stopped at " + Fmt.bytes(MAX_HITS) + ")" : ""));
    }

    /** Walks the tree on a worker thread, handing results to the window in batches. */
    private void search(Criteria c, AtomicBoolean flag) {
        List<Hit> batch = new ArrayList<>();
        int[] counts = new int[2];       // scanned, found
        long[] lastEmit = { System.currentTimeMillis() };
        String needle = c.contains();
        byte[] pattern = needle.isEmpty() ? null
                : (c.caseSensitive() ? needle : needle.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
        try {
            Files.walkFileTree(c.root(), EnumSet.noneOf(FileVisitOption.class), c.recursive() ? Integer.MAX_VALUE : 1,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (flag.get()) return FileVisitResult.TERMINATE;
                            if (dir.equals(c.root())) return FileVisitResult.CONTINUE;
                            if (!c.includeHidden() && attrs instanceof DosFileAttributes d && d.isHidden()) return FileVisitResult.SKIP_SUBTREE;
                            consider(dir, attrs, true);
                            if (FileOps.isLink(dir, attrs)) return FileVisitResult.SKIP_SUBTREE;
                            return counts[1] >= MAX_HITS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (flag.get()) return FileVisitResult.TERMINATE;
                            if (!c.includeHidden() && attrs instanceof DosFileAttributes d && d.isHidden()) return FileVisitResult.CONTINUE;
                            consider(file, attrs, attrs.isDirectory());
                            return counts[1] >= MAX_HITS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return flag.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        private void consider(Path p, BasicFileAttributes a, boolean isDir) {
                            counts[0]++;
                            String fileName = FileOps.name(p);
                            boolean ok = true;
                            if (isDir && (!c.includeFolders() || pattern != null)) ok = false;
                            if (ok && !c.names().matches(fileName)) ok = false;
                            long size = isDir ? 0 : a.size();
                            if (ok && c.minSize() != null && (isDir || size < c.minSize())) ok = false;
                            if (ok && c.maxSize() != null && (isDir || size > c.maxSize())) ok = false;
                            long modified = a.lastModifiedTime().toMillis();
                            if (ok && c.modifiedAfter() != null && modified < c.modifiedAfter()) ok = false;
                            if (ok && pattern != null) ok = a.isRegularFile() && size < CONTENT_LIMIT && fileContains(p, pattern, c.caseSensitive(), flag);
                            if (ok) {
                                batch.add(new Hit(p, fileName, p.getParent() == null ? "" : p.getParent().toString(), size, modified, isDir));
                                counts[1]++;
                            }
                            long now = System.currentTimeMillis();
                            if (batch.size() >= 300 || now - lastEmit[0] > 250) {
                                emit(new ArrayList<>(batch), counts[0], false, flag);
                                batch.clear();
                                lastEmit[0] = now;
                            }
                        }
                    });
        } catch (IOException e) {
            // what was found is shown
        }
        emit(batch, counts[0], true, flag);
    }

    private void emit(List<Hit> hits, int scannedCount, boolean done, AtomicBoolean flag) {
        ToolWindows.ui(display, () -> {
            if (shell.isDisposed() || flag != cancel) return;
            receive(hits, scannedCount, done, flag);
        });
    }

    /** Byte search, with ASCII case folding when not case-sensitive. */
    static boolean fileContains(Path file, byte[] pattern, boolean caseSensitive, AtomicBoolean flag) {
        int m = pattern.length;
        if (m == 0) return true;
        byte[] buf = new byte[(1 << 20) + m];
        int keep = 0;
        try (InputStream in = Files.newInputStream(file)) {
            while (true) {
                if (flag != null && flag.get()) return false;
                int n = in.readNBytes(buf, keep, buf.length - keep);
                int len = keep + n;
                if (len < m) {
                    if (n <= 0) return false;
                    keep = len;
                    continue;
                }
                for (int i = 0; i <= len - m; i++) {
                    if (fold(buf[i], caseSensitive) != pattern[0]) continue;
                    int j = 1;
                    while (j < m && fold(buf[i + j], caseSensitive) == pattern[j]) j++;
                    if (j == m) return true;
                }
                if (n <= 0) return false;
                keep = m - 1;
                System.arraycopy(buf, len - keep, buf, 0, keep);
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static byte fold(byte b, boolean caseSensitive) {
        return !caseSensitive && b >= 'A' && b <= 'Z' ? (byte) (b + 32) : b;
    }
}
