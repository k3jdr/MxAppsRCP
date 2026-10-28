package com.k3jdr.mxacctmgr.invest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Screen;
import com.k3jdr.mxacctmgr.port.AccountsClient;
import com.k3jdr.mxacctmgr.port.AssetClass;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.Holding;
import com.k3jdr.mxacctmgr.port.OptimizationResult;
import com.k3jdr.mxacctmgr.port.Portfolio;
import com.k3jdr.mxacctmgr.port.Security;
import com.k3jdr.mxacctmgr.port.TaxEstimate;
import com.k3jdr.mxacctmgr.port.Trade;
import com.k3jdr.mxacctmgr.port.Universe;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.ui.Bars;
import com.k3jdr.mxacctmgr.ui.LineChart;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Investments step 5: Trades &amp; Taxes (active; PathViews.swift's TradesView) — turnover, trading cost, realised gains
 * and estimated tax, How far to go, the trades, and taxes by lot — or Rebalance Bands (passive; PassiveViews.swift's
 * BandsView) — ± points or ± 25% of target, back to the target or the band's edge, each class on its band, the stock
 * share through the past year, and the trades with their taxes.
 */
public final class TradesPage extends InvestPage {
    private static final double COST_RATE = 0.0015;
    private double fraction = 1.0;
    private boolean relative, toEdge;
    private List<LineChart.Pt> history;
    private String historyNote;
    private String historyKey;

    public TradesPage(App app) {
        super(app);
    }

    @Override
    protected String stamp() {
        return super.stamp() + "|" + System.identityHashCode(port().activeResult) + "|" + System.identityHashCode(history);
    }

    @Override
    protected void started() {
        if (app.active() && port().activeResult == null && app.engine() == App.Engine.BUILT_IN && port().inputs().n() > 0) port().optimize();
        if (!app.active()) loadHistory();
    }

    @Override
    protected void fill(Composite body) {
        if (app.active()) trades(body); else bands(body);
    }

    // Trades & Taxes

    private void trades(Composite body) {
        OptimizationResult r = port().activeResult;
        if (r == null) {
            Composite box = empty(body, "No Trades Yet", "Optimize the account first.");
            Ui.button(box, "Optimize…", () -> app.go(Screen.MY_IMPROVE));
            return;
        }
        Portfolio p = p();
        List<Trade> trades = new ArrayList<>();
        for (Trade t : r.trades()) trades.add(new Trade(t.asset(), t.from(), t.from() + fraction * t.change()));
        TaxEstimate estimate = TaxEstimate.of(p, trades, port().lotOrder, port().longTermRate, port().shortTermRate);
        double sum = trades.stream().mapToDouble(t -> Math.abs(t.change())).sum();
        double traded = sum * p.total();
        Composite tiles = Parts.tiles(body, 4);
        Parts.tile(tiles, "Turnover", Fmt.pct(sum / 2), Fmt.money(traded / 2) + " bought and sold", null, Help.term("Turnover"));
        Parts.tile(tiles, "Trading cost", Fmt.money(traded * COST_RATE), "at 0.15% per dollar traded", null, "Spread and commission on every buy and sell.");
        double gains = estimate.longTermGain() + estimate.shortTermGain();
        Parts.tile(tiles, "Realised gains", Fmt.money(gains), "long " + Fmt.money(estimate.longTermGain()) + " · short " + Fmt.money(estimate.shortTermGain()),
                signed(gains), "Gains (and losses) realised by the sells, by lot.");
        Parts.tile(tiles, "Estimated tax", Fmt.money(estimate.tax()),
                p.account.isTaxable() ? "at " + Fmt.pct(port().longTermRate, 0) + " / " + Fmt.pct(port().shortTermRate, 0) : "none in this account type",
                estimate.tax() > 0 ? Ui.RED : Ui.SECONDARY, "Net gains taxed at the long- and short-term rates set below; losses offset gains. Illustrative rates, not tax advice.");
        Composite how = Parts.box(body, "How far to go", "Trade part of the way to the optimized weights: less turnover, cost and tax, less of the improvement.");
        Composite g = InvestParts.sliders(how);
        whenReleased(InvestParts.slider(g, "Part of the way", fraction, 0.1, 1, 0.05, v -> Fmt.pct(v, 0) + " of the way",
                "Share of the way from today's weights to the optimized ones", v -> fraction = v));
        Composite box = Parts.box(body, "Trades", "Buys and sells in the securities (funds looked through), largest first. Names held only through a fund are traded by changing the fund or adding the name directly. Select rows (Shift / Ctrl for several) and Ctrl+C copies them under the column titles.");
        Table t = Parts.table(box, SWT.MULTI, Col.l("Side", 55), Col.l("Security", 300), Col.r("Today", 75), Col.r("Target", 75), Col.r("Change", 75),
                Col.r("Amount", 110), Col.l("Held", 110));
        for (Trade x : trades) {
            if (Math.abs(x.change()) <= 1e-4) continue;
            Security s = Universe.security(x.asset());
            String held = p.holdings.stream().anyMatch(h -> h.ticker.equals(x.asset())) ? "directly" : x.from() > 0 ? "through a fund" : "new";
            TableItem it = new TableItem(t, SWT.NONE);
            it.setText(new String[] { x.side(), x.asset() + " — " + (s != null ? s.name : ""), Fmt.pct(x.from(), 2), Fmt.pct(x.to(), 2),
                    Fmt.signedPct(x.change()), Fmt.money(x.change() * p.total()), held });
            it.setForeground(0, x.change() > 0 ? Ui.GREEN : Ui.RED);
            it.setForeground(6, Ui.SECONDARY);
        }
        Parts.rows(t, Math.min(Math.max(t.getItemCount(), 6), 14));
        InvestParts.taxBox(body, port(), estimate, p.account.isTaxable(), p.account.title, this::rebuildLater);
    }

    /** Shows the slider's new value everywhere once it is let go (or moved by keys). */
    private void whenReleased(org.eclipse.swt.widgets.Scale s) {
        s.addListener(SWT.MouseUp, e -> rebuildLater());
        s.addListener(SWT.KeyUp, e -> rebuildLater());
    }

    private boolean pendingRebuild;

    /** Rebuilds once the slider or spinner event is over (never while its control handles it). */
    private void rebuildLater() {
        if (pendingRebuild) return;
        pendingRebuild = true;
        app.shell.getDisplay().timerExec(250, () -> {
            pendingRebuild = false;
            if (!app.shell.isDisposed()) rebuild();
        });
    }

    // Rebalance Bands

    private void bands(Composite body) {
        Portfolio p = p();
        Map<AssetClass, Double> targets = Portfolio.classes(p.benchmarkInfo().weights());
        Map<AssetClass, Double> now = p.classWeights();
        Function<AssetClass, Double> band = c -> relative ? targets.getOrDefault(c, 0.0) * 0.25 : port().bandWidth;
        List<AssetClass> outside = new ArrayList<>();
        for (AssetClass c : AssetClass.values()) {
            if (Math.abs(now.getOrDefault(c, 0.0) - targets.getOrDefault(c, 0.0)) > band.apply(c) + 1e-9) outside.add(c);
        }
        List<Trade> trades = rebalanceTrades(p, targets, band, toEdge);
        TaxEstimate estimate = TaxEstimate.of(p, trades, port().lotOrder, port().longTermRate, port().shortTermRate);
        Composite box = Parts.box(body, "Band", "The band around each target: a fixed number of points, or a quarter of the target (the '5/25' rule: 5 points or 25% of the target).");
        Composite row = Parts.row(box, 6);
        Button points = radio(row, "± points", !relative, () -> { relative = false; rebuild(); });
        Button rel = radio(row, "± 25% of target", relative, () -> { relative = true; rebuild(); });
        for (Button b : new Button[] { points, rel }) QuickHelp.help(b, "Rebalance band", Help.term("Rebalance band"));
        Ui.label(row, "Trade back to");
        Button target = radio(row, "the target", !toEdge, () -> { toEdge = false; rebuild(); });
        Button edge = radio(row, "the band's edge", toEdge, () -> { toEdge = true; rebuild(); });
        for (Button b : new Button[] { target, edge }) b.setToolTipText("All the way to the target, or only back inside the band (less trading)");
        new Label(row, SWT.NONE);
        if (!relative) {
            Composite g = InvestParts.sliders(box);
            whenReleased(InvestParts.slider(g, "Band", port().bandWidth, 0.01, 0.15, 0.01, v -> "± " + Math.round(v * 100) + " points",
                    Help.term("Rebalance band"), v -> port().bandWidth = v));
        }
        Composite two = new Composite(body, SWT.NONE);
        GridLayout gl = new GridLayout(2, true);
        gl.marginWidth = 0;
        gl.horizontalSpacing = 14;
        two.setLayout(gl);
        two.setLayoutData(Ui.fillH());
        Composite tracks = Parts.box(two, outside.isEmpty() ? "Inside every band" : "Outside the band: " + String.join(", ", outside.stream().map(c -> c.title).toList()),
                "Each class's target (line), band (shaded) and today's weight (dot).");
        ((GridData) tracks.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
        for (AssetClass c : AssetClass.values()) bandTrack(tracks, c.title, targets.getOrDefault(c, 0.0), band.apply(c), now.getOrDefault(c, 0.0));
        Composite hist = Parts.box(two, "Stocks over the past year, untouched", "The stock share at each month-end of the past year if today's holdings had been held all along — real closes from the security master — with the band shaded: how the mix drifted to where it is now.");
        ((GridData) hist.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
        double t = targets.getOrDefault(AssetClass.EQUITY, 0.0) * 100, b = band.apply(AssetClass.EQUITY) * 100;
        if (history == null || history.isEmpty()) {
            Parts.secondary(hist, historyNote != null ? historyNote : "Reading month-end prices from the security master…");
        } else {
            new LineChart(hist, history, null, t - b, t + b, "month", "stocks %", -1, 220);
        }
        Composite tb = Parts.box(body, trades.isEmpty() ? "No trades needed" : "Trades to rebalance", "Sells of the overweight class (in proportion to its holdings) and buys of the underweight class (its holdings, or an index fund).");
        if (trades.isEmpty()) {
            Parts.text(tb, "Every class is inside its band: the rule says leave it alone — no cost, no tax.");
        } else {
            Table table = Parts.table(tb, SWT.MULTI, Col.l("Side", 55), Col.l("Holding", 300), Col.r("Today", 80), Col.r("After", 80), Col.r("Amount", 110));
            for (Trade x : trades) {
                Security s = Universe.security(x.asset());
                TableItem it = new TableItem(table, SWT.NONE);
                it.setText(new String[] { x.side(), x.asset() + " — " + (s != null ? s.name : ""), Fmt.pct(x.from()), Fmt.pct(x.to()), Fmt.money(x.change() * p.total()) });
                it.setForeground(0, x.change() > 0 ? Ui.GREEN : Ui.RED);
            }
            Parts.rows(table, Math.min(Math.max(trades.size(), 3), 14));
            InvestParts.taxBox(body, port(), estimate, p.account.isTaxable(), p.account.title, this::rebuildLater);
        }
    }

    private static Button radio(Composite row, String text, boolean on, Runnable chosen) {
        Button b = new Button(row, SWT.RADIO);
        b.setText(text);
        b.setSelection(on);
        b.addListener(SWT.Selection, e -> {
            if (b.getSelection()) b.getDisplay().asyncExec(chosen);
        });
        return b;
    }

    /** One class on a 0–100% track: its band (shaded), target (tick) and today's weight (dot, red outside the band). */
    private static void bandTrack(Composite parent, String label, double target, double band, double now) {
        boolean outside = Math.abs(now - target) > band + 1e-9;
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayout(Ui.grid(3, 0, 8));
        row.setLayoutData(Ui.fillH());
        Label l = Ui.label(row, label);
        l.setLayoutData(new GridData(60, SWT.DEFAULT));
        Canvas c = new Canvas(row, SWT.DOUBLE_BUFFERED);
        GridData cg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        cg.heightHint = 24;
        cg.widthHint = 100;
        c.setLayoutData(cg);
        c.addListener(SWT.Paint, e -> {
            GC gc = e.gc;
            gc.setAntialias(SWT.ON);
            int w = c.getClientArea().width - 14, x0 = 7;
            gc.setBackground(Ui.BORDER);
            gc.fillRoundRectangle(x0, 9, w, 6, 6, 6);
            gc.setBackground(new org.eclipse.swt.graphics.Color(200, 235, 205));
            int lo = x0 + (int) (w * Math.max(0, target - band)), hi = x0 + (int) (w * Math.min(1, target + band));
            gc.fillRectangle(lo, 3, Math.max(1, hi - lo), 18);
            gc.setBackground(Ui.SECONDARY);
            gc.fillRectangle(x0 + (int) (w * target) - 1, 1, 2, 22);
            gc.setBackground(outside ? Bars.RED : Bars.GREEN);
            gc.fillOval(x0 + (int) (w * now) - 7, 5, 14, 14);
        });
        Label v = Ui.label(row, Fmt.pct(now) + " vs " + Fmt.pct(target) + " ± " + Fmt.pct(band));
        v.setForeground(outside ? Ui.RED : Ui.SECONDARY);
        v.setLayoutData(new GridData(170, SWT.DEFAULT));
        String help = label + ": " + Fmt.pct(now) + " now, target " + Fmt.pct(target) + ", band " + Fmt.pct(Math.max(0, target - band)) + " to " + Fmt.pct(target + band);
        c.setToolTipText(help);
        QuickHelp.help(c, label, help);
    }

    /** Today's quantities at each month-end close of the past year (cash at its value), as the stock share. */
    private void loadHistory() {
        Portfolio p = p().copy();
        String key = p.id + p.holdings.size() + (long) p.total();
        if (key.equals(historyKey)) return;
        historyKey = key;
        List<Holding> held = p.holdings.stream().filter(h -> !h.ticker.equals("CASH") && h.security() != null).toList();
        List<String> tickers = held.stream().map(h -> h.security().displayTicker()).toList();
        if (tickers.isEmpty()) {
            history = List.of();
            historyNote = "No holdings to price.";
            return;
        }
        double cash = p.holdings.stream().filter(h -> h.ticker.equals("CASH")).mapToDouble(h -> h.value).sum();
        com.k3jdr.mx.core.ui.Background.run(app.shell, () -> AccountsClient.monthlyPrices(tickers, 12), prices -> {
            Set<String> months = new TreeSet<>();
            prices.values().forEach(list -> list.forEach(m -> months.add(m.date().substring(0, 7))));
            List<LineChart.Pt> out = new ArrayList<>();
            for (String m : months) {
                double equity = 0, total = cash;
                for (Holding h : held) {
                    Security s = h.security();
                    var closes = prices.get(s.displayTicker());
                    if (closes == null) continue;
                    var close = closes.stream().filter(c -> c.date().startsWith(m)).findFirst();
                    if (close.isEmpty()) continue;
                    double q = h.quantity != null ? h.quantity : s.price > 0 ? h.value / s.price : 0;
                    total += q * close.get().close();
                    if (s.assetClass == AssetClass.EQUITY) equity += q * close.get().close();
                }
                if (total > 0) out.add(new LineChart.Pt(m.substring(2), equity / total * 100));
            }
            history = out;
            historyNote = out.isEmpty() ? "The security master has no month-end prices for these holdings." : null;
            rebuild();
        }, e -> {
            history = List.of();
            historyNote = e.getMessage();
            rebuild();
        });
    }

    /** Holding-level trades back to the target mix (or the band's edge) for every class outside its band. */
    static List<Trade> rebalanceTrades(Portfolio p, Map<AssetClass, Double> targets, Function<AssetClass, Double> band, boolean toEdge) {
        Map<AssetClass, Double> now = p.classWeights();
        Map<AssetClass, Double> goal = new EnumMap<>(AssetClass.class);
        for (AssetClass c : AssetClass.values()) {
            double w = now.getOrDefault(c, 0.0), t = targets.getOrDefault(c, 0.0), b = band.apply(c);
            goal.put(c, Math.abs(w - t) > b + 1e-9 ? (toEdge ? (w > t ? t + b : t - b) : t) : w);
        }
        boolean any = false;
        for (AssetClass c : AssetClass.values()) if (Math.abs(goal.get(c) - now.getOrDefault(c, 0.0)) > 1e-9) any = true;
        if (!any) return List.of();
        // Classes inside their band absorb the difference in proportion to their size.
        double moved = 0;
        for (AssetClass c : AssetClass.values()) moved += goal.get(c) - now.getOrDefault(c, 0.0);
        List<AssetClass> inside = new ArrayList<>();
        for (AssetClass c : AssetClass.values()) if (Math.abs(goal.get(c) - now.getOrDefault(c, 0.0)) < 1e-9) inside.add(c);
        double insideTotal = inside.stream().mapToDouble(c -> now.getOrDefault(c, 0.0)).sum();
        if (Math.abs(moved) > 1e-9) {
            if (insideTotal > 0) {
                for (AssetClass c : inside) goal.put(c, goal.get(c) - moved * now.getOrDefault(c, 0.0) / insideTotal);
            } else {
                List<AssetClass> changed = new ArrayList<>();
                for (AssetClass c : AssetClass.values()) if (!inside.contains(c)) changed.add(c);
                for (AssetClass c : changed) goal.put(c, goal.get(c) - moved / changed.size());
            }
        }
        List<Trade> trades = new ArrayList<>();
        for (AssetClass c : AssetClass.values()) {
            double have = now.getOrDefault(c, 0.0), want = goal.get(c);
            if (Math.abs(want - have) <= 1e-6) continue;
            List<Holding> members = p.holdings.stream().filter(h -> classOf(h) == c).toList();
            if (members.isEmpty()) {
                String fund = c == AssetClass.EQUITY ? "VTI" : c == AssetClass.BOND ? "BND" : "CASH";
                trades.add(new Trade(fund, 0, want));
            } else {
                double total = members.stream().mapToDouble(p::weight).sum();
                for (Holding h : members) {
                    double w = p.weight(h);
                    trades.add(new Trade(h.ticker, w, w * want / Math.max(total, 1e-9)));
                }
            }
        }
        return trades.stream().filter(t -> Math.abs(t.change()) > 1e-5).sorted((a, b) -> Double.compare(a.change(), b.change())).toList();
    }

    private static AssetClass classOf(Holding h) {
        if (h.ticker.equals("CASH")) return AssetClass.CASH;
        Security s = h.security();
        if (s == null) return null;
        if (s.lookThrough != null && !s.lookThrough.isEmpty()) {
            return Portfolio.classes(s.lookThrough).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(s.assetClass);
        }
        return s.assetClass;
    }
}
