package com.k3jdr.mxacctmgr.invest;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Settings;
import com.k3jdr.mxacctmgr.port.AttributionEngine;
import com.k3jdr.mxacctmgr.port.AttributionResult;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.ui.Bars;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Investments step 6, Attribution (PathViews.swift's AttributionView): the analytics service's monthly Brinson-Fachler
 * or Brinson-Hood-Beebower attribution by sector, Carino-linked — account, benchmark and active return; allocation,
 * selection and interaction by sector and month by month; top and bottom contributors; assumptions; publish or
 * review. Read as a check-up for a passive account: every effect should be close to zero.
 */
public final class AttributionPage extends InvestPage {
    private boolean requested;

    public AttributionPage(App app) {
        super(app);
    }

    @Override
    protected String stamp() {
        return super.stamp() + "|" + System.identityHashCode(port().attribution) + "|" + app.busy();
    }

    @Override
    protected void started() {
        PortfolioStore s = port();
        if (s.attribution == null && !s.attributeMaster && !requested && s.accountsLoaded && p().srmCode != null) {
            requested = true;
            s.attribute();
        }
    }

    @Override
    public void stateChanged() {
        super.stateChanged();
        if (!requested) started();
    }

    @Override
    protected void fill(Composite body) {
        controls(body);
        AttributionResult a = port().attribution;
        if (a == null) {
            empty(body, "No Attribution Yet", "Run Attribution (Ctrl+Shift+A).");
            return;
        }
        result(body, a, !app.active());
    }

    private void controls(Composite body) {
        PortfolioStore s = port();
        Composite box = Parts.box(body, "Attribute", "What to attribute — the selected account, or another portfolio in the security master — with the analytics service, over its real holdings history and returns, and the method.");
        Composite row = Parts.row(box, 7);
        Combo source = new Combo(row, SWT.READ_ONLY);
        source.setItems("This account", "Another security-master portfolio");
        source.select(s.attributeMaster ? 1 : 0);
        source.setToolTipText("Attribute the account selected in the toolbar, or another portfolio from MxDataHUB, with the analytics service on port 8772");
        QuickHelp.help(source, "Source", source.getToolTipText());
        Text code = new Text(row, SWT.BORDER);
        code.setText(s.masterPortfolio);
        code.setMessage("Portfolio code");
        code.setLayoutData(new GridData(150, SWT.DEFAULT));
        code.setEnabled(s.attributeMaster);
        code.setToolTipText("A portfolio code from MxDataHUB ▸ Portfolios, e.g. DEMO_GROWTH");
        code.addListener(SWT.Modify, e -> {
            s.masterPortfolio = code.getText().trim();
            Settings.PREFS.put("masterPortfolio", s.masterPortfolio);
        });
        source.addListener(SWT.Selection, e -> {
            s.attributeMaster = source.getSelectionIndex() == 1;
            s.attribution = null;
            code.setEnabled(s.attributeMaster);
        });
        Ui.label(row, "Method");
        Combo method = new Combo(row, SWT.READ_ONLY);
        for (AttributionEngine.Method m : AttributionEngine.Method.values()) method.add(m.title());
        method.select(s.attributionMethod.ordinal());
        method.setToolTipText("Brinson-Fachler measures allocation against the whole benchmark's return; Brinson-Hood-Beebower against zero");
        QuickHelp.help(method, "Method", method.getToolTipText());
        method.addListener(SWT.Selection, e -> {
            s.attributionMethod = AttributionEngine.Method.values()[method.getSelectionIndex()];
            s.attribution = null;
        });
        Button run = Ui.button(row, "▶ Run Attribution", s::attribute);
        run.setEnabled(!app.busy());
        QuickHelp.help(run, "Run Attribution", "Attributes the active return by sector, month by month, Carino-linked.", "Ctrl+Shift+A");
        Label busy = Parts.secondary(row, app.busy() ? "working…" : "");
        busy.setLayoutData(Ui.fillH());
        new Label(row, SWT.NONE);
    }

    private static String interpretation(AttributionResult r, boolean passive) {
        String biggest = Math.abs(r.allocation()) > Math.abs(r.selection()) ? "allocation (sector weights)" : "selection (companies within sectors)";
        if (passive) {
            return Math.abs(r.activeReturn()) < 0.005 ? "Within half a point of the index: the account did what a passive portfolio should."
                    : Fmt.signedPct(r.activeReturn()) + " from the index is large for a passive account; most came from " + biggest + ". Look for drift, idle cash or missing names.";
        }
        return "Most of the active return came from " + biggest + ". Ask whether that was the bet you meant to make.";
    }

    private void result(Composite body, AttributionResult r, boolean passive) {
        Composite t = Parts.tiles(body, 6);
        Parts.tile(t, "Account", Fmt.signedPct(r.portfolioReturn()), "", null, "The account's compounded return over the period.");
        Parts.tile(t, "Benchmark", Fmt.signedPct(r.benchmarkReturn()), "", null, "The benchmark's compounded return.");
        Parts.tile(t, "Active return", Fmt.signedPct(r.activeReturn()), "", signed(r.activeReturn()), "Account minus benchmark.");
        Parts.tile(t, "Allocation", Fmt.signedPct(r.allocation()), "", signed(r.allocation()), Help.term("Allocation effect"));
        Parts.tile(t, "Selection", Fmt.signedPct(r.selection()), "", signed(r.selection()), Help.term("Selection effect"));
        Parts.tile(t, "Interaction", Fmt.signedPct(r.interaction()), "", signed(r.interaction()), Help.term("Interaction effect"));
        Composite read = new Composite(body, SWT.BORDER);
        read.setLayout(Ui.grid(2, 10, 4));
        read.setLayoutData(Ui.fillH());
        boolean publish = "publish".equals(r.recommendation());
        Label mark = Ui.label(read, publish ? "✔" : "⚠", Ui.font(6, SWT.BOLD));
        mark.setForeground(publish ? Ui.GREEN : Ui.ORANGE);
        mark.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 1, 4));
        Ui.label(read, r.highlight() == null ? "" : r.highlight(), Ui.bold());
        Parts.text(read, (publish ? "Complete: " : "Review: ") + String.join("; ", r.reasons()) + ".");
        Parts.text(read, interpretation(r, passive)).setForeground(Ui.SECONDARY);
        Parts.secondary(read, r.engine() + " · " + r.method() + ", monthly, Carino-linked");
        QuickHelp.help(read, "Reading the result", interpretation(r, passive));
        Composite two = new Composite(body, SWT.NONE);
        GridLayout gl = new GridLayout(2, true);
        gl.marginWidth = 0;
        gl.horizontalSpacing = 14;
        two.setLayout(gl);
        two.setLayoutData(Ui.fillH());
        Composite effects = Parts.box(two, "Effects by sector", "Allocation, selection and interaction in each sector, linked over the period. They add up to the active return.");
        ((GridData) effects.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
        List<Bars.Row> rows = new ArrayList<>();
        for (AttributionResult.Group g : r.groups()) rows.add(new Bars.Row(g.group(), g.allocation() * 100, g.selection() * 100, g.interaction() * 100));
        new Bars(effects, List.of(new Bars.Series("Allocation", Bars.BLUE), new Bars.Series("Selection", Bars.ORANGE), new Bars.Series("Interaction", Bars.PURPLE)), rows, 130, "pp");
        Composite months = Parts.box(two, "Month by month", "Each month's active return, with its allocation (blue) and selection (orange) effects.");
        ((GridData) months.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
        List<Bars.Row> mrows = new ArrayList<>();
        for (AttributionResult.Period p : r.periods()) {
            String m = p.month().length() > 5 ? p.month().substring(p.month().length() - 5) : p.month();
            mrows.add(new Bars.Row(m, new double[] { p.activeReturn() * 100, p.allocation() * 100, p.selection() * 100 },
                    new org.eclipse.swt.graphics.Color[] { p.activeReturn() >= 0 ? Bars.GREEN : Bars.RED, null, null }));
        }
        new Bars(months, List.of(new Bars.Series("Active", Bars.GRAY), new Bars.Series("Allocation", Bars.BLUE), new Bars.Series("Selection", Bars.ORANGE)), mrows, 60, "pp");
        Composite sectors = Parts.box(body, "By sector", "Average weights over the period and each effect, largest total first. Select rows and Ctrl+C copies them under the column titles.");
        Table table = Parts.table(sectors, SWT.MULTI, Col.l("Sector", 180), Col.r("Account", 80), Col.r("Benchmark", 85), Col.r("Active", 75), Col.r("Allocation", 85),
                Col.r("Selection", 85), Col.r("Interaction", 85), Col.r("Total", 80));
        for (AttributionResult.Group g : r.groups()) {
            TableItem it = new TableItem(table, SWT.NONE);
            it.setText(new String[] { g.group(), Fmt.pct(g.portfolioWeight()), Fmt.pct(g.benchmarkWeight()), Fmt.signedPct(g.activeWeight(), 1),
                    Fmt.signedPct(g.allocation()), Fmt.signedPct(g.selection()), Fmt.signedPct(g.interaction()), Fmt.signedPct(g.total()) });
            for (int c = 4; c <= 6; c++) it.setForeground(c, signed(new double[] { g.allocation(), g.selection(), g.interaction() }[c - 4]));
            it.setFont(7, Ui.bold());
        }
        Parts.rows(table, Math.max(3, r.groups().size()));
        Composite pair = new Composite(body, SWT.NONE);
        GridLayout pl = new GridLayout(2, true);
        pl.marginWidth = 0;
        pl.horizontalSpacing = 14;
        pair.setLayout(pl);
        pair.setLayoutData(Ui.fillH());
        contributors(pair, "Helped most", r.top());
        contributors(pair, "Hurt most", r.bottom());
        Composite assumptions = Parts.box(body, "Assumptions", "How the numbers were computed; read them before trusting the result.");
        Parts.text(assumptions, String.join("\n", r.assumptions().stream().map(x -> "•  " + x).toList()));
    }

    private static void contributors(Composite parent, String title, List<AttributionResult.Contributor> rows) {
        Composite box = Parts.box(parent, title, "Securities with the largest contribution to active return: active weight times return, summed over the months.");
        ((GridData) box.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
        Composite g = new Composite(box, SWT.NONE);
        g.setLayout(Ui.grid(4, 0, 4));
        ((GridLayout) g.getLayout()).horizontalSpacing = 12;
        g.setLayoutData(Ui.fillH());
        for (AttributionResult.Contributor c : rows) {
            Ui.label(g, c.ticker(), Ui.mono());
            Label sector = Parts.secondary(g, c.sector());
            sector.setLayoutData(Ui.fillH());
            Parts.secondary(g, Fmt.pct(c.portfolioWeight()) + " vs " + Fmt.pct(c.benchmarkWeight()));
            Label v = Ui.label(g, Fmt.signedPct(c.contribution()));
            v.setForeground(signed(c.contribution()));
        }
    }
}
