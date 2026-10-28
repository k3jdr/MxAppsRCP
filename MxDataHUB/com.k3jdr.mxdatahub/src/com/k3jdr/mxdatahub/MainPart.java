package com.k3jdr.mxdatahub;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.annotation.PostConstruct;

import org.eclipse.e4.ui.di.Focus;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.links.AppLinks;
import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.NavigationHistory;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Sidebar;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mx.core.ui.WindowState;

/**
 * MxDataHUB's window (ContentView.swift, MxDataHUBApp.swift): a sidebar of the seven screens with Quick Help docked
 * at its bottom, the screen with Back / Forward and the Database drop-down above it and the status bar below, and
 * the menus — Hub (Ctrl+1 … Ctrl+7, Connect), Go (Back, Forward, History), Edit, View and Help.
 */
public class MainPart {
    private static MainPart instance;

    /** A place in the window, for Back and Forward: a screen and the security, portfolio or benchmark on it. */
    record Place(Section section, Long item) {}

    private Shell shell;
    final Hub hub = new Hub();
    private Sidebar sidebar;
    private Composite stack;
    private final StackLayout stackLayout = new StackLayout();
    private final Map<Section, Screen> screens = new EnumMap<>(Section.class);
    private Placeholder unavailable;
    private Section section = Section.CONNECTION;
    private final NavigationHistory<Place> history = new NavigationHistory<>((a, b) -> a.section() == b.section());
    /** Titles of the places visited, kept when rows are gone from the screen. */
    private final Map<Place, String> titles = new HashMap<>();

    private Label title;
    private ToolItem backItem, forwardItem;
    private Composite databaseBox;
    private Combo databaseCombo;
    private Canvas statusDot;
    private Label statusText, statusInfo;
    private ProgressBar statusBusy;
    private MenuItem connectItem;

    /** Launch arguments: -section, -query, -host, -port, -dbUser, -database, -sslMode, -autoConnect, -servicesTab, -srmLoaderPort. */
    private final Map<String, String> launch = new HashMap<>();
    /** -layoutPreview YES: the database screens show (empty) while not connected, to check their layout. */
    private boolean layoutPreview;
    /** A link that names a portfolio or benchmark not loaded yet. */
    private AppLink pendingLink;
    /** The lists a pending link was last tried against. */
    private Object pendingPortfolios, pendingBenchmarks;

    @PostConstruct
    public void create(Composite parent) {
        instance = this;
        shell = parent.getShell();
        hub.shell = shell;
        WindowState.track(shell, LifeCycle.APP);
        shell.setMinimumSize(1100, 640);
        parent.setLayout(Ui.grid(1, 0, 0));
        readLaunchArguments(Platform.getApplicationArgs());

        SplitPanes split = new SplitPanes(parent, "mxdatahub.sidebar", 180, 260, 200, 760);
        split.setLayoutData(Ui.fill());
        buildSidebar(split.leading());
        buildDetail(split.trailing());
        buildMenus();

        hub.addListener(this::hubChanged);
        history.addListener(this::updateNavigation);
        QuickHelp.get().setScreen(() -> HubHelp.screen(section, hub));
        QuickHelp.get().setSelection(() -> {
            Screen s = screens.get(section);
            return s != null && stackLayout.topControl == s.control() ? s.selectionHelp() : null;
        });
        installNavigationKeys();

        hubChanged();
        updateNavigation();
        start();
    }

    private boolean focusedOnce;

    /**
     * The part is activated (the window comes forward): the first time, the keyboard goes to the sidebar — not to a
     * connection field, where Enter connects; afterwards it stays where the user left it.
     */
    @Focus
    public void focus() {
        if (sidebar == null || sidebar.tree().isDisposed()) return;
        Control f = shell.getDisplay().getFocusControl();
        if (!focusedOnce || f == null || f.getShell() != shell) {
            focusedOnce = true;
            sidebar.tree().setFocus();
        }
    }

    // MARK: Building the window

    private void buildSidebar(Composite left) {
        left.setLayout(Ui.grid(1, 0, 0));
        left.setBackground(Ui.SIDEBAR);
        sidebar = new Sidebar(left);
        for (Section s : Section.values()) {
            sidebar.item(null, s.id, s.title, HubHelp.about(s).detail(), s.shortcut());
        }
        sidebar.onSelect(id -> show(Section.byId(id)));
        QuickHelp.get().createDockedPanel(left);
    }

