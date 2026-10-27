package com.k3jdr.mxfilemgr.cloud;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.dialogs.Dialogs;
import com.k3jdr.mxfilemgr.model.FileItem;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.model.NameMatcher;
import com.k3jdr.mxfilemgr.ops.CLITool;
import com.k3jdr.mxfilemgr.ops.TransferManager;
import com.k3jdr.mxfilemgr.win.ShellInfo;
import com.k3jdr.mxfilemgr.workspace.BrowserTab;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * The browser window of a cloud storage service or SFTP account (S3View, AzureView, GCSView and SFTPView): pickers
 * on top, the path with Up and a filter, the items, and Download to / Upload from the active pane, New Folder,
 * Rename, Delete and copying links. Everything goes through the service's command-line tool off the UI thread;
 * transfers run in the main window's transfer panel. Files dropped on the list are uploaded.
 */
public final class RemoteBrowser {
    private static final Map<String, RemoteBrowser> OPEN = new HashMap<>();
    static final int MAX_ITEMS = 10_000;

    /** Opens the window for {@code id} (one per service or account), or brings it to the front. */
    public static void open(Workspace ws, String id, Supplier<RemoteBackend> backend) {
        RemoteBrowser b = OPEN.get(id);
        if (b != null && !b.shell.isDisposed()) {
            if (b.shell.getMinimized()) b.shell.setMinimized(false);
            b.shell.forceActive();
            return;
        }
        b = new RemoteBrowser(ws, id, backend.get());
        OPEN.put(id, b);
        b.shell.open();
        b.start();
    }

    /** Closes the window of {@code id} (an SFTP account that was removed). */
    public static void close(String id) {
        RemoteBrowser b = OPEN.get(id);
        if (b != null && !b.shell.isDisposed()) b.shell.close();
    }

    private final Workspace ws;
    private final String id;
    private final RemoteBackend backend;
    private final Shell shell;
    private final int levels;
    private final Combo[] pickers;
    private final List<List<RemoteBackend.Choice>> choices = new ArrayList<>();
    private final List<String> chosen = new ArrayList<>();
    private String prefix = "";
    private List<RemoteEntry> entries = List.of();
    private List<RemoteEntry> visible = List.of();
    private final Set<String> selection = new LinkedHashSet<>();
    private String pendingSelect;
    private boolean truncated, needsLogin, signingIn, installMissing, listed;
    private int loadingCount;
    private String errorText, loginMessage;
    private long levelGeneration, listGeneration;

    private Composite stack;
    private StackLayout stackLayout;
    private Composite installPage, loginPage, listPage;
    private Label installText, loginText, errorLabel, loadingLabel, status, emptyNote;
    private Button signIn, up, refresh, newFolder, upload, delete, download;
    private Link crumbs;
    private List<RemoteBackend.Crumb> crumbList = List.of();
    private Text filter;
    private Table table;
    private Image folderIcon, fileIcon;
    private final Map<String, Image> extIcons = new HashMap<>();

    private RemoteBrowser(Workspace ws, String id, RemoteBackend backend) {
        this.ws = ws;
        this.id = id;
        this.backend = backend;
        this.levels = backend.pickerTitles().size();
        this.pickers = new Combo[levels];
        for (int i = 0; i < levels; i++) {
            choices.add(List.of());
            chosen.add(null);
        }
        shell = new Shell(ws.display(), SWT.SHELL_TRIM);
        shell.setImages(ws.shell().getImages());
        shell.setText(backend.title());
        build();
        Rectangle main = ws.shell().getBounds();
        shell.setBounds(main.x + 60, main.y + 50, Math.min(980, Math.max(760, main.width - 200)), Math.min(640, Math.max(480, main.height - 160)));
        shell.addListener(SWT.Activate, e -> {
            QuickHelp.get().setScreen(this::screenHelp);
            refresh();
        });
        shell.addListener(SWT.Dispose, e -> {
            OPEN.remove(id);
            if (folderIcon != null) folderIcon.dispose();
            if (fileIcon != null) fileIcon.dispose();
            extIcons.values().forEach(Image::dispose);
        });
    }

