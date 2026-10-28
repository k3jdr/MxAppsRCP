package com.k3jdr.mxfilemgr.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.Settings;
import com.k3jdr.mxfilemgr.model.FileItem;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.model.Places;
import com.k3jdr.mxfilemgr.model.SortKey;
import com.k3jdr.mxfilemgr.tools.MediaMetadataDialog;
import com.k3jdr.mxfilemgr.tools.ViewerWindow;
import com.k3jdr.mxfilemgr.workspace.BrowserTab;
import com.k3jdr.mxfilemgr.workspace.Pane;
import com.k3jdr.mxfilemgr.workspace.Workspace;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

/**
 * One side of the window (PaneView.swift and FileListView.swift): the accent line of the active pane, the tab strip
 * (with two or more tabs), Back / Forward / Up, the breadcrumb and the quick filter, the file list — a virtual table
 * with Name, Ext, Size, Date Modified and Kind, a ".." row, sorting by header, remembered column order and widths,
 * type-to-jump, drag and drop, a context menu — and the status line.
 */
public final class PaneView {
    private final Workspace ws;
    private final Pane pane;
    private final Composite root;
    private final Composite accent;
    private final CTabFolder tabStrip;
    private final ToolItem back, forward, up;
    private final PathBar pathBar;
    private final Label loading;
    private final Text filter;
    private final Table table;
    private final Label status, free;
    private final Composite errorBox;
    private final Label errorText;
    private final Font italic;
    private final Color dim = new Color(new RGB(140, 140, 150));

    private BrowserTab shownTab;
    private int shownVersion = -1;
    private Path shownURL;
    private boolean folderChangedPending;
    private List<FileItem> rows = List.of();
    private boolean parentRow;
    private boolean updating;
    private int cursor;
    private final StringBuilder typed = new StringBuilder();
    private long lastTyped;
    private final Map<String, Long> freeCache = new HashMap<>();