    private void buildDetail(Composite right) {
        right.setLayout(Ui.grid(1, 0, 0));
        // The toolbar: ‹ › and the screen's title, then the Database drop-down while connected.
        Composite bar = new Composite(right, SWT.NONE);
        bar.setLayout(Ui.grid(4, 6, 6));
        bar.setLayoutData(Ui.fillH());
        ToolBar tb = new ToolBar(bar, SWT.FLAT | SWT.RIGHT);
        backItem = new ToolItem(tb, SWT.DROP_DOWN);
        backItem.setText("‹ Back");
        backItem.addListener(SWT.Selection, e -> navMenuOrGo(e, true));
        forwardItem = new ToolItem(tb, SWT.DROP_DOWN);
        forwardItem.setText("Forward ›");
        forwardItem.addListener(SWT.Selection, e -> navMenuOrGo(e, false));
        QuickHelp.provider(tb, (c, p) -> {
            ToolItem item = tb.getItem(p);
            if (item == backItem) return HubHelp.BACK;
            if (item == forwardItem) return HubHelp.FORWARD;
            return null;
        });
        title = Ui.label(bar, "", Ui.subheading());
        title.setLayoutData(Ui.fillH());
        databaseBox = new Composite(bar, SWT.NONE);
        databaseBox.setLayout(Ui.grid(2, 0, 6));
        databaseBox.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        Ui.label(databaseBox, "Database:");
        databaseCombo = new Combo(databaseBox, SWT.READ_ONLY | SWT.DROP_DOWN);
        GridData cg = new GridData(SWT.FILL, SWT.CENTER, false, false);
        cg.widthHint = 170;
        databaseCombo.setLayoutData(cg);
        databaseCombo.addListener(SWT.Selection, e -> {
            int i = databaseCombo.getSelectionIndex();
            if (i >= 0) hub.switchDatabase(databaseCombo.getItem(i));
        });
        QuickHelp.provider(databaseCombo, (c, p) -> new QuickHelpItem("Database",
                "The database on " + (hub.connected == null ? "the server" : hub.connected.host())
                        + " this window works in. Choosing another reconnects with the same user and password."));
        new Label(bar, SWT.NONE).setLayoutData(new GridData(0, 0));

        new Label(right, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());
        stack = new Composite(right, SWT.NONE);
        stack.setLayoutData(Ui.fill());
        stack.setLayout(stackLayout);
        unavailable = new Placeholder(stack);
        new Label(right, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());

        // The status bar: the connection, the last action's message, and a bar while something runs.
        Composite status = new Composite(right, SWT.NONE);
        status.setLayout(Ui.grid(4, 8, 8));
        status.setLayoutData(Ui.fillH());
        statusDot = new Canvas(status, SWT.NONE);
        GridData dg = new GridData(10, 10);
        statusDot.setLayoutData(dg);
        statusDot.addPaintListener(e -> {
            e.gc.setAntialias(SWT.ON);
            e.gc.setBackground(statusColor());
            e.gc.fillOval(0, 0, 9, 9);
        });
        statusText = new Label(status, SWT.NONE);
        statusText.setLayoutData(Ui.fillH());
        statusInfo = new Label(status, SWT.NONE);
        statusInfo.setForeground(Ui.SECONDARY);
        statusInfo.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        statusBusy = new ProgressBar(status, SWT.INDETERMINATE | SWT.HORIZONTAL);
        GridData bg = new GridData(70, 12);
        statusBusy.setLayoutData(bg);
        for (Control c : status.getChildren()) QuickHelp.help(c, HubHelp.STATUS.title(), HubHelp.STATUS.detail());
        QuickHelp.help(status, HubHelp.STATUS.title(), HubHelp.STATUS.detail());
    }

