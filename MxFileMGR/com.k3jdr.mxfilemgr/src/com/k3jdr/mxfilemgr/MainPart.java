package com.k3jdr.mxfilemgr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jakarta.annotation.PostConstruct;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mx.core.ui.WindowState;
import com.k3jdr.mxfilemgr.cloud.SftpAccount;
import com.k3jdr.mxfilemgr.cloud.SftpStore;
import com.k3jdr.mxfilemgr.dialogs.SettingsDialog;
import com.k3jdr.mxfilemgr.model.Places;
import com.k3jdr.mxfilemgr.model.SortKey;
import com.k3jdr.mxfilemgr.tools.ClipboardWindow;
import com.k3jdr.mxfilemgr.ui.FunctionKeyBar;
import com.k3jdr.mxfilemgr.ui.PaneView;
import com.k3jdr.mxfilemgr.ui.SidebarView;
import com.k3jdr.mxfilemgr.ui.TransferPanel;
import com.k3jdr.mxfilemgr.workspace.BrowserTab;
import com.k3jdr.mxfilemgr.workspace.Pane;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * MxFileMGR's window (MainView.swift and MainCommands.swift): the toolbar, the sidebar with Quick Help docked at its
 * bottom, the two panes, the transfer panel and the function key bar, and the menu bar with the Mac app's commands
 * on Windows keys (⌘ → Ctrl, ⌥ → Alt; F2–F8 as in Total Commander).
 */
public class MainPart {
    private static MainPart instance;
    private Shell shell;
    private Workspace ws;
    private PaneView leftView, rightView;
    private FunctionKeyBar keyBar;
    private Composite content;

    @PostConstruct
    public void create(Composite parent) {
        instance = this;
        shell = parent.getShell();
        WindowState.track(shell, LifeCycle.APP);
        ws = Workspace.restored(shell, List.of(Platform.getApplicationArgs()));
        parent.setLayout(Ui.grid(1, 0, 0));

        buildToolbar(parent);
        SplitPanes split = new SplitPanes(parent, "mxfilemgr.sidebar", 150, 400, 220, 560);
        split.setLayoutData(Ui.fill());
        Composite side = split.leading();
        side.setLayout(Ui.grid(1, 0, 0));
        new SidebarView(side, ws);
        QuickHelp.get().createDockedPanel(side);

        content = split.trailing();
        content.setLayout(Ui.grid(1, 0, 0));
        SashForm panes = new SashForm(content, SWT.HORIZONTAL | SWT.SMOOTH);
        panes.setLayoutData(Ui.fill());
        panes.setSashWidth(5);
        leftView = new PaneView(panes, ws, ws.left());
        rightView = new PaneView(panes, ws, ws.right());
        int lw = Settings.PREFS.getInt("paneWeight", 500);
        panes.setWeights(Math.max(100, Math.min(900, lw)), 1000 - Math.max(100, Math.min(900, lw)));
        leftView.control().addListener(SWT.Resize, e -> {
            int total = panes.getClientArea().width;
            if (total > 100) Settings.PREFS.putInt("paneWeight", leftView.control().getSize().x * 1000 / total);
        });
        new TransferPanel(content);
        keyBar = new FunctionKeyBar(content, ws);
        Ui.setVisible(keyBar.control(), Settings.get().showFunctionKeyBar());
        Settings.get().addListener(what -> {
            if (Settings.LAYOUT.equals(what)) Ui.async(content, () -> {
                Ui.setVisible(keyBar.control(), Settings.get().showFunctionKeyBar());
                content.layout(true, true);
            });
        });

        ws.setFocuser(s -> (s == Pane.Side.LEFT ? leftView : rightView).focusList());
        ws.addListener(() -> Ui.async(content, this::updateTitle));
        updateTitle();

        QuickHelp.get().setScreen(() -> FileHelp.MAIN);
        QuickHelp.get().setSelection(() -> FileHelp.selection(ws.activeTab()));
        shell.addListener(SWT.Activate, e -> {
            QuickHelp.get().setScreen(() -> FileHelp.MAIN);
            QuickHelp.get().setSelection(() -> FileHelp.selection(ws.activeTab()));
        });

        buildMenus();
        installKeyFilter();
        shell.getDisplay().asyncExec(() -> {
            if (shell.isDisposed()) return;
            leftView.ready();
            rightView.ready();
            ws.focus(ws.activeSide());
        });
    }

