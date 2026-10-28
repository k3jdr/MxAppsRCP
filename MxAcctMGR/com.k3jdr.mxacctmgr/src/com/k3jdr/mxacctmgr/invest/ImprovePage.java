package com.k3jdr.mxacctmgr.invest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Screen;
import com.k3jdr.mxacctmgr.port.ActiveSettings;
import com.k3jdr.mxacctmgr.port.AssetClass;
import com.k3jdr.mxacctmgr.port.Engine;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.OptimizationInputs;
import com.k3jdr.mxacctmgr.port.OptimizationResult;
import com.k3jdr.mxacctmgr.port.Portfolio;
import com.k3jdr.mxacctmgr.port.Security;
import com.k3jdr.mxacctmgr.port.TaxEstimate;
import com.k3jdr.mxacctmgr.port.Trade;
import com.k3jdr.mxacctmgr.port.TrackingSettings;
import com.k3jdr.mxacctmgr.port.Universe;
import com.k3jdr.mxacctmgr.ui.Bars;
import com.k3jdr.mxacctmgr.ui.LineChart;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Investments step 4: Optimize (active; PathViews.swift's OptimizeView) — the limits, the decision, tracking error,
 * information ratio, forecast active return, active share, turnover, binding limits, the largest changes and sector
 * active weights — or Build the Tracker (passive; PassiveViews.swift's TrackView) — names, selection and budget, the
 * tracking-error-vs-names curve, sector gaps, three ways to be passive and the trades. Built-in (Java) or the Layer 3
 * services (portfolio-java, portfolio-cpp).
 */
public final class ImprovePage extends InvestPage {
    public ImprovePage(App app) {
        super(app);
    }

    @Override
    protected String stamp() {
        return super.stamp() + "|" + System.identityHashCode(port().activeResult) + "|" + System.identityHashCode(port().trackResult) + "|" + app.busy();
    }

    @Override
    protected void started() {
        // The built-in engine runs at once on opening, as on the Mac.
        if (app.engine() == App.Engine.BUILT_IN && port().inputs().n() > 0) {
            if (app.active() && port().activeResult == null) port().optimize();
            if (!app.active() && port().trackResult == null) port().buildTracker();
        }
    }

    @Override
    protected void fill(Composite body) {
        if (app.active()) optimize(body); else track(body);
    }

    private void engineRow(Composite box, String label, String detail, String shortcut, Runnable run) {
        Composite row = Parts.row(box, 3);
        Button b = Ui.button(row, label, run);
        b.setEnabled(!app.busy() && port().openPortfolio() != null || !"mine".equals(port().selectedID()));
        QuickHelp.help(b, label, detail, shortcut);
        Parts.secondary(row, "Engine: " + app.engine().title + (app.busy() ? "   · working…" : ""));
        new org.eclipse.swt.widgets.Label(row, SWT.NONE);
    }

    // Optimize (active)

    private void optimize(Composite body) {
        ActiveSettings s = port().activeSettings;
        Composite box = Parts.box(body, "Limits", "The optimizer's risk budget and limits. Change one and Optimize again to see what it does.");
        Composite g = InvestParts.sliders(box);
        InvestParts.optional(g, "Tracking-error target", "Tracking error", s.trackingErrorTarget, 0.03, 0.005, 0.08, 0.0025,
                "Off: the risk aversion below sets the balance instead.", v -> {
                    s.trackingErrorTarget = v;
                    port().activeResult = null;
                });
        InvestParts.slider(g, "Risk aversion", s.riskAversion, 1, 100, 1, v -> Fmt.fixed(v, 0),
                "Higher: closer to the benchmark; lower: bigger bets (used when the tracking-error target is off)", v -> {
                    s.riskAversion = v;
                    port().activeResult = null;
                });
        InvestParts.slider(g, "Largest position", s.maxWeight, 0.03, 0.40, 0.01, Fmt::pct,
                "No security above this weight (unless the benchmark itself holds more, less the active-weight limit).", v -> {
                    s.maxWeight = v;
                    port().activeResult = null;
                });
        InvestParts.slider(g, "Largest active weight", s.maxActiveWeight, 0.005, 0.15, 0.005, Fmt::pct,
                "No security more than this far from its benchmark weight, over or under.", v -> {
                    s.maxActiveWeight = v;
                    port().activeResult = null;
                });
        InvestParts.optional(g, "Sector band", "Active weight", s.sectorBand, 0.05, 0.01, 0.20, 0.01,
                "Keeps each sector within ± this of the benchmark's weight.", v -> {
                    s.sectorBand = v;
                    port().activeResult = null;
                });
        InvestParts.optional(g, "Turnover limit", "Turnover", s.maxTurnover, 0.25, 0.05, 1, 0.05,
                "Above it the decision is to rebalance part of the way.", v -> {
                    s.maxTurnover = v;
                    port().activeResult = null;
                });
        engineRow(box, "Optimize", "Runs the active optimizer on this account with these limits — built-in, or portfolio-java when Engine is Layer 3 services.",
                "Ctrl+R", port()::optimize);
        OptimizationResult r = port().activeResult;
        if (r == null) {
            empty(body, "Not Optimized Yet", "Set the limits above and Optimize (Ctrl+R).");
            return;
        }
        InvestParts.summary(body, r, true);
        Composite two = twoColumns(body);
        Composite changes = Parts.box(two, "Largest changes: today vs optimized", "The fifteen positions that change most, with today's look-through weight, the optimized weight and the benchmark's.");
        fillHeight(changes);
        OptimizationInputs in = port().inputs();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < in.n(); i++) idx.add(i);
        idx.sort(Comparator.comparingDouble(i -> -Math.abs(r.weights().getOrDefault(in.assets.get(i), 0.0) - in.current[i])));
        List<Bars.Row> rows = new ArrayList<>();
        for (int i : idx.subList(0, Math.min(15, idx.size()))) {
            rows.add(new Bars.Row(in.assets.get(i), in.current[i] * 100, r.weights().getOrDefault(in.assets.get(i), 0.0) * 100, in.benchmark[i] * 100));
        }
        new Bars(changes, List.of(new Bars.Series("Today", Bars.GRAY), new Bars.Series("Optimized", Bars.BLUE), new Bars.Series("Benchmark", Bars.PURPLE)), rows, 70, "%");
        Composite sectors = Parts.box(two, "Sector active weights", "Optimized weight minus benchmark weight for each sector. The sector band keeps these within ± the band.");
        fillHeight(sectors);
        sectorBars(sectors, r.sectorActive());
        Composite row = Parts.row(body, 2);
        Parts.spacer(row);
        Ui.button(row, "Review the Trades and Taxes  ›", () -> app.go(Screen.MY_TRADES));
    }

    static void sectorBars(Composite parent, Map<String, Double> active) {
        List<Map.Entry<String, Double>> e = new ArrayList<>(active.entrySet());
        e.removeIf(x -> Math.abs(x.getValue()) < 1e-6);
        e.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Bars.Row> rows = new ArrayList<>();
        for (Map.Entry<String, Double> x : e) {
            rows.add(new Bars.Row(x.getKey(), new double[] { x.getValue() * 100 }, new org.eclipse.swt.graphics.Color[] { x.getValue() >= 0 ? Bars.GREEN : Bars.RED }));
        }
        new Bars(parent, List.of(new Bars.Series("Active", Bars.BLUE)), rows, 130, "%");
    }

    private static Composite twoColumns(Composite body) {
        Composite two = new Composite(body, SWT.NONE);
        GridLayout gl = new GridLayout(2, true);
        gl.marginWidth = 0;
        gl.horizontalSpacing = 14;
        two.setLayout(gl);
        two.setLayoutData(Ui.fillH());
        return two;
    }

    private static void fillHeight(Composite boxContent) {
        ((GridData) boxContent.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
    }

    // Build the Tracker (passive)

    private void track(Composite body) {
        TrackingSettings s = port().trackingSettings;
        OptimizationInputs in = port().inputs();
        int members = 0;
        for (double b : in.benchmark) if (b > 0) members++;
        int all = Math.max(members, 4);
        Composite box = Parts.box(body, "Tracker", "How many names, chosen how, and the tracking-error budget.");
        Composite g = InvestParts.sliders(box);
        InvestParts.slider(g, "Names to hold", Math.min(s.maxNames, all), 3, all, 1,
                v -> (int) v >= all ? "all " + all + " (full replication)" : (int) v + " of " + all,
                "At most this many names; " + all + " is every name in the index", v -> {
                    s.maxNames = (int) Math.round(v);
                    port().trackResult = null;
                });
        Ui.label(g, "Choose names");
        Composite sel = Parts.row(g, 2);
        Button strat = new Button(sel, SWT.RADIO);
        strat.setText("Stratified by sector");
        strat.setSelection(s.selection == TrackingSettings.Selection.STRATIFIED);
        Button largest = new Button(sel, SWT.RADIO);
        largest.setText("Largest weights");
        largest.setSelection(s.selection != TrackingSettings.Selection.STRATIFIED);
        strat.addListener(SWT.Selection, e -> { if (strat.getSelection()) { s.selection = TrackingSettings.Selection.STRATIFIED; port().trackResult = null; } });
        largest.addListener(SWT.Selection, e -> { if (largest.getSelection()) { s.selection = TrackingSettings.Selection.LARGEST; port().trackResult = null; } });
        for (Button b : new Button[] { strat, largest }) {
            QuickHelp.help(b, "Choose Names", "Stratified: names in every sector in proportion to its weight, largest first in each. Largest: the biggest names in the index, whatever their sector.");
        }
        new org.eclipse.swt.widgets.Label(g, SWT.NONE);
        InvestParts.slider(g, "Tracking-error budget", s.trackingErrorBudget, 0.0025, 0.03, 0.0025, v -> Fmt.pct(v, 2),
                com.k3jdr.mxacctmgr.text.Help.term("Tracking error"), v -> {
                    s.trackingErrorBudget = v;
                    port().trackResult = null;
                });
        engineRow(box, "Build Tracker", "Finds the weights with the smallest tracking error using at most this many names — built-in, or portfolio-cpp when Engine is Layer 3 services.",
                "Ctrl+R", port()::buildTracker);
        OptimizationResult r = port().trackResult;
        if (r == null) {
            empty(body, "No Tracker Yet", "Build the tracker (Ctrl+R).");
            return;
        }
        InvestParts.summary(body, r, false);
        Composite two = twoColumns(body);
        Composite curve = Parts.box(two, "Tracking error vs names held", "Tracking error of the best tracker with each number of names (the selected method); the dashed line is your budget.");
        fillHeight(curve);
        List<LineChart.Pt> pts = new ArrayList<>();
        int hi = -1;
        for (Engine.CurvePoint c : port().trackCurve) {
            if (c.names() == s.maxNames) hi = pts.size();
            pts.add(new LineChart.Pt(String.valueOf(c.names()), c.te() * 100));
        }
        if (pts.isEmpty()) Parts.secondary(curve, "The curve is computed with the built-in engine after the tracker is built.");
        else new LineChart(curve, pts, s.trackingErrorBudget * 100, null, null, "names held", "tracking error %", hi, 260);
        Composite gaps = Parts.box(two, "Sector gaps", "Tracker weight minus index weight by sector. Stratified selection keeps every sector represented.");
        fillHeight(gaps);
        sectorBars(gaps, r.sectorActive());
        options(body, r);
        Composite trades = Parts.box(body, "Trades to the tracker", "From today's look-through weights to the tracker's, largest first.");
        Table t = Parts.table(trades, SWT.MULTI, Col.l("Side", 60), Col.l("Security", 300), Col.r("Today", 80), Col.r("Tracker", 80), Col.r("Amount", 110));
        double total = p().total();
        for (Trade x : r.trades().subList(0, Math.min(14, r.trades().size()))) {
            Security sec = Universe.security(x.asset());
            TableItem it = new TableItem(t, SWT.NONE);
            it.setText(new String[] { x.side(), x.asset() + " — " + (sec != null ? sec.name : ""), Fmt.pct(x.from(), 2), Fmt.pct(x.to(), 2), Fmt.money(x.change() * total) });
            it.setForeground(0, x.change() > 0 ? Ui.GREEN : Ui.RED);
        }
        Parts.rows(t, Math.max(3, t.getItemCount()));
    }

    private void options(Composite body, OptimizationResult r) {
        Portfolio p = p();
        TaxEstimate trackerTax = TaxEstimate.of(p, r.trades(), port().lotOrder, port().longTermRate, port().shortTermRate);
        TaxEstimate fundTax = TaxEstimate.sellAll(p, port().lotOrder, port().longTermRate, port().shortTermRate);
        Map<AssetClass, Double> parts = Portfolio.classes(p.benchmarkInfo().weights());
        double stockShare = parts.getOrDefault(AssetClass.EQUITY, 1.0);
        boolean blended = stockShare < 0.999;
        String fund = blended ? "VTI + BND at " + p.benchmark + "'s mix" : "VTI";
        Security vti = Universe.security("VTI"), bnd = Universe.security("BND");
        Double fundFee = blended ? (vti != null && bnd != null ? vti.fee * stockShare + bnd.fee * (1 - stockShare) : null) : (vti != null ? vti.fee : null);
        Composite box = Parts.box(body, "Three ways to be passive", "Today's account, the tracker, and one index fund side by side: tracking error, yearly fee (expense ratios from the security master's fund profiles) and the tax of switching.");
        Table t = Parts.table(box, SWT.MULTI, Col.l("", 230), Col.r("Tracking error", 110), Col.r("Yearly fee", 90), Col.r("Trades", 60), Col.r("Tax to switch", 110), Col.l("Notes", 420));
        new TableItem(t, SWT.NONE).setText(new String[] { "Today's account", Fmt.pct(r.currentTrackingError() == null ? 0 : r.currentTrackingError(), 2),
                Fmt.pct(p.weightedFee(), 2), "0", "—", "Keeps drifting unless rebalanced." });
        new TableItem(t, SWT.NONE).setText(new String[] { r.names() + "-name tracker", Fmt.pct(r.trackingError(), 2), "0.00%", String.valueOf(r.trades().size()),
                p.account.isTaxable() ? Fmt.money(trackerTax.tax()) : "none", "Direct indexing: own the names, harvest losses name by name; needs upkeep." });
        new TableItem(t, SWT.NONE).setText(new String[] { "One index fund (" + fund + ")", "≈ 0.05%", fundFee == null ? "—" : Fmt.pct(fundFee, 2),
                String.valueOf(p.holdings.size() + 1), p.account.isTaxable() ? Fmt.money(fundTax.tax()) : "none",
                "Simplest; the fund rebalances itself. Switching in a taxable account can be done gradually." });
        Parts.rows(t, 3);
    }
}