    private void buildMenus() {
        MenuBarBuilder mb = new MenuBarBuilder(shell);
        mb.menu("&File").item("E&xit", SWT.MOD3 | SWT.F4, shell::close);

        MenuBarBuilder.MenuBuilder edit = mb.menu("&Edit");
        EditActions.addStandardItems(edit, () -> new EditActions.Target() {
            @Override public boolean canCopy() { return focusTable() != null && focusTable().getSelectionCount() > 0; }
            @Override public void copy() { if (focusTable() != null) TableCopy.copySelected(focusTable()); }
            @Override public boolean canSelectAll() { return focusTable() != null && (focusTable().getStyle() & SWT.MULTI) != 0; }
            @Override public void selectAll() {
                Table t = focusTable();
                if (t == null) return;
                t.selectAll();
                t.notifyListeners(SWT.Selection, new Event());
            }
        });
        edit.separator();
        edit.item("&Link…", SWT.MOD1 | 'K', () -> {
            if (screens.get(section) instanceof SecuritiesScreen s) s.editLink(null);
            else if (screens.get(section) instanceof PortfoliosScreen p) p.editLink(null);
        }, () -> hub.isConnected() && (section == Section.SECURITIES || section == Section.PORTFOLIOS));

        MenuBarBuilder.MenuBuilder hubMenu = mb.menu("H&ub");
        for (Section s : Section.values()) {
            hubMenu.item(Ui.escape(s.title), SWT.MOD1 | ('1' + s.ordinal()), () -> show(s));
        }
        hubMenu.separator();
        hubMenu.item("&Connect", SWT.MOD1 | SWT.SHIFT | 'K', this::connect,
                () -> hub.status != Hub.Status.CONNECTING && !hub.host.isBlank());
        Menu hm = hubMenu.swtMenu();
        connectItem = hm.getItem(hm.getItemCount() - 1);
        hubMenu.item("&Disconnect", 0, hub::disconnect, hub::isConnected);
        hm.addListener(SWT.Show, e -> connectItem.setText((hub.isConnected() ? "&Reconnect" : "&Connect") + "\t"
                + MenuBarBuilder.acceleratorText(SWT.MOD1 | SWT.SHIFT | 'K')));

        MenuBarBuilder.MenuBuilder go = mb.menu("&Go");
        history.addMenuItems(go, this::showPlace);

        QuickHelp.get().addMenuItems(mb.menu("&View"));

        mb.menu("&Help")
                .item("&Register mxdatahub:// Links", () -> hub.info(AppLinks.registerSchemes(LifeCycle.APP, AppLink.HUB_SCHEME)))
                .separator()
                .item("&About MxDataHUB", () -> hub.error("About MxDataHUB", "MxDataHUB — the security reference master (SRM) in PostgreSQL: "
                        + "securities and identifiers, portfolios and benchmarks, data refresh through the SRM loader service, load history, "
                        + "and the Layer 3 services and workflows.\n\nPart of MxAppsRCP, the Windows editions of the MxAppsIOS apps."));
        mb.install();
    }

    private Table focusTable() {
        Control c = Display.getCurrent().getFocusControl();
        return c instanceof Table t && t.getShell() == shell ? t : null;
    }

    /** Alt+Left / Alt+Right and a mouse's side buttons go back and forward in the main window. */
    private void installNavigationKeys() {
        Display d = shell.getDisplay();
        Listener mouse = e -> {
            if (!(e.widget instanceof Control c) || c.isDisposed() || c.getShell() != shell) return;
            if (e.button == 4) goBack();
            else if (e.button == 5) goForward();
        };
        Listener keys = e -> {
            if (!(e.widget instanceof Control c) || c.isDisposed() || c.getShell() != shell) return;
            if ((e.stateMask & SWT.MODIFIER_MASK) != SWT.ALT) return;
            if (e.keyCode == SWT.ARROW_LEFT) {
                e.doit = false;
                goBack();
            } else if (e.keyCode == SWT.ARROW_RIGHT) {
                e.doit = false;
                goForward();
            }
        };
        d.addFilter(SWT.MouseDown, mouse);
        d.addFilter(SWT.KeyDown, keys);
        shell.addListener(SWT.Dispose, e -> {
            d.removeFilter(SWT.MouseDown, mouse);
            d.removeFilter(SWT.KeyDown, keys);
        });
    }

    // MARK: Showing screens

    /** Shows a screen (the sidebar, Ctrl+1 … Ctrl+7, a link). */
    void show(Section s) {
        if (s == null) return;
        section = s;
        sidebar.select(s.id);
        title.setText(Ui.escape(s.title));
        shell.setText("MxDataHUB — " + s.title);
        refreshScreen();
        placeChanged();
    }

    Section section() { return section; }

