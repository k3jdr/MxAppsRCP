package com.k3jdr.mxacctmgr;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.links.AppLinks;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.NavigationHistory;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Sidebar;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mx.core.ui.WindowState;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.Portfolio;
import com.k3jdr.mxacctmgr.port.PortfolioStyle;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.text.ReportCatalog;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.PageText;
import com.k3jdr.mxacctmgr.ui.Parts;

/**
 * MxAcctMGR's window (ContentView.swift): the guide in the sidebar with Quick Help docked under it, the toolbar (Back,
 * Forward, Account, Engine), the screen, and the status bar; the menus (MxAcctMGRApp.swift, Navigation.swift).
 */
public class MainPart {
    private static MainPart instance;

    private Shell shell;
    private App app;
    private Sidebar sidebar;
    private Composite host;
    private Page page;
    private Composite banner;
    private Text bannerText;
    private ToolItem backItem, forwardItem;
    private Label titleLabel;
    private Combo accountCombo, engineCombo;
    private Label statusAccount, statusMessage;
    private ProgressBar busyBar;
    private final List<String> accountIds = new ArrayList<>();
    private boolean quiet;
    private PortfolioStyle menuApproach;

    @PostConstruct
    public void create(Composite parent) {
        instance = this;
        shell = parent.getShell();
        WindowState.track(shell, LifeCycle.APP);
        shell.setMinimumSize(1100, 680);
        app = new App(shell);
        parent.setLayout(Ui.grid(1, 0, 0));

        toolbar(parent);
        new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());
        banner(parent);

        SplitPanes split = new SplitPanes(parent, "mxacctmgr.sidebar", 210, 340, 250, 600);
        split.setLayoutData(Ui.fill());
        Composite left = split.leading();
        left.setLayout(Ui.grid(1, 0, 0));
        sidebar(left);
        QuickHelp.get().createDockedPanel(left);
        host = split.trailing();
        host.setLayout(new FillLayout());