    public PaneView(Composite parent, Workspace ws, Pane pane) {
        this.ws = ws;
        this.pane = pane;
        root = new Composite(parent, SWT.NONE);
        root.setLayout(Ui.grid(1, 0, 0));

        accent = new Composite(root, SWT.NONE);
        GridData ag = new GridData(SWT.FILL, SWT.TOP, true, false);
        ag.heightHint = 3;
        accent.setLayoutData(ag);

        tabStrip = new CTabFolder(root, SWT.FLAT | SWT.CLOSE);
        tabStrip.setUnselectedCloseVisible(true);
        tabStrip.setMRUVisible(false);
        tabStrip.setSimple(true);
        tabStrip.setTabHeight(Math.max(22, tabStrip.getTabHeight()));
        GridData tg = new GridData(SWT.FILL, SWT.TOP, true, false);
        tg.heightHint = 0;      // CTabFolder adds its tab row to the hint: no content area
        tabStrip.setLayoutData(tg);
        ToolBar plus = new ToolBar(tabStrip, SWT.FLAT);
        ToolItem add = new ToolItem(plus, SWT.PUSH);
        add.setText("+");
        add.setToolTipText("New Tab (Ctrl+T)");
        add.addListener(SWT.Selection, e -> {
            pane.newTab(null, true);
            ws.focus(pane.side);
        });
        tabStrip.setTopRight(plus);
        tabStrip.addListener(SWT.Selection, e -> {
            int i = tabStrip.indexOf((CTabItem) e.item);
            pane.setActiveIndex(i);
            ws.focus(pane.side);
        });
        tabStrip.addCTabFolder2Listener(new CTabFolder2Adapter() {
            @Override
            public void close(CTabFolderEvent e) {
                e.doit = false;
                pane.closeTab(tabStrip.indexOf((CTabItem) e.item));
            }
        });
        QuickHelp.help(tabStrip, FileHelp.TABS.title(), FileHelp.TABS.detail(), "Ctrl+T / Ctrl+W");
        QuickHelp.help(plus, "New Tab", "Opens a new tab in this pane, on the same folder.", "Ctrl+T");

        Composite header = new Composite(root, SWT.NONE);
        header.setLayout(Ui.grid(4, 4, 6));
        header.setLayoutData(Ui.fillH());
        ToolBar nav = new ToolBar(header, SWT.FLAT);
        back = navItem(nav, "◀", "Back (Ctrl+[ or Alt+Left)", () -> pane.active().goBack());
        forward = navItem(nav, "▶", "Forward (Ctrl+] or Alt+Right)", () -> pane.active().goForward());
        up = navItem(nav, "▲", "Enclosing Folder (Alt+Up, Backspace)", () -> pane.active().goUp());
        QuickHelp.provider(nav, (c, p) -> {
            ToolItem item = nav.getItem(p);
            if (item == back) return new QuickHelpItem("Back", "Goes back to the folder this tab showed before.", "Ctrl+[ / Alt+Left");
            if (item == forward) return new QuickHelpItem("Forward", "Goes forward again after Back.", "Ctrl+] / Alt+Right");
            if (item == up) return new QuickHelpItem("Enclosing Folder", "Goes up to the folder that contains this one; from a drive, to This PC.", "Alt+Up / Backspace");
            return null;
        });
        pathBar = new PathBar(header, p -> {
            pane.active().navigate(p);
            ws.focus(pane.side);
        });
        pathBar.setLayoutData(Ui.fillH());
        QuickHelp.help(pathBar, FileHelp.PATH_BAR.title(), FileHelp.PATH_BAR.detail(), "Ctrl+Shift+G");
        Menu pathMenu = new Menu(pathBar);
        menuItem(pathMenu, "Copy Path", () -> {
            Path u = pane.active().url();
            Clipboards.copyText(root.getDisplay(), u == null ? "This PC" : u.toString());
        });
        menuItem(pathMenu, "Go to Folder…", () -> {
            ws.focus(pane.side);
            ws.goToFolderPrompt();
        });
        pathBar.setMenu(pathMenu);
        loading = new Label(header, SWT.NONE);
        loading.setText("   ");
        loading.setForeground(Ui.SECONDARY);
        filter = new Text(header, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL | SWT.ICON_SEARCH);
        filter.setMessage("Filter");
        GridData fg = new GridData(SWT.END, SWT.CENTER, false, false);
        fg.widthHint = 140;
        filter.setLayoutData(fg);
        filter.addListener(SWT.Modify, e -> {
            if (!updating) pane.active().setFilterText(filter.getText());
        });
        filter.addListener(SWT.DefaultSelection, e -> {
            if (e.detail == SWT.ICON_CANCEL) pane.active().setFilterText("");
            focusList();
        });
        filter.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.ESC) {
                filter.setText("");
                focusList();
            } else if (e.keyCode == SWT.ARROW_DOWN) {
                focusList();
            }
        });
        filter.addListener(SWT.FocusIn, e -> ws.setActiveSide(pane.side));
        QuickHelp.help(filter, FileHelp.FILTER.title(), FileHelp.FILTER.detail(), FileHelp.FILTER.shortcut());

        Composite listArea = new Composite(root, SWT.NONE);
        listArea.setLayoutData(Ui.fill());
        listArea.setLayout(new org.eclipse.swt.layout.FormLayout());
        table = new Table(listArea, SWT.VIRTUAL | SWT.MULTI | SWT.FULL_SELECTION | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLinesVisible(false);
        org.eclipse.swt.layout.FormData full = new org.eclipse.swt.layout.FormData();
        full.left = new org.eclipse.swt.layout.FormAttachment(0);
        full.right = new org.eclipse.swt.layout.FormAttachment(100);
        full.top = new org.eclipse.swt.layout.FormAttachment(0);
        full.bottom = new org.eclipse.swt.layout.FormAttachment(100);
        table.setLayoutData(full);
        errorBox = new Composite(listArea, SWT.NONE);
        errorBox.setLayout(Ui.grid(1, 20, 6));
        org.eclipse.swt.layout.FormData eb = new org.eclipse.swt.layout.FormData();
        eb.left = new org.eclipse.swt.layout.FormAttachment(0, 2);
        eb.right = new org.eclipse.swt.layout.FormAttachment(100, -2);
        eb.top = new org.eclipse.swt.layout.FormAttachment(0, 40);
        errorBox.setLayoutData(eb);
        Label et = Ui.label(errorBox, "Can't Show Folder", Ui.subheading());
        et.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        errorText = new Label(errorBox, SWT.WRAP | SWT.CENTER);
        GridData etg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        etg.widthHint = 200;
        errorText.setLayoutData(etg);
        errorBox.setVisible(false);
        errorBox.moveAbove(table);
        Ui.background(errorBox, table.getBackground());

        FontData[] fd = table.getFont().getFontData();
        for (FontData d : fd) d.setStyle(SWT.ITALIC);
        italic = new Font(table.getDisplay(), fd);
        table.addListener(SWT.Dispose, e -> italic.dispose());
        createColumns();
        table.addListener(SWT.SetData, this::setData);
        table.addListener(SWT.Selection, e -> {
            if (e.item instanceof TableItem ti) cursor = table.indexOf(ti);
            if (updating) return;
            syncSelectionToModel();
        });
        table.addListener(SWT.DefaultSelection, e -> {
            int i = e.item instanceof TableItem ti ? table.indexOf(ti) : cursor;
            if (table.getSelectionCount() > 1) ws.openSelection();
            else openRow(i);
        });
        table.addListener(SWT.FocusIn, e -> ws.setActiveSide(pane.side));
        table.addListener(SWT.MouseDown, e -> ws.setActiveSide(pane.side));
        table.addListener(SWT.KeyDown, this::keyDown);
        table.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS) {
                if ((e.stateMask & SWT.CTRL) != 0) return;
                e.doit = false;
                e.detail = SWT.TRAVERSE_NONE;
                ws.switchPane();
            } else if (e.detail == SWT.TRAVERSE_RETURN || e.detail == SWT.TRAVERSE_ESCAPE) {
                e.doit = false;
            }
        });
        table.addListener(SWT.MenuDetect, this::contextMenu);
        QuickHelp.provider(table, (c, p) -> quickHelpAt(p));
        installDragAndDrop();

        Composite statusRow = new Composite(root, SWT.NONE);
        statusRow.setLayout(Ui.grid(2, 6, 4));
        statusRow.setLayoutData(Ui.fillH());
        status = new Label(statusRow, SWT.NONE);
        status.setLayoutData(Ui.fillH());
        status.setForeground(Ui.SECONDARY);
        free = new Label(statusRow, SWT.RIGHT);
        free.setForeground(Ui.SECONDARY);
        free.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        QuickHelp.help(statusRow, FileHelp.STATUS.title(), FileHelp.STATUS.detail());
        QuickHelp.help(status, FileHelp.STATUS.title(), FileHelp.STATUS.detail());
        QuickHelp.help(free, FileHelp.STATUS.title(), FileHelp.STATUS.detail());

        root.addListener(SWT.MouseDown, e -> ws.setActiveSide(pane.side));
        pane.addListener(() -> Ui.async(root, this::update));
        ws.addListener(() -> Ui.async(root, this::updateActive));
        update();
        updateActive();
    }

    public Composite control() { return root; }
    public Table table() { return table; }

    private static ToolItem navItem(ToolBar bar, String text, String tip, Runnable action) {
        ToolItem item = new ToolItem(bar, SWT.PUSH);
        item.setText(text);
        item.setToolTipText(tip);
        item.addListener(SWT.Selection, e -> action.run());
        return item;
    }

    private static MenuItem menuItem(Menu menu, String text, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(text);
        mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    public void focusList() {
        if (!table.isDisposed()) table.setFocus();
    }

    // MARK: Columns

    private String prefKey(String what) { return "columns." + pane.side.name().toLowerCase(Locale.ROOT) + "." + what; }

    private void createColumns() {
        int[] widths = { 210, 45, 75, 125, 130 };
        String saved = Settings.PREFS.get(prefKey("widths"), null);
        if (saved != null) {
            String[] parts = saved.split(",");
            for (int i = 0; i < Math.min(parts.length, widths.length); i++) {
                try {
                    widths[i] = Math.max(24, Integer.parseInt(parts[i].trim()));
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
            }
        }
        for (SortKey k : SortKey.values()) {
            TableColumn col = new TableColumn(table, k == SortKey.SIZE ? SWT.RIGHT : SWT.LEFT);
            col.setText(k.title);
            col.setWidth(widths[k.ordinal()]);
            col.setMoveable(true);
            col.setData(k);
            col.addListener(SWT.Selection, e -> {
                BrowserTab tab = pane.active();
                tab.setSort(tab.sort().toggled(k));
                ws.focus(pane.side);
            });
            col.addListener(SWT.Resize, e -> saveColumns());
            col.addListener(SWT.Move, e -> saveColumns());
        }
        String order = Settings.PREFS.get(prefKey("order"), null);
        if (order != null) {
            try {
                String[] parts = order.split(",");
                int[] o = new int[parts.length];
                for (int i = 0; i < parts.length; i++) o[i] = Integer.parseInt(parts[i].trim());
                if (o.length == table.getColumnCount()) table.setColumnOrder(o);
            } catch (RuntimeException ignored) {
                // the default order
            }
        }
    }

    private boolean columnsReady;

    private void saveColumns() {
        if (!columnsReady || table.isDisposed()) return;
        StringBuilder w = new StringBuilder();
        for (TableColumn c : table.getColumns()) w.append(w.isEmpty() ? "" : ",").append(c.getWidth());
        StringBuilder o = new StringBuilder();
        for (int i : table.getColumnOrder()) o.append(o.isEmpty() ? "" : ",").append(i);
        Settings.PREFS.put(prefKey("widths"), w.toString());
        Settings.PREFS.put(prefKey("order"), o.toString());
    }

    // MARK: Rows

    private FileItem item(int row) {
        int i = parentRow ? row - 1 : row;
        return i >= 0 && i < rows.size() ? rows.get(i) : null;
    }

    private int rowOf(Path p) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).path().equals(p)) return parentRow ? i + 1 : i;
        return -1;
    }

    private void setData(Event e) {
        TableItem ti = (TableItem) e.item;
        int row = table.indexOf(ti);
        BrowserTab tab = pane.active();
        if (parentRow && row == 0) {
            ti.setText(new String[] { "..", "", "<UP>", "", "" });
            ti.setImage(0, Icons.named("up"));
            return;
        }
        FileItem it = item(row);
        if (it == null) return;
        String size;
        if (it.isDrive()) size = it.size() > 0 ? Fmt.size(it.size()) : "";
        else if (it.isDirectory()) {
            Long s = tab.folderSize(it.path());
            size = s != null ? Fmt.size(s) : "<DIR>";
        } else size = Fmt.size(it.size());
        String kind = it.isDrive() && it.size() > 0 ? it.kind() + ", " + Fmt.size(it.free()) + " free" : it.kind();
        ti.setText(new String[] { it.name(), it.ext(), size, Fmt.date(it.modified()), kind == null ? "" : kind });
        ti.setImage(0, Icons.forItem(it));
        if (it.isHidden()) ti.setForeground(dim);
        if (it.isLink()) ti.setFont(0, italic);
    }

    // MARK: Updates from the model

    private void update() {
        if (root.isDisposed()) return;
        BrowserTab tab = pane.active();
        updating = true;
        try {
            updateTabs();
            boolean tabChanged = tab != shownTab;
            boolean folderChanged = tabChanged || !Objects.equals(tab.url(), shownURL);
            if (folderChanged) folderChangedPending = true;
            if (tabChanged || tab.version() != shownVersion) {
                shownTab = tab;
                shownVersion = tab.version();
                shownURL = tab.url();
                parentRow = tab.url() != null;
                rows = tab.items();
                table.setRedraw(false);
                table.setItemCount(rows.size() + (parentRow ? 1 : 0));
                table.clearAll();
                applySelection(tab.selection());
                if (folderChangedPending && !tab.isLoading()) {
                    folderChangedPending = false;
                    if (tab.selection().isEmpty() && table.getItemCount() > 0) {
                        table.setSelection(0);
                        cursor = 0;
                    }
                    if (tab.scrollTarget == null) table.setTopIndex(0);
                }
                if (tab.scrollTarget != null) {
                    int r = rowOf(tab.scrollTarget);
                    if (r >= 0) {
                        table.showItem(table.getItem(r));
                        cursor = r;
                        tab.scrollTarget = null;
                    }
                }
                table.setRedraw(true);
            } else if (!selectionFromTable().equals(tab.selection())) {
                applySelection(tab.selection());
            }
            back.setEnabled(tab.canGoBack());
            forward.setEnabled(tab.canGoForward());
            up.setEnabled(tab.canGoUp());
            pathBar.setURL(tab.url());
            for (TableColumn c : table.getColumns()) {
                if (c.getData() == tab.sort().key()) {
                    if (table.getSortColumn() != c) table.setSortColumn(c);
                    table.setSortDirection(tab.sort().ascending() ? SWT.UP : SWT.DOWN);
                }
            }
            loading.setText(tab.isLoading() ? "…" : "   ");
            if (!filter.getText().equals(tab.filterText())) filter.setText(tab.filterText());
            boolean showError = tab.error() != null;
            if (showError) errorText.setText(tab.error());
            errorBox.setVisible(showError);
            if (showError) errorBox.getParent().layout(true, true);
            updateStatus(tab);
        } finally {
            updating = false;
        }
    }

    private void updateTabs() {
        List<BrowserTab> tabs = pane.tabs();
        boolean show = tabs.size() > 1;
        if (tabStrip.getVisible() != show) {
            Ui.setVisible(tabStrip, show);
            root.layout(true);
        }
        while (tabStrip.getItemCount() > tabs.size()) tabStrip.getItem(tabStrip.getItemCount() - 1).dispose();
        while (tabStrip.getItemCount() < tabs.size()) new CTabItem(tabStrip, SWT.CLOSE);
        for (int i = 0; i < tabs.size(); i++) {
            CTabItem ti = tabStrip.getItem(i);
            String title = tabs.get(i).title();
            if (!title.equals(ti.getText())) ti.setText(title);
            Path u = tabs.get(i).url();
            ti.setToolTipText(u == null ? "This PC" : u.toString());
        }
        if (tabStrip.getSelectionIndex() != pane.activeIndex()) tabStrip.setSelection(pane.activeIndex());
    }

    private void applySelection(Set<Path> sel) {
        if (sel.isEmpty()) {
            if (!selectionFromTable().isEmpty()) table.deselectAll();
            return;
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) if (sel.contains(rows.get(i).path())) idx.add(parentRow ? i + 1 : i);
        int[] a = idx.stream().mapToInt(Integer::intValue).toArray();
        table.setSelection(a);
        if (a.length > 0) {
            int top = table.getTopIndex();
            int visible = Math.max(1, table.getClientArea().height / Math.max(1, table.getItemHeight()) - 1);
            if (a[0] < top || a[0] > top + visible) table.showItem(table.getItem(a[0]));
            cursor = a[0];
        }
    }

    private Set<Path> selectionFromTable() {
        Set<Path> s = new LinkedHashSet<>();
        for (int i : table.getSelectionIndices()) {
            FileItem it = item(i);
            if (it != null) s.add(it.path());
        }
        return s;
    }

    private void syncSelectionToModel() {
        BrowserTab tab = pane.active();
        Set<Path> s = selectionFromTable();
        if (!s.equals(tab.selection())) tab.setSelection(s);
        else updateStatus(tab);
    }

    private void updateActive() {
        if (root.isDisposed()) return;
        boolean active = ws.activeSide() == pane.side;
        accent.setBackground(active ? Ui.ACCENT : root.getBackground());
    }

    private void updateStatus(BrowserTab tab) {
        List<FileItem> items = tab.items();
        List<FileItem> marked = tab.selectedItems();
        String text;
        if (tab.url() == null) {
            text = Fmt.count(items.size(), "drive");
        } else if (marked.isEmpty()) {
            long bytes = 0;
            for (FileItem i : items) if (!i.isDirectory()) bytes += i.size();
            text = Fmt.count(items.size(), "item") + ", " + Fmt.size(bytes) + " in files";
        } else {
            long bytes = 0;
            for (FileItem i : marked) {
                if (i.isDirectory()) {
                    Long s = tab.folderSize(i.path());
                    if (s != null) bytes += s;
                } else bytes += i.size();
            }
            text = marked.size() + " of " + Fmt.count(items.size(), "item") + " selected, " + Fmt.size(bytes);
        }
        if (!tab.filterText().isEmpty()) text += "  (filtered)";
        status.setText(text);
        status.setForeground(tab.filterText().isEmpty() ? Ui.SECONDARY : Ui.ORANGE);
        Path u = tab.url();
        if (u == null) {
            free.setText("");
        } else {
            String key = u.getRoot() == null ? u.toString() : u.getRoot().toString();
            Long cached = freeCache.get(key);
            free.setText(cached != null && cached >= 0 ? Fmt.size(cached) + " free" : "");
            Background.run(root, () -> Places.freeSpace(u), bytes -> {
                freeCache.put(key, bytes);
                if (Objects.equals(pane.active().url(), u)) {
                    free.setText(bytes >= 0 ? Fmt.size(bytes) + " free" : "");
                    free.getParent().layout();
                }
            }, err -> { });
        }
        status.getParent().layout();
    }

    // MARK: Actions

    private void openRow(int row) {
        BrowserTab tab = pane.active();
        if (parentRow && row == 0) {
            tab.goUp();
            return;
        }
        FileItem it = item(row);
        if (it != null) ws.open(it, tab);
    }

    private void keyDown(Event e) {
        int mods = e.stateMask & SWT.MODIFIER_MASK;
        BrowserTab tab = pane.active();
        switch (e.keyCode) {
            case SWT.BS -> {
                if (mods == 0) {
                    tab.goUp();
                    e.doit = false;
                }
                return;
            }
            case SWT.ARROW_UP -> {
                if (mods == SWT.CTRL || mods == SWT.ALT) {
                    tab.goUp();
                    e.doit = false;
                }
                return;
            }
            case SWT.ARROW_DOWN -> {
                if (mods == SWT.CTRL) {
                    if (table.getSelectionCount() > 1) ws.openSelection();
                    else openRow(cursor);
                    e.doit = false;
                }
                return;
            }
            case SWT.ARROW_LEFT, SWT.ARROW_RIGHT -> {
                if (mods == SWT.ALT) {
                    if (e.keyCode == SWT.ARROW_LEFT) tab.goBack();
                    else tab.goForward();
                    e.doit = false;
                }
                return;
            }
            case SWT.ESC -> {
                if (!tab.filterText().isEmpty()) tab.setFilterText("");
                else if (ViewerWindow.isQuickViewOpen()) ViewerWindow.closeQuickView();
                e.doit = false;
                return;
            }
            case SWT.DEL -> {
                if (mods == SWT.SHIFT) ws.deleteImmediately();
                else if (mods == 0) ws.moveToTrash();
                e.doit = false;
                return;
            }
            case SWT.INSERT -> {
                if (mods == 0) {
                    markAndMoveDown();
                    e.doit = false;
                }
                return;
            }
            case SWT.CR, SWT.KEYPAD_CR -> {
                if (mods == SWT.ALT) {
                    ws.showProperties();
                    e.doit = false;
                }
                return;
            }
            case SWT.KEYPAD_ADD -> {
                if (mods == 0) { ws.selectPatternPrompt(true); e.doit = false; }
                return;
            }
            case SWT.KEYPAD_SUBTRACT -> {
                if (mods == 0) { ws.selectPatternPrompt(false); e.doit = false; }
                return;
            }
            case SWT.KEYPAD_MULTIPLY -> {
                if (mods == 0) { ws.invertSelection(); e.doit = false; }
                return;
            }
            default -> { }
        }
        if (e.character == ' ' && mods == 0) {
            FileItem it = item(cursor);
            ws.toggleQuickView(it == null || it.isDirectory() ? null : it.path());
            e.doit = false;
            return;
        }
        // Typing a name jumps to it.
        if ((mods & (SWT.CTRL | SWT.ALT)) == 0 && e.character > ' ' && e.character != 127) {
            long now = System.currentTimeMillis();
            if (now - lastTyped > 1000) typed.setLength(0);
            lastTyped = now;
            typed.append(Character.toLowerCase(e.character));
            String prefix = typed.toString();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    int r = parentRow ? i + 1 : i;
                    table.setSelection(r);
                    table.showItem(table.getItem(r));
                    cursor = r;
                    syncSelectionToModel();
                    break;
                }
            }
            e.doit = false;
        }
    }

    /** Insert (Total Commander): marks or unmarks the item under the cursor and moves the cursor down. */
    private void markAndMoveDown() {
        int i = Math.max(0, cursor);
        if (i >= table.getItemCount()) return;
        if (!(parentRow && i == 0)) {
            if (table.isSelected(i)) table.deselect(i);
            else table.select(i);
        }
        int next = Math.min(table.getItemCount() - 1, i + 1);
        setFocusRow(next);
        table.showItem(table.getItem(next));
        cursor = next;
        syncSelectionToModel();
    }

    /** Moves the list's focus rectangle without changing what's selected (LVM_SETITEMSTATE with LVIS_FOCUSED). */
    private void setFocusRow(int row) {
        try {
            Memory lvitem = new Memory(96);
            lvitem.clear();
            lvitem.setInt(0, 0x8);      // LVIF_STATE
            lvitem.setInt(4, row);
            lvitem.setInt(12, 0x1);     // LVIS_FOCUSED
            lvitem.setInt(16, 0x1);
            User32.INSTANCE.SendMessage(new WinDef.HWND(new Pointer(table.handle)), 0x102B, new WinDef.WPARAM(row),
                    new WinDef.LPARAM(Pointer.nativeValue(lvitem)));
        } catch (Throwable t) {
            // the cursor stays where it was
        }
    }

    // MARK: Context menu

    private void contextMenu(Event e) {
        Point p = table.toControl(e.x, e.y);
        TableItem ti = table.getItem(p);
        if (e.detail == SWT.MENU_MOUSE) {
            if (ti != null) {
                int r = table.indexOf(ti);
                if (!table.isSelected(r)) {
                    table.setSelection(r);
                    cursor = r;
                }
            } else if (p.y > table.getHeaderHeight()) {
                table.deselectAll();
            }
            syncSelectionToModel();
        }
        ws.focus(pane.side);
        Menu old = table.getMenu();
        if (old != null) old.dispose();
        Menu m = buildContextMenu();
        table.setMenu(m);
    }

    private Menu buildContextMenu() {
        Menu m = new Menu(table);
        List<FileItem> marked = pane.active().selectedItems();
        boolean folder = pane.active().url() != null;
        if (!marked.isEmpty()) {
            FileItem first = marked.get(0);
            menuItem(m, "Open\tEnter", ws::openSelection);
            if (marked.size() == 1 && first.isDirectory()) {
                menuItem(m, "Open in New Tab", ws::openInNewTab);
                menuItem(m, "Open in Other Pane", ws::openInOtherPane);
            }
            if (!first.isDirectory()) menuItem(m, "Open With…", ws::openWith);
            new MenuItem(m, SWT.SEPARATOR);
            if (!first.isDirectory()) {
                menuItem(m, "Quick View\tSpace", () -> ws.toggleQuickView(first.path()));
                menuItem(m, "View\tF3", ws::view);
                menuItem(m, "Edit\tF4", ws::edit);
                new MenuItem(m, SWT.SEPARATOR);
            }
            if (!first.isDrive()) {
                menuItem(m, "Copy to Other Pane…\tF5", () -> ws.copyOrMoveToOtherPane(false));
                menuItem(m, "Move to Other Pane…\tF6", () -> ws.copyOrMoveToOtherPane(true));
                menuItem(m, "Rename…\tF2", ws::rename);
                menuItem(m, "Duplicate\tCtrl+D", ws::duplicate);
                menuItem(m, "Compress", ws::compress);
                if (marked.size() == 1 && !first.isDirectory() && MediaMetadataDialog.supports(first.path())) {
                    menuItem(m, "ffmpeg Meta-data…", ws::editMediaMetadata);
                }
                if (marked.stream().anyMatch(i -> !i.isDirectory() && FileOps.isArchive(i.path()))) {
                    menuItem(m, "Extract to Other Pane\tAlt+F9", ws::extract);
                }
                new MenuItem(m, SWT.SEPARATOR);
                menuItem(m, "Cut\tCtrl+X", () -> ws.copyToClipboard(true));
                menuItem(m, "Copy\tCtrl+C", () -> ws.copyToClipboard(false));
            }
            menuItem(m, "Copy Path\tCtrl+Alt+C", ws::copyPaths);
            menuItem(m, "Show in Explorer\tCtrl+Alt+R", ws::revealInExplorer);
            menuItem(m, "Properties…\tCtrl+I", ws::showProperties);
            menuItem(m, "Windows Properties…\tAlt+Enter", ws::windowsProperties);
            if (!first.isDrive()) {
                new MenuItem(m, SWT.SEPARATOR);
                menuItem(m, "Move to Recycle Bin\tDel", ws::moveToTrash);
                menuItem(m, "Delete Immediately…\tShift+Del", ws::deleteImmediately);
            }
            new MenuItem(m, SWT.SEPARATOR);
        }
        menuItem(m, "New Folder…\tF7", ws::newFolder).setEnabled(folder);
        menuItem(m, "New File…\tShift+F4", ws::newFile).setEnabled(folder);
        menuItem(m, "Paste\tCtrl+V", () -> ws.pasteFromClipboard(false)).setEnabled(folder);
        new MenuItem(m, SWT.SEPARATOR);
        menuItem(m, "Open Terminal Here\tCtrl+Alt+T", ws::openTerminal);
        menuItem(m, "Add to Favorites\tCtrl+B", ws::addToFavorites);
        menuItem(m, "Refresh\tCtrl+R", () -> pane.active().reload());
        return m;
    }

    // MARK: Quick Help

    private QuickHelpItem quickHelpAt(Point p) {
        BrowserTab tab = pane.active();
        if (p.y >= 0 && p.y < table.getHeaderHeight()) {
            int x = p.x + (table.getHorizontalBar() != null ? table.getHorizontalBar().getSelection() : 0);
            for (int index : table.getColumnOrder()) {
                TableColumn c = table.getColumn(index);
                if (x < c.getWidth()) return FileHelp.column((SortKey) c.getData(), tab.sort());
                x -= c.getWidth();
            }
            return null;
        }
        TableItem ti = table.getItem(p);
        if (ti == null) return null;
        int r = table.indexOf(ti);
        if (parentRow && r == 0) return FileHelp.parentRow(tab.parentURL());
        FileItem it = item(r);
        return it == null ? null : FileHelp.describe(it, tab);
    }

    // MARK: Drag and drop

    private void installDragAndDrop() {
        DragSource source = new DragSource(table, DND.DROP_COPY | DND.DROP_MOVE);
        source.setTransfer(FileTransfer.getInstance());
        source.addDragListener(new DragSourceAdapter() {
            @Override
            public void dragStart(DragSourceEvent e) {
                e.doit = !draggedPaths().isEmpty();
            }

            @Override
            public void dragSetData(DragSourceEvent e) {
                e.data = draggedPaths().stream().map(Path::toString).toArray(String[]::new);
            }
        });
        DropTarget target = new DropTarget(table, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
        target.setTransfer(new Transfer[] { FileTransfer.getInstance() });
        target.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetEvent e) { validate(e); }

            @Override
            public void dragOver(DropTargetEvent e) { validate(e); }

            @Override
            public void dragOperationChanged(DropTargetEvent e) { validate(e); }

            @Override
            public void drop(DropTargetEvent e) {
                if (!(e.data instanceof String[] names) || names.length == 0) return;
                Path folder = dropFolder(e);
                if (folder == null) {
                    e.detail = DND.DROP_NONE;
                    return;
                }
                List<Path> paths = new ArrayList<>();
                for (String n : names) paths.add(Path.of(n));
                if (intoItself(paths, folder)) {
                    e.detail = DND.DROP_NONE;
                    return;
                }
                boolean move = e.detail == DND.DROP_MOVE;
                ws.transfer(paths, folder, move);
                // The job moves (removing each source once copied); the source app must not delete anything itself.
                e.detail = DND.DROP_COPY;
            }
        });
    }

    private List<Path> draggedPaths() {
        List<Path> out = new ArrayList<>();
        for (FileItem i : pane.active().selectedItems()) if (!i.isDrive()) out.add(i.path());
        return out;
    }

    private Path dropFolder(DropTargetEvent e) {
        BrowserTab tab = pane.active();
        if (e.item instanceof TableItem ti) {
            int r = table.indexOf(ti);
            if (parentRow && r == 0) return tab.parentURL();
            FileItem it = item(r);
            if (it != null && it.isDirectory()) return it.path();
        }
        return tab.url();
    }

    private static boolean intoItself(List<Path> paths, Path folder) {
        for (Path p : paths) if (p.equals(folder)) return true;
        return paths.stream().allMatch(p -> folder.equals(p.getParent()));
    }

    private void validate(DropTargetEvent e) {
        Path folder = dropFolder(e);
        boolean onRow = e.item instanceof TableItem ti && !Objects.equals(folder, pane.active().url());
        e.feedback = DND.FEEDBACK_SCROLL | (onRow ? DND.FEEDBACK_SELECT : DND.FEEDBACK_NONE);
        if (folder == null) {
            e.detail = DND.DROP_NONE;
            return;
        }
        if (e.detail == DND.DROP_DEFAULT || e.detail == DND.DROP_NONE) {
            boolean move = false;
            if (FileTransfer.getInstance().isSupportedType(e.currentDataType)) {
                Object data = FileTransfer.getInstance().nativeToJava(e.currentDataType);
                if (data instanceof String[] names && names.length > 0) {
                    move = FileOps.sameVolume(Path.of(names[0]), folder);
                    List<Path> ps = new ArrayList<>();
                    for (String n : names) ps.add(Path.of(n));
                    if (intoItself(ps, folder)) {
                        e.detail = DND.DROP_NONE;
                        return;
                    }
                }
            }
            e.detail = (move && (e.operations & DND.DROP_MOVE) != 0) ? DND.DROP_MOVE : DND.DROP_COPY;
        }
    }

    /** Called once the window is laid out, so column changes made by layout aren't saved as the user's. */
    public void ready() { columnsReady = true; }
}
