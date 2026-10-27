package com.k3jdr.mxdatahub;

import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.links.AppLinks;
import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.RefreshService.Category;
import com.k3jdr.mxdatahub.RefreshService.Entry;
import com.k3jdr.mxdatahub.RefreshService.Loader;
import com.k3jdr.mxdatahub.RefreshService.Result;
import com.k3jdr.mxdatahub.RefreshService.Running;

/**
 * Data Refresh (DataRefreshView.swift): every category of market data in the security master — how fresh it is,
 * when it was last loaded — and Refresh, which runs its srm.py loaders through the SRM loader service
 * (Services/srm-loader, 127.0.0.1:8770), so it works without the database connection. Refresh All runs every
 * category but the optional ones in dependency order, stopping at a failure; Preview only shows the commands.
 * Activity, on the right, streams what the loaders print, whoever started them.
 */
final class DataRefreshScreen extends Composite implements Screen {
    final Hub hub;
    final MainPart main;
    private final RefreshService service;

    private final Button dryRun, refreshAll, automate, reload;
    private final Combo verbosityCombo;
    private final Composite body;
    private final StackLayout bodyStack = new StackLayout();
    private final Placeholder notRunning;
    private final SplitPanes split;
    private final ScrolledComposite scroller;
    private final Composite categoryList;
    private final ActivityPanel activityPanel;

    List<Category> categories = List.of();
    private Boolean serviceUp;
    /** The category this window is refreshing. */
    private String running;
    private int verbosity;
    private int[] allProgress;
    final List<Entry> activity = new ArrayList<>();
    int lastSeq;
    Running current;
    LocalTime lastPoll;
    private boolean polling;
    private final List<Result> results = new ArrayList<>();

