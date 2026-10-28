package com.k3jdr.mxacctmgr.pages;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Screen;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.OptimizationInputs;
import com.k3jdr.mxacctmgr.port.Portfolio;
import com.k3jdr.mxacctmgr.port.SeededRandom;
import com.k3jdr.mxacctmgr.port.Security;
import com.k3jdr.mxacctmgr.port.ServiceClient;
import com.k3jdr.mxacctmgr.port.ServiceException;
import com.k3jdr.mxacctmgr.port.Universe;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.text.LearnTexts;
import com.k3jdr.mxacctmgr.ui.Bars;
import com.k3jdr.mxacctmgr.ui.LineChart;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;

/**
 * The lesson-only pages (LearnViews.swift): Welcome, Choose Your Path, Why Optimize? (two demos from real prices
 * through the analytics service), Why Attribution? (a two-sector Brinson-Fachler demo with sliders) and Active vs
 * Passive (what fees compound to, and a comparison table).
 */
public final class LearnPage extends Page {
    private Composite body;
    // Why Optimize?
    private List<double[]> diversification;
    private List<double[]> mixes;
    private String problem;
    private int names = LearnTexts.OPTIMIZE_DEFAULT_NAMES;
    private double stockShare = LearnTexts.OPTIMIZE_DEFAULT_STOCK_SHARE;
    // Why Attribution?
    private double techWeight = LearnTexts.ATTRIBUTION_SLIDERS.get(0).initial();
    private double techReturn = LearnTexts.ATTRIBUTION_SLIDERS.get(1).initial();
    private double restReturn = LearnTexts.ATTRIBUTION_SLIDERS.get(2).initial();
    // Active vs Passive
    private double activeFee = LearnTexts.FEES_ACTIVE_FEE.initial();
    private double alpha = LearnTexts.FEES_ALPHA.initial();

    public LearnPage(App app) {
        super(app);
    }

    @Override
    protected void build(Composite body) {
        this.body = body;
        fill();
        if (screen == Screen.WHY_OPTIMIZE) load();
    }

    private void fill() {
        switch (screen) {
            case WELCOME -> welcome();
            case CHOOSE_PATH -> choosePath();
            case WHY_OPTIMIZE -> whyOptimize();
            case WHY_ATTRIBUTION -> whyAttribution();
            default -> activeVsPassive();
        }
    }

    private void again() {
        Page.clear(body);
        fill();
        relayout();
    }

    /** Rebuilds once a slider is let go (or moved by keys). */
    private void whenReleased(Scale s) {
        s.addListener(SWT.MouseUp, e -> s.getDisplay().asyncExec(this::again));
        s.addListener(SWT.KeyUp, e -> s.getDisplay().asyncExec(this::again));
    }

    private Scale slider(Composite grid, String title, String help, double value, double min, double max, double step, java.util.function.DoubleConsumer set,
                         java.util.function.DoubleFunction<String> label) {
        Label l = Ui.label(grid, title);
        Scale s = new Scale(grid, SWT.HORIZONTAL);
        int steps = (int) Math.round((max - min) / step);
        s.setMaximum(steps);
        s.setPageIncrement(Math.max(1, steps / 10));
        s.setSelection((int) Math.round((value - min) / step));
        s.setLayoutData(new GridData(300, SWT.DEFAULT));
        Label v = Ui.label(grid, label.apply(value));
        v.setLayoutData(Ui.fillH());
        s.addListener(SWT.Selection, e -> {
            double x = min + s.getSelection() * step;
            set.accept(x);
            v.setText(Ui.escape(label.apply(x)));
        });
        s.setToolTipText(help);
        QuickHelp.help(s, title, help);
        QuickHelp.help(l, title, help);
        whenReleased(s);
        return s;
    }

    private static Composite grid(Composite parent, int columns) {
        Composite g = new Composite(parent, SWT.NONE);
        GridLayout gl = new GridLayout(columns, false);
        gl.marginWidth = 0;
        gl.horizontalSpacing = 12;
        g.setLayout(gl);
        g.setLayoutData(Ui.fillH());
        return g;
    }

    // Welcome

