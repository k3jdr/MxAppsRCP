package com.k3jdr.mxacctmgr.invest;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.port.Engine;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.OptimizationInputs;
import com.k3jdr.mxacctmgr.port.RiskReport;
import com.k3jdr.mxacctmgr.port.Security;
import com.k3jdr.mxacctmgr.port.Universe;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.text.Lessons;
import com.k3jdr.mxacctmgr.ui.Bars;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Investments step 3, Risk Today (PathViews.swift's RiskView): volatility, tracking error, beta, one-month 95% VaR and
 * expected shortfall in dollars, rate sensitivity; weight against share of risk; risk by sector; what drives tracking
 * error — from the analytics service's risk inputs (Ledoit-Wolf covariance of two years of srm prices), computed by the
 * built-in engine's risk formulas (portfolio-cpp's /risk).
 */
public final class RiskPage extends InvestPage {
    private boolean requested;

    public RiskPage(App app) {
        super(app);
    }

    @Override
    protected Lessons.Lesson lesson() {
        if (pending()) return Lessons.of("myRisk.pending");
        return super.lesson();
    }

    private boolean pending() { return p().isSRM() && port().inputs().n() == 0; }

    /** Opens the screen again when the inputs come in, so the lesson and header follow. */
    @Override
    protected void started() {
        if (pending() && port().srmInputs == null && !requested && port().accountsLoaded) {
            requested = true;
            port().loadInputs(null);
        }
    }

    @Override
    public void stateChanged() {
        boolean was = pending();
        super.stateChanged();
        if (was != pending()) started();
        if (!requested) started();
    }

    @Override
    protected void fill(Composite body) {
        if (pending()) {
            Composite box = Parts.box(body, "Risk inputs", "The account is saved to the security master, then the analytics service (port 8772) estimates a Ledoit-Wolf covariance from two years of prices for its holdings and its benchmark.");
            if (app.busy()) Parts.secondary(box, "Saving the account and estimating its covariance…");
            Parts.text(box, port().srmInputsNote != null ? port().srmInputsNote
                    : "The account is saved to the security master, then the analytics service (port 8772) estimates a Ledoit-Wolf covariance from two years of prices for its holdings and its benchmark.");
            Ui.button(box, "Load Risk Inputs", () -> port().loadInputs(null));
            return;
        }
        OptimizationInputs inputs = port().inputs();
        if (inputs.n() == 0) {
            empty(body, "No risk inputs", "The risk model needs the account's holdings in the security master and the analytics service's inputs.");
            return;
        }
        RiskReport r = Engine.risk(inputs, inputs.current);
        double value = p().total();
        if (p().isSRM() && port().srmInputsNote != null) {
            Parts.secondary(body, "Risk model from the analytics service and the security master's prices: " + port().srmInputsNote + ". Cash is not in the risk model.");
        }
        Composite t = Parts.tiles(body, 6);
        Parts.tile(t, "Volatility", Fmt.pct(r.volatility()), "benchmark " + Fmt.pct(r.benchmarkVolatility()), null, Help.term("Volatility"));
        Parts.tile(t, "Tracking error", Fmt.pct(r.trackingError(), 2),
                r.trackingError() < 0.01 ? "index-like" : r.trackingError() < 0.04 ? "moderately active" : "very active", null, Help.term("Tracking error"));
        Parts.tile(t, "Beta", Fmt.fixed(r.beta(), 2), r.beta() < 0.9 ? "more defensive" : r.beta() > 1.1 ? "more aggressive" : "market-like", null, Help.term("Beta"));
        Parts.tile(t, "VaR (95%, 1 month)", Fmt.money(r.var95Month() * value), Fmt.pct(r.var95Month()) + " of the account", Ui.ORANGE, Help.term("VaR (95%, 1 month)"));
        Parts.tile(t, "Expected shortfall", Fmt.money(r.es95Month() * value), "average of the worst 1 month in 20", Ui.RED, Help.term("Expected shortfall"));
        Parts.tile(t, "Rates +1 point", Fmt.signedPct(r.rateSensitivity()), Fmt.money(r.rateSensitivity() * value), signed(r.rateSensitivity()),
                "The model's change in value if interest rates rose 1 point at once: mostly the bonds' duration, plus rate-sensitive stocks.");
        Composite two = new Composite(body, SWT.NONE);
        GridLayout gl = new GridLayout(2, true);
        gl.marginWidth = 0;
        gl.horizontalSpacing = 14;
        two.setLayout(gl);
        two.setLayoutData(Ui.fillH());
        Composite top = Parts.box(two, "Weight vs share of risk (top 10)", "For the ten largest contributors: their share of the account's value and of its variance. A bar for risk much longer than for weight is a concentration of risk.");
        ((GridData) top.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
        List<Bars.Row> rows = new ArrayList<>();
        for (RiskReport.Contribution c : r.contributions().subList(0, Math.min(10, r.contributions().size()))) {
            rows.add(new Bars.Row(c.name(), c.weight() * 100, c.share() * 100));
        }
        new Bars(top, List.of(new Bars.Series("Weight", Bars.GRAY), new Bars.Series("Share of risk", Bars.ORANGE)), rows, 70, "%");
        if (!r.contributions().isEmpty()) {
            RiskReport.Contribution c = r.contributions().get(0);
            Parts.text(top, c.name() + " is " + Fmt.pct(c.weight()) + " of the value and " + Fmt.pct(c.share()) + " of the risk.");
        }
        Composite sectors = Parts.box(two, "Risk by sector", "Each sector's share of the account's variance (funds looked through).");
        ((GridData) sectors.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
        List<Bars.Row> srows = new ArrayList<>();
        for (RiskReport.Contribution c : r.sectorContributions()) srows.add(new Bars.Row(c.name(), c.share() * 100));
        new Bars(sectors, List.of(new Bars.Series("Share of risk", Bars.ORANGE)), srows, 130, "%");
        Composite drivers = Parts.box(body, "What drives the tracking error", "The positions whose difference from the benchmark adds most to tracking error: active weight and share of active variance.");
        Table table = Parts.table(drivers, SWT.MULTI, Col.l("Security", 320), Col.r("Active weight", 110), Col.r("Share of tracking-error variance", 220));
        for (RiskReport.Contribution c : r.activeContributions().subList(0, Math.min(8, r.activeContributions().size()))) {
            Security s = Universe.security(c.name());
            TableItem it = new TableItem(table, SWT.NONE);
            it.setText(new String[] { c.name() + " — " + (s != null ? s.name : ""), Fmt.signedPct(c.weight()), Fmt.pct(c.share()) });
            it.setForeground(1, signed(c.weight()));
        }
        Parts.rows(table, Math.max(3, table.getItemCount()));
    }
}