    DataRefreshScreen(Composite parent, Hub hub, MainPart main) {
        super(parent, SWT.NONE);
        this.hub = hub;
        this.main = main;
        Integer port = null;
        try {
            String p = main.launchArgument("srmLoaderPort");
            if (p != null) port = Integer.valueOf(p.trim());
        } catch (NumberFormatException ignored) {
            // the catalogue's port
        }
        service = new RefreshService(port);
        verbosity = Math.max(0, Math.min(2, hub.prefs.getInt("refreshVerbosity", 0)));
        setLayout(Ui.grid(1, 0, 0));

        // The header.
        Composite header = new Composite(this, SWT.NONE);
        header.setLayout(Ui.grid(7, 12, 10));
        header.setLayoutData(Ui.fillH());
        Composite titles = new Composite(header, SWT.NONE);
        titles.setLayout(Ui.grid(1, 0, 2));
        titles.setLayoutData(Ui.fillH());
        Ui.label(titles, "Market data in the security master", Ui.bold());
        Label caption = Ui.wrap(titles, "Refresh a category now, or automate the whole refresh — with data-quality checks and approval — in MxJobsMGR.");
        caption.setForeground(Ui.SECONDARY);
        caption.setFont(Ui.small());
        dryRun = Ui.check(header, "Preview only (dry run)", false, on -> { });
        dryRun.setToolTipText("Show the srm.py command each loader would run, without loading");
        QuickHelp.help(dryRun, "Preview Only", "Refresh and Refresh All show the srm.py command each loader would run, without loading anything.");
        Ui.label(header, "Log detail");
        verbosityCombo = new Combo(header, SWT.READ_ONLY);
        verbosityCombo.setItems("Normal", "Verbose (-v)", "Trace (-vv)");
        verbosityCombo.select(verbosity);
        verbosityCombo.addListener(SWT.Selection, e -> {
            verbosity = verbosityCombo.getSelectionIndex();
            hub.prefs.putInt("refreshVerbosity", verbosity);
        });
        verbosityCombo.setToolTipText("How much the loaders print in Activity for the refreshes you start here");
        QuickHelp.help(verbosityCombo, "Log Detail", "How much srm.py prints in Activity for the refreshes you start here. Normal: the progress lines. "
                + "Verbose (-v): also what each step does — connection, downloads and cache hits, rate-limit waits, retries, every reject, "
                + "identifier results, timings, and a failing loader's traceback. Trace (-vv): also every HTTP request and SQL statement with its time. "
                + "The extra lines show as DBG; Level ▸ Info hides them.");
        refreshAll = Ui.button(header, "⟳ Refresh All", this::refreshAll);
        refreshAll.setToolTipText("Every category in dependency order, except the optional ones; stops at the first failure");
        QuickHelp.help(refreshAll, "Refresh All", "Refreshes every category in dependency order — later ones use the earlier ones — except the optional ones, and stops at the first failure.");
        automate = Ui.button(header, "Automate in MxJobsMGR…", () -> {
            if (!AppLinks.open(new AppLink.Sample("srm-daily-refresh", null, null)))
                hub.error("Data Refresh", "MxJobsMGR is not installed: install it beside MxDataHUB, or register its mxjobsmgr:// links.");
        });
        automate.setToolTipText("Open the SRM Daily Refresh workflow: scheduled, checked and approved");
        QuickHelp.help(automate, "Automate in MxJobsMGR", "Opens the SRM Daily Refresh workflow in MxJobsMGR: the same loaders on a schedule, with data-quality checks and the data steward's approval.");
        reload = Ui.button(header, "⟳", this::loadCategories);
        reload.setToolTipText("Reload freshness");
        QuickHelp.help(reload, "Reload", "Asks the SRM loader service again how fresh each category is.");
        new Label(this, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());

        body = new Composite(this, SWT.NONE);
        body.setLayoutData(Ui.fill());
        body.setLayout(bodyStack);
        notRunning = new Placeholder(body);
        notRunning.show("SRM Loader Service Not Running", "Data Refresh runs the security master's loaders through the SRM loader service on this PC. "
                + "Start the Layer 3 services, then check again.");
        Text command = new Text(notRunning.buttons(), SWT.READ_ONLY | SWT.BORDER);
        command.setText(ServiceCatalog.startCommand());
        command.setFont(Ui.mono());
        GridData cg = new GridData(SWT.FILL, SWT.CENTER, false, false);
        cg.widthHint = 520;
        command.setLayoutData(cg);
        QuickHelp.help(command, "Start All", "The command that starts every Layer 3 service (Services/mxservices.py). Copy it into a terminal.");
        Ui.button(notRunning.buttons(), "Copy", () -> Clipboards.copyText(getDisplay(), ServiceCatalog.startCommand()));
        Ui.button(notRunning.buttons(), "Check Again", this::loadCategories);
        notRunning.buttons().setLayout(Ui.grid(3, 0, 8));
        QuickHelp.help(notRunning, "SRM Loader Service Not Running", "Nothing answers on " + service.base
                + ". Start the Layer 3 services (the command here), then Check Again.");

        // 580 + 5 + 430: the widest screen.
        split = new SplitPanes(body, "dataRefresh", 580, 700, 580, 430);
        Composite left = split.leading();
        left.setLayout(Ui.grid(1, 0, 0));
        Label listHeader = Ui.label(left, "  In dependency order — later categories use the earlier ones");
        listHeader.setForeground(Ui.SECONDARY);
        listHeader.setFont(Ui.font(-1, SWT.BOLD));
        listHeader.setLayoutData(Ui.fillH());
        scroller = new ScrolledComposite(left, SWT.V_SCROLL | SWT.BORDER);
        scroller.setLayoutData(Ui.fill());
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        categoryList = new Composite(scroller, SWT.NONE);
        categoryList.setLayout(Ui.grid(1, 0, 0));
        categoryList.setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        scroller.setContent(categoryList);
        scroller.addListener(SWT.Resize, e -> resizeList());
        Label note = Ui.wrap(left, "Before optimizing or attributing a portfolio, make sure Benchmark constituents and Prices & index levels are fresh. "
                + "Identifiers matter when portfolios or benchmarks change; reference data rarely does.");
        note.setForeground(Ui.SECONDARY);
        note.setFont(Ui.small());
        ((GridData) note.getLayoutData()).horizontalIndent = 10;
        ((GridData) note.getLayoutData()).verticalIndent = 6;

        Composite rightPane = split.trailing();
        rightPane.setLayout(new org.eclipse.swt.layout.FillLayout());
        activityPanel = new ActivityPanel(rightPane, this);
        bodyStack.topControl = split;
        updateHeader();
        poll();
    }

    // MARK: The service

    private void loadCategories() {
        Background.run(this, () -> {
            try {
                return (Object) service.categories();
            } catch (IOException e) {
                return null;   // not running
            }
        }, r -> {
            if (r == null) {
                serviceUp = false;
            } else {
                serviceUp = true;
                @SuppressWarnings("unchecked") List<Category> c = (List<Category>) r;
                if (!c.equals(categories)) {
                    categories = c;
                    buildRows();
                }
            }
            showBody();
        }, t -> {
            serviceUp = true;
            showBody();
            hub.error("Data Refresh", t instanceof RefreshService.ServiceError ? t.getMessage() : ErrorDialog.describe(t));
        });
    }

    private void showBody() {
        Control top = Boolean.FALSE.equals(serviceUp) ? notRunning : split;
        if (bodyStack.topControl != top) {
            bodyStack.topControl = top;
            body.layout(true, true);
        }
        updateHeader();
    }

