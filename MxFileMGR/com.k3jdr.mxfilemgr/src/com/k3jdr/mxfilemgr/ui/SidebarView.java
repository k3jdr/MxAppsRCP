package com.k3jdr.mxfilemgr.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.Settings;
import com.k3jdr.mxfilemgr.cloud.CloudWindows;
import com.k3jdr.mxfilemgr.cloud.SftpAccount;
import com.k3jdr.mxfilemgr.cloud.SftpStore;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Places;
import com.k3jdr.mxfilemgr.win.ShellInfo;
import com.k3jdr.mxfilemgr.workspace.BrowserTab;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * The sidebar (SidebarView.swift): Favorites — the cloud folders on this PC, SharePoint and Teams when nothing is
 * synced yet, the cloud object stores, SFTP accounts, then your folders (drop folders to add, drag to reorder,
 * right-click to remove) —; Locations — the drives with their free space and ⏏ for removable and network drives —;
 * Servers — Connect to Server… and recent addresses.
 */
public final class SidebarView {
    /** One row: what clicking does, its Quick Help, and its context menu. */
    private record Entry(String kind, String title, Path path, Object payload, QuickHelpItem help, Runnable action) {}

    private final Workspace ws;
    private final Tree tree;
    private final TreeColumn nameColumn, ejectColumn;
    private List<Places.Volume> volumes = List.of();
    private List<Places.Cloud> clouds = List.of();
    private String signature = "";

    public SidebarView(Composite parent, Workspace ws) {
        this.ws = ws;
        tree = new Tree(parent, SWT.SINGLE | SWT.FULL_SELECTION | SWT.NO_SCROLL | SWT.V_SCROLL);
        tree.setBackground(Ui.SIDEBAR);
        tree.setLayoutData(Ui.fill());
        tree.setHeaderVisible(false);
        nameColumn = new TreeColumn(tree, SWT.LEFT);
        ejectColumn = new TreeColumn(tree, SWT.CENTER);
        tree.addListener(SWT.Resize, e -> fitColumns());
        tree.addListener(SWT.MouseUp, e -> {
            if (e.button != 1) return;
            TreeItem ti = tree.getItem(new Point(e.x, e.y));
            if (ti == null) return;
            if (ti.getData() instanceof Entry entry) {
                if (entry.payload() instanceof Places.Volume v && v.isEjectable() && e.x >= nameColumn.getWidth()) ws.eject(v);
                else if (entry.action() != null) entry.action().run();
            }
            tree.getDisplay().asyncExec(() -> {
                if (!tree.isDisposed()) tree.deselectAll();
            });
        });
        tree.addListener(SWT.DefaultSelection, e -> {
            if (e.item instanceof TreeItem ti && ti.getData() instanceof Entry entry && entry.action() != null) entry.action().run();
        });
        tree.addListener(SWT.MenuDetect, e -> {
            Point p = tree.toControl(e.x, e.y);
            TreeItem ti = tree.getItem(p);
            Menu old = tree.getMenu();
            if (old != null) old.dispose();
            tree.setMenu(ti != null && ti.getData() instanceof Entry entry ? menu(entry) : null);
        });
        QuickHelp.provider(tree, (c, p) -> {
            TreeItem ti = tree.getItem(p);
            if (ti == null) return FileHelp.FAVORITES;
            if (ti.getData() instanceof Entry entry) {
                if (entry.payload() instanceof Places.Volume v && v.isEjectable() && p.x >= nameColumn.getWidth()) {
                    return new QuickHelpItem(v.isNetwork() ? "Disconnect" : "Eject",
                            v.isNetwork() ? "Disconnects the network drive “" + v.title() + "”." : "Ejects “" + v.title() + "” so it can be unplugged safely.");
                }
                return entry.help();
            }
            if (ti.getData() instanceof QuickHelpItem group) return group;
            return null;
        });
        installDragAndDrop();
        Settings.get().addListener(what -> {
            if (Settings.SIDEBAR.equals(what)) Ui.async(tree, this::rebuild);
        });
        SftpStore.get().addListener(() -> Ui.async(tree, this::rebuild));
        clouds = Places.cloudLocations();
        rebuild();
        poll();
    }