    /** Puts the screen — or why it can't show (not connected, no srm schema) — on top. */
    private void refreshScreen() {
        Control top;
        if (layoutPreview && section.needsConnection() && !hub.isConnected()) {
            top = screen(section).control();
        } else if (section.needsConnection() && !hub.isConnected()) {
            Control button = unavailable.show("Not Connected", "Connect to the PostgreSQL database that holds the srm schema.")
                    .button("DB Connection…", () -> show(Section.CONNECTION));
            shell.getDisplay().asyncExec(() -> {
                if (!button.isDisposed() && button.isVisible()) button.setFocus();
            });
            QuickHelp.help(unavailable, "Not Connected", HubHelp.screen(section, hub).detail());
            top = unavailable;
        } else if (section.needsConnection() && !Boolean.TRUE.equals(hub.available)) {
            if (hub.available == null) {
                unavailable.show("Connecting…", "Checking the database for the security master.");
            } else {
                unavailable.show("No Security Master Here", "Database " + (hub.connected == null ? "" : hub.connected.database())
                        + " has no srm schema. Choose the database that has it in the Database drop-down, or create it with "
                        + "SRM/srm.py migrate and load it with SRM/srm.py load all.");
            }
            top = unavailable;
        } else {
            Screen screen = screen(section);
            top = screen.control();
            if (stackLayout.topControl != top) {
                stackLayout.topControl = top;
                stack.layout(true, true);
                screen.shown();
                return;
            }
        }
        if (stackLayout.topControl != top) {
            stackLayout.topControl = top;
            stack.layout(true, true);
        } else {
            stack.layout(true, true);
        }
    }

    private Screen screen(Section s) {
        return screens.computeIfAbsent(s, k -> switch (k) {
            case CONNECTION -> new ConnectionScreen(stack, hub, this);
            case SECURITIES -> new SecuritiesScreen(stack, hub, this);
            case PORTFOLIOS -> new PortfoliosScreen(stack, hub, this);
            case BENCHMARKS -> new BenchmarksScreen(stack, hub, this);
            case REFRESH -> new DataRefreshScreen(stack, hub, this);
            case LOADS -> new LoadsScreen(stack, hub, this);
            case SERVICES -> new ServicesScreen(stack, hub, this);
        });
    }

    Screen existing(Section s) { return screens.get(s); }

    private Section lastHubSection;
    private Hub.Status lastStatus;
    private Boolean lastAvailable;

    private void hubChanged() {
        if (shell.isDisposed()) return;
        updateStatusBar();
        boolean connectedNow = hub.isConnected();
        if (databaseBox.getVisible() != connectedNow) {
            Ui.setVisible(databaseBox, connectedNow);
            databaseBox.getParent().layout(true);
        }
        if (connectedNow) {
            String[] items = hub.databases.toArray(String[]::new);
            if (!java.util.Arrays.equals(items, databaseCombo.getItems())) databaseCombo.setItems(items);
            String current = hub.connected == null ? "" : hub.connected.database();
            int i = hub.databases.indexOf(current);
            if (i >= 0 && databaseCombo.getSelectionIndex() != i) databaseCombo.select(i);
        }
        databaseCombo.setEnabled(!hub.isBusy());
        // A change of connection or schema: the screens show it.
        if (lastStatus != hub.status || !Objects.equals(lastAvailable, hub.available) || lastHubSection != section) {
            lastStatus = hub.status;
            lastAvailable = hub.available;
            lastHubSection = section;
            refreshScreen();
        }
        for (Screen s : List.copyOf(screens.values())) s.hubChanged();
        // A link that named a portfolio or benchmark before the lists were there: try again when they change.
        if (pendingLink != null && hub.isConnected() && Boolean.TRUE.equals(hub.available)
                && (hub.portfolios != pendingPortfolios || hub.benchmarks != pendingBenchmarks)) {
            AppLink link = pendingLink;
            pendingLink = null;
            openLink(link);
        }
    }

    private Color statusColor() {
        return switch (hub.status) {
            case CONNECTED -> Ui.GREEN;
            case CONNECTING -> Ui.ORANGE;
            case FAILED -> Ui.RED;
            case DISCONNECTED -> Ui.SECONDARY;
        };
    }

    private void updateStatusBar() {
        String text = switch (hub.status) {
            case DISCONNECTED -> "Not connected";
            case CONNECTING -> "Connecting to " + hub.host + "…";
            case CONNECTED -> (hub.connected == null ? "" : hub.connected.label()) + " — PostgreSQL " + hub.serverVersion
                    + (Boolean.TRUE.equals(hub.available) ? " — security master" : "");
            case FAILED -> "Connection failed";
        };
        statusText.setText(Ui.escape(text));
        statusInfo.setText(Ui.escape(hub.infoMessage == null ? "" : hub.infoMessage));
        Ui.setVisible(statusBusy, hub.isBusy());
        statusDot.redraw();
        statusText.getParent().layout(true);
    }

