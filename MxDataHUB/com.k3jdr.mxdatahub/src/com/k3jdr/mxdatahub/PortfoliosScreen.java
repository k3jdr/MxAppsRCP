package com.k3jdr.mxdatahub;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.links.AppLinks;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Db.HoldingEntry;
import com.k3jdr.mxdatahub.Rows.*;

/**
 * Portfolios (SRMPortfoliosView.swift): portfolios with dated holdings snapshots. Holdings are added by any
 * identifier, as shares and/or a target weight; the snapshot is valued with the latest prices, checked by
 * srm.portfolio_validate, and compared with the portfolio's benchmark. A personal account (V011) shows its tax lots
 * and opens in MxAcctMGR. Every change is one SQL statement, committed on its own.
 */
final class PortfoliosScreen extends Composite implements Screen {
    private final Hub hub;
    private final MainPart main;
    private final Table list;
    private final Button edit, workflows, delete;

    private final Composite right;
    private final StackLayout rightStack = new StackLayout();
    private final Placeholder noPortfolio;
    private final Composite panel;
    private final Label name, subtitle;
    private final Button openInAcct;
    private final Composite personalRow;
    private final Label personalText;
    private final Combo dates;
    private final Button tabHoldings, tabActive, tabLots;
    private final Composite tables;
    private final StackLayout tableStack = new StackLayout();
    private final Composite holdingsBox, activeBox, lotsBox;
    private final Table holdingsTable, activeTable, lotsTable;
    private final Label holdingsSummary, activeSummary, lotsSummary;
    private final Composite issuesBox;
    private final Button normalize, remove;

    private List<String> snapshotDates = List.of();
    private String snapshotDate;
    private List<HoldingRow> holdings = List.of();
    private List<ValidationIssue> issues = List.of();
    private List<ActiveWeightRow> activeWeights = List.of();
    private List<TaxLotRow> taxLots = List.of();
    /** 0 holdings, 1 vs benchmark, 2 tax lots (personal accounts). */
    private int panelIndex;
    private int generation;
    private List<PortfolioRow> listed = List.of();

    PortfoliosScreen(Composite parent, Hub hub, MainPart main) {
        super(parent, SWT.NONE);
        this.hub = hub;
        this.main = main;
        setLayout(new FillLayout());
        SplitPanes split = new SplitPanes(this, "portfolios", 200, 360, 300, 560);

        // The list of portfolios.
        Composite left = split.leading();
        left.setLayout(Ui.grid(1, 10, 8));
        list = Tables.create(left, false, HubHelp.PORTFOLIO_LIST, Tables.col("Code", 80), Tables.col("Name", 120), Tables.col("Benchmark", 70));
        Tables.copyMenu(list);
        list.addListener(SWT.Selection, e -> {
            PortfolioRow p = Tables.row(list.getSelectionCount() > 0 ? list.getSelection()[0] : null);
            selectPortfolio(p == null ? null : p.id());
        });
        list.addListener(SWT.DefaultSelection, e -> editPortfolio(hub.selectedPortfolio()));
        Composite buttons = new Composite(left, SWT.NONE);
        org.eclipse.swt.layout.RowLayout rl = new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL);
        rl.wrap = true;
        rl.marginLeft = rl.marginRight = rl.marginTop = rl.marginBottom = 0;
        rl.spacing = 6;
        buttons.setLayout(rl);
        buttons.setLayoutData(Ui.fillH());
        Button create = Ui.button(buttons, "New…", () -> editPortfolio(null));
        QuickHelp.help(create, "New Portfolio", "A portfolio with a code, name, currency and optional benchmark; then add its holdings.");
        edit = Ui.button(buttons, "Edit…", () -> editPortfolio(hub.selectedPortfolio()));
        QuickHelp.help(edit, "Edit Portfolio", "Changes the selected portfolio's code, name, currency, benchmark or description (double-clicking it does too).");
        workflows = Ui.button(buttons, "Workflows ▾", this::workflowsMenu);
        QuickHelp.provider(workflows, (c, p) -> {
            String b = benchmarkName();
            return new QuickHelpItem("Workflows", "Opens a workflow for this portfolio in MxJobsMGR: optimize it against " + b
                    + " within a tracking-error budget, track " + b + " passively, or attribute its returns against " + b + ".");
        });
        delete = Ui.button(buttons, "Delete…", this::deletePortfolio);
        QuickHelp.help(delete, "Delete Portfolio", "Deletes the selected portfolio with its holdings and transactions, after asking. It can't be undone.");

