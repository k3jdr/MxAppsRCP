package com.k3jdr.mxacctmgr.invest;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.LotOrder;
import com.k3jdr.mxacctmgr.port.OptimizationResult;
import com.k3jdr.mxacctmgr.port.TaxEstimate;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/** Pieces the Investments steps share (PathViews.swift): a labelled slider, the result summary, the taxes by lot. */
final class InvestParts {
    private InvestParts() {}

    /**
     * A slider row in a 3-column grid: title, slider from {@code min} to {@code max} in {@code step}s, and its value as
     * {@code format} shows it; {@code changed} gets the new value when the slider is released or moved by keys.
     */
    static Scale slider(Composite grid, String title, double value, double min, double max, double step, DoubleFunction<String> format,
                        String help, DoubleConsumer changed) {
        Label l = Ui.label(grid, title);
        Scale s = new Scale(grid, SWT.HORIZONTAL);
        int steps = (int) Math.round((max - min) / step);
        s.setMinimum(0);
        s.setMaximum(steps);
        s.setPageIncrement(Math.max(1, steps / 10));
        s.setSelection((int) Math.round((value - min) / step));
        GridData g = new GridData(280, SWT.DEFAULT);
        s.setLayoutData(g);
        Label v = Ui.label(grid, format.apply(value));
        v.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        s.addListener(SWT.Selection, e -> {
            double x = min + s.getSelection() * step;
            v.setText(Ui.escape(format.apply(x)));
            changed.accept(x);
        });
        if (help != null) {
            s.setToolTipText(help);
            QuickHelp.help(s, title, help);
            QuickHelp.help(l, title, help);
        }
        return s;
    }

    /** An on/off limit: its check box, slider and value ("off" when unchecked). */
    static void optional(Composite grid, String title, String term, Double value, double def, double min, double max, double step,
                         String explain, java.util.function.Consumer<Double> changed) {
        Button on = Ui.check(grid, title, value != null, b -> {});
        Scale s = new Scale(grid, SWT.HORIZONTAL);
        int steps = (int) Math.round((max - min) / step);
        s.setMaximum(steps);
        s.setPageIncrement(Math.max(1, steps / 10));
        s.setSelection((int) Math.round(((value == null ? def : value) - min) / step));
        s.setLayoutData(new GridData(280, SWT.DEFAULT));
        s.setEnabled(value != null);
        Label v = Ui.label(grid, value == null ? "off" : Fmt.pct(value, 2));
        v.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Runnable apply = () -> {
            Double x = on.getSelection() ? min + s.getSelection() * step : null;
            s.setEnabled(x != null);
            v.setText(x == null ? "off" : Fmt.pct(x, 2));
            changed.accept(x);
        };
        on.addListener(SWT.Selection, e -> apply.run());
        s.addListener(SWT.Selection, e -> apply.run());
        String help = (term == null ? "" : Help.term(term) + " ") + explain;
        for (var c : new org.eclipse.swt.widgets.Control[] { on, s, v }) QuickHelp.help(c, title, help);
        on.setToolTipText(explain);
    }

    /** A 3-column grid for slider rows. */
    static Composite sliders(Composite parent) {
        Composite g = new Composite(parent, SWT.NONE);
        g.setLayout(Ui.grid(3, 0, 6));
        ((org.eclipse.swt.layout.GridLayout) g.getLayout()).horizontalSpacing = 14;
        g.setLayoutData(Ui.fillH());
        return g;
    }

