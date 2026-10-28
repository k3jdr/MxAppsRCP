package com.k3jdr.mxacctmgr.invest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Screen;
import com.k3jdr.mxacctmgr.port.AssetClass;
import com.k3jdr.mxacctmgr.port.Benchmark;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.Holding;
import com.k3jdr.mxacctmgr.port.Portfolio;
import com.k3jdr.mxacctmgr.port.SRMBenchmarkInfo;
import com.k3jdr.mxacctmgr.port.Security;
import com.k3jdr.mxacctmgr.port.TaxLot;
import com.k3jdr.mxacctmgr.port.Universe;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.text.Lessons;
import com.k3jdr.mxacctmgr.ui.Bars;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Investments steps 1 and 2 (PathViews.swift's PortfolioView): My Holdings — the holdings with their shares, values,
 * cost and tax lots (Edit Holding…, Add Securities…, remove), value, look-through, weighted fee, unrealised gain, the
 * checks and the asset mix; My Benchmark — the benchmark that matches the plan, overlap and active share, sectors and
 * asset mix against it, and its 25 largest members.
 */
public final class HoldingsPage extends InvestPage {
    private static final String[] TITLES = { "Ticker", "Name", "Sector / class", "Shares", "Value", "Weight", "Cost basis", "Gain", "Lots" };
    private final boolean holdings;

    public HoldingsPage(App app) {
        super(app);
        this.holdings = screen == Screen.MY_PORTFOLIO;
    }

    @Override
    protected Lessons.Lesson lesson() {
        Lessons.Lesson l = super.lesson();
        if (holdings && !p().isSRM() && l != null) {
            Lessons.Lesson local = Lessons.of("myPortfolio.local");
            if (local != null) return new Lessons.Lesson(local.subtitle(), l.title(), l.points());
        }
        return l;
    }

    @Override
    protected void started() {
        Portfolio p = p();
        if (!holdings && p.isSRM() && Universe.benchmark(p.benchmark).weights().isEmpty()) loadBenchmark(p.benchmark);
    }

    private void loadBenchmark(String code) {
        List<SRMBenchmarkInfo> known = port().srmBenchmarks;
        port().run(() -> {
            PortfolioStore.useBenchmark(code, known);
            return Boolean.TRUE;
        }, ok -> rebuild(), null);
    }

    @Override
    protected void fill(Composite body) {
        Portfolio p = p();
        if (holdings) {
            if (p.id.equals(PortfolioStore.MINE)) addRow(body);
            holdingsTable(body, p);
            Composite t = Parts.tiles(body, 4);
            Parts.tile(t, "Value", Fmt.money(p.total()), p.account.title, null, "Market value of the account's holdings.");
            Parts.tile(t, "Holdings", String.valueOf(p.holdings.size()), p.lookThrough().size() + " securities by look-through", null,
                    "Positions as held, and the number of different securities once funds are replaced by what they hold.");
            Parts.tile(t, "Weighted fee", Fmt.pct(p.weightedFee(), 2), "a year, from fund fees", null, "Fund expense ratios weighted by position size. Single stocks have no fee.");
            double gain = p.holdings.stream().mapToDouble(Holding::unrealizedGain).sum();
            Parts.tile(t, "Unrealised gain", Fmt.money(gain), p.account.isTaxable() ? "taxed when sold" : "no tax in this account type", signed(gain),
                    "Value minus cost basis across tax lots. In a taxable account, selling realises it.");
        } else {
            benchmarkBox(body, p);
        }
        List<Portfolio.Issue> issues = p.issues();
        if (!issues.isEmpty() && holdings) checks(body, issues);
        if (holdings) {
            Composite box = Parts.box(body, "Asset mix", "Stocks, bonds and cash by look-through.");
            Map<AssetClass, Double> mine = p.classWeights();
            List<Bars.Row> rows = new ArrayList<>();
            for (AssetClass c : AssetClass.values()) rows.add(new Bars.Row(c.title, mine.getOrDefault(c, 0.0) * 100));
            new Bars(box, List.of(new Bars.Series("Account", Bars.BLUE)), rows, 70, "%");
        } else {
            Composite two = new Composite(body, SWT.NONE);
            two.setLayout(new org.eclipse.swt.layout.GridLayout(2, false));
            ((org.eclipse.swt.layout.GridLayout) two.getLayout()).marginWidth = 0;
            two.setLayoutData(Ui.fillH());
            Composite sectors = Parts.box(two, "Sectors: account vs benchmark (look-through)", "Each sector's weight in the account (funds looked through) beside its weight in the benchmark. The gaps are the account's sector bets.");
            ((GridData) sectors.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
            Map<String, Double> acc = p.sectorWeights(), ben = Portfolio.sectors(p.benchmarkInfo().weights());
            Set<String> all = new HashSet<>(acc.keySet());
            all.addAll(ben.keySet());
            List<String> order = new ArrayList<>(all);
            order.sort(Comparator.comparingDouble(s -> -(acc.getOrDefault(s, 0.0) + ben.getOrDefault(s, 0.0))));
            List<Bars.Row> rows = new ArrayList<>();
            for (String s : order) rows.add(new Bars.Row(s, acc.getOrDefault(s, 0.0) * 100, ben.getOrDefault(s, 0.0) * 100));
            new Bars(sectors, List.of(new Bars.Series("Account", Bars.BLUE), new Bars.Series("Benchmark", Bars.GRAY)), rows, 130, "%");
            Composite mix = Parts.box(two, "Asset mix", "Stocks, bonds and cash by look-through, against the benchmark's mix.");
            GridData mg = (GridData) mix.getParent().getLayoutData();
            mg.grabExcessHorizontalSpace = false;
            mg.widthHint = 320;
            mg.verticalAlignment = SWT.FILL;
            Map<AssetClass, Double> mine = p.classWeights(), bench = Portfolio.classes(p.benchmarkInfo().weights());
            List<Bars.Row> mixRows = new ArrayList<>();
            for (AssetClass c : AssetClass.values()) mixRows.add(new Bars.Row(c.title, mine.getOrDefault(c, 0.0) * 100, bench.getOrDefault(c, 0.0) * 100));
            new Bars(mix, List.of(new Bars.Series("Account", Bars.BLUE), new Bars.Series("Benchmark", Bars.GRAY)), mixRows, 60, "%");
            constituents(body, p);
        }
    }

    private void addRow(Composite body) {
        Composite row = Parts.row(body, 2);
        Button add = Ui.button(row, "＋ Add Securities…", () -> new AddSecuritiesDialog(app).open());
        add.setEnabled(port().openPortfolio() != null);
        add.setToolTipText("Find and add stocks, individual bonds, funds or cash (Ctrl+Shift+N)");
        QuickHelp.help(add, "Add Securities", "Opens a dialog to find securities in the security master — by ticker, name, ISIN or CUSIP — see what each is, and add shares with a tax lot (cost and purchase date).", "Ctrl+Shift+N");
        Parts.caption(row, p().isSRM() ? "Double-click a holding to edit its tax lots — purchase date, shares, cost — or the cash; the context menu removes it. Changes are kept when you save (File ▸ Save, Ctrl+S)."
                : "Double-click a holding to edit it; the context menu removes it.");
    }

    private void holdingsTable(Composite body, Portfolio p) {
        Composite box = Parts.box(body, "Holdings", "Each position: what it is, its value and weight, and in a taxable account its cost basis, gain and tax lots. In My Portfolio double-click a row to edit its tax lots (Edit Holding…); select rows (Shift / Ctrl for several, Ctrl+A for all) and Ctrl+C copies them under the column titles.");
        Table t = Parts.table(box, SWT.MULTI, Col.l("Ticker", 80), Col.l("Name", 220), Col.l("Sector / class", 160), Col.r("Shares", 90),
                Col.r("Value", 105), Col.r("Weight", 65), Col.r("Cost basis", 105), Col.r("Gain", 100), Col.l("Lots", 220));
        boolean mine = p.id.equals(PortfolioStore.MINE);
        for (Holding h : p.holdings) {
            TableItem it = new TableItem(t, SWT.NONE);
            it.setData(h);
            it.setText(cells(h, p));
            if (h.security() == null && !h.ticker.equals("CASH") || h.unpriced) it.setForeground(0, Ui.RED);
            if (!h.lots.isEmpty()) it.setForeground(7, signed(h.unrealizedGain()));
            it.setForeground(8, Ui.SECONDARY);
            if (port().holdingSelection.contains(h.ticker)) t.select(t.getItemCount() - 1);
        }
        Parts.rows(t, Math.max(Math.min(p.holdings.size(), 40), 6));
        t.addListener(SWT.Selection, e -> {
            port().holdingSelection.clear();
            for (TableItem it : t.getSelection()) port().holdingSelection.add(((Holding) it.getData()).ticker);
        });
        if (mine) {
            t.addListener(SWT.DefaultSelection, e -> {
                TableItem[] sel = t.getSelection();
                if (sel.length == 1) edit((Holding) sel[0].getData());
            });
        }
        QuickHelp.provider(t, (c, pt) -> {
            TableItem it = t.getItem(pt);
            if (it == null) return null;
            Holding h = (Holding) it.getData();
            Security s = h.security();
            List<String> lines = new ArrayList<>();
            if (h.unpriced) lines.add("No price in the security master yet: valued at cost.");
            else if (s != null && s.priceDate != null) lines.add("Priced " + s.priceDate + (s.price > 0 ? " at " + Parts.money2(s.price) : "") + ".");
            for (TaxLot l : h.lots) {
                lines.add(l.acquired + ": " + Fmt.pct(l.share, 0) + " of the position, cost " + Fmt.money(l.costBasis) + (l.note != null ? " — " + l.note : ""));
            }
            if (mine) lines.add(h.ticker.equals("CASH") ? "Double-click to change the cash." : "Double-click to edit its tax lots.");
            return new QuickHelpItem(h.ticker + (s != null ? " — " + s.name : ""), String.join("\n", lines));
        });
        Menu menu = new Menu(t);
        t.setMenu(menu);
        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();
            List<Holding> chosen = new ArrayList<>();
            for (TableItem it : t.getSelection()) chosen.add((Holding) it.getData());
            if (chosen.size() == 1 && mine) {
                Holding h = chosen.get(0);
                item(menu, "Edit Holding…", () -> edit(h));
                item(menu, "Remove " + h.ticker + " from My Portfolio", () -> port().removeFromMine(h.ticker));
                new MenuItem(menu, SWT.SEPARATOR);
            }
            if (!chosen.isEmpty()) {
                item(menu, chosen.size() == 1 ? "Copy Row" : "Copy " + chosen.size() + " Rows", () -> TableCopy.copySelected(t));
                MenuItem col = new MenuItem(menu, SWT.CASCADE);
                col.setText(chosen.size() == 1 ? "Copy Column" : "Copy Column of " + chosen.size() + " Rows");
                Menu sub = new Menu(col);
                col.setMenu(sub);
                for (int c = 0; c < TITLES.length; c++) {
                    final int index = c;
                    item(sub, TITLES[c], () -> {
                        StringBuilder sb = new StringBuilder(TITLES[index]);
                        for (Holding h : chosen) sb.append("\r\n").append(cells(h, p())[index]);
                        Clipboards.copyText(t.getDisplay(), sb.toString());
                    });
                }
            }
            item(menu, "Copy All Rows", () -> TableCopy.copyAll(t));
        });
    }

    private static MenuItem item(Menu menu, String text, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(Ui.escape(text));
        mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    private static String[] cells(Holding h, Portfolio p) {
        Security s = h.security();
        String sector = s == null ? "—" : s.lookThrough != null ? "Fund · " + s.assetClass.title : s.issuer != null ? "Bond · " + s.issuer : s.sector;
        String lots = String.join(", ", h.lots.stream().map(l -> l.acquired + " (" + Fmt.pct(l.share, 0) + ")").toList());
        return new String[] { h.ticker, s != null ? s.name : h.ticker.equals("CASH") ? "Cash" : "—", sector,
                h.quantity == null ? "—" : trimNumber(h.quantity), Fmt.money(h.value), Fmt.pct(p.weight(h)),
                h.lots.isEmpty() ? "—" : Fmt.money(h.costBasis()), h.lots.isEmpty() ? "—" : Fmt.money(h.unrealizedGain()), lots };
    }

    static String trimNumber(double v) {
        String s = String.format("%.4f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private void edit(Holding h) {
        new HoldingDialog(app, h).open();
    }

    private void checks(Composite body, List<Portfolio.Issue> issues) {
        String id = "checks-" + screen.id;
        List<String> counts = new ArrayList<>();
        for (Portfolio.Issue.Level level : Portfolio.Issue.Level.values()) {
            long n = issues.stream().filter(i -> i.level() == level).count();
            if (n > 0) counts.add(n + " " + level.rawValue + (n == 1 ? "" : "s"));
        }
        Composite box = Parts.box(body, "Check before you go on", "Problems in the account's data and concentration that change what the tools can tell you. Click Show / Hide to fold them away.");
        Composite head = Parts.row(box, 2);
        Parts.secondary(head, String.join(" · ", counts)).setLayoutData(Ui.fillH());
        Button toggle = Ui.button(head, app.collapsed(id) ? "Show" : "Hide", () -> {
            app.setCollapsed(id, !app.collapsed(id));
            rebuild();
        });
        QuickHelp.help(toggle, "Check before you go on", app.collapsed(id) ? "Show the checks" : "Hide the checks");
        if (app.collapsed(id)) return;
        for (Portfolio.Issue i : issues) {
            Composite row = Parts.row(box, 2);
            Label mark = Ui.label(row, switch (i.level()) { case ERROR -> "⛔"; case WARNING -> "⚠"; default -> "ℹ"; });
            mark.setForeground(i.level() == Portfolio.Issue.Level.ERROR ? Ui.RED : i.level() == Portfolio.Issue.Level.WARNING ? Ui.ORANGE : Ui.SECONDARY);
            mark.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            Composite text = Parts.column(row, 1);
            Ui.label(text, i.title(), Ui.bold());
            Parts.text(text, i.detail());
            QuickHelp.help(row, i.title(), i.detail());
        }
    }

    private void benchmarkBox(Composite body, Portfolio p) {
        Composite box = Parts.box(body, "Benchmark", "The portfolio this account is measured against. Choose the one that matches your plan's stock / bond mix.");
        Composite row = Parts.row(box, 2);
        Combo pick = new Combo(row, SWT.READ_ONLY);
        List<String> codes = new ArrayList<>();
        if (p.isSRM()) {
            for (SRMBenchmarkInfo b : port().srmBenchmarks) {
                codes.add(b.code());
                pick.add(b.code() + " — " + b.name() + (b.members() != null ? " (" + b.members() + " members)" : ""));
            }
        } else {
            for (Benchmark b : Universe.benchmarks()) {
                codes.add(b.code());
                pick.add(b.code() + " — " + b.name());
            }
        }
        if (!codes.contains(p.benchmark)) {
            codes.add(p.benchmark);
            pick.add(p.benchmark);
        }
        pick.select(codes.indexOf(p.benchmark));
        GridData pg = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        pg.widthHint = 480;
        pick.setLayoutData(pg);
        pick.setEnabled(p.id.equals(PortfolioStore.MINE) || !p.isSRM());
        pick.setToolTipText(p.isSRM() ? "Benchmarks loaded in the security master" : "The benchmark the account is compared with");
        QuickHelp.help(pick, "Benchmark", "The portfolio this account is measured against. Choose the one that matches your plan's stock / bond mix.");
        pick.addListener(SWT.Selection, e -> {
            String code = codes.get(pick.getSelectionIndex());
            Portfolio q = p().copy();
            q.benchmark = code;
            port().update(q);
            if (Universe.benchmark(code).weights().isEmpty()) loadBenchmark(code);
        });
        new Label(row, SWT.NONE);
        Benchmark info = p.benchmarkInfo();
        Parts.text(box, info.code().equals(p.benchmark) && !info.weights().isEmpty() ? info.summary() : "Loading " + p.benchmark + " from the security master…")
                .setForeground(Ui.SECONDARY);
        Map<String, Double> w = p.lookThrough(), b = info.weights();
        Set<String> keys = new HashSet<>(w.keySet());
        keys.addAll(b.keySet());
        double overlap = keys.stream().mapToDouble(k -> Math.min(w.getOrDefault(k, 0.0), b.getOrDefault(k, 0.0))).sum();
        Composite t = Parts.tiles(box, 4);
        Parts.tile(t, "Members", String.valueOf(b.size()), "", null, "Securities in the benchmark.");
        Parts.tile(t, "Overlap", Fmt.pct(overlap), "weight held in common", null, "The sum over securities of the smaller of the account's and the benchmark's weight.");
        Parts.tile(t, "Active share", Fmt.pct(1 - overlap), "", null, Help.term("Active share"));
        long held = w.keySet().stream().filter(b::containsKey).count();
        Parts.tile(t, "Names you hold in it", held + " of " + w.size(), "", null, "How many of the account's securities are benchmark members.");
    }

    private void constituents(Composite body, Portfolio p) {
        Map<String, Double> w = p.lookThrough();
        List<Map.Entry<String, Double>> rows = new ArrayList<>(p.benchmarkInfo().weights().entrySet());
        rows.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Composite box = Parts.box(body, "Largest members", "The benchmark's 25 largest members with the account's weight in each.");
        Table t = Parts.table(box, SWT.MULTI, Col.l("Security", 300), Col.l("Sector", 160), Col.r("Benchmark", 90), Col.r("Account", 90), Col.r("Active", 90));
        int n = 0;
        for (Map.Entry<String, Double> e : rows) {
            if (n++ >= 25) break;
            Security s = Universe.security(e.getKey());
            double mine = w.getOrDefault(e.getKey(), 0.0);
            TableItem it = new TableItem(t, SWT.NONE);
            it.setText(new String[] { (s != null ? s.displayTicker() : e.getKey()) + " — " + (s != null ? s.name : ""), s != null ? s.sector : "",
                    Fmt.pct(e.getValue(), 2), Fmt.pct(mine, 2), Fmt.signedPct(mine - e.getValue()) });
            it.setForeground(4, signed(mine - e.getValue()));
        }
        Parts.rows(t, Math.max(6, Math.min(25, t.getItemCount())));
        if (rows.isEmpty()) Parts.secondary(box, "The benchmark's members aren't loaded yet.");
    }
}