        // The selected portfolio.
        right = split.trailing();
        right.setLayout(rightStack);
        noPortfolio = new Placeholder(right);
        noPortfolio.show("No Portfolio", "Create a portfolio, then add holdings by ticker, ISIN, CUSIP, SEDOL or FIGI.")
                .button("New Portfolio…", () -> editPortfolio(null));
        panel = new Composite(right, SWT.NONE);
        panel.setLayout(Ui.grid(1, 10, 8));

        Composite head = new Composite(panel, SWT.NONE);
        head.setLayout(Ui.grid(3, 0, 8));
        head.setLayoutData(Ui.fillH());
        name = Ui.label(head, "", Ui.subheading());
        subtitle = Ui.label(head, "");
        subtitle.setForeground(Ui.SECONDARY);
        subtitle.setLayoutData(Ui.fillH());
        openInAcct = Ui.button(head, "Open in MxAcctMGR", () -> {
            PortfolioRow p = hub.selectedPortfolio();
            if (p != null && !AppLinks.open(new AppLink.MyPortfolio(p.code()))) hub.error("MxAcctMGR is not installed: install it beside MxDataHUB, or register its mxacctmgr:// links.");
        });
        openInAcct.setToolTipText("Open this account as My Portfolio in MxAcctMGR");
        QuickHelp.help(openInAcct, "Open in MxAcctMGR", "Opens this personal account as My Portfolio in MxAcctMGR, where its tax lots are edited and it is analysed, optimized or tracked.");

        personalRow = new Composite(panel, SWT.NONE);
        personalRow.setLayout(Ui.grid(2, 0, 8));
        personalRow.setLayoutData(Ui.fillH());
        Ui.label(personalRow, "Personal account", Ui.bold());
        personalText = Ui.label(personalRow, "");
        personalText.setForeground(Ui.SECONDARY);
        personalText.setLayoutData(Ui.fillH());

        Composite controls = new Composite(panel, SWT.NONE);
        controls.setLayout(Ui.grid(7, 0, 6));
        controls.setLayoutData(Ui.fillH());
        Ui.label(controls, "Holdings on");
        dates = new Combo(controls, SWT.READ_ONLY);
        GridData dg = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        dg.widthHint = 110;
        dates.setLayoutData(dg);
        dates.addListener(SWT.Selection, e -> {
            int i = dates.getSelectionIndex();
            if (i >= 0) {
                snapshotDate = dates.getItem(i);
                loadHoldings();
            }
        });
        QuickHelp.help(dates, "Holdings On", "The snapshot date shown: each date has its own holdings, valued with the latest prices.");
        Button newSnapshot = Ui.button(controls, "New Snapshot…", this::newSnapshot);
        newSnapshot.setToolTipText("Start holdings on another date, copied from the latest earlier snapshot");
        QuickHelp.help(newSnapshot, "New Snapshot", "Starts holdings on another date, copied from the latest earlier snapshot, to change from there.");
        new Label(controls, SWT.NONE).setLayoutData(Ui.fillH());
        tabHoldings = tab(controls, "Holdings", 0);
        tabActive = tab(controls, "vs Benchmark", 1);
        tabLots = tab(controls, "Tax Lots", 2);

        tables = new Composite(panel, SWT.NONE);
        tables.setLayoutData(Ui.fill());
        tables.setLayout(tableStack);