    public Tree tree() { return tree; }

    private void fitColumns() {
        int w = tree.getClientArea().width;
        ejectColumn.setWidth(24);
        nameColumn.setWidth(Math.max(60, w - 24));
    }

    /** Drives and cloud folders come and go: looked at every few seconds in the background. */
    private void poll() {
        Background.run(tree, () -> {
            List<Places.Volume> v = Places.volumes();
            List<Places.Cloud> c = Places.rescanClouds();
            return new Object[] { v, c };
        }, r -> {
            @SuppressWarnings("unchecked") List<Places.Volume> v = (List<Places.Volume>) r[0];
            @SuppressWarnings("unchecked") List<Places.Cloud> c = (List<Places.Cloud>) r[1];
            String sig = v.toString().replaceAll("free=\\d+", "") + c;
            volumes = v;
            clouds = c;
            if (!sig.equals(signature)) {
                signature = sig;
                rebuild();
            } else {
                refreshVolumeText();
            }
            tree.getDisplay().timerExec(5000, () -> {
                if (!tree.isDisposed()) poll();
            });
        }, err -> tree.getDisplay().timerExec(5000, () -> {
            if (!tree.isDisposed()) poll();
        }));
    }

    private void refreshVolumeText() {
        for (TreeItem group : tree.getItems()) {
            for (TreeItem ti : group.getItems()) {
                if (ti.getData() instanceof Entry entry && entry.payload() instanceof Places.Volume old) {
                    for (Places.Volume v : volumes) {
                        if (v.root().equals(old.root())) {
                            ti.setData(volumeEntry(v));
                        }
                    }
                }
            }
        }
    }

    private void rebuild() {
        if (tree.isDisposed()) return;
        tree.setRedraw(false);
        tree.removeAll();
        Settings s = Settings.get();
        List<String> favorites = s.favorites();

        TreeItem fav = group("Favorites", FileHelp.FAVORITES);
        for (Places.Cloud c : clouds) {
            if (favorites.stream().anyMatch(f -> f.equalsIgnoreCase(c.path().toString()))) continue;
            add(fav, new Entry("cloud", c.name(), c.path(), c, new QuickHelpItem(c.name(), FileHelp.cloudFolder(c.name(), c.path())),
                    () -> ws.go(c.path())), Icons.forPath(c.path(), "cloud"), null);
        }
        if (clouds.stream().noneMatch(c -> "sharepoint".equals(c.icon()))) {
            add(fav, new Entry("window", "SharePoint", null, null, FileHelp.SHAREPOINT_SETUP, ws::openSharePoint), Icons.named("cloud"), null);
        }
        if (clouds.stream().noneMatch(c -> "teams".equals(c.icon()))) {
            add(fav, new Entry("window", "Microsoft Teams", null, null, FileHelp.TEAMS_SETUP, ws::openTeams), Icons.named("teams"), null);
        }
        if (CloudWindows.isS3Available()) {
            add(fav, new Entry("window", "Amazon S3", null, null, FileHelp.S3, ws::openS3), Icons.named("server"), null);
        }
        add(fav, new Entry("window", "Azure Blob Storage", null, null, FileHelp.AZURE, ws::openAzure), Icons.named("server"), null);
        add(fav, new Entry("window", "Google Cloud Storage", null, null, FileHelp.GCS, ws::openGCS), Icons.named("cloud"), null);
        for (SftpAccount a : SftpStore.get().accounts()) {
            add(fav, new Entry("sftp", a.name(), null, a, new QuickHelpItem(a.name(), a.destination()
                    + (a.port() == null || a.port().isBlank() ? "" : ":" + a.port()) + (a.path() == null || a.path().isBlank() ? "" : " in " + a.path())
                    + ". Click to open it in its own window: browse, download to and upload from the active pane, rename, delete. Right-click to edit or remove it."),
                    () -> SftpStore.get().open(ws, a)), Icons.named("sftp"), null);
        }
        add(fav, new Entry("window", "Add SFTP Account…", null, null, FileHelp.ADD_SFTP, () -> SftpStore.get().add(ws)), Icons.named("add"), null);
        for (String f : favorites) {
            Path p = Path.of(f);
            String title = favoriteTitle(p);
            add(fav, new Entry("favorite", title, p, f, new QuickHelpItem(title, "Opens " + f
                    + " in the active pane. Right-click to open it in a new tab or the other pane, to move it up or down, or to remove it from Favorites; drag to reorder."),
                    () -> ws.go(p)), Files.isDirectory(p) ? Icons.forPath(p, "folder") : Icons.named("folder"), null);
        }
        fav.setExpanded(true);

        TreeItem loc = group("Locations", FileHelp.LOCATIONS);
        add(loc, new Entry("pc", "This PC", null, null, new QuickHelpItem("This PC", "Lists the drives of this PC in the active pane."),
                () -> ws.go(null)), Icons.named("drive"), null);
        for (Places.Volume v : volumes) add(loc, volumeEntry(v), Icons.forPath(v.root(), v.isNetwork() ? "network" : "drive"), v.isEjectable() ? "⏏" : null);
        loc.setExpanded(true);

        TreeItem srv = group("Servers", FileHelp.SERVERS);
        add(srv, new Entry("connect", "Connect to Server…", null, null, FileHelp.CONNECT, () -> ws.connectToServer("")), Icons.named("network"), null);
        for (String address : s.recentServers()) {
            add(srv, new Entry("recent", address, null, address, new QuickHelpItem(address,
                    "Opens Connect to Server with this address filled in. Right-click to remove it from the list."),
                    () -> ws.connectToServer(address)), Icons.named("server"), null);
        }
        srv.setExpanded(true);
        fitColumns();
        tree.setRedraw(true);
    }