    private int pollToken;
    private boolean pollAgain;

    /**
     * What the service logged since the last poll; every 0.7 s while something runs, else every 3 s (one chain of
     * polls: a newer call cancels the one scheduled).
     */
    private void poll() {
        if (isDisposed()) return;
        if (polling) {
            pollAgain = true;
            return;
        }
        int token = ++pollToken;
        if (!isVisible()) {
            later(1000);
            return;
        }
        polling = true;
        int after = lastSeq;
        Background.run(this, () -> service.log(after), page -> {
            polling = false;
            if (page.last() < lastSeq) {
                // The service restarted and numbers its log from 1 again.
                activity.clear();
                lastSeq = 0;
                poll();
                return;
            }
            for (Entry e : page.entries()) if (e.seq() > lastSeq) activity.add(e);
            if (activity.size() > 20000) activity.subList(0, activity.size() - 20000).clear();
            boolean wasRunning = current != null;
            lastSeq = page.last();
            current = page.running();
            lastPoll = LocalTime.now();
            activityPanel.refresh();
            updateRows();
            if (wasRunning && current == null && running == null) loadCategories();
            if (pollAgain) {
                pollAgain = false;
                poll();
            } else if (token == pollToken) {
                later(running != null || current != null ? 700 : 3000);
            }
        }, t -> {
            polling = false;
            current = null;
            activityPanel.updateStrip();
            updateRows();
            if (pollAgain) {
                pollAgain = false;
                poll();
            } else if (token == pollToken) {
                later(3000);
            }
        });
    }

    private void later(int millis) {
        if (isDisposed()) return;
        int token = ++pollToken;
        getDisplay().timerExec(millis, () -> {
            if (token == pollToken) poll();
        });
    }

    /** Runs one category's loaders, in order. */
    private void refresh(String id, Runnable after) {
        if (running != null) return;
        running = id;
        updateHeader();
        updateRows();
        boolean dry = dryRun.getSelection();
        int v = verbosity;
        Background.run(this, () -> service.refresh(id, v, dry), result -> {
            results.add(0, result);
            finishRefresh(after);
        }, t -> {
            String message = t instanceof RefreshService.ServiceError ? t.getMessage() : ErrorDialog.describe(t);
            results.add(0, new Result(id, id, "failed", null, dry, null, List.of()));
            hub.error("Data Refresh", message);
            finishRefresh(after);
        });
    }

    private void finishRefresh(Runnable after) {
        running = null;
        updateHeader();
        updateRows();
        poll();
        loadCategories();
        if (hub.isConnected() && main.existing(Section.LOADS) instanceof LoadsScreen loads) loads.reload();
        if (after != null) after.run();
    }

    /** Every category except the optional ones, in order; stops at the first failure. */
    private void refreshAll() {
        List<Category> all = categories.stream().filter(c -> !c.optional()).toList();
        if (all.isEmpty() || running != null) return;
        refreshStep(all, 0);
    }

    private void refreshStep(List<Category> all, int i) {
        if (i >= all.size() || isDisposed()) {
            allProgress = null;
            updateHeader();
            return;
        }
        allProgress = new int[] { i + 1, all.size() };
        updateHeader();
        refresh(all.get(i).id(), () -> {
            if (!results.isEmpty() && "failed".equals(results.get(0).status())) {
                allProgress = null;
                updateHeader();
            } else {
                refreshStep(all, i + 1);
            }
        });
    }

    private boolean busy() {
        return running != null || (current != null && !current.dryRun());
    }

    private void updateHeader() {
        if (isDisposed()) return;
        refreshAll.setText(allProgress != null ? "⟳ Refreshing " + allProgress[0] + " of " + allProgress[1] + "…" : "⟳ Refresh All");
        refreshAll.setEnabled(running == null && !categories.isEmpty());
        refreshAll.getParent().layout(true);
    }

    // MARK: Category rows

    private record Row(Category category, Composite box, Button button, Label badge) {}

    private final List<Row> rows = new ArrayList<>();