        holdingsBox = box(tables);
        holdingsTable = Tables.create(holdingsBox, true, HubHelp.HOLDINGS, Tables.col("Ticker", 60), Tables.col("Name", 170),
                Tables.col("Entered as", 110), Tables.num("Shares", 75), Tables.num("Target", 65), Tables.num("Price", 70),
                Tables.num("Market value", 95), Tables.num("Weight", 70));
        Menu hm = new Menu(holdingsTable);
        holdingsTable.setMenu(hm);
        hub.links.addMenuItems(hm, holdingsTable, 0, 1, this::linkTarget, this::paintLinks);
        Tables.copyMenu(holdingsTable);
        hub.links.decorate(holdingsTable, 0, 1, this::linkTarget);
        holdingsTable.addListener(SWT.Selection, e -> updateButtons());
        holdingsTable.addListener(SWT.KeyDown, e -> {
            if ((e.keyCode == SWT.DEL || e.keyCode == SWT.BS) && (e.stateMask & SWT.MODIFIER_MASK) == 0) removeHoldings();
        });
        holdingsSummary = summary(holdingsBox);

        activeBox = box(tables);
        activeTable = Tables.create(activeBox, true, HubHelp.ACTIVE_WEIGHTS, Tables.col("Ticker", 60), Tables.col("Name", 220),
                Tables.num("Portfolio", 80), Tables.num("Benchmark", 80), Tables.num("Active", 80));
        Tables.copyMenu(activeTable);
        activeSummary = summary(activeBox);

        lotsBox = box(tables);
        lotsTable = Tables.create(lotsBox, true, HubHelp.TAX_LOTS, Tables.col("Ticker", 60), Tables.col("Name", 180),
                Tables.col("Bought", 85), Tables.num("Shares", 75), Tables.num("Cost", 85), Tables.num("Value", 85),
                Tables.num("Gain", 85), Tables.col("Term", 50));
        Tables.copyMenu(lotsTable);
        lotsSummary = summary(lotsBox);
        tableStack.topControl = holdingsBox;

        issuesBox = new Composite(panel, SWT.NONE);
        issuesBox.setLayout(Ui.grid(1, 0, 2));
        issuesBox.setLayoutData(Ui.fillH());
        QuickHelp.help(issuesBox, "Checks", "srm.portfolio_validate's findings for the snapshot: weights that don't add up to 100%, unpriced or stale holdings, inactive or short positions.");

        Composite actions = new Composite(panel, SWT.NONE);
        actions.setLayout(Ui.grid(6, 0, 6));
        actions.setLayoutData(Ui.fillH());
        Button add = Ui.button(actions, "Add Holding…", this::addHolding);
        QuickHelp.help(add, "Add Holding", "Adds a security by any identifier (ticker, TICKER:MIC, ISIN, CUSIP, SEDOL, FIGI) as shares or a weight; one already held is replaced.", "Ctrl+Shift+N");
        Button paste = Ui.button(actions, "Paste Holdings…", this::pasteHoldings);
        QuickHelp.help(paste, "Paste Holdings", "Adds many holdings from pasted lines: an identifier, optionally its type, then shares or a weight ending in % (\"AAPL 100\", \"US5949181045, 4.5%\").");
        Button copyBenchmark = new Button(actions, SWT.PUSH);
        copyBenchmark.setText("Copy Benchmark ▾");
        copyBenchmark.addListener(SWT.Selection, e -> copyBenchmarkMenu(copyBenchmark));
        copyBenchmark.setToolTipText("Fill this snapshot with a benchmark's weights");
        QuickHelp.help(copyBenchmark, "Copy Benchmark", "Fills this snapshot with a benchmark's members and weights, as target weights — a start for an index-like portfolio.");
        normalize = Ui.button(actions, "Normalize Weights", this::normalizeWeights);
        normalize.setToolTipText("Scale target weights to add up to 100%");
        QuickHelp.help(normalize, "Normalize Weights", "Scales the target weights so they add up to 100%.");
        new Label(actions, SWT.NONE).setLayoutData(Ui.fillH());
        remove = Ui.button(actions, "Remove", this::removeHoldings);
        QuickHelp.help(remove, "Remove", "Takes the selected holdings out of this snapshot.", "Delete");