    // MARK: Back and Forward (Navigation.swift)

    /** Where the window is now. */
    private Place place() {
        Screen s = screens.get(section);
        Long item = s != null && (section == Section.SECURITIES || section == Section.PORTFOLIOS || section == Section.BENCHMARKS)
                ? s.selectedItem() : null;
        return new Place(section, item);
    }

    /** A screen's selection changed (or the screen did): the place joins the history. */
    void placeChanged() {
        Place p = place();
        String t = title(p);
        titles.put(p, t);
        history.visit(p, t);
        QuickHelp.get().update();
    }

    private String title(Place p) {
        if (p.item() == null) return p.section().title;
        Screen s = screens.get(p.section());
        String shown = s == null ? null : s.itemTitle(p.item());
        if (shown == null) shown = titles.get(p);
        return p.section().title + " — " + (shown == null ? "#" + p.item() : shown);
    }

    private void showPlace(Place p) {
        if (p.section() != section) show(p.section());
        if (p.item() != null) {
            Screen s = screens.get(p.section());
            if (s != null) s.selectItem(p.item());
        }
    }

    void goBack() {
        history.back().ifPresentOrElse(this::showPlace, () -> shell.getDisplay().beep());
    }

    void goForward() {
        history.forward().ifPresentOrElse(this::showPlace, () -> shell.getDisplay().beep());
    }

    private void navMenuOrGo(Event e, boolean back) {
        if (e.detail == SWT.ARROW) {
            Menu menu = new Menu(shell, SWT.POP_UP);
            List<NavigationHistory.Entry<Place>> entries = history.entries();
            for (int i : back ? history.before() : history.after()) {
                MenuItem mi = new MenuItem(menu, SWT.PUSH);
                mi.setText(Ui.escape(entries.get(i).title()));
                mi.addListener(SWT.Selection, x -> history.go(i).ifPresent(this::showPlace));
            }
            if (menu.getItemCount() == 0) {
                MenuItem none = new MenuItem(menu, SWT.PUSH);
                none.setText("(none)");
                none.setEnabled(false);
            }
            ToolItem item = (ToolItem) e.widget;
            Rectangle r = item.getBounds();
            Point pt = item.getParent().toDisplay(r.x, r.y + r.height);
            menu.setLocation(pt);
            menu.setVisible(true);
        } else if (back) {
            goBack();
        } else {
            goForward();
        }
    }

    private void updateNavigation() {
        if (backItem.isDisposed()) return;
        backItem.setEnabled(history.canGoBack());
        forwardItem.setEnabled(history.canGoForward());
        List<NavigationHistory.Entry<Place>> entries = history.entries();
        backItem.setToolTipText(history.canGoBack()
                ? "Back to " + entries.get(history.index() - 1).title() + " (Ctrl+[ or Alt+Left) — the arrow lists the places before"
                : "Back (Ctrl+[ or Alt+Left)");
        forwardItem.setToolTipText(history.canGoForward()
                ? "Forward to " + entries.get(history.index() + 1).title() + " (Ctrl+] or Alt+Right) — the arrow lists the places after"
                : "Forward (Ctrl+] or Alt+Right)");
    }

    // MARK: Connecting

    /** Connect (Ctrl+Shift+K): connects with the DB Connection fields; then Securities opens when the security master is there. */
    void connect() {
        if (screens.get(Section.CONNECTION) instanceof ConnectionScreen c) c.readFields();
        hub.connect(ok -> {
            if (ok && Boolean.TRUE.equals(hub.available) && section == Section.CONNECTION) show(Section.SECURITIES);
        });
    }

    // MARK: Launch arguments and links

    String launchArgument(String key) { return launch.get(key.toLowerCase()); }