    private void buildRows() {
        for (Control c : categoryList.getChildren()) c.dispose();
        rows.clear();
        Color white = getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        for (Category c : categories) {
            Composite box = new Composite(categoryList, SWT.NONE);
            box.setLayout(Ui.grid(3, 10, 4));
            box.setLayoutData(Ui.fillH());
            Label badge = Ui.label(box, "", Ui.font(-1, SWT.BOLD));
            badge.setForeground(Ui.WHITE);
            GridData bg = new GridData(SWT.LEFT, SWT.TOP, false, false);
            bg.widthHint = 70;
            badge.setLayoutData(bg);
            badge.setAlignment(SWT.CENTER);
            Composite text = new Composite(box, SWT.NONE);
            text.setLayout(Ui.grid(1, 0, 3));
            text.setLayoutData(Ui.fillH());
            Composite title = new Composite(text, SWT.NONE);
            title.setLayout(Ui.grid(2, 0, 6));
            Ui.label(title, c.name(), Ui.bold());
            if (c.optional()) Ui.label(title, " Optional ", Ui.small()).setBackground(Ui.SIDEBAR);
            Label desc = Ui.wrap(text, c.description());
            desc.setForeground(Ui.SECONDARY);
            List<String> info = new ArrayList<>();
            if (c.dataAsOf() != null) info.add("Data as of " + c.dataAsOf());
            info.add("Last load " + shortDate(c.lastRun()));
            info.add("Refresh " + c.cadence());
            Label meta = Ui.label(text, String.join("   ·   ", info));
            meta.setForeground(Ui.SECONDARY);
            meta.setFont(Ui.small());
            Composite chips = new Composite(text, SWT.NONE);
            org.eclipse.swt.layout.RowLayout rl = new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL);
            rl.marginLeft = rl.marginTop = rl.marginBottom = 0;
            rl.spacing = 6;
            chips.setLayout(rl);
            chips.setLayoutData(Ui.fillH());
            for (Loader l : c.loaders()) {
                Label chip = Ui.label(chips, " " + l.loader() + " ");
                chip.setFont(Ui.mono());
                chip.setForeground(ActivityPanel.stateColor(l.status()));
                chip.setToolTipText(l.loader() + ": " + l.status() + (l.message() == null ? "" : " — " + l.message()));
                QuickHelp.help(chip, l.loader(), l.status() + (l.lastRun() == null ? "" : ", last run " + shortDate(l.lastRun()))
                        + (l.message() == null ? "" : " — " + l.message()));
            }
            Button button = Ui.button(box, "Refresh", () -> refresh(c.id(), null));
            button.setLayoutData(new GridData(SWT.END, SWT.TOP, false, false));
            QuickHelp.help(button, "Refresh " + c.name(), "Runs " + String.join(", ", c.loaders().stream().map(Loader::loader).toList())
                    + " now through the SRM loader service; the result appears on the right.");
            Ui.background(box, white);
            QuickHelpItem help = new QuickHelpItem(c.name(), c.description() + "\n" + Fmt.capitalized(c.state())
                    + (c.optional() ? " · optional: Refresh All skips it" : "") + " · refreshed " + c.cadence() + " · loaders "
                    + String.join(", ", c.loaders().stream().map(l -> l.loader() + " (" + l.status() + ")").toList()) + ".");
            for (Control k : new Control[] { box, text, title, desc, meta, badge }) QuickHelp.help(k, help.title(), help.detail());
            new Label(categoryList, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());
            rows.add(new Row(c, box, button, badge));
        }
        updateRows();
        resizeList();
    }

    /** The running category highlighted, its badge, and the buttons enabled while nothing runs. */
    private void updateRows() {
        if (isDisposed()) return;
        boolean busy = busy();
        Color white = getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        for (Row r : rows) {
            if (r.box().isDisposed()) continue;
            boolean isRunning = Objects.equals(running, r.category().id()) || (current != null && Objects.equals(current.category(), r.category().id()));
            String state = isRunning ? "running" : r.category().state();
            r.badge().setText(Fmt.capitalized(state));
            Color bg = isRunning ? ActivityPanel.RUNNING_BG : white;
            if (!Objects.equals(r.box().getBackground(), bg)) {
                Ui.background(r.box(), bg);
            }
            r.badge().setBackground(ActivityPanel.stateColor(state));
            r.button().setText(isRunning ? "Running…" : "Refresh");
            r.button().setEnabled(!busy);
        }
        categoryList.layout(true, true);
    }

    private void resizeList() {
        int width = scroller.getClientArea().width;
        if (width <= 0) return;
        scroller.setMinSize(categoryList.computeSize(width, SWT.DEFAULT));
        categoryList.layout(true, true);
    }

    private static String shortDate(String iso) {
        if (iso == null) return "never";
        return (iso.length() > 16 ? iso.substring(0, 16) : iso).replace('T', ' ');
    }

    // MARK: Screen

    @Override
    public void shown() {
        loadCategories();
        poll();
    }

    @Override
    public void hubChanged() {
        if (!isDisposed() && isVisible()) activityPanel.refresh();
    }

    @Override
    public Control control() { return this; }
}