        rightStack.topControl = noPortfolio;
        installShortcut();
        updateButtons();
    }

    private static Composite box(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(Ui.grid(1, 0, 4));
        return c;
    }

    private static Label summary(Composite parent) {
        Label l = Ui.label(parent, "");
        l.setForeground(Ui.SECONDARY);
        l.setLayoutData(Ui.fillH());
        return l;
    }

    private Button tab(Composite parent, String text, int index) {
        Button b = new Button(parent, SWT.TOGGLE);
        b.setText(text);
        b.setSelection(index == 0);
        b.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        b.addListener(SWT.Selection, e -> showPanel(index));
        QuickHelp.help(b, "Holdings / vs Benchmark / Tax Lots", "The holdings, each security's weight against the benchmark's (needs a benchmark), or — for a personal account — its open tax lots.");
        return b;
    }

    /** Add Holding… (Ctrl+Shift+N) while Portfolios shows. */
    private void installShortcut() {
        Display d = getDisplay();
        Listener l = e -> {
            if (!isVisible() || !(e.widget instanceof Control c) || c.getShell() != getShell()) return;
            if ((e.stateMask & SWT.MODIFIER_MASK) == (SWT.MOD1 | SWT.SHIFT) && (e.keyCode == 'n' || e.keyCode == 'N')) {
                e.doit = false;
                addHolding();
            }
        };
        d.addFilter(SWT.KeyDown, l);
        addListener(SWT.Dispose, e -> d.removeFilter(SWT.KeyDown, l));
    }

    private Links.Target linkTarget(Object data) {
        return data instanceof HoldingRow h ? new Links.Target(h.securityId(), h.ticker(), h.name(), h.type()) : null;
    }

    private void paintLinks() {
        hub.links.paint(holdingsTable, 0, 1, this::linkTarget);
    }

    /** Edit ▸ Link… (Ctrl+K): the link on the first selected holding's ticker, or its name when the pointer rests there. */
    void editLink(String field) {
        TableItem[] sel = holdingsTable.getSelection();
        if (sel.length == 0 || panelIndex != 0) return;
        Links.Target t = linkTarget(sel[0].getData());
        if (t == null) return;
        String f = field != null ? field : hub.links.fieldAt(holdingsTable, 0, 1);
        hub.links.edit(holdingsTable, t, f == null ? "ticker" : f, this::paintLinks);
    }

    // MARK: The list

    private void fillList() {
        listed = hub.portfolios;
        Tables.fill(list, listed, p -> new String[] { p.code(), p.name(), p.benchmarkCode() });
        selectInList(hub.selectedPortfolioId);
    }

    private void selectInList(Integer id) {
        TableItem[] items = list.getItems();
        for (int i = 0; i < items.length; i++) {
            if (items[i].getData() instanceof PortfolioRow p && id != null && p.id() == id) {
                list.setSelection(i);
                list.showSelection();
                return;
            }
        }
        list.deselectAll();
    }

    private void selectPortfolio(Integer id) {
        boolean changed = !Objects.equals(id, hub.selectedPortfolioId);
        hub.selectedPortfolioId = id;
        if (changed) {
            loadSnapshots();
            main.placeChanged();
        }
    }

    private String benchmarkName() {
        PortfolioRow p = hub.selectedPortfolio();
        return p != null && !p.benchmarkCode().isEmpty() ? p.benchmarkCode() : "benchmark";
    }

    private void workflowsMenu() {
        PortfolioRow p = hub.selectedPortfolio();
        if (p == null) return;
        String b = benchmarkName();
        Menu m = new Menu(getShell(), SWT.POP_UP);
        MenuItem header = new MenuItem(m, SWT.PUSH);
        header.setText("Open in MxJobsMGR");
        header.setEnabled(false);
        menuItem(m, "Optimize — active vs " + b, () -> openSample("active-optimization"));
        menuItem(m, "Track " + b + " — passive", () -> openSample("passive-tracking"));
        menuItem(m, "Attribution vs " + b, () -> openSample("attribution"));
        popUnder(m, workflows);
    }

    private void openSample(String id) {
        PortfolioRow p = hub.selectedPortfolio();
        if (p == null) return;
        if (!AppLinks.open(new AppLink.Sample(id, p.code(), p.benchmarkCode())))
            hub.error("MxJobsMGR is not installed: install it beside MxDataHUB, or register its mxjobsmgr:// links.");
    }

    static void menuItem(Menu m, String text, Runnable action) {
        MenuItem mi = new MenuItem(m, SWT.PUSH);
        mi.setText(Ui.escape(text));
        mi.addListener(SWT.Selection, e -> action.run());
    }

    static void popUnder(Menu m, Control under) {
        Rectangle r = under.getBounds();
        Point p = under.getParent().toDisplay(r.x, r.y + r.height);
        m.setLocation(p);
        m.setVisible(true);
    }

    private void editPortfolio(PortfolioRow existing) {
        if (!hub.isConnected()) return;
        PortfolioDialogs.PortfolioDialog d = new PortfolioDialogs.PortfolioDialog(getShell(), hub, existing);
        if (d.open() != PortfolioDialogs.PortfolioDialog.OK) return;
        Integer id = existing == null ? null : existing.id();
        hub.run(this, () -> hub.db.savePortfolio(id, d.codeValue, d.nameValue, d.currencyValue, d.benchmarkValue, d.descriptionValue), newId -> {
            hub.selectedPortfolioId = newId;
            hub.refreshPortfolios(() -> {
                fillList();
                loadSnapshots();
                main.placeChanged();
            });
        });
    }

    private void deletePortfolio() {
        PortfolioRow p = hub.selectedPortfolio();
        if (p == null) return;
        MessageBox box = new MessageBox(getShell(), SWT.ICON_WARNING | SWT.YES | SWT.NO);
        box.setText("MxDataHUB");
        box.setMessage("Delete portfolio " + p.code() + "?\n\nIts holdings and transactions are deleted too. This cannot be undone.");
        if (box.open() != SWT.YES) return;
        hub.run(this, () -> {
            hub.db.deletePortfolio(p.id());
            return true;
        }, ok -> {
            hub.selectedPortfolioId = null;
            hub.refreshPortfolios(() -> {
                fillList();
                loadSnapshots();
                main.placeChanged();
            });
        });
    }

    // MARK: Snapshots and holdings

    /** Snapshot dates of the selected portfolio (keeps the date shown when it still exists), then its holdings. */
    private void loadSnapshots() {
        PortfolioRow p = hub.selectedPortfolio();
        showPortfolio();
        if (p == null) {
            snapshotDates = List.of();
            holdings = List.of();
            return;
        }
        int gen = ++generation;
        String keep = snapshotDate;
        hub.run(this, () -> {
            List<String> d = hub.db.snapshotDates(p.id());
            String date = keep == null || !d.contains(keep) ? (d.isEmpty() ? LocalDate.now().toString() : d.get(0)) : keep;
            Db.Snapshot s = hub.db.snapshot(p.id(), date, p.benchmarkId());
            List<TaxLotRow> lots = p.personal() ? hub.db.taxLots(p.id()) : List.of();
            return new Object[] { d, date, s, lots };
        }, r -> {
            if (gen != generation) return;
            @SuppressWarnings("unchecked") List<String> d = (List<String>) r[0];
            snapshotDates = d;
            snapshotDate = (String) r[1];
            showSnapshot((Db.Snapshot) r[2]);
            @SuppressWarnings("unchecked") List<TaxLotRow> lots = (List<TaxLotRow>) r[3];
            showLots(lots);
            fillDates();
        });
    }

    private void loadHoldings() {
        PortfolioRow p = hub.selectedPortfolio();
        if (p == null || snapshotDate == null) return;
        int gen = ++generation;
        String date = snapshotDate;
        hub.run(this, () -> hub.db.snapshot(p.id(), date, p.benchmarkId()), s -> {
            if (gen == generation) showSnapshot(s);
        });
    }

    private void fillDates() {
        List<String> choices = new ArrayList<>(snapshotDates);
        if (snapshotDate != null && !choices.contains(snapshotDate)) choices.add(0, snapshotDate);
        dates.setItems(choices.toArray(String[]::new));
        int i = choices.indexOf(snapshotDate);
        if (i >= 0) dates.select(i);
        dates.getParent().layout(true);
    }

    private void showPortfolio() {
        PortfolioRow p = hub.selectedPortfolio();
        if (p == null) {
            rightStack.topControl = noPortfolio;
            right.layout(true, true);
            updateButtons();
            return;
        }
        name.setText(Ui.escape(p.name()));
        subtitle.setText(Ui.escape(p.code() + " · " + p.currency() + (p.benchmarkCode().isEmpty() ? "" : " · benchmark " + p.benchmarkCode())));
        Ui.setVisible(openInAcct, p.personal());
        Ui.setVisible(personalRow, p.personal());
        if (p.personal()) {
            List<String> parts = new ArrayList<>();
            parts.add(Fmt.capitalized(p.accountType()));
            if (!p.approach().isEmpty()) parts.add(Fmt.capitalized(p.approach()) + " approach");
            if (p.cash() > 0) parts.add("cash " + Fmt.number(p.cash()));
            if (!p.managedBy().isEmpty()) parts.add("maintained in " + p.managedBy());
            personalText.setText(Ui.escape(String.join(" · ", parts)));
            QuickHelp.help(personalRow, "Personal Account", "A portfolio with an account type (V011): taxable, retirement or Roth, run actively or passively, "
                    + "with uninvested cash and open tax lots." + (p.managedBy().isEmpty() ? "" : " " + p.managedBy()
                    + " rewrites today's holdings from the lots whenever it saves, so edit the lots there."));
        }
        Ui.setVisible(tabLots, p.personal());
        if (!p.personal() && panelIndex == 2) panelIndex = 0;
        showPanel(panelIndex);
        rightStack.topControl = panel;
        right.layout(true, true);
        updateButtons();
    }

    private void showPanel(int index) {
        panelIndex = index;
        tabHoldings.setSelection(index == 0);
        tabActive.setSelection(index == 1);
        tabLots.setSelection(index == 2);
        PortfolioRow p = hub.selectedPortfolio();
        if (index == 1 && p != null && p.benchmarkId() != null) tableStack.topControl = activeBox;
        else if (index == 2 && p != null && p.personal()) tableStack.topControl = lotsBox;
        else tableStack.topControl = holdingsBox;
        tables.layout(true, true);
    }

    private void showSnapshot(Db.Snapshot s) {
        holdings = s.holdings();
        issues = s.issues();
        activeWeights = s.activeWeights();
        Tables.fill(holdingsTable, holdings, h -> new String[] { h.ticker(), h.name(),
                h.input().isEmpty() ? "" : h.input() + " (" + h.inputType() + ")", Fmt.number(h.quantity(), 0),
                Fmt.percent(h.targetWeight(), 2), Fmt.number(h.price()), Fmt.number(h.marketValue()), Fmt.percent(h.weight(), 2) });
        for (TableItem item : holdingsTable.getItems()) item.setForeground(2, Ui.SECONDARY);
        double total = holdings.stream().map(HoldingRow::marketValue).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        List<Double> targets = holdings.stream().map(HoldingRow::targetWeight).filter(Objects::nonNull).toList();
        holdingsSummary.setText(holdings.size() + " holdings" + (total > 0 ? " · market value " + Fmt.number(total) : "")
                + (targets.isEmpty() ? "" : " · target weights " + Fmt.percent(targets.stream().mapToDouble(Double::doubleValue).sum(), 2)));
        hub.links.load(holdingsTable, holdings.stream().map(HoldingRow::securityId).toList(), this::paintLinks);

        Tables.fill(activeTable, activeWeights, a -> new String[] { a.ticker(), a.name(), Fmt.percent(a.portfolio(), 2),
                Fmt.percent(a.benchmark(), 2), Fmt.percent(a.active(), 2) });
        for (TableItem item : activeTable.getItems()) {
            if (item.getData() instanceof ActiveWeightRow a) item.setForeground(4, a.active() >= 0 ? Ui.GREEN : Ui.RED);
        }
        double overlap = activeWeights.stream().mapToDouble(a -> Math.min(a.portfolio(), a.benchmark())).sum();
        activeSummary.setText("Largest 200 active weights · overlap with benchmark " + Fmt.percent(overlap, 1)
                + " · active share " + Fmt.percent(1 - overlap, 1));

        for (Control c : issuesBox.getChildren()) c.dispose();
        if (!issues.isEmpty()) {
            for (ValidationIssue i : issues.stream().limit(8).toList()) {
                boolean error = "error".equals(i.severity());
                Label l = Ui.label(issuesBox, (error ? "✖ " : "⚠ ") + i.message());
                l.setForeground(error ? Ui.RED : Ui.ORANGE);
            }
            if (issues.size() > 8) Ui.label(issuesBox, "… and " + (issues.size() - 8) + " more").setForeground(Ui.SECONDARY);
        } else if (!holdings.isEmpty()) {
            Ui.label(issuesBox, "✔ Valid: weights add up and every holding is priced").setForeground(Ui.GREEN);
        }
        panel.layout(true, true);
        updateButtons();
        QuickHelp.get().update();
    }

    private void showLots(List<TaxLotRow> lots) {
        taxLots = lots;
        Tables.fill(lotsTable, lots, l -> new String[] { l.ticker(), l.name(), l.acquired(), Fmt.number(l.quantity(), 0),
                Fmt.number(l.costBasis()), Fmt.number(l.marketValue()), Fmt.number(l.gain()), l.longTerm() ? "long" : "short" });
        for (TableItem item : lotsTable.getItems()) {
            if (item.getData() instanceof TaxLotRow l) {
                item.setForeground(6, l.gain() == null || l.gain() >= 0 ? Ui.GREEN : Ui.RED);
                item.setForeground(7, l.longTerm() ? Ui.SECONDARY : Ui.ORANGE);
            }
        }
        double value = lots.stream().map(TaxLotRow::marketValue).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        double cost = lots.stream().mapToDouble(TaxLotRow::costBasis).sum();
        lotsSummary.setText(lots.size() + " lots · value " + Fmt.number(value) + " · cost " + Fmt.number(cost) + " · unrealised " + Fmt.number(value - cost));
    }

    private void updateButtons() {
        boolean has = hub.selectedPortfolio() != null;
        edit.setEnabled(has);
        workflows.setEnabled(has);
        delete.setEnabled(has);
        normalize.setEnabled(holdings.stream().anyMatch(h -> h.targetWeight() != null));
        remove.setEnabled(holdingsTable.getSelectionCount() > 0 && panelIndex == 0);
    }

    // MARK: Changes (one statement each)

    private String heading(String what, String preposition) {
        PortfolioRow p = hub.selectedPortfolio();
        return what + " " + preposition + " " + (p == null ? "" : p.code()) + " on " + (snapshotDate == null ? "" : snapshotDate);
    }

    /** Adds holdings in the background; tells {@code done} which lines failed, then reloads the snapshot. */
    private void add(List<HoldingEntry> entries, Consumer<List<String>> done) {
        PortfolioRow p = hub.selectedPortfolio();
        if (p == null || snapshotDate == null) {
            done.accept(List.of("Select a portfolio first."));
            return;
        }
        String date = snapshotDate;
        hub.run(this, () -> hub.db.addHoldings(p.id(), date, entries), failures -> {
            done.accept(failures);
            loadSnapshots();
            hub.refreshPortfolios(this::fillList);
        });
    }

    private void addHolding() {
        if (hub.selectedPortfolio() == null) return;
        new PortfolioDialogs.AddHoldingDialog(getShell(), hub, heading("Add Holding", "to"), this::add).open();
    }

    private void pasteHoldings() {
        if (hub.selectedPortfolio() == null) return;
        new PortfolioDialogs.PasteHoldingsDialog(getShell(), heading("Paste Holdings", "into"), this::add).open();
    }

    private void copyBenchmarkMenu(Button under) {
        if (hub.selectedPortfolio() == null) return;
        Menu m = new Menu(getShell(), SWT.POP_UP);
        for (BenchmarkRow b : hub.benchmarks) {
            if (b.members() > 0) menuItem(m, b.code() + " — " + b.members() + " members", () -> copyBenchmark(b.id()));
        }
        if (m.getItemCount() == 0) {
            MenuItem none = new MenuItem(m, SWT.PUSH);
            none.setText("(no benchmark has constituents)");
            none.setEnabled(false);
        }
        popUnder(m, under);
    }

    private void copyBenchmark(int benchmarkId) {
        PortfolioRow p = hub.selectedPortfolio();
        if (p == null || snapshotDate == null) return;
        String date = snapshotDate;
        hub.run(this, () -> {
            hub.db.copyBenchmark(p.id(), date, benchmarkId);
            return true;
        }, ok -> {
            loadSnapshots();
            hub.refreshPortfolios(this::fillList);
        });
    }

    private void normalizeWeights() {
        PortfolioRow p = hub.selectedPortfolio();
        if (p == null || snapshotDate == null) return;
        String date = snapshotDate;
        hub.run(this, () -> {
            hub.db.normalizeWeights(p.id(), date);
            return true;
        }, ok -> loadHoldings());
    }

    private void removeHoldings() {
        PortfolioRow p = hub.selectedPortfolio();
        List<HoldingRow> sel = Tables.selected(holdingsTable);
        if (p == null || snapshotDate == null || sel.isEmpty()) return;
        String date = snapshotDate;
        List<Long> ids = sel.stream().map(HoldingRow::securityId).toList();
        hub.run(this, () -> {
            hub.db.removeHoldings(p.id(), date, ids);
            return true;
        }, ok -> {
            holdingsTable.deselectAll();
            loadSnapshots();
        });
    }

    private void newSnapshot() {
        PortfolioRow p = hub.selectedPortfolio();
        if (p == null) return;
        PortfolioDialogs.NewSnapshotDialog d = new PortfolioDialogs.NewSnapshotDialog(getShell());
        if (d.open() != PortfolioDialogs.NewSnapshotDialog.OK) return;
        String day = d.chosen.toString();
        hub.run(this, () -> {
            hub.db.newSnapshot(p.id(), day);
            return true;
        }, ok -> {
            snapshotDate = day;
            loadSnapshots();
            hub.refreshPortfolios(this::fillList);
        });
    }

    // MARK: Screen

    @Override
    public void shown() {
        hub.refreshPortfolios(() -> {
            fillList();
            loadSnapshots();
            main.placeChanged();
        });
    }

    @Override
    public void hubChanged() {
        if (isDisposed()) return;
        if (!hub.portfolios.equals(listed)) {
            fillList();
            if (isVisible()) showPortfolio();
        }
    }

    @Override
    public Control control() { return this; }

    @Override
    public Long selectedItem() {
        return hub.selectedPortfolioId == null ? null : hub.selectedPortfolioId.longValue();
    }

    @Override
    public void selectItem(long id) {
        selectInList((int) id);
        selectPortfolio((int) id);
    }

    @Override
    public String itemTitle(long id) {
        return hub.portfolios.stream().filter(p -> p.id() == id).findFirst().map(p -> p.code() + " " + p.name()).orElse(null);
    }

    @Override
    public QuickHelpItem selectionHelp() {
        PortfolioRow p = hub.selectedPortfolio();
        if (!hub.isConnected() || p == null) return null;
        String valid = holdings.isEmpty() ? "No holdings on this date yet."
                : issues.isEmpty() ? "Valid: weights add up and every holding is priced."
                : issues.size() + " problem" + (issues.size() == 1 ? "" : "s") + " under the holdings.";
        return new QuickHelpItem(p.code() + " — " + p.name(), holdings.size() + " holdings on " + (snapshotDate == null ? "–" : snapshotDate)
                + (p.benchmarkCode().isEmpty() ? ", no benchmark. " : " against " + p.benchmarkCode() + ". ") + valid
                + " Holdings on picks another date; vs Benchmark shows active weights; Workflows opens it in MxJobsMGR.");
    }
}