    /** The decision and headline numbers of an optimization or tracker (ResultSummary). */
    static void summary(Composite parent, OptimizationResult r, boolean active) {
        String rec = r.decision().recommendation();
        Color c = switch (rec) {
            case "hold", "rebalance" -> Ui.GREEN;
            case "rebalance_partially" -> Ui.ACCENT;
            default -> Ui.ORANGE;
        };
        String icon = switch (rec) {
            case "hold" -> "⏸";
            case "rebalance" -> "⟳";
            case "rebalance_partially" -> "◐";
            default -> "⚠";
        };
        Composite d = new Composite(parent, SWT.BORDER);
        d.setLayout(Ui.grid(2, 10, 4));
        d.setLayoutData(Ui.fillH());
        Label i = Ui.label(d, icon, Ui.font(8, SWT.BOLD));
        i.setForeground(c);
        i.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 1, 3));
        Ui.label(d, r.decision().title(), Ui.subheading()).setLayoutData(Ui.fillH());
        Parts.text(d, r.decision().reason());
        Parts.secondary(d, r.engine() + " · " + r.status() + (r.method() != null ? " · " + r.method().replace('_', ' ') : "") + " · "
                + Math.round(r.milliseconds()) + " ms");
        QuickHelp.help(d, "Decision: " + rec.replace('_', ' '),
                "The same decision rules the Layer 3 services hand to MxJobsMGR agents: hold when turnover is under 2%, rebalance part of the way above the turnover limit, relax a limit that can't be met, otherwise rebalance. A rule of thumb for learning, not advice.");
        Composite t = Parts.tiles(parent, active ? 6 : 5);
        Parts.tile(t, "Tracking error", Fmt.pct(r.trackingError(), 2), r.currentTrackingError() == null ? "" : "today " + Fmt.pct(r.currentTrackingError(), 2),
                null, Help.term("Tracking error"));
        if (active) {
            Parts.tile(t, "Information ratio", Fmt.fixed(r.informationRatio() == null ? 0 : r.informationRatio(), 2), "forecast", null, Help.term("Information ratio"));
            Parts.tile(t, "Forecast active return", Fmt.signedPct(r.activeAlpha() == null ? 0 : r.activeAlpha()), "a year, if the forecasts are right", null,
                    "Alpha forecasts times active weights. Real results will differ: forecasts are uncertain.");
        } else {
            Parts.tile(t, "Index weight covered", Fmt.pct(r.benchmarkCovered() == null ? 1 : r.benchmarkCovered()), "by the names held", null,
                    "Share of the index's weight in the names the tracker holds.");
        }
        Parts.tile(t, "Active share", Fmt.pct(r.activeShare()), "", null, Help.term("Active share"));
        Parts.tile(t, "Turnover", Fmt.pct(r.turnover()), "to get there", null, Help.term("Turnover"));
        Parts.tile(t, "Names", String.valueOf(r.names()), "", null, "Securities held after the change.");
        if (!r.bindingConstraints().isEmpty()) {
            Composite row = Parts.row(parent, r.bindingConstraints().size() + 2);
            Ui.label(row, "Limits that bind:", Ui.bold());
            for (String b : r.bindingConstraints()) {
                Label l = Ui.label(row, " " + b.replace('_', ' ') + " ");
                l.setBackground(new Color(255, 228, 196));
                l.setToolTipText(bindingHelp(b));
                QuickHelp.help(l, "Binding: " + b.replace('_', ' '), bindingHelp(b));
            }
            new Label(row, SWT.NONE);
        }
    }

    static String bindingHelp(String c) {
        return switch (c) {
            case "max_weight" -> "Some position is at the largest weight allowed: the optimizer would like more of it.";
            case "max_active_weight" -> "Some position is as far from its benchmark weight as allowed: the forecasts want a bigger bet.";
            case "max_sector_active" -> "A sector is at the edge of its band: the bets are being held back by the sector limit.";
            case "tracking_error_target" -> "The portfolio uses the whole risk budget.";
            case "max_turnover" -> "Getting all the way there trades more than the turnover limit.";
            default -> c;
        };
    }

    /** Taxes by lot (TaxBox): the lot order and illustrative rates, then each lot sold with its gain and term. */
    static void taxBox(Composite parent, PortfolioStore store, TaxEstimate estimate, boolean taxable, String accountTitle, Runnable changed) {
        Composite box = Parts.box(parent, "Taxes by lot", "Which tax lots the sells come from, the gain on each and whether it is short- or long-term. Choose the lot order and the illustrative tax rates.");
        Composite row = Parts.row(box, 6);
        Ui.label(row, "Sell lots");
        Combo order = new Combo(row, SWT.READ_ONLY);
        for (LotOrder o : LotOrder.values()) order.add(o.title);
        order.select(store.lotOrder.ordinal());
        order.setToolTipText("Which lots are sold first: the oldest, or those that cost most (usually the smallest gain)");
        QuickHelp.help(order, "Tax lot", Help.term("Tax lot"));
        order.addListener(SWT.Selection, e -> {
            store.lotOrder = LotOrder.values()[order.getSelectionIndex()];
            changed.run();
        });
        Ui.label(row, "Long-term rate %");
        Spinner lt = new Spinner(row, SWT.BORDER);
        lt.setValues((int) Math.round(store.longTermRate * 100), 0, 40, 0, 1, 5);
        lt.addListener(SWT.Selection, e -> {
            store.longTermRate = lt.getSelection() / 100.0;
            changed.run();
        });
        Ui.label(row, "Short-term rate %");
        Spinner st = new Spinner(row, SWT.BORDER);
        st.setValues((int) Math.round(store.shortTermRate * 100), 0, 50, 0, 1, 5);
        st.addListener(SWT.Selection, e -> {
            store.shortTermRate = st.getSelection() / 100.0;
            changed.run();
        });
        if (!taxable) Parts.text(box, "This is a " + accountTitle.toLowerCase() + " account: selling realises no tax today, so the lots don't matter here.");
        if (estimate.lines().isEmpty()) {
            Parts.secondary(box, "No sells from directly held positions.");
        } else {
            Table t = Parts.table(box, SWT.MULTI, Col.l("Security", 110), Col.l("Lot bought", 110), Col.r("Sold", 110), Col.r("Gain", 110), Col.l("Term", 100));
            for (TaxEstimate.Line l : estimate.lines()) {
                TableItem it = new TableItem(t, SWT.NONE);
                it.setText(new String[] { l.ticker(), l.acquired(), Fmt.money(l.sold()), Fmt.money(l.gain()), l.shortTerm() ? "short-term" : "long-term" });
                it.setForeground(3, InvestPage.signed(l.gain()));
                if (l.shortTerm()) it.setForeground(4, Ui.ORANGE);
            }
            Parts.rows(t, Math.min(12, Math.max(3, estimate.lines().size())));
        }
        if (estimate.lines().stream().anyMatch(l -> l.gain() < 0)) {
            Parts.text(box, "🍃 A sale at a loss can offset gains (tax-loss harvesting). Buying the same security back within 30 days before or after makes it a wash sale and the loss is disallowed — a similar, not identical, fund keeps the exposure.");
        }
        List<String> via = estimate.viaFunds();
        if (!via.isEmpty()) {
            Parts.caption(box, "Held only through funds, not estimated: " + String.join(", ", via.subList(0, Math.min(12, via.size()))) + (via.size() > 12 ? "…" : "")
                    + ". Reducing them means selling part of a fund (its own lots) or simply not adding to them.");
        }
        Parts.caption(box, "Illustrative tax arithmetic, not tax advice: real rules (state taxes, net investment income tax, carry-forwards) differ.");
    }
}