    private void updateTitle() {
        if (shell.isDisposed()) return;
        BrowserTab tab = ws.activeTab();
        shell.setText(tab.title() + " — MxFileMGR");
    }

    // MARK: Toolbar

    private void buildToolbar(Composite parent) {
        ToolBar bar = new ToolBar(parent, SWT.FLAT | SWT.RIGHT | SWT.WRAP);
        bar.setLayoutData(Ui.fillH());
        tool(bar, "＋ New Tab", "New Tab (Ctrl+T)", () -> {
            ws.activePane().newTab(null, true);
            ws.focus(ws.activeSide());
        }, new QuickHelpItem("New Tab", "Opens a new tab in the active pane, on the same folder.", "Ctrl+T"));
        tool(bar, "⇄ Swap Panes", "Swap Panes (Ctrl+U)", ws::swapPanes,
                new QuickHelpItem("Swap Panes", "Exchanges the left and right panes, tabs and all.", "Ctrl+U"));
        tool(bar, "= Same Folder", "Show this folder in the other pane (Ctrl+Alt+=)", ws::mirrorToOtherPane,
                new QuickHelpItem("Same Folder", "Shows the active pane's folder in the other pane too.", "Ctrl+Alt+="));
        ToolItem hidden = new ToolItem(bar, SWT.CHECK);
        hidden.setText("👁 Hidden Files");
        hidden.setToolTipText("Show Hidden Files (Ctrl+Shift+.)");
        hidden.setSelection(Settings.get().showHidden());
        hidden.addListener(SWT.Selection, e -> Settings.get().setShowHidden(hidden.getSelection()));
        hidden.setData(new QuickHelpItem("Hidden Files", "Shows or hides hidden and system items in every pane; they appear dimmed.", "Ctrl+Shift+."));
        Settings.get().addListener(what -> {
            if (Settings.LISTING.equals(what)) Ui.async(bar, () -> hidden.setSelection(Settings.get().showHidden()));
        });
        new ToolItem(bar, SWT.SEPARATOR);
        tool(bar, "🔍 Find", "Find Files (Ctrl+F, Alt+F7)", ws::search, FileHelp.SEARCH);
        tool(bar, "Multi-Rename", "Multi-Rename (Ctrl+M)", ws::multiRename, FileHelp.MULTI_RENAME);
        tool(bar, "Compare", "Select differences between the panes (Ctrl+Alt+K)", ws::compareFolders,
                new QuickHelpItem("Compare Folders", "Marks, in both panes, the items missing on the other side or newer than their twin (by name, date and size).", "Ctrl+Alt+K"));
        tool(bar, "Synchronize", "Synchronize Folders… (Ctrl+Alt+Y)", ws::synchronizeFolders, FileHelp.SYNC);
        tool(bar, "Terminal", "Open Terminal Here (Ctrl+Alt+T)", ws::openTerminal,
                new QuickHelpItem("Terminal", "Opens the active pane's folder (or the marked folder) in Windows Terminal, or the terminal chosen in Settings.", "Ctrl+Alt+T"));
        tool(bar, "Connect", "Connect to Server (Ctrl+K)", () -> ws.connectToServer(""), FileHelp.CONNECT);
        QuickHelp.provider(bar, (c, p) -> {
            ToolItem item = bar.getItem(p);
            return item != null && item.getData() instanceof QuickHelpItem q ? q
                    : new QuickHelpItem("Toolbar", "Tabs, panes, hidden files and the tools. Rest the pointer on a button to learn what it does.");
        });
    }