    private void readLaunchArguments(String[] args) {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a != null && a.startsWith("-") && a.length() > 1 && i + 1 < args.length && !args[i + 1].startsWith("-")) {
                launch.put(a.substring(1).toLowerCase(), args[i + 1]);
                i++;
            }
        }
        String v;
        layoutPreview = List.of("yes", "true", "1").contains(launch.getOrDefault("layoutpreview", "").toLowerCase());
        if ((v = launch.get("host")) != null && !v.isBlank()) hub.host = v.trim();
        if ((v = launch.get("port")) != null) {
            try {
                hub.port = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // keep the remembered port
            }
        }
        if ((v = launch.getOrDefault("dbuser", launch.get("user"))) != null && !v.isBlank()) hub.user = v.trim();
        if ((v = launch.get("database")) != null && !v.isBlank()) hub.database = v.trim();
        if ((v = launch.get("sslmode")) != null && List.of("disable", "prefer", "require").contains(v.trim())) hub.sslMode = v.trim();
    }

    /**
     * The saved password, then the launch arguments for testing and demos: {@code -autoConnect YES -section Portfolios
     * -query AAPL} (connection fields: -host, -port, -dbUser, -database, -sslMode), and a link given as an argument.
     */
    private void start() {
        hub.loadSavedPassword();
        List<String> links = AppLinks.linkArguments(Platform.getApplicationArgs(), AppLink.HUB_SCHEME);
        Section requested = Section.byTitle(launch.get("section"));
        String auto = launch.get("autoconnect");
        boolean autoConnect = auto != null && List.of("yes", "true", "1").contains(auto.toLowerCase());
        show(requested != null ? requested : Section.CONNECTION);
        // Start with the keyboard in the sidebar, not in a connection field where Enter connects. As on the Mac,
        // only -autoConnect YES connects at startup.
        shell.getDisplay().asyncExec(() -> {
            if (!sidebar.tree().isDisposed()) sidebar.tree().setFocus();
        });
        if (autoConnect && !hub.password.isEmpty()) {
            hub.connect(ok -> {
                if (ok && Boolean.TRUE.equals(hub.available) && requested == null) show(Section.SECURITIES);
            });
        }
        for (String l : links) AppLink.parse(l).ifPresent(this::openLink);
    }

    /** mxdatahub:// links from MxJobsMGR (Shared/AppLinks.swift). */
    void openLink(AppLink link) {
        switch (link) {
            case AppLink.Portfolio p -> {
                show(Section.PORTFOLIOS);
                var match = hub.portfolios.stream().filter(x -> x.code().equalsIgnoreCase(p.code())).findFirst();
                if (match.isPresent() && screens.get(Section.PORTFOLIOS) != null) screens.get(Section.PORTFOLIOS).selectItem(match.get().id());
                else if (match.isPresent()) hub.selectedPortfolioId = match.get().id();
                else if (!hub.isConnected() || hub.portfolios.isEmpty()) pend(link);
            }
            case AppLink.Benchmark b -> {
                show(Section.BENCHMARKS);
                var match = hub.benchmarks.stream().filter(x -> x.code().equalsIgnoreCase(b.code())).findFirst();
                if (match.isPresent() && screens.get(Section.BENCHMARKS) != null) screens.get(Section.BENCHMARKS).selectItem(match.get().id());
                else if (!hub.isConnected() || hub.benchmarks.isEmpty()) pend(link);
            }
            case AppLink.DataRefresh d -> show(Section.REFRESH);
            case AppLink.Services s -> show(Section.SERVICES);
            default -> { }
        }
    }

    private void pend(AppLink link) {
        pendingLink = link;
        pendingPortfolios = hub.portfolios;
        pendingBenchmarks = hub.benchmarks;
    }

    /** Arguments of a later launch (a link or a document), handed over by the running copy. */
    public static void handleArguments(List<String> args) {
        if (instance == null || instance.shell.isDisposed()) return;
        MainPart m = instance;
        List<String> list = new ArrayList<>(args);
        for (int i = 0; i < list.size(); i++) {
            String a = list.get(i);
            if ("-section".equalsIgnoreCase(a) && i + 1 < list.size()) m.show(Section.byTitle(list.get(++i)));
            else if ("-query".equalsIgnoreCase(a) && i + 1 < list.size() && m.screen(Section.SECURITIES) instanceof SecuritiesScreen s) {
                s.search(list.get(++i));
                m.show(Section.SECURITIES);
            }
        }
        for (String l : AppLinks.linkArguments(list.toArray(String[]::new), AppLink.HUB_SCHEME)) AppLink.parse(l).ifPresent(m::openLink);
        if (m.shell.getMinimized()) m.shell.setMinimized(false);
        m.shell.forceActive();
    }
}