    private void welcome() {
        Composite cards = Parts.tiles(body, LearnTexts.WELCOME_CARDS.size());
        for (LearnTexts.Card c : LearnTexts.WELCOME_CARDS) {
            Screen target = Screen.of(c.target());
            Composite card = new Composite(cards, SWT.BORDER);
            card.setLayout(Ui.grid(1, 12, 6));
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            card.setLayoutData(gd);
            card.setBackground(Parts.MINE_BG);
            card.setBackgroundMode(SWT.INHERIT_FORCE);
            Button b = Ui.button(card, c.title(), () -> app.go(target));
            b.setToolTipText(String.format(LearnTexts.WELCOME_CARD_HELP_FORMAT, target == null ? c.target() : app.title(target, true)));
            Parts.text(card, c.text());
            QuickHelp.help(card, c.title(), c.text());
            QuickHelp.help(b, c.title(), c.text());
        }
        Composite how = Parts.box(body, LearnTexts.WELCOME_HOW_TO.title(), LearnTexts.WELCOME_HOW_TO.help());
        Parts.text(how, String.join("\n", LearnTexts.WELCOME_HOW_TO_LINES.stream().map(x -> "•  " + x).toList()));
        Composite acc = Parts.box(body, LearnTexts.WELCOME_ACCOUNT.title(), null);
        Portfolio p = app.port.portfolio();
        Ui.label(acc, p.name, Ui.bold());
        if (p.lesson != null) Parts.text(acc, p.lesson);
        Ui.button(acc, LearnTexts.WELCOME_ALL_SAMPLES, () -> app.go(Screen.SAMPLES));
    }

    // Choose Your Path

    private void choosePath() {
        Composite box = Parts.box(body, "The questions", "The questions that separate an actively managed portfolio from a passive index one. They don't choose for you.");
        Table t = Parts.table(box, SWT.MULTI, Parts.Col.l(LearnTexts.CHOOSE_PATH_HEADER.cells()[0], 300), Parts.Col.l(LearnTexts.CHOOSE_PATH_HEADER.cells()[1], 330),
                Parts.Col.l(LearnTexts.CHOOSE_PATH_HEADER.cells()[2], 330));
        for (LearnTexts.Question q : LearnTexts.CHOOSE_PATH_QUESTIONS) {
            TableItem it = new TableItem(t, SWT.NONE);
            it.setText(new String[] { q.question(), q.active(), q.passive() });
        }
        Parts.rows(t, LearnTexts.CHOOSE_PATH_QUESTIONS.size());
        QuickHelp.provider(t, (c, p) -> {
            TableItem it = t.getItem(p);
            if (it == null) return null;
            LearnTexts.Question q = LearnTexts.CHOOSE_PATH_QUESTIONS.get(t.indexOf(it));
            return new com.k3jdr.mx.core.ui.QuickHelpItem(q.question(), String.format(LearnTexts.CHOOSE_PATH_QUESTION_HELP_FORMAT, q.active(), q.passive()));
        });
        // Each answer in full, as the table's cells cut long text.
        for (LearnTexts.Question q : LearnTexts.CHOOSE_PATH_QUESTIONS) {
            Composite g = grid(box, 1);
            Ui.label(g, q.question(), Ui.bold());
            Parts.text(g, "Active: " + q.active() + "\nPassive: " + q.passive());
        }
        Composite start = Parts.box(body, LearnTexts.START_TITLE, LearnTexts.START_QUICK_HELP.text());
        Parts.text(start, LearnTexts.START_TEXT);
        Ui.button(start, LearnTexts.START_BUTTON, () -> app.go(Screen.MY_PORTFOLIO));
    }

    // Why Optimize?