    private static void tool(ToolBar bar, String text, String tip, Runnable action, QuickHelpItem help) {
        ToolItem item = new ToolItem(bar, SWT.PUSH);
        item.setText(text);
        item.setToolTipText(tip);
        item.setData(help);
        item.addListener(SWT.Selection, e -> action.run());
    }

    // MARK: Menus

    private void buildMenus() {
        MenuBarBuilder mb = new MenuBarBuilder(shell);
        int ctrl = SWT.MOD1, shift = SWT.MOD2, alt = SWT.MOD3;
        mb.menu("&File")
                .item("New &Tab", ctrl | 'T', () -> {
                    ws.activePane().newTab(null, true);
                    ws.focus(ws.activeSide());
                })
                .item("&Close Tab", ctrl | 'W', () -> ws.activePane().closeTab(ws.activePane().activeIndex()), () -> ws.activePane().tabs().size() > 1)
                .separator()
                .item("New &Folder…", SWT.F7, ws::newFolder)
                .item("New F&ile…", shift | SWT.F4, ws::newFile)
                .separator()
                .item("&Open", ctrl | 'O', ws::openSelection)
                .item("Open in New Ta&b", ctrl | shift | 'O', ws::openInNewTab)
                .item("Open &With…", ws::openWith)
                .item("&Quick View\tSpace, Ctrl+Y", ctrl | 'Y', () -> ws.toggleQuickView(null))
                .item("&View", SWT.F3, ws::view)
                .item("&Edit", SWT.F4, ws::edit)
                .separator()
                .item("&Rename…", SWT.F2, ws::rename)
                .item("&Copy to Other Pane…", SWT.F5, () -> ws.copyOrMoveToOtherPane(false))
                .item("&Move to Other Pane…", SWT.F6, () -> ws.copyOrMoveToOtherPane(true))
                .item("&Duplicate", ctrl | 'D', ws::duplicate)
                .item("Com&press", ws::compress)
                .item("E&xtract to Other Pane", alt | SWT.F9, ws::extract)
                .item("ffmpeg Meta-data…", ws::editMediaMetadata)
                .separator()
                .item("Move to Recycle &Bin\tF8, Del", SWT.F8, ws::moveToTrash)
                .item("Delete Immediately…\tShift+Del", ws::deleteImmediately)
                .separator()
                .item("Propert&ies…", ctrl | 'I', ws::showProperties)
                .item("Windows Properties…\tAlt+Enter", ws::windowsProperties)
                .item("Show in E&xplorer", ctrl | alt | 'R', ws::revealInExplorer)
                .item("Add to F&avorites", ctrl | 'B', ws::addToFavorites)
                .separator()
                .item("E&xit", alt | SWT.F4, shell::close);

        MenuBarBuilder.MenuBuilder edit = mb.menu("&Edit");
        EditActions.addStandardItems(edit, ws::editTarget);
        edit.separator()
                .item("Show Clip&board", ctrl | shift | 'V', () -> ClipboardWindow.open(ws))
                .item("&Move Item Here", ctrl | alt | 'V', () -> ws.pasteFromClipboard(true))
                .item("Copy &Path", ctrl | alt | 'C', ws::copyPaths)
                .separator()
                .item("Select &Group…\tCtrl+=, Num +", ctrl | '=', () -> ws.selectPatternPrompt(true))
                .item("&Deselect Group…\tCtrl+-, Num -", ctrl | '-', () -> ws.selectPatternPrompt(false))
                .item("&Invert Selection\tCtrl+Shift+I, Num *", ctrl | shift | 'I', ws::invertSelection);

        MenuBarBuilder.MenuBuilder view = mb.menu("&View");
        view.check("Show &Hidden Files", ctrl | shift | '.', () -> Settings.get().showHidden(), v -> Settings.get().setShowHidden(v))
                .check("&Folders First", 0, () -> Settings.get().foldersFirst(), v -> Settings.get().setFoldersFirst(v))
                .check("Function &Key Bar", 0, () -> Settings.get().showFunctionKeyBar(), v -> Settings.get().setShowFunctionKeyBar(v))
                .separator();
        MenuBarBuilder.MenuBuilder sort = view.submenu("&Sort By");
        for (SortKey k : SortKey.values()) {
            sort.check(k.title, ctrl | alt | ('1' + k.ordinal()), () -> ws.activeTab().sort().key() == k, v -> ws.sort(k));
        }
        view.item("&Calculate Folder Sizes", ctrl | alt | 'S', ws::calculateFolderSizes)
                .item("&Refresh", ctrl | 'R', () -> ws.activeTab().reload())
                .separator();
        QuickHelp.get().addMenuItems(view);

        MenuBarBuilder.MenuBuilder go = mb.menu("&Go");
        go.item("&Back\tCtrl+[, Alt+Left", ctrl | '[', () -> ws.activeTab().goBack(), () -> ws.activeTab().canGoBack())
                .item("&Forward\tCtrl+], Alt+Right", ctrl | ']', () -> ws.activeTab().goForward(), () -> ws.activeTab().canGoForward())
                .item("&Enclosing Folder\tAlt+Up, Backspace", alt | SWT.ARROW_UP, () -> ws.activeTab().goUp(), () -> ws.activeTab().canGoUp())
                .separator()
                .item("&Home", ctrl | shift | 'H', () -> ws.go(Places.home()))
                .item("&Desktop", ctrl | shift | 'D', () -> ws.go(Places.desktop()))
                .item("D&ocuments", ctrl | alt | 'O', () -> ws.go(Places.documents()))
                .item("Do&wnloads", ctrl | alt | 'L', () -> ws.go(Places.downloads()))
                .item("&Program Files", ctrl | shift | 'A', () -> ws.go(Places.programFiles()))
                .item("This &PC", ctrl | shift | 'C', () -> ws.go(null))
                .separator()
                .item("iCloud Drive", () -> ws.openCloud("iCloud Drive"))
                .item("OneDrive", () -> ws.openCloud("OneDrive"))
                .item("Google Drive", () -> ws.openCloud("Google Drive"))
                .item("Dropbox", () -> ws.openCloud("Dropbox"))
                .item("Box", () -> ws.openCloud("Box"))
                .item("SharePoint", ws::openSharePoint)
                .item("Microsoft Teams", ws::openTeams)
                .dynamic("SFTP", m -> {
                    for (SftpAccount a : SftpStore.get().accounts()) m.item(Ui.escape(a.name()), () -> SftpStore.get().open(ws, a));
                    if (!SftpStore.get().accounts().isEmpty()) m.separator();
                    m.item("Add SFTP Account…", () -> SftpStore.get().add(ws));
                })
                .item("Amazon S3", ws::openS3)
                .item("Azure Blob Storage", ws::openAzure)
                .item("Google Cloud Storage", ws::openGCS)
                .dynamic("Fa&vorites", m -> {
                    for (String f : Settings.get().favorites()) m.item(Ui.escape(BrowserTab.displayName(Path.of(f))), () -> ws.go(Path.of(f)));
                })
                .separator()
                .item("&Go to Folder…", ctrl | shift | 'G', ws::goToFolderPrompt)
                .separator()
                .item("S&witch Pane\tTab", ws::switchPane)
                .item("&Next Tab\tCtrl+Tab", ctrl | shift | ']', () -> ws.activePane().selectNextTab(1))
                .item("P&revious Tab\tCtrl+Shift+Tab", ctrl | shift | '[', () -> ws.activePane().selectNextTab(-1))
                .item("Swap Panes", ctrl | 'U', ws::swapPanes)
                .item("Same Folder in Other Pane", ctrl | alt | '=', ws::mirrorToOtherPane)
                .item("Open Selected Folder in Other Pane", ctrl | alt | SWT.ARROW_RIGHT, ws::openInOtherPane);

        mb.menu("&Commands")
                .item("&Find Files…\tCtrl+F, Alt+F7", ctrl | 'F', ws::search)
                .item("&Multi-Rename…", ctrl | 'M', ws::multiRename)
                .separator()
                .item("Compare Folders (Select &Differences)", ctrl | alt | 'K', ws::compareFolders)
                .item("&Synchronize Folders…", ctrl | alt | 'Y', ws::synchronizeFolders)
                .item("Compare Files by &Content…", ctrl | alt | 'D', ws::compareFiles)
                .separator()
                .item("Open &Terminal Here", ctrl | alt | 'T', ws::openTerminal)
                .item("Connect to Ser&ver…", ctrl | 'K', () -> ws.connectToServer(""))
                .separator()
                .item("Se&ttings…", ctrl | ',', () -> new SettingsDialog(shell).open());
        tidyKeyTexts(mb.install());
    }