    // MARK: Building

    private void build() {
        shell.setLayout(Ui.grid(1, 0, 0));

        Composite top = new Composite(shell, SWT.NONE);
        top.setLayout(Ui.grid(levels * 2 + 2, 8, 6));
        top.setLayoutData(Ui.fillH());
        List<String> titles = backend.pickerTitles();
        for (int i = 0; i < levels; i++) {
            final int level = i;
            Ui.label(top, titles.get(i) + ":");
            Combo c = new Combo(top, SWT.READ_ONLY | SWT.DROP_DOWN);
            GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            gd.widthHint = 150;
            c.setLayoutData(gd);
            c.addListener(SWT.Selection, e -> pick(level, c.getSelectionIndex()));
            QuickHelp.help(c, titles.get(i), pickerHelp(titles.get(i)));
            pickers[i] = c;
        }
        if (levels == 0) {
            Label who = Ui.label(top, backend.title());
            who.setFont(Ui.bold());
            Label where = Ui.label(top, backend.uri(chosen, ""));
            where.setForeground(Ui.SECONDARY);
            where.setLayoutData(Ui.fillH());
        } else {
            new Label(top, SWT.NONE).setLayoutData(Ui.fillH());
        }
        refresh = Ui.button(top, "Refresh", this::reload);
        QuickHelp.help(refresh, "Refresh", "Lists this folder again (and the pickers' choices).", "Ctrl+R");

        Composite nav = new Composite(shell, SWT.NONE);
        nav.setLayout(Ui.grid(4, 8, 6));
        nav.setLayoutData(Ui.fillH());
        up = Ui.button(nav, "↑ Up", this::goUp);
        QuickHelp.help(up, "Enclosing Folder", "Goes up to the folder that contains this one.", "Backspace");
        crumbs = new Link(nav, SWT.NONE);
        crumbs.setLayoutData(Ui.fillH());
        crumbs.addListener(SWT.Selection, e -> {
            try {
                int i = Integer.parseInt(e.text);
                if (i >= 0 && i < crumbList.size()) go(crumbList.get(i).prefix());
            } catch (NumberFormatException ignored) {
                // not a crumb
            }
        });
        QuickHelp.help(crumbs, "Path", "The folder's path: click any part to go there.");
        loadingLabel = Ui.label(nav, "Loading…");
        loadingLabel.setForeground(Ui.SECONDARY);
        filter = new Text(nav, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
        filter.setMessage("Filter");
        GridData fg = new GridData(SWT.END, SWT.CENTER, false, false);
        fg.widthHint = 150;
        filter.setLayoutData(fg);
        filter.addListener(SWT.Modify, e -> applyFilter());
        filter.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.ESC) filter.setText("");
        });
        QuickHelp.help(filter, FileHelp.FILTER.title(), FileHelp.FILTER.detail(), FileHelp.FILTER.shortcut());

        errorLabel = new Label(shell, SWT.WRAP);
        errorLabel.setForeground(Ui.RED);
        GridData eg = new GridData(SWT.FILL, SWT.TOP, true, false);
        eg.widthHint = 300;
        eg.horizontalIndent = 8;
        errorLabel.setLayoutData(eg);
        QuickHelp.help(errorLabel, "Error", "What the command-line tool reported. Select Refresh to try again.");

        stack = new Composite(shell, SWT.NONE);
        stackLayout = new StackLayout();
        stack.setLayout(stackLayout);
        stack.setLayoutData(Ui.fill());

        installPage = new Composite(stack, SWT.NONE);
        installPage.setLayout(Ui.grid(1, 30, 12));
        Label it = Ui.label(installPage, backend.title() + " isn't available", Ui.subheading());
        it.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        installText = new Label(installPage, SWT.WRAP | SWT.CENTER);
        GridData ig = new GridData(SWT.FILL, SWT.CENTER, true, false);
        ig.widthHint = 420;
        installText.setLayoutData(ig);
        Button again = Ui.button(installPage, "Check Again", this::start);
        again.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        QuickHelp.help(again, "Check Again", "Looks for the command-line tool again, after installing it. A newly installed tool may need MxFileMGR to be restarted to be on its PATH.");

        loginPage = new Composite(stack, SWT.NONE);
        loginPage.setLayout(Ui.grid(1, 30, 12));
        Label lt = Ui.label(loginPage, "Sign In", Ui.subheading());
        lt.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        loginText = new Label(loginPage, SWT.WRAP | SWT.CENTER);
        GridData lg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        lg.widthHint = 420;
        loginText.setLayoutData(lg);
        signIn = Ui.button(loginPage, "Sign In", this::signIn);
        signIn.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        QuickHelp.help(signIn, "Sign In", "Runs the CLI's sign-in, which opens your browser to approve it; the window then loads again. The app never sees or stores keys.");

        listPage = new Composite(stack, SWT.NONE);
        listPage.setLayout(Ui.grid(1, 0, 0));
        table = new Table(listPage, SWT.VIRTUAL | SWT.MULTI | SWT.FULL_SELECTION | SWT.BORDER);
        table.setLayoutData(Ui.fill());
        table.setHeaderVisible(true);
        table.setLinesVisible(false);
        column("Name", 340, SWT.LEFT);
        column("Size", 90, SWT.RIGHT);
        column("Modified", 150, SWT.LEFT);
        if (backend.classColumn() != null) column(backend.classColumn(), 110, SWT.LEFT);
        table.addListener(SWT.SetData, e -> fill((TableItem) e.item, table.indexOf((TableItem) e.item)));
        table.addListener(SWT.Selection, e -> {
            selection.clear();
            for (int i : table.getSelectionIndices()) if (i < visible.size()) selection.add(visible.get(i).key());
            refreshStatus();
        });
        table.addListener(SWT.DefaultSelection, e -> {
            int i = table.getSelectionIndex();
            if (i >= 0 && i < visible.size()) activate(visible.get(i));
        });
        table.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.BS) { goUp(); e.doit = false; }
            else if (e.keyCode == SWT.DEL) { delete(selected()); e.doit = false; }
            else if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'r' || e.keyCode == 'R')) { reload(); e.doit = false; }
            else if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'c' || e.keyCode == 'C')) { copyUris(selected()); e.doit = false; }
            else if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'a' || e.keyCode == 'A')) { table.selectAll(); table.notifyListeners(SWT.Selection, null); e.doit = false; }
        });
        QuickHelp.provider(table, (c, p) -> {
            if (p.y < table.getHeaderHeight()) return null;
            TableItem ti = table.getItem(p);
            if (ti != null) {
                int i = table.indexOf(ti);
                if (i >= 0 && i < visible.size()) return describe(visible.get(i));
            }
            return FileHelp.remoteList(backend.shortName());
        });
        Menu menu = new Menu(table);
        table.setMenu(menu);
        menu.addListener(SWT.Show, e -> fillMenu(menu));
        DropTarget drop = new DropTarget(table, DND.DROP_COPY | DND.DROP_DEFAULT);
        drop.setTransfer(new Transfer[] { FileTransfer.getInstance() });
        drop.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetEvent e) { e.detail = ready() ? DND.DROP_COPY : DND.DROP_NONE; }

            @Override
            public void dragOperationChanged(DropTargetEvent e) { e.detail = ready() ? DND.DROP_COPY : DND.DROP_NONE; }

            @Override
            public void drop(DropTargetEvent e) {
                if (e.data instanceof String[] names) {
                    List<Path> paths = new ArrayList<>();
                    for (String n : names) paths.add(Path.of(n));
                    upload(paths);
                }
            }
        });
        emptyNote = Ui.label(listPage, "");
        emptyNote.setForeground(Ui.SECONDARY);
        emptyNote.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

        Composite footer = new Composite(shell, SWT.NONE);
        footer.setLayout(Ui.grid(5, 8, 6));
        footer.setLayoutData(Ui.fillH());
        status = Ui.label(footer, "");
        status.setForeground(Ui.SECONDARY);
        status.setLayoutData(Ui.fillH());
        newFolder = Ui.button(footer, "New Folder…", this::newFolder);
        QuickHelp.help(newFolder, "New Folder", backend.virtualFolders() ? "Opens a new folder here. " + backend.virtualFolderNote()
                : "Creates a folder in this folder.");
        upload = Ui.button(footer, "Upload", this::uploadMarked);
        QuickHelp.help(upload, "Upload", FileHelp.UPLOAD_DETAIL);
        delete = Ui.button(footer, "Delete…", () -> delete(selected()));
        QuickHelp.help(delete, "Delete", "Deletes the selected items, and folders with everything in them, after asking. There is no Recycle Bin.", "Delete");
        download = Ui.button(footer, "Download", () -> download(selected()));
        QuickHelp.help(download, "Download", FileHelp.DOWNLOAD_DETAIL, "Enter");
        shell.setDefaultButton(download);

        folderIcon = icon("folder", true);
        fileIcon = icon("file", false);
        stackLayout.topControl = listPage;
    }

    private void column(String title, int width, int align) {
        TableColumn c = new TableColumn(table, align);
        c.setText(title);
        c.setWidth(width);
        c.setMoveable(true);
    }

    private Image icon(String name, boolean folder) {
        ImageData d = ShellInfo.smallIcon(name, true, folder);
        return d == null ? null : new Image(shell.getDisplay(), d);
    }

    private Image iconFor(RemoteEntry e) {
        if (e.isFolder()) return folderIcon;
        int dot = e.name().lastIndexOf('.');
        if (dot <= 0) return fileIcon;
        String ext = e.name().substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.length() > 8) return fileIcon;
        return extIcons.computeIfAbsent(ext, x -> {
            Image i = icon("file." + x, false);
            return i != null ? i : fileIcon;
        });
    }

    private static String pickerHelp(String title) {
        return switch (title) {
            case "Profile" -> "The AWS profile from your .aws\\config and .aws\\credentials files the CLI signs in with.";
            case "Bucket" -> "The bucket to browse.";
            case "Subscription" -> "The Azure subscription whose storage accounts are listed.";
            case "Account" -> "The storage account to browse.";
            case "Container" -> "The blob container to browse.";
            case "Project" -> "The Google Cloud project whose buckets are listed.";
            default -> "Choose what to browse.";
        };
    }

    private void fill(TableItem item, int index) {
        if (index < 0 || index >= visible.size()) return;
        RemoteEntry e = visible.get(index);
        item.setText(0, e.name());
        item.setText(1, e.isFolder() ? "" : Fmt.size(e.size()));
        item.setText(2, Fmt.date(e.modified()));
        if (table.getColumnCount() > 3) item.setText(3, e.storageClass() == null ? "" : e.storageClass());
        Image i = iconFor(e);
        if (i != null) item.setImage(0, i);
        if (e.isFolder()) item.setForeground(1, Ui.SECONDARY);
        item.setForeground(2, Ui.SECONDARY);
    }

    private void fillMenu(Menu menu) {
        for (MenuItem old : menu.getItems()) old.dispose();
        List<RemoteEntry> items = selected();
        if (items.isEmpty()) {
            add(menu, "New Folder…", this::newFolder, ready());
            add(menu, "Upload " + markedText(), this::uploadMarked, ready() && !ws.marked().isEmpty());
            add(menu, "Refresh", this::reload, true);
            return;
        }
        if (items.size() == 1 && items.get(0).isFolder()) add(menu, "Open", () -> activate(items.get(0)), true);
        Path pane = ws.activeTab().url();
        add(menu, pane == null ? "Download" : "Download to “" + BrowserTab.displayName(pane) + "”", () -> download(items), pane != null);
        new MenuItem(menu, SWT.SEPARATOR);
        add(menu, backend.copyUriTitle(), () -> copyUris(items), true);
        if (backend.copyLinkTitle() != null) {
            add(menu, backend.copyLinkTitle(), () -> copyLinks(items), items.stream().anyMatch(i -> !i.isFolder()));
        }
        new MenuItem(menu, SWT.SEPARATOR);
        add(menu, "Rename…", () -> rename(items.get(0)), items.size() == 1 && backend.canRename(items.get(0)));
        add(menu, "Delete…", () -> delete(items), true);
    }

    private static void add(Menu menu, String text, Runnable action, boolean enabled) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(Ui.escape(text));
        mi.setEnabled(enabled);
        mi.addListener(SWT.Selection, e -> action.run());
    }

    // MARK: State

    private boolean ready() {
        if (installMissing || needsLogin) return false;
        for (String c : chosen) if (c == null) return false;
        return listed;
    }

    private List<RemoteEntry> selected() {
        List<RemoteEntry> out = new ArrayList<>();
        for (RemoteEntry e : entries) if (selection.contains(e.key())) out.add(e);
        return out;
    }

    private String markedText() {
        Path pane = ws.activeTab().url();
        return "from “" + (pane == null ? "This PC" : BrowserTab.displayName(pane)) + "”";
    }

    private QuickHelpItem screenHelp() {
        QuickHelpItem sel = shell.isDisposed() ? null : selectionHelp(selected());
        return sel != null ? sel : backend.screenHelp();
    }

    private QuickHelpItem selectionHelp(List<RemoteEntry> items) {
        if (items.isEmpty()) return null;
        if (items.size() == 1) return describe(items.get(0));
        long bytes = 0;
        for (RemoteEntry e : items) bytes += e.size();
        return new QuickHelpItem(items.size() + " Items Selected", Fmt.size(bytes) + " in files. Download (Enter) copies them to the active pane; Delete removes them.");
    }

    private QuickHelpItem describe(RemoteEntry e) {
        if (e.isFolder()) return new QuickHelpItem(e.name(), "A folder. Double-click to open it.");
        String cls = e.storageClass() == null || e.storageClass().isEmpty() ? "" : ", " + e.storageClass();
        return new QuickHelpItem(e.name(), Fmt.size(e.size()) + (e.modified() > 0 ? ", modified " + Fmt.date(e.modified()) : "") + cls
                + ". Download (Enter) copies it to the active pane.");
    }

    private void setLoading(boolean on) {
        loadingCount = Math.max(0, loadingCount + (on ? 1 : -1));
        if (!loadingLabel.isDisposed()) loadingLabel.setVisible(loadingCount > 0);
    }

    private void report(Throwable err) {
        String message = TransferManager.message(err);
        if (err instanceof RemoteBackend.SignInNeeded) {
            needsLogin = true;
            loginMessage = message;
            errorText = null;
        } else {
            needsLogin = backend.needsLogin(message);
            loginMessage = null;
            errorText = message;
        }
        refresh();
    }

    // MARK: Loading

    private void start() {
        installMissing = backend.cliPath() == null;
        errorText = null;
        needsLogin = false;
        if (installMissing) {
            refresh();
            return;
        }
        refresh();
        if (levels == 0) {
            prefix = backend.initialPrefix();
            list();
        } else {
            loadLevel(0);
        }
    }

    private void reload() {
        if (installMissing || needsLogin) {
            start();
            return;
        }
        if (levels > 0 && (chosen.get(0) == null)) loadLevel(0);
        else list();
    }

    private void loadLevel(int level) {
        if (level >= levels) {
            prefix = backend.initialPrefix();
            list();
            return;
        }
        List<String> above = new ArrayList<>(chosen.subList(0, level));
        long gen = ++levelGeneration;
        setLoading(true);
        Background.run(shell, () -> backend.choices(level, above), list -> {
            setLoading(false);
            if (gen != levelGeneration) return;
            choices.set(level, list);
            Combo c = pickers[level];
            c.removeAll();
            for (RemoteBackend.Choice ch : list) c.add(ch.label());
            for (int i = level; i < levels; i++) chosen.set(i, null);
            for (int i = level + 1; i < levels; i++) {
                pickers[i].removeAll();
                choices.set(i, List.of());
            }
            if (list.isEmpty()) {
                entries = List.of();
                listed = false;
                errorText = null;
                needsLogin = false;
                emptyNote.setText("No " + backend.pickerTitles().get(level).toLowerCase(Locale.ROOT) + "s to choose from.");
                applyFilter();
                refresh();
                return;
            }
            String saved = backend.savedChoice(level);
            int pick = -1;
            for (int i = 0; i < list.size() && saved != null; i++) if (list.get(i).id().equals(saved)) pick = i;
            for (int i = 0; i < list.size() && pick < 0; i++) if (list.get(i).isDefault()) pick = i;
            if (pick < 0) pick = 0;
            c.select(pick);
            chosen.set(level, list.get(pick).id());
            errorText = null;
            needsLogin = false;
            refresh();
            loadLevel(level + 1);
        }, err -> {
            setLoading(false);
            if (gen != levelGeneration) return;
            report(err);
        });
    }

    private void pick(int level, int index) {
        List<RemoteBackend.Choice> list = choices.get(level);
        if (index < 0 || index >= list.size()) return;
        String picked = list.get(index).id();
        if (picked.equals(chosen.get(level))) return;
        chosen.set(level, picked);
        backend.saveChoice(level, picked);
        for (int i = level + 1; i < levels; i++) {
            chosen.set(i, null);
            pickers[i].removeAll();
        }
        selection.clear();
        entries = List.of();
        listed = false;
        applyFilter();
        loadLevel(level + 1);
    }

    private void list() {
        for (String c : chosen) if (c == null) return;
        long gen = ++listGeneration;
        String p = prefix;
        List<String> ch = new ArrayList<>(chosen);
        setLoading(true);
        refresh();
        Background.run(shell, () -> backend.list(ch, p), result -> {
            setLoading(false);
            if (gen != listGeneration) return;
            entries = result.entries();
            prefix = result.prefix();
            truncated = result.truncated();
            listed = true;
            errorText = null;
            needsLogin = false;
            Set<String> keys = new LinkedHashSet<>();
            for (RemoteEntry e : entries) keys.add(e.key());
            selection.retainAll(keys);
            if (pendingSelect != null && keys.contains(pendingSelect)) {
                selection.clear();
                selection.add(pendingSelect);
            }
            pendingSelect = null;
            emptyNote.setText(entries.isEmpty() ? "Empty folder — drop files here, or use Upload, to add them." : "");
            applyFilter();
            refresh();
        }, err -> {
            setLoading(false);
            if (gen != listGeneration) return;
            report(err);
        });
    }

    private void applyFilter() {
        if (table.isDisposed()) return;
        String f = filter.getText().trim();
        if (f.isEmpty()) {
            visible = entries;
        } else {
            NameMatcher m = new NameMatcher(f, true, false, false);
            List<RemoteEntry> v = new ArrayList<>();
            for (RemoteEntry e : entries) if (m.matches(e.name())) v.add(e);
            visible = v;
        }
        table.setRedraw(false);
        table.removeAll();
        table.setItemCount(visible.size());
        List<Integer> sel = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) if (selection.contains(visible.get(i).key())) sel.add(i);
        table.setSelection(sel.stream().mapToInt(Integer::intValue).toArray());
        if (!sel.isEmpty()) table.showSelection();
        table.setRedraw(true);
        Ui.setVisible(emptyNote, !emptyNote.getText().isEmpty());
        listPage.layout();
        refreshStatus();
    }

    // MARK: Showing

    private void refresh() {
        if (shell.isDisposed()) return;
        boolean ready = ready();
        if (installMissing) {
            installText.setText(backend.installHint());
            stackLayout.topControl = installPage;
        } else if (needsLogin) {
            loginText.setText(loginMessage != null ? loginMessage : backend.signInText(chosen));
            signIn.setText(signingIn ? "Waiting for the browser…" : "Sign In");
            signIn.setEnabled(!signingIn && backend.signInArgs(chosen) != null);
            stackLayout.topControl = loginPage;
        } else {
            stackLayout.topControl = listPage;
        }
        errorLabel.setText(errorText == null ? "" : errorText);
        Ui.setVisible(errorLabel, errorText != null && !needsLogin);
        for (Combo c : pickers) c.setEnabled(!installMissing && c.getItemCount() > 0);
        crumbList = ready || listed ? backend.crumbs(chosen, prefix) : List.of();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < crumbList.size(); i++) {
            String label = crumbList.get(i).label().replace("<", "").replace(">", "").replace("&", "&&");
            if (i > 0) sb.append("  ›  ");
            if (i == crumbList.size() - 1) sb.append(label);
            else sb.append("<a href=\"").append(i).append("\">").append(label).append("</a>");
        }
        crumbs.setText(sb.toString());
        up.setEnabled(ready && backend.parent(prefix) != null);
        refresh.setEnabled(!installMissing);
        filter.setEnabled(ready);
        Path pane = ws.activeTab().url();
        String paneName = pane == null ? "This PC" : BrowserTab.displayName(pane);
        upload.setText(Ui.escape("Upload from “" + paneName + "”"));
        download.setText(Ui.escape("Download to “" + paneName + "”"));
        List<FileItem> marked = ws.marked();
        newFolder.setEnabled(ready);
        upload.setEnabled(ready && !marked.isEmpty() && pane != null);
        loadingLabel.setVisible(loadingCount > 0);
        shell.setText(backend.title() + (ready ? " — " + backend.uri(chosen, prefix) : ""));
        refreshStatus();
        stack.layout();
        shell.layout(true, true);
    }

    private void refreshStatus() {
        if (status.isDisposed()) return;
        List<RemoteEntry> sel = selected();
        String text = Fmt.count(entries.size(), "item");
        if (!sel.isEmpty()) {
            long bytes = 0;
            for (RemoteEntry e : sel) bytes += e.size();
            text = sel.size() + " of " + text + " selected, " + Fmt.size(bytes);
        }
        if (truncated) text += " · first " + Fmt.count(MAX_ITEMS, "item") + " shown";
        if (!listed) text = "";
        status.setText(text);
        boolean ready = ready();
        delete.setEnabled(ready && !sel.isEmpty());
        download.setEnabled(ready && !sel.isEmpty() && ws.activeTab().url() != null);
    }

    // MARK: Commands

    private void go(String newPrefix) {
        prefix = newPrefix;
        selection.clear();
        if (!filter.getText().isEmpty()) filter.setText("");
        list();
    }

    private void goUp() {
        String p = backend.parent(prefix);
        if (p != null && ready()) go(p);
    }

    private void activate(RemoteEntry e) {
        if (e.isFolder()) {
            go(e.key());
            return;
        }
        if (e.storageClass() != null && e.storageClass().startsWith("l")) {
            List<String> ch = new ArrayList<>(chosen);
            setLoading(true);
            Background.run(shell, () -> backend.isFolderLink(ch, e), folder -> {
                setLoading(false);
                if (folder) go(e.path() + "/");
                else download(List.of(e));
            }, err -> {
                setLoading(false);
                download(List.of(e));
            });
            return;
        }
        download(List.of(e));
    }

    private void download(List<RemoteEntry> items) {
        if (items.isEmpty() || !ready()) {
            shell.getDisplay().beep();
            return;
        }
        Path folder = ws.activeTab().url();
        if (folder == null) {
            Dialogs.message(shell, "The active pane shows This PC.", "Open a folder there to download into.");
            return;
        }
        backend.download(new ArrayList<>(chosen), items, folder);
    }

    private void uploadMarked() {
        List<Path> paths = new ArrayList<>();
        for (FileItem i : ws.marked()) if (!i.isDrive()) paths.add(i.path());
        if (paths.isEmpty()) {
            shell.getDisplay().beep();
            return;
        }
        upload(paths);
    }

    private void upload(List<Path> paths) {
        if (paths.isEmpty() || !ready()) {
            shell.getDisplay().beep();
            return;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (RemoteEntry e : entries) existing.add(e.name());
        List<String> clashes = new ArrayList<>();
        for (Path p : paths) if (existing.contains(p.getFileName().toString())) clashes.add(p.getFileName().toString());
        if (!clashes.isEmpty() && !Dialogs.confirm(shell, "Replace " + Fmt.count(clashes.size(), "item") + " in " + backend.uri(chosen, prefix) + "?",
                String.join("\n", clashes.subList(0, Math.min(10, clashes.size()))), "Replace", false)) {
            return;
        }
        backend.upload(new ArrayList<>(chosen), prefix, paths, () -> Ui.async(table, this::list));
    }

    private void newFolder() {
        if (!ready()) return;
        boolean virtual = backend.virtualFolders();
        String name = Dialogs.prompt(shell, "New Folder", virtual ? backend.virtualFolderNote() : "Create a folder in " + backend.uri(chosen, prefix),
                "New folder", virtual ? "Open" : "Create", false, RemoteBrowser::nameProblem, null);
        if (name == null) return;
        if (virtual) {
            prefix = prefix + name + "/";
            selection.clear();
            entries = List.of();
            emptyNote.setText("Empty folder — upload into it to keep it.");
            applyFilter();
            refresh();
            return;
        }
        List<String> ch = new ArrayList<>(chosen);
        String p = prefix;
        setLoading(true);
        Background.run(shell, () -> {
            backend.createFolder(ch, p, name);
            return true;
        }, ok -> {
            setLoading(false);
            pendingSelect = p + name + "/";
            list();
        }, err -> {
            setLoading(false);
            report(err);
        });
    }

    private static String nameProblem(String n) {
        return n.contains("/") || n.contains("\\") ? "A name can't contain / or \\." : null;
    }

    private void rename(RemoteEntry item) {
        if (!backend.canRename(item)) {
            Dialogs.message(shell, "Folders can't be renamed here.", "Blob Storage has no real folders; rename the files inside instead.");
            return;
        }
        String name = Dialogs.prompt(shell, "Rename", backend.uri(chosen, item.key()), item.name(), "Rename", !item.isFolder(), n -> {
            String p = nameProblem(n);
            if (p != null) return p;
            if (!n.equals(item.name())) for (RemoteEntry e : entries) if (e.name().equals(n)) return "“" + n + "” already exists in this folder.";
            return null;
        }, null);
        if (name == null || name.equals(item.name())) return;
        List<String> ch = new ArrayList<>(chosen);
        String p = prefix;
        setLoading(true);
        Background.run(shell, () -> backend.rename(ch, p, item, name), key -> {
            setLoading(false);
            pendingSelect = key;
            list();
        }, err -> {
            setLoading(false);
            report(err);
        });
    }

    private void delete(List<RemoteEntry> items) {
        if (items.isEmpty() || !ready()) {
            shell.getDisplay().beep();
            return;
        }
        boolean folders = items.stream().anyMatch(RemoteEntry::isFolder);
        String what = items.size() == 1 ? "“" + items.get(0).name() + "”" : Fmt.count(items.size(), "item");
        if (!Dialogs.confirm(shell, "Delete " + what + " from " + backend.title() + "?", backend.deleteInfo(folders), "Delete", true)) return;
        backend.delete(new ArrayList<>(chosen), items, () -> Ui.async(table, this::list));
        selection.clear();
        refreshStatus();
    }

    private void copyUris(List<RemoteEntry> items) {
        if (items.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        for (RemoteEntry e : items) lines.add(backend.uri(chosen, e.key()));
        Clipboards.copyText(shell.getDisplay(), String.join("\r\n", lines));
    }

    private void copyLinks(List<RemoteEntry> items) {
        List<String> ch = new ArrayList<>(chosen);
        setLoading(true);
        Background.run(shell, () -> backend.links(ch, items), links -> {
            setLoading(false);
            if (!links.isEmpty()) Clipboards.copyText(shell.getDisplay(), String.join("\r\n", links));
        }, err -> {
            setLoading(false);
            report(err);
        });
    }

    private void signIn() {
        List<String> args = backend.signInArgs(chosen);
        String cli = backend.cliPath();
        if (args == null || cli == null) return;
        signingIn = true;
        refresh();
        Background.run(shell, () -> CLITool.run(cli, args, backend.signInEnvironment()), out -> {
            signingIn = false;
            needsLogin = false;
            loginMessage = null;
            start();
        }, err -> {
            signingIn = false;
            report(err);
        });
    }
}