    private void load() {
        app.port.run(() -> {
            OptimizationInputs sp = ServiceClient.riskInputs(List.of(), "SP500").inputs();
            int n = sp.n();
            List<double[]> div = new ArrayList<>();
            SeededRandom rng = new SeededRandom(5);
            for (int k = 1; n >= 2 && k <= Math.min(LearnTexts.OPTIMIZE_MAX_NAMES, n); k++) {
                double total = 0;
                for (int d = 0; d < LearnTexts.OPTIMIZE_DRAWS; d++) {
                    int[] idx = new int[n];
                    for (int i = 0; i < n; i++) idx[i] = i;
                    for (int i = n - 1; i > 0; i--) {
                        int j = (int) Math.floor(rng.uniform() * (i + 1));
                        int tmp = idx[i];
                        idx[i] = idx[j];
                        idx[j] = tmp;
                    }
                    double v = 0;
                    for (int a = 0; a < k; a++) for (int b = 0; b < k; b++) v += sp.cov[idx[a] * n + idx[b]];
                    total += Math.sqrt(v / ((double) k * k));
                }
                div.add(new double[] { k, total / LearnTexts.OPTIMIZE_DRAWS });
            }
            OptimizationInputs two = ServiceClient.riskInputs(List.of("VTI", "BND"), null).inputs();
            int iv = -1, ib = -1;
            for (int i = 0; i < two.n(); i++) {
                Security s = Universe.security(two.assets.get(i));
                if (s != null && "VTI".equals(s.displayTicker())) iv = i;
                if (s != null && "BND".equals(s.displayTicker())) ib = i;
            }
            if (iv < 0 || ib < 0) throw ServiceException.rejected(LearnTexts.OPTIMIZE_NO_VTI_BND);
            double rv = Universe.expectedReturn(Universe.security(two.assets.get(iv))), rb = Universe.expectedReturn(Universe.security(two.assets.get(ib)));
            List<double[]> mix = new ArrayList<>();
            int nn = two.n();
            for (int k = 0; k <= 20; k++) {
                double x = k * 0.05;
                double v = x * x * two.cov[iv * nn + iv] + (1 - x) * (1 - x) * two.cov[ib * nn + ib] + 2 * x * (1 - x) * two.cov[iv * nn + ib];
                mix.add(new double[] { x, x * rv + (1 - x) * rb, Math.sqrt(v) });
            }
            return new Object[] { div, mix };
        }, out -> {
            @SuppressWarnings("unchecked") List<double[]> d = (List<double[]>) out[0];
            @SuppressWarnings("unchecked") List<double[]> m = (List<double[]>) out[1];
            diversification = d;
            mixes = m;
            problem = null;
            if (!body.isDisposed()) again();
        }, e -> {
            problem = e;
            if (!body.isDisposed()) again();
        });
    }