    /** Items whose label already names their keys ("Back\tCtrl+[, Alt+Left") keep that text, not the generated one too. */
    private static void tidyKeyTexts(org.eclipse.swt.widgets.Menu menu) {
        for (org.eclipse.swt.widgets.MenuItem mi : menu.getItems()) {
            String t = mi.getText();
            int first = t.indexOf('\t');
            if (first >= 0 && t.indexOf('\t', first + 1) > 0) mi.setText(t.substring(0, t.indexOf('\t', first + 1)));
            if (mi.getMenu() != null) tidyKeyTexts(mi.getMenu());
        }
    }

    /** Keys the menus can't carry: Alt+F7 (Find), Ctrl+Tab / Ctrl+Shift+Tab (tabs), Ctrl+L (Go to Folder). */
    private void installKeyFilter() {
        Display d = shell.getDisplay();
        Listener keys = e -> {
            if (d.getActiveShell() != shell) return;
            int mods = e.stateMask & SWT.MODIFIER_MASK;
            if (e.type == SWT.Traverse && (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS)
                    && (mods & SWT.CTRL) != 0) {
                ws.activePane().selectNextTab((mods & SWT.SHIFT) != 0 ? -1 : 1);
                consume(e);
            } else if (e.type == SWT.KeyDown && e.keyCode == SWT.F7 && mods == SWT.ALT) {
                ws.search();
                consume(e);
            } else if (e.type == SWT.KeyDown && (e.keyCode == 'l' || e.keyCode == 'L') && mods == SWT.CTRL) {
                ws.goToFolderPrompt();
                consume(e);
            }
        };
        d.addFilter(SWT.KeyDown, keys);
        d.addFilter(SWT.Traverse, keys);
        shell.addListener(SWT.Dispose, e -> {
            d.removeFilter(SWT.KeyDown, keys);
            d.removeFilter(SWT.Traverse, keys);
        });
    }

    private static void consume(Event e) {
        e.doit = false;
        e.detail = SWT.TRAVERSE_NONE;
        e.type = SWT.None;
    }

    /** Arguments of a later launch, handed over by the running copy: -left/-right folders, or a folder to open in a new tab. */
    public static void handleArguments(List<String> args) {
        if (instance == null || instance.shell.isDisposed()) return;
        Workspace ws = instance.ws;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i).replace("\"", "");
            if ((a.equals("-left") || a.equals("-right")) && i + 1 < args.size()) {
                Path p = Path.of(args.get(++i).replace("\"", ""));
                if (Files.isDirectory(p)) ws.pane(a.equals("-left") ? Pane.Side.LEFT : Pane.Side.RIGHT).active().navigate(p);
            } else if (!a.startsWith("-")) {
                try {
                    Path p = Path.of(a);
                    if (Files.isDirectory(p)) ws.activePane().newTab(p, false);
                    else if (Files.exists(p)) ws.reveal(p);
                } catch (RuntimeException ignored) {
                    // not a path
                }
            }
        }
        instance.shell.forceActive();
    }
}