    private Entry volumeEntry(Places.Volume v) {
        return new Entry("volume", v.title(), v.root(), v, new QuickHelpItem(v.title(), "Opens the drive in the active pane. " + v.capacityText() + "."),
                () -> ws.go(v.root()));
    }

    private static String favoriteTitle(Path p) {
        Places.Cloud c = Places.cloudAt(p);
        if (c != null) return c.name();
        if (p.equals(Places.home())) return FileOps.name(p) + " (Home)";
        return BrowserTab.displayName(p);
    }

    private TreeItem group(String title, QuickHelpItem help) {
        TreeItem g = new TreeItem(tree, SWT.NONE);
        g.setText(0, title);
        g.setFont(Ui.bold());
        g.setForeground(Ui.SECONDARY);
        g.setData(help);
        return g;
    }

    private void add(TreeItem group, Entry e, Image icon, String trailing) {
        TreeItem ti = new TreeItem(group, SWT.NONE);
        ti.setText(0, e.title());
        if (icon != null) ti.setImage(0, icon);
        if (trailing != null) ti.setText(1, trailing);
        ti.setData(e);
    }

    // MARK: Context menus

    private Menu menu(Entry e) {
        Menu m = new Menu(tree);
        switch (e.kind()) {
            case "cloud", "favorite", "volume", "pc" -> {
                item(m, "Open", e.action());
                item(m, "Open in New Tab", () -> {
                    ws.activePane().newTab(e.path(), false);
                    ws.focus(ws.activeSide());
                });
                item(m, "Open in Other Pane", () -> ws.otherTab().navigate(e.path()));
                if (e.path() != null) item(m, "Show in Explorer", () -> ShellInfo.showInExplorer(e.path(), false));
                if (e.payload() instanceof Places.Volume v) {
                    if (v.isEjectable()) item(m, v.isNetwork() ? "Disconnect" : "Eject", () -> ws.eject(v));
                    item(m, "Windows Properties…", () -> ShellInfo.shellProperties(v.root()));
                }
                if ("favorite".equals(e.kind())) {
                    String f = (String) e.payload();
                    new MenuItem(m, SWT.SEPARATOR);
                    item(m, "Move Up", () -> Settings.get().moveFavorite(f, -1));
                    item(m, "Move Down", () -> Settings.get().moveFavorite(f, 1));
                    item(m, "Remove from Favorites", () -> Settings.get().removeFavorite(f));
                }
            }
            case "sftp" -> {
                SftpAccount a = (SftpAccount) e.payload();
                item(m, "Open", e.action());
                item(m, "Edit…", () -> SftpStore.get().edit(ws, a));
                new MenuItem(m, SWT.SEPARATOR);
                item(m, "Move Up", () -> SftpStore.get().move(a, -1));
                item(m, "Move Down", () -> SftpStore.get().move(a, 1));
                item(m, "Remove Account…", () -> SftpStore.get().remove(ws.shell(), a));
            }
            case "recent" -> {
                item(m, "Connect…", e.action());
                item(m, "Remove", () -> Settings.get().removeRecentServer((String) e.payload()));
            }
            default -> item(m, "Open", e.action());
        }
        return m;
    }