    private void whyOptimize() {
        if (problem != null) {
            Composite box = Parts.box(body, LearnTexts.OPTIMIZE_NEEDS_SERVICE.title(), LearnTexts.OPTIMIZE_NEEDS_SERVICE.help());
            Parts.text(box, LearnTexts.OPTIMIZE_NEEDS_SERVICE.help() + " " + problem);
            Composite row = Parts.row(box, 2);
            Ui.button(row, LearnTexts.OPTIMIZE_TRY_AGAIN, () -> {
                problem = null;
                again();
                load();
            });
            Ui.button(row, "Layer 3 Services…", () -> app.go(Screen.SERVICES));
        } else if (diversification == null) {
            Composite box = Parts.box(body, LearnTexts.OPTIMIZE_LOADING.title(), LearnTexts.OPTIMIZE_LOADING.help());
            Parts.secondary(box, LearnTexts.OPTIMIZE_LOADING_TEXT);
        } else {
            Composite div = Parts.box(body, LearnTexts.DIVERSIFICATION.title(), LearnTexts.DIVERSIFICATION.help());
            List<LineChart.Pt> pts = new ArrayList<>();
            for (double[] d : diversification) pts.add(new LineChart.Pt(String.valueOf((int) d[0]), d[1] * 100));
            int k = Math.min(names, Math.max(1, diversification.size()));
            new LineChart(div, pts, Universe.marketVol() * 100, null, null, LearnTexts.DIVERSIFICATION_X_AXIS, LearnTexts.DIVERSIFICATION_Y_AXIS, k - 1, 240);
            Composite g = grid(div, 3);
            slider(g, LearnTexts.DIVERSIFICATION_SLIDER, LearnTexts.DIVERSIFICATION_SLIDER_HELP, k, 1, Math.max(2, diversification.size()), 1,
                    v -> names = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));
            double vol = diversification.isEmpty() ? 0 : diversification.get(k - 1)[1];
            Parts.text(div, LearnTexts.diversificationCaption(k, Fmt.pct(vol), Fmt.pct(vol - 0.08)));
            Composite trade = Parts.box(body, LearnTexts.TRADE_OFF.title(), LearnTexts.TRADE_OFF.help());
            List<LineChart.Pt> mp = new ArrayList<>();
            int sel = (int) Math.round(stockShare / 0.05);
            for (double[] m : mixes) mp.add(new LineChart.Pt(Fmt.pct(m[2], 0), m[1] * 100));
            new LineChart(trade, mp, null, null, null, LearnTexts.TRADE_OFF_X_AXIS, LearnTexts.TRADE_OFF_Y_AXIS, sel, 240);
            Composite g2 = grid(trade, 3);
            slider(g2, LearnTexts.TRADE_OFF_SLIDER, LearnTexts.TRADE_OFF_SLIDER_HELP, stockShare, 0, 1, 0.05, v -> stockShare = v,
                    v -> String.format(LearnTexts.TRADE_OFF_POINT_FORMAT, Math.round(v * 100), 100 - Math.round(v * 100)));
            double[] m = mixes.get(Math.max(0, Math.min(mixes.size() - 1, sel)));
            int stocks = (int) Math.round(m[0] * 100);
            Parts.text(trade, String.format(LearnTexts.TRADE_OFF_CAPTION_FORMAT, stocks, 100 - stocks, Fmt.pct(m[1]), Fmt.pct(Universe.riskFree(), 2),
                    Fmt.pct(LearnTexts.OPTIMIZE_EQUITY_PREMIUM, 0), Fmt.pct(m[2])));
        }
        Composite opt = Parts.box(body, LearnTexts.OPTIMIZER.title(), LearnTexts.OPTIMIZER.help());
        Composite steps = grid(opt, LearnTexts.OPTIMIZER_STEPS.size() * 2 - 1);
        for (int i = 0; i < LearnTexts.OPTIMIZER_STEPS.size(); i++) {
            LearnTexts.Section s = LearnTexts.OPTIMIZER_STEPS.get(i);
            Composite c = new Composite(steps, SWT.BORDER);
            c.setLayout(Ui.grid(1, 8, 4));
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            gd.widthHint = 150;
            c.setLayoutData(gd);
            Ui.label(c, s.title(), Ui.bold());
            Parts.text(c, s.text());
            QuickHelp.help(c, s.title(), s.text());
            if (i < LearnTexts.OPTIMIZER_JOINS.size()) Ui.label(steps, LearnTexts.OPTIMIZER_JOINS.get(i), Ui.subheading());
        }
        Parts.text(opt, LearnTexts.OPTIMIZER_NOTE);
    }

    // Why Attribution?

    private void whyAttribution() {
        double[] wp = { techWeight, 1 - techWeight }, rp = { techReturn, restReturn };
        double[] wb = { LearnTexts.ATTRIBUTION_BENCH_TECH_WEIGHT, 1 - LearnTexts.ATTRIBUTION_BENCH_TECH_WEIGHT };
        double[] rbs = { LearnTexts.ATTRIBUTION_BENCH_TECH_RETURN, LearnTexts.ATTRIBUTION_BENCH_REST_RETURN };
        double rb = 0, total = 0, allocation = 0, selection = 0, interaction = 0;
        for (int i = 0; i < 2; i++) {
            rb += wb[i] * rbs[i];
            total += wp[i] * rp[i];
        }
        for (int i = 0; i < 2; i++) {
            allocation += (wp[i] - wb[i]) * (rbs[i] - rb);
            selection += wb[i] * (rp[i] - rbs[i]);
            interaction += (wp[i] - wb[i]) * (rp[i] - rbs[i]);
        }
        Composite demo = Parts.box(body, LearnTexts.ATTRIBUTION_DEMO.title(), LearnTexts.ATTRIBUTION_DEMO.help());
        Composite g = grid(demo, 3);
        LearnTexts.Slider s0 = LearnTexts.ATTRIBUTION_SLIDERS.get(0), s1 = LearnTexts.ATTRIBUTION_SLIDERS.get(1), s2 = LearnTexts.ATTRIBUTION_SLIDERS.get(2);
        slider(g, s0.label(), s0.label(), techWeight, s0.min(), s0.max(), s0.step(), v -> techWeight = v, v -> Fmt.pct(v, 0));
        slider(g, s1.label(), s1.label(), techReturn, s1.min(), s1.max(), s1.step(), v -> techReturn = v, v -> Fmt.pct(v, 0));
        slider(g, s2.label(), s2.label(), restReturn, s2.min(), s2.max(), s2.step(), v -> restReturn = v, v -> Fmt.pct(v, 0));
        Parts.text(demo, String.format(LearnTexts.ATTRIBUTION_BENCHMARK_FORMAT, Fmt.pct(wb[0], 0), Fmt.pct(rbs[0], 0), Fmt.pct(wb[1], 0), Fmt.pct(rbs[1], 0), Fmt.pct(rb)));
        Parts.text(demo, String.format(LearnTexts.ATTRIBUTION_ACCOUNT_FORMAT, Fmt.pct(total), Fmt.signedPct(total - rb)));
        Composite effects = Parts.box(body, LearnTexts.ATTRIBUTION_EFFECTS.title(), LearnTexts.ATTRIBUTION_EFFECTS.help());
        double[] values = { allocation, selection, interaction, total - rb };
        List<Bars.Row> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(new Bars.Row(LearnTexts.ATTRIBUTION_BARS.get(i), new double[] { values[i] * 100 },
                    new org.eclipse.swt.graphics.Color[] { i == 3 ? Bars.BLUE : values[i] >= 0 ? Bars.GREEN : Bars.RED }));
        }
        new Bars(effects, List.of(new Bars.Series(LearnTexts.ATTRIBUTION_Y_AXIS, Bars.BLUE)), rows, 100, "pp");
        Parts.text(effects, LearnTexts.attributionStory(allocation, selection, interaction, techWeight));
        Composite terms = Parts.row(effects, LearnTexts.ATTRIBUTION_TERMS.size() + 1);
        for (String term : LearnTexts.ATTRIBUTION_TERMS) {
            Label l = Ui.label(terms, " " + term + " ");
            l.setBackground(Parts.TILE_BG);
            l.setToolTipText(Help.term(term));
            QuickHelp.help(l, term, Help.term(term));
        }
        new Label(terms, SWT.NONE);
    }

    // Active vs Passive

    private void activeVsPassive() {
        Composite fees = Parts.box(body, LearnTexts.FEES.title(), LearnTexts.FEES.help());
        Composite g = grid(fees, 3);
        LearnTexts.Slider f = LearnTexts.FEES_ACTIVE_FEE, a = LearnTexts.FEES_ALPHA;
        slider(g, String.format(LearnTexts.FEES_ACTIVE_FEE_FORMAT, ""), f.label(), activeFee, f.min(), f.max(), f.step(), v -> activeFee = v, v -> Fmt.pct(v, 2));
        slider(g, String.format(LearnTexts.FEES_ALPHA_FORMAT, ""), a.label(), alpha, a.min(), a.max(), a.step(), v -> alpha = v, v -> Fmt.signedPct(v, 2));
        List<Bars.Row> rows = new ArrayList<>();
        double index = LearnTexts.FEES_START, active = LearnTexts.FEES_START;
        for (int y = 0; y <= LearnTexts.FEES_YEARS; y++) {
            index = LearnTexts.FEES_START * Math.pow(1 + LearnTexts.FEES_GROSS - LearnTexts.FEES_PASSIVE_FEE, y);
            active = LearnTexts.FEES_START * Math.pow(1 + LearnTexts.FEES_GROSS + alpha - activeFee, y);
            if (y % 5 == 0) rows.add(new Bars.Row("Year " + y, index / 1000, active / 1000));
        }
        Composite chart = grid(fees, 1);
        Parts.secondary(chart, "Value in $ thousands");
        new Bars(chart, List.of(new Bars.Series(LearnTexts.FEES_INDEX_FUND, Bars.GRAY), new Bars.Series(LearnTexts.FEES_ACTIVE_FUND, Bars.BLUE)), rows, 70, "k");
        Parts.text(fees, LearnTexts.feesCaption(LearnTexts.FEES_YEARS, Fmt.money(index), Fmt.money(active), active >= index, Fmt.money(Math.abs(active - index)),
                Fmt.pct(activeFee - LearnTexts.FEES_PASSIVE_FEE, 2)));
        Composite cmp = Parts.box(body, "Side by side", LearnTexts.COMPARISON_QUICK_HELP.text());
        String[] head = LearnTexts.COMPARISON_HEADER.cells();
        Table t = Parts.table(cmp, SWT.MULTI, Parts.Col.l(head[0], 200), Parts.Col.l(head[1], 300), Parts.Col.l(head[2], 300));
        for (LearnTexts.Row r : LearnTexts.COMPARISON_ROWS) {
            TableItem it = new TableItem(t, SWT.NONE);
            it.setText(r.cells());
            it.setForeground(0, Ui.SECONDARY);
        }
        Parts.rows(t, LearnTexts.COMPARISON_ROWS.size());
    }
}