        new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());
        statusBar(parent);

        app.onScreen(this::show);
        app.onChange(this::refreshFrame);
        app.history.addListener(this::refreshNavigation);
        Settings.addListener(() -> {
            if (!Settings.shows(app.screen())) app.go(Screen.MY_PORTFOLIO);
            else show(app.screen());
            rebuildSidebar();
            buildMenus();
        });
        buildMenus();
        installNavigationKeys();
        QuickHelp.get().setSelection(() -> {
            Portfolio p = app.port.portfolio();
            return new QuickHelpItem(p.name, String.format(Help.SELECTION_FORMAT, p.account.title, Fmt.money(p.total()),
                    p.benchmarkInfo().name(), p.lesson == null ? "" : p.lesson));
        });
        QuickHelp.get().setScreen(() -> new QuickHelpItem(app.title(app.screen(), true), Help.screen(app.screen().id)));

        // Launch arguments for demos and tests (-screen myImprove, -sample CODE, -srmAccount CODE) and links.
        String[] args = Platform.getApplicationArgs();
        Screen start = app.screen();
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("-screen") && Screen.of(args[i + 1]) != null) start = Screen.of(args[i + 1]);
            if (args[i].equals("-sample")) app.port.select(args[i + 1]);
            if (args[i].equals("-srmAccount")) app.port.srmCode = args[i + 1];
            if (args[i].equals("-quickHelpVisible") && args[i + 1].equalsIgnoreCase("NO")) QuickHelp.get().setVisible(false);
        }
        app.go(start);
        app.port.loadAccounts();
        List<String> links = AppLinks.linkArguments(args, AppLink.ACCT_SCHEME, AppLink.LEGACY_ACCT_SCHEME);
        if (!links.isEmpty()) shell.getDisplay().asyncExec(() -> handleArguments(links));
    }

    // Toolbar

    private void toolbar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(Ui.grid(7, 6, 8));
        bar.setLayoutData(Ui.fillH());
        ToolBar tb = new ToolBar(bar, SWT.FLAT | SWT.RIGHT);
        backItem = new ToolItem(tb, SWT.DROP_DOWN);
        backItem.setText("‹ Back");
        forwardItem = new ToolItem(tb, SWT.DROP_DOWN);
        forwardItem.setText("Forward ›");
        backItem.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.ARROW) historyMenu(tb, backItem, app.history.before());
            else app.back();
        });
        forwardItem.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.ARROW) historyMenu(tb, forwardItem, app.history.after());
            else app.forward();
        });
        QuickHelp.provider(tb, (c, p) -> {
            ToolItem item = tb.getItem(p);
            if (item == backItem) return new QuickHelpItem(Help.BACK_TITLE, Help.BACK.replace("Hold the button", "Click its arrow"), Help.BACK_SHORTCUT + " or Alt+Left");
            if (item == forwardItem) return new QuickHelpItem(Help.FORWARD_TITLE, Help.FORWARD.replace("Hold the button", "Click its arrow"), Help.FORWARD_SHORTCUT + " or Alt+Right");
            return null;
        });
        titleLabel = new Label(bar, SWT.NONE);
        titleLabel.setFont(Ui.subheading());
        titleLabel.setLayoutData(Ui.fillH());
        Label al = Ui.label(bar, "Account:");
        accountCombo = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
        GridData ag = new GridData(SWT.FILL, SWT.CENTER, false, false);
        ag.widthHint = 250;
        accountCombo.setLayoutData(ag);
        accountCombo.addListener(SWT.Selection, e -> {
            if (quiet) return;
            int i = accountCombo.getSelectionIndex();
            if (i >= 0 && i < accountIds.size()) app.port.select(accountIds.get(i));
        });
        QuickHelp.help(accountCombo, Help.SAMPLE_PICKER_TITLE, Help.SAMPLE_PICKER);
        QuickHelp.help(al, Help.SAMPLE_PICKER_TITLE, Help.SAMPLE_PICKER);
        Label el = Ui.label(bar, "Engine:");
        engineCombo = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (App.Engine e : App.Engine.values()) engineCombo.add(e.title);
        engineCombo.addListener(SWT.Selection, e -> app.setEngine(App.Engine.values()[engineCombo.getSelectionIndex()]));
        engineCombo.setToolTipText(Help.ENGINE_PICKER_HELP);
        QuickHelp.help(engineCombo, Help.ENGINE_TITLE, Help.ENGINE);
        QuickHelp.help(el, Help.ENGINE_TITLE, Help.ENGINE);
        new Label(bar, SWT.NONE);
    }

    private void historyMenu(ToolBar tb, ToolItem item, List<Integer> indexes) {
        Menu menu = new Menu(shell, SWT.POP_UP);
        List<NavigationHistory.Entry<App.Place>> entries = app.history.entries();
        for (int i : indexes) {
            MenuItem mi = new MenuItem(menu, SWT.PUSH);
            mi.setText(Ui.escape(entries.get(i).title()));
            mi.addListener(SWT.Selection, e -> app.history.go(i).ifPresent(p -> app.go(Screen.of(p.screen()))));
        }
        Rectangle r = item.getBounds();
        Point at = tb.toDisplay(r.x, r.y + r.height);
        menu.setLocation(at);
        menu.setVisible(true);
    }

    private void banner(Composite parent) {
        banner = new Composite(parent, SWT.NONE);
        banner.setLayout(Ui.grid(4, 10, 8));
        banner.setLayoutData(Ui.fillH());
        banner.setBackground(Parts.WARN_BG);
        Label icon = Ui.label(banner, "⚠");
        icon.setForeground(Ui.ORANGE);
        bannerText = new Text(banner, SWT.READ_ONLY | SWT.WRAP);
        GridData g = new GridData(SWT.FILL, SWT.CENTER, true, false);
        g.widthHint = 300;
        bannerText.setLayoutData(g);
        Ui.button(banner, Help.TRY_AGAIN, () -> app.port.loadAccounts());
        Ui.button(banner, Help.LAYER3_SERVICES_BUTTON, () -> app.go(Screen.SERVICES));
        Ui.background(banner, Parts.WARN_BG);
        bannerText.setBackground(Parts.WARN_BG);
        QuickHelp.help(banner, "Security master", "The samples and My Portfolio are in the security master, read through the accounts service (port 8774). When it can't be reached, nothing is made up in its place: start the services and Try Again.");
        Ui.setVisible(banner, false);
    }

    // Sidebar

    private void sidebar(Composite parent) {
        sidebar = new Sidebar(parent);
        rebuildSidebar();
        Tree tree = sidebar.tree();
        tree.addListener(SWT.Expand, e -> remember((TreeItem) e.item, false));
        tree.addListener(SWT.Collapse, e -> remember((TreeItem) e.item, true));
        sidebar.onSelect(id -> {
            Screen s = Screen.of(id);
            if (s != null && s != app.screen()) app.go(s);
        });
    }

    private void remember(TreeItem group, boolean collapsed) {
        List<String> names = Settings.PREFS.getList("sidebarCollapsed");
        names.remove(group.getText());
        if (collapsed) names.add(group.getText());
        Settings.PREFS.putList("sidebarCollapsed", names);
    }

    private void rebuildSidebar() {
        Tree tree = sidebar.tree();
        tree.setRedraw(false);
        try {
            tree.removeAll();
            sidebar = sidebarAgain(tree);
        } finally {
            tree.setRedraw(true);
        }
    }

    /** Fills the tree (the core Sidebar keeps its items; a fresh one over the same tree after removeAll). */
    private Sidebar sidebarAgain(Tree tree) {
        Sidebar s = sidebar;
        List<String> collapsed = Settings.PREFS.getList("sidebarCollapsed");
        for (Screen.Group g : Screen.Group.SIDEBAR) {
            TreeItem group = s.group(g.title);
            for (Screen screen : Screen.values()) {
                if (screen.group != g || !Settings.shows(screen)) continue;
                s.item(group, screen.id, app.title(screen, false), Help.screen(screen.id), shortcut(screen));
            }
            group.setExpanded(!collapsed.contains(g.title));
        }
        if (app.screen() != null) s.select(app.screen().id);
        return s;
    }

    private static String shortcut(Screen s) {
        return switch (s) {
            case MY_PORTFOLIO, MY_BENCHMARK, MY_RISK, MY_IMPROVE, MY_TRADES, MY_ATTRIBUTION -> "Ctrl+Alt+" + s.step();
            case SAMPLES -> "Ctrl+Shift+L";
            case SERVICES -> "Ctrl+Alt+L";
            case REPORT_LIBRARY -> "Ctrl+Alt+R";
            case MORNING_BRIEFING -> "Ctrl+Alt+M";
            case EARNINGS_CALENDAR -> "Ctrl+Alt+E";
            case VIX -> "Ctrl+Alt+V";
            case COMPUTE -> "Ctrl+Alt+U";
            default -> null;
        };
    }

    private void updateSidebarTitles() {
        for (TreeItem g : sidebar.tree().getItems()) {
            for (TreeItem ti : g.getItems()) {
                if (ti.getData() instanceof Sidebar.Entry e) {
                    Screen s = Screen.of(e.id());
                    String t = app.title(s, false);
                    if (!t.equals(ti.getText())) {
                        ti.setText(t);
                        ti.setData(new Sidebar.Entry(e.id(), t, e.detail(), e.shortcut()));
                    }
                }
            }
        }
    }

    // Status bar

    private void statusBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(Ui.grid(4, 8, 10));
        bar.setLayoutData(Ui.fillH());
        statusAccount = new Label(bar, SWT.NONE);
        statusMessage = new Label(bar, SWT.NONE);
        statusMessage.setForeground(Ui.SECONDARY);
        statusMessage.setLayoutData(Ui.fillH());
        busyBar = new ProgressBar(bar, SWT.INDETERMINATE | SWT.HORIZONTAL);
        GridData bg = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        bg.widthHint = 60;
        bg.heightHint = 10;
        busyBar.setLayoutData(bg);
        Label edu = new Label(bar, SWT.NONE);
        edu.setText("🎓 " + Ui.escape(Help.EDUCATIONAL_BANNER));
        edu.setForeground(Ui.SECONDARY);
        edu.setFont(Ui.small());
        GridData eg = new GridData(SWT.END, SWT.CENTER, false, false);
        eg.widthHint = 560;
        edu.setLayoutData(eg);
        edu.setToolTipText(Help.EDUCATIONAL_BANNER_HELP);
        QuickHelp.help(edu, Help.DISCLAIMER_TITLE, Help.DISCLAIMER);
        QuickHelp.help(bar, Help.STATUS_TITLE, Help.STATUS);
        QuickHelp.help(statusAccount, Help.STATUS_TITLE, Help.STATUS);
        QuickHelp.help(statusMessage, Help.STATUS_TITLE, Help.STATUS);
    }

    // Showing a screen

    private void show(Screen s) {
        if (page != null) {
            page.dispose();
            page = null;
        }
        for (Control c : host.getChildren()) c.dispose();
        page = Pages.create(app, s);
        try {
            page.create(host);
        } catch (RuntimeException ex) {
            for (Control c : host.getChildren()) c.dispose();
            Composite err = new Composite(host, SWT.NONE);
            err.setLayout(Ui.grid(1, 20, 10));
            Parts.warning(err, "This page could not be shown: " + ex);
        }
        host.layout(true, true);
        if (page != null) page.relayout();
        sidebar.select(s.id);
        if (!Screen.Group.SIDEBAR.contains(s.group)) sidebar.tree().deselectAll();
        refreshFrame();
    }

    private void refreshFrame() {
        if (shell.isDisposed()) return;
        Screen s = app.screen();
        String title = app.title(s, true);
        Portfolio p = app.port.portfolio();
        shell.setText("MxAcctMGR — " + title);
        titleLabel.setText(Ui.escape(title + "   ·   " + p.name));
        // Account picker
        quiet = true;
        try {
            accountIds.clear();
            accountCombo.removeAll();
            for (Portfolio q : app.port.portfolios()) {
                if (q.id.equals("mine")) continue;
                accountIds.add(q.id);
                accountCombo.add(q.name);
            }
            for (Portfolio q : app.port.portfolios()) {
                if (!q.id.equals("mine")) continue;
                accountIds.add(q.id);
                accountCombo.add(q.name);
            }
            accountCombo.select(accountIds.indexOf(app.port.selectedID()));
            boolean mine = s.group == Screen.Group.MINE;
            accountCombo.setEnabled(!mine);
            accountCombo.setToolTipText(mine ? Help.ACCOUNT_PICKER_HELP_INVESTMENTS : Help.ACCOUNT_PICKER_HELP);
            engineCombo.select(app.engine().ordinal());
        } finally {
            quiet = false;
        }
        // Banner: the accounts couldn't be read.
        String problem = app.port.dataProblem;
        boolean showBanner = problem != null && s.group != Screen.Group.BANKING && s != Screen.SERVICES;
        if (showBanner && !problem.equals(bannerText.getText())) bannerText.setText(problem);
        if (banner.getVisible() != showBanner) {
            Ui.setVisible(banner, showBanner);
            banner.getParent().layout(true, true);
        }
        // Status bar
        statusAccount.setText(Ui.escape(String.format(Help.STATUS_LINE_FORMAT, p.name, p.account.title, Fmt.money(p.total()), p.benchmark)));
        statusMessage.setText(Ui.escape(app.message() == null ? "" : app.message()));
        busyBar.setVisible(app.busy() || app.port.loading || app.port.srmBusy);
        statusAccount.getParent().layout(true);
        updateSidebarTitles();
        refreshNavigation();
        if (menuApproach != app.approach()) {
            menuApproach = app.approach();
            buildMenus();
        }
        if (page != null) page.stateChanged();
    }

    private void refreshNavigation() {
        if (backItem.isDisposed()) return;
        backItem.setEnabled(app.history.canGoBack());
        forwardItem.setEnabled(app.history.canGoForward());
        List<NavigationHistory.Entry<App.Place>> entries = app.history.entries();
        int i = app.history.index();
        backItem.setToolTipText(i > 0 ? String.format(Help.BACK_HELP_FORMAT, entries.get(i - 1).title()).replace("hold for", "its arrow for") : Help.BACK_HELP);
        forwardItem.setToolTipText(i >= 0 && i + 1 < entries.size()
                ? String.format(Help.FORWARD_HELP_FORMAT, entries.get(i + 1).title()).replace("hold for", "its arrow for") : Help.FORWARD_HELP);
    }

    /** Alt+Left / Alt+Right (Windows' Back and Forward) and a mouse's back and forward buttons. */
    private void installNavigationKeys() {
        Display d = shell.getDisplay();
        Listener keys = e -> {
            if (shell.isDisposed() || d.getActiveShell() != shell) return;
            if ((e.stateMask & SWT.MODIFIER_MASK) == SWT.ALT && e.keyCode == SWT.ARROW_LEFT) {
                app.back();
                e.doit = false;
            } else if ((e.stateMask & SWT.MODIFIER_MASK) == SWT.ALT && e.keyCode == SWT.ARROW_RIGHT) {
                app.forward();
                e.doit = false;
            }
        };
        Listener mouse = e -> {
            if (shell.isDisposed() || d.getActiveShell() != shell) return;
            if (e.button == 4) app.back();
            else if (e.button == 5) app.forward();
        };
        d.addFilter(SWT.KeyDown, keys);
        d.addFilter(SWT.MouseDown, mouse);
        shell.addListener(SWT.Dispose, e -> {
            d.removeFilter(SWT.KeyDown, keys);
            d.removeFilter(SWT.MouseDown, mouse);
        });
    }

    // Menus

    private void buildMenus() {
        menuApproach = app.approach();
        Menu old = shell.getMenuBar();
        MenuBarBuilder mb = new MenuBarBuilder(shell);
        fileMenu(mb.menu("&File"));
        MenuBarBuilder.MenuBuilder edit = mb.menu("&Edit");
        EditActions.addStandardItems(edit, () -> page == null ? null : page.editTarget());
        edit.separator();
        edit.item("Copy &Page Text", SWT.MOD1 | SWT.MOD2 | SWT.MOD3 | 'C', this::copyPageText);
        goMenu(mb.menu("&Go"));
        guideMenu(mb.menu("G&uide"));
        portfolioMenu(mb.menu("&Portfolio"));
        reportsMenu(mb.menu("&Reports"));
        MenuBarBuilder.MenuBuilder view = mb.menu("&View");
        QuickHelp.get().addMenuItems(view);
        MenuBarBuilder.MenuBuilder help = mb.menu("&Help");
        help.item("Register mxacctmgr:// &Links", () -> app.setMessage(AppLinks.registerSchemes(LifeCycle.APP, AppLink.ACCT_SCHEME, AppLink.LEGACY_ACCT_SCHEME)));
        help.item("&About MxAcctMGR", () -> ErrorAlert.show(app, "MxAcctMGR for Windows — a personal-accounts IDE that teaches: Banking registers and secure accounts, and an Investments path for My Portfolio. Educational only; not investment, tax or financial advice."));
        mb.install();
        if (old != null && !old.isDisposed()) old.dispose();
    }

    private void fileMenu(MenuBarBuilder.MenuBuilder m) {
        m.item("&New Portfolio…", SWT.MOD1 | 'N', () -> com.k3jdr.mxacctmgr.invest.PortfolioDialogs.newPortfolio(app));
        m.item("&Open Portfolio…", SWT.MOD1 | 'O', () -> com.k3jdr.mxacctmgr.invest.PortfolioDialogs.openPortfolio(app));
        m.dynamic("Open &Recent", sub -> {
            for (String code : app.port.recentPortfolios()) sub.item(code, () -> openPortfolio(code));
            if (!app.port.recentPortfolios().isEmpty()) {
                sub.separator();
                sub.item("Clear Menu", app.port::clearRecent);
            }
        });
        m.separator();
        m.item("&Close Portfolio", 0, () -> settled("close " + app.port.srmCode, app.port::closePortfolio), () -> app.port.openPortfolio() != null);
        m.item("&Save", SWT.MOD1 | 'S', () -> app.port.save(null), () -> app.port.openPortfolio() != null && app.port.isDirty());
        m.item("Re&vert to Saved", 0, app.port::revert, () -> app.port.openPortfolio() != null && app.port.isDirty());
        m.separator();
        m.item("Se&ttings…", SWT.MOD1 | ',', () -> new SettingsDialog(shell).open());
        m.separator();
        m.item("E&xit", SWT.MOD3 | SWT.F4, shell::close);
    }

    /** Opens a portfolio (Open Portfolio…, a link), after the open one is settled. */
    public static void openPortfolioCode(String code) {
        if (instance != null && !instance.shell.isDisposed()) instance.openPortfolio(code);
    }

    /** Opens a portfolio after the open one is settled (Save / Discard / Cancel when it has unsaved changes). */
    public void openPortfolio(String code) {
        settled("open " + code.toUpperCase(), () -> app.port.requestOpen(code, () -> {
            if (!Screen.MY_PATH.contains(app.screen())) app.go(Screen.MY_PORTFOLIO);
        }));
    }

    /** Runs {@code proceed} now when the open portfolio has nothing unsaved, else asks to Save, Discard or Cancel first. */
    private void settled(String action, Runnable proceed) {
        List<String> ends = app.port.looseEnds();
        if (ends.isEmpty()) {
            proceed.run();
            return;
        }
        String text = String.format(Help.SAVE_CHANGES_MESSAGE_FORMAT, action,
                String.join("\n", ends.stream().map(s -> Help.SAVE_CHANGES_BULLET + s).toList()));
        org.eclipse.jface.dialogs.MessageDialog d = new org.eclipse.jface.dialogs.MessageDialog(shell,
                String.format(Help.SAVE_CHANGES_TITLE_FORMAT, app.port.srmCode), null, text,
                org.eclipse.jface.dialogs.MessageDialog.QUESTION, 0, Help.SAVE, Help.DISCARD_CHANGES, Help.CANCEL);
        switch (d.open()) {
            case 0 -> app.port.save(ok -> { if (ok) proceed.run(); });
            case 1 -> {
                Portfolio mine = app.port.openPortfolio();
                if (mine != null) app.port.markSaved(mine, app.port.openVersion);
                proceed.run();
            }
            default -> { }
        }
    }

    private void goMenu(MenuBarBuilder.MenuBuilder m) {
        m.item("&Back", SWT.MOD1 | '[', app::back, app.history::canGoBack);
        m.item("&Forward", SWT.MOD1 | ']', app::forward, app.history::canGoForward);
        m.dynamic("&History", sub -> {
            List<NavigationHistory.Entry<App.Place>> entries = app.history.entries();
            for (int i = entries.size() - 1; i >= Math.max(0, entries.size() - 31); i--) {
                final int at = i;
                sub.check(entries.get(i).title(), 0, () -> at == app.history.index(),
                        v -> app.history.go(at).ifPresent(p -> app.go(Screen.of(p.screen()))));
            }
        });
        m.separator();
        m.item("&Previous Step", SWT.MOD1 | SWT.MOD3 | SWT.ARROW_UP, () -> app.go(app.screen().previous()), () -> app.screen().previous() != null);
        m.item("&Next Step", SWT.MOD1 | SWT.MOD3 | SWT.ARROW_DOWN, () -> app.go(app.screen().next()), () -> app.screen().next() != null);
    }

    private void guideMenu(MenuBarBuilder.MenuBuilder m) {
        boolean lessons = Settings.guidance() != Settings.Guidance.MINIMAL;
        if (lessons) {
            m.item("&Welcome", SWT.MOD1 | '0', () -> app.go(Screen.WELCOME));
            m.item("&Choose Your Path", () -> app.go(Screen.CHOOSE_PATH));
            m.separator();
        }
        for (Screen s : Screen.MY_PATH) {
            m.item(Ui.escape(app.title(s, false)), SWT.MOD1 | SWT.MOD3 | ('0' + s.step()), () -> app.go(s));
        }
        m.separator();
        if (lessons) {
            MenuBarBuilder.MenuBuilder why = m.submenu("&Learn the Why");
            for (Screen s : List.of(Screen.WHY_OPTIMIZE, Screen.WHY_ATTRIBUTION, Screen.ACTIVE_VS_PASSIVE)) why.item(s.title, () -> app.go(s));
            why.end();
        }
        m.item("S&pending Analysis", () -> app.go(Screen.SPENDING));
        m.item("SRM &Table", () -> app.go(Screen.SECURITIES));
        m.item("Sample &Portfolios", SWT.MOD1 | SWT.MOD2 | 'L', () -> app.go(Screen.SAMPLES));
        m.item("Layer &3 Services", SWT.MOD1 | SWT.MOD3 | 'L', () -> app.go(Screen.SERVICES));
    }

    private void portfolioMenu(MenuBarBuilder.MenuBuilder m) {
        m.dynamic("&Account", sub -> {
            for (Portfolio p : app.port.portfolios()) {
                sub.check(Ui.escape(p.name), 0, () -> p.id.equals(app.port.selectedID()), v -> app.port.select(p.id));
            }
        });
        m.separator();
        boolean active = app.active();
        m.item(active ? "&Optimize My Portfolio" : "&Build My Index Tracker", SWT.MOD1 | 'R', () -> {
            app.go(Screen.MY_IMPROVE);
            if (app.active()) app.port.optimize(); else app.port.buildTracker();
        });
        m.item("Run A&ttribution", SWT.MOD1 | SWT.MOD2 | 'A', () -> {
            app.go(Screen.MY_ATTRIBUTION);
            app.port.attribute();
        });
        m.separator();
        m.dynamic("&Engine", sub -> {
            for (App.Engine e : App.Engine.values()) sub.check(e.title, 0, () -> app.engine() == e, v -> app.setEngine(e));
        });
        m.dynamic("My A&pproach", sub -> {
            sub.check("Active", 0, () -> app.active(), v -> app.setApproach(PortfolioStyle.ACTIVE));
            sub.check("Passive index", 0, () -> !app.active(), v -> app.setApproach(PortfolioStyle.PASSIVE));
        });
        m.separator();
        m.item("&Copy Account into the Open Portfolio", 0, () -> {
            app.port.copyToMine(app.port.portfolio());
            app.go(Screen.MY_PORTFOLIO);
        }, () -> !"mine".equals(app.port.selectedID()) && app.port.openPortfolio() != null);
    }

    private void reportsMenu(MenuBarBuilder.MenuBuilder m) {
        m.item("Report &Library", SWT.MOD1 | SWT.MOD3 | 'R', () -> app.go(Screen.REPORT_LIBRARY));
        m.item("&Morning Briefing", SWT.MOD1 | SWT.MOD3 | 'M', () -> app.go(Screen.MORNING_BRIEFING));
        m.item("&Earnings Calendar", SWT.MOD1 | SWT.MOD3 | 'E', () -> app.go(Screen.EARNINGS_CALENDAR));
        m.item("Earnings &Today", SWT.MOD1 | SWT.MOD2 | SWT.MOD3 | 'E', () -> app.go(Screen.EARNINGS_DAY));
        m.item("CBOE &Volatility Index (VIX)", SWT.MOD1 | SWT.MOD3 | 'V', () -> app.go(Screen.VIX));
        m.separator();
        MenuBarBuilder.MenuBuilder five = m.submenu("The &Five Most Important");
        for (ReportCatalog.Report r : ReportCatalog.TOP_FIVE) {
            five.item(Ui.escape(r.title()), SWT.MOD1 | SWT.MOD2 | ('0' + r.topFive()), () -> openReport(r.id()));
        }
        five.end();
        m.separator();
        for (ReportCatalog.Tier t : ReportCatalog.TIERS) {
            MenuBarBuilder.MenuBuilder tier = m.submenu(Ui.escape(t.title()));
            for (ReportCatalog.Report r : ReportCatalog.reportsInTier(t.id())) {
                tier.item(Ui.escape(r.title()), () -> openReport(r.id()));
                if (r.id().equals("semiconductors")) {
                    tier.item("Compute Hyperscalers Tracking", SWT.MOD1 | SWT.MOD3 | 'U', () -> app.go(Screen.COMPUTE));
                }
            }
            tier.end();
        }
        m.separator();
        m.item("Simulate the Long Run", SWT.MOD1 | SWT.CR, () -> {}, () -> false);
    }

    private void openReport(String id) {
        Settings.PREFS.put("report", id);
        if (app.screen() == Screen.REPORT_WALKTHROUGH) show(Screen.REPORT_WALKTHROUGH);
        else app.go(Screen.REPORT_WALKTHROUGH);
    }

    private void copyPageText() {
        if (page == null || page.root() == null || page.root().isDisposed()) return;
        Clipboards.copyText(shell.getDisplay(), PageText.of(page.root()));
        app.setMessage("Copied the page's text");
    }

    // Links and later launches

    /** Arguments of a later launch (a link), handed over by the running copy; also this launch's links. */
    public static void handleArguments(List<String> args) {
        if (instance == null || instance.shell.isDisposed()) return;
        instance.shell.forceActive();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-screen") && i + 1 < args.size() && Screen.of(args.get(i + 1)) != null) {
                instance.app.go(Screen.of(args.get(i + 1)));
                continue;
            }
            AppLink.parse(a).ifPresent(instance::open);
        }
    }

    private void open(AppLink link) {
        switch (link) {
            case AppLink.MyPortfolio m -> {
                if (m.code() != null && !m.code().isEmpty()) openPortfolio(m.code());
                else app.go(Screen.MY_PORTFOLIO);
            }
            case AppLink.SecureAccount s -> {
                app.secureRequest = s;
                if (app.screen() == Screen.SECURE_ACCOUNTS) show(Screen.SECURE_ACCOUNTS);
                else app.go(Screen.SECURE_ACCOUNTS);
            }
            case AppLink.NewSecureAccount n -> {
                app.secureRequest = n;
                if (app.screen() == Screen.SECURE_ACCOUNTS) show(Screen.SECURE_ACCOUNTS);
                else app.go(Screen.SECURE_ACCOUNTS);
            }
            default -> { }
        }
    }
}