    private static void item(Menu m, String text, Runnable action) {
        MenuItem mi = new MenuItem(m, SWT.PUSH);
        mi.setText(text);
        mi.addListener(SWT.Selection, ev -> {
            if (action != null) action.run();
        });
    }

    // MARK: Drag and drop

    private static final String FAV = "mxfilemgr-favorite:";

    private void installDragAndDrop() {
        DragSource source = new DragSource(tree, DND.DROP_MOVE);
        source.setTransfer(TextTransfer.getInstance());
        source.addDragListener(new DragSourceAdapter() {
            private String dragged;

            @Override
            public void dragStart(DragSourceEvent e) {
                Point p = tree.toControl(tree.getDisplay().getCursorLocation());
                TreeItem ti = tree.getItem(p);
                dragged = ti != null && ti.getData() instanceof Entry en && "favorite".equals(en.kind()) ? (String) en.payload() : null;
                e.doit = dragged != null;
            }

            @Override
            public void dragSetData(DragSourceEvent e) {
                e.data = FAV + dragged;
            }
        });
        DropTarget target = new DropTarget(tree, DND.DROP_MOVE | DND.DROP_COPY | DND.DROP_LINK | DND.DROP_DEFAULT);
        target.setTransfer(new Transfer[] { TextTransfer.getInstance(), FileTransfer.getInstance() });
        target.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragOver(DropTargetEvent e) {
                e.feedback = DND.FEEDBACK_SELECT | DND.FEEDBACK_SCROLL;
                if (FileTransfer.getInstance().isSupportedType(e.currentDataType)) e.detail = DND.DROP_LINK;
                else if (TextTransfer.getInstance().isSupportedType(e.currentDataType)) e.detail = DND.DROP_MOVE;
            }

            @Override
            public void dragEnter(DropTargetEvent e) { dragOver(e); }

            @Override
            public void drop(DropTargetEvent e) {
                if (e.data instanceof String text && text.startsWith(FAV)) {
                    String moved = text.substring(FAV.length());
                    List<String> list = new ArrayList<>(Settings.get().favorites());
                    int from = list.indexOf(moved);
                    int to = list.size() - 1;
                    if (e.item instanceof TreeItem ti && ti.getData() instanceof Entry en && "favorite".equals(en.kind())) {
                        to = list.indexOf((String) en.payload());
                    }
                    if (from >= 0 && to >= 0 && from != to) {
                        list.remove(from);
                        list.add(to, moved);
                        Settings.get().setFavorites(list);
                    }
                } else if (e.data instanceof String[] names) {
                    for (String n : names) {
                        Path p = Path.of(n);
                        if (Files.isDirectory(p)) Settings.get().addFavorite(p);
                    }
                }
                e.detail = DND.DROP_NONE;   // nothing happens to the dropped folders themselves
            }
        });
    }
}
