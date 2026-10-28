package com.k3jdr.mxacctmgr.invest;

import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.port.AccountType;
import com.k3jdr.mxacctmgr.port.AccountsClient;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.Portfolio;
import com.k3jdr.mxacctmgr.port.PortfolioStyle;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;

/**
 * An Investments step (PathViews.swift, PassiveViews.swift): the My Portfolio bar above the lesson — the open
 * portfolio, My approach, its account type, Save / Revert / Open / New, Load Prices and Start From a Sample — and the
 * step's content, built again whenever the account or a result changes.
 */
public abstract class InvestPage extends Page {
    private Composite barHost;
    private Composite content;
    private String stamp;

    protected InvestPage(App app) {
        super(app);
    }

    protected PortfolioStore port() { return app.port; }

    protected Portfolio p() { return app.port.portfolio(); }

    @Override
    protected final void beforeLesson(Composite parent) {
        barHost = new Composite(parent, SWT.NONE);
        barHost.setLayout(Ui.grid(1, 0, 0));
        barHost.setLayoutData(Ui.fillH());
        bar();
    }

    @Override
    protected final void build(Composite body) {
        content = body;
        stamp = stamp();
        fill(body);
        started();
    }

    /** The step's content. */
    protected abstract void fill(Composite body);

    /** After the first build: start what the step needs (risk inputs, an optimization). */
    protected void started() {}

    /** What the content shows: when it changes the content is built again. */
    protected String stamp() {
        PortfolioStore s = port();
        Portfolio p = p();
        return s.selectedID() + "|" + p.hashCode() + "|" + p.benchmark + "|" + s.accountsLoaded + "|" + s.loading + "|" + s.srmBusy + "|"
                + Objects.hashCode(s.dataProblem) + "|" + System.identityHashCode(s.srmInputs) + "|" + s.openVersion + "|" + s.mineLoadedCode
                + "|" + s.isDirty() + "|" + Objects.hashCode(s.srmStatus) + "|" + app.approach();
    }

    @Override
    public void stateChanged() {
        String now = stamp();
        if (now.equals(stamp) || content == null || content.isDisposed()) return;
        stamp = now;
        rebuild();
    }

    /** Builds the bar and the content again. */
    protected void rebuild() {
        if (content == null || content.isDisposed()) return;
        Page.clear(barHost);
        bar();
        Page.clear(content);
        fill(content);
        relayout();
    }

    // The My Portfolio bar

    private void bar() {
        Composite b = new Composite(barHost, SWT.BORDER);
        b.setLayout(Ui.grid(1, 10, 8));
        b.setLayoutData(Ui.fillH());
        b.setBackground(Parts.MINE_BG);
        b.setBackgroundMode(SWT.INHERIT_DEFAULT);
        QuickHelp.help(b, Help.MY_PORTFOLIO_TITLE, Help.MY_PORTFOLIO);
        PortfolioStore s = port();
        if (s.openPortfolio() == null) {
            Composite row = Parts.row(b, 4);
            Composite text = Parts.column(row, 1);
            Ui.label(text, Help.NO_PORTFOLIO_TITLE, Ui.bold());
            Parts.secondary(text, Help.NO_PORTFOLIO_TEXT);
            Ui.button(row, Help.NEW_PORTFOLIO, () -> PortfolioDialogs.newPortfolio(app));
            Ui.button(row, Help.OPEN_PORTFOLIO, () -> PortfolioDialogs.openPortfolio(app));
            if (s.loading || s.srmBusy) Parts.secondary(row, "Reading the security master…");
            return;
        }
        Portfolio p = p();
        boolean dirty = s.isDirty();
        Composite top = Parts.row(b, 6);
        Composite names = Parts.column(top, 1);
        Composite line1 = Parts.row(names, 3);
        ((org.eclipse.swt.layout.GridData) line1.getLayoutData()).grabExcessHorizontalSpace = false;
        Ui.label(line1, String.format(Help.PORTFOLIO_NAME_FORMAT, p.name, p.srmCode == null ? "" : p.srmCode), Ui.bold());
        Parts.secondary(line1, s.openVersion == 0 ? Help.NOT_SAVED_YET : String.format(Help.VERSION_FORMAT, s.openVersion));
        if (dirty) {
            Label edited = Ui.label(line1, " " + Help.EDITED + " ");
            edited.setBackground(new Color(255, 225, 180));
            edited.setToolTipText(Help.EDITED_HELP_PREFIX + String.join("; ", s.looseEnds()));
            QuickHelp.help(edited, Help.EDITED, edited.getToolTipText());
        } else {
            new Label(line1, SWT.NONE);
        }
        Parts.secondary(names, String.format(Help.PORTFOLIO_LINE_FORMAT, p.account.title, Fmt.money(p.total()), p.holdings.size(), p.benchmark));
        Ui.label(top, Help.MY_APPROACH);
        Button active = new Button(top, SWT.RADIO);
        active.setText(Help.APPROACH_ACTIVE);
        active.setSelection(app.active());
        Button passive = new Button(top, SWT.RADIO);
        passive.setText(Help.APPROACH_PASSIVE);
        passive.setSelection(!app.active());
        active.addListener(SWT.Selection, e -> { if (active.getSelection()) app.setApproach(PortfolioStyle.ACTIVE); });
        passive.addListener(SWT.Selection, e -> { if (passive.getSelection()) app.setApproach(PortfolioStyle.PASSIVE); });
        for (Button x : new Button[] { active, passive }) {
            x.setToolTipText(Help.APPROACH_HELP);
            QuickHelp.help(x, Help.MY_APPROACH, Help.APPROACH_HELP);
        }
        Combo type = new Combo(top, SWT.READ_ONLY);
        for (AccountType t : AccountType.values()) type.add(t.title);
        type.select(p.account.ordinal());
        type.setToolTipText(Help.ACCOUNT_TYPE_HELP);
        QuickHelp.help(type, Help.ACCOUNT_TYPE, Help.ACCOUNT_TYPE_HELP);
        type.addListener(SWT.Selection, e -> {
            Portfolio q = p().copy();
            q.account = AccountType.values()[type.getSelectionIndex()];
            port().update(q);
        });
        Composite row = Parts.row(b, 9);
        Button save = Ui.button(row, Help.SAVE_BUTTON, () -> port().save(null));
        save.setEnabled(dirty);
        save.setToolTipText(Help.SAVE_HELP);
        QuickHelp.help(save, Help.SAVE_BUTTON, Help.SAVE_HELP, "Ctrl+S");
        Button revert = Ui.button(row, Help.REVERT_BUTTON, port()::revert);
        revert.setEnabled(dirty);
        revert.setToolTipText(Help.REVERT_HELP);
        QuickHelp.help(revert, Help.REVERT_BUTTON, Help.REVERT_HELP);
        Button open = Ui.button(row, Help.OPEN_BUTTON, () -> PortfolioDialogs.openPortfolio(app));
        open.setToolTipText(Help.OPEN_HELP);
        QuickHelp.help(open, Help.OPEN_BUTTON, Help.OPEN_HELP, "Ctrl+O");
        Button nw = Ui.button(row, Help.NEW_BUTTON, () -> PortfolioDialogs.newPortfolio(app));
        nw.setToolTipText(Help.NEW_HELP);
        QuickHelp.help(nw, Help.NEW_BUTTON, Help.NEW_HELP, "Ctrl+N");
        if (p.holdings.stream().anyMatch(h -> h.unpriced)) {
            Button load = Ui.button(row, Help.LOAD_PRICES_BUTTON, this::loadPrices);
            load.setToolTipText(Help.LOAD_PRICES_HELP);
            QuickHelp.help(load, Help.LOAD_PRICES_BUTTON, Help.LOAD_PRICES_HELP);
        } else {
            new Label(row, SWT.NONE);
        }
        Label status = Parts.secondary(row, s.srmStatus == null ? (s.srmBusy ? "Working…" : "") : s.srmStatus);
        status.setLayoutData(Ui.fillH());
        Button start = Ui.button(row, Help.START_FROM_SAMPLE + " ▾", () -> {});
        start.setEnabled(s.accountsLoaded);
        start.setToolTipText(Help.START_FROM_SAMPLE_HELP);
        QuickHelp.help(start, Help.START_FROM_SAMPLE, Help.START_FROM_SAMPLE_HELP);
        Menu samples = new Menu(start);
        for (Portfolio x : port().portfolios()) {
            if (x.id.equals(PortfolioStore.MINE)) continue;
            MenuItem mi = new MenuItem(samples, SWT.PUSH);
            mi.setText(Ui.escape(x.name));
            mi.addListener(SWT.Selection, e -> port().copyToMine(x));
        }
        start.addListener(SWT.Selection, e -> {
            Point at = start.toDisplay(0, start.getSize().y);
            samples.setLocation(at);
            samples.setVisible(true);
        });
        new Label(row, SWT.NONE);
        new Label(row, SWT.NONE);
    }

    /** Asks the security master to load prices for the unpriced holdings (saved first), then reads the account again. */
    private void loadPrices() {
        String code = p().srmCode;
        if (code == null) return;
        port().save(ok -> port().run(() -> AccountsClient.loadPrices(code), r -> {
            if ("failed".equals(r.status())) {
                port().srmStatus = "Prices could not be loaded: " + (r.message() == null ? "the srm-loader reported a failure" : r.message()) + ".";
            } else {
                port().srmStatus = r.loaded().isEmpty() ? "Every holding already has prices." : "Loaded prices for " + String.join(", ", r.loaded()) + ".";
                port().revert();
            }
        }, null));
    }

    // Helpers for the steps

    /** A placeholder while there is nothing to show yet (ContentUnavailableView). */
    protected Composite empty(Composite parent, String title, String detail) {
        Composite box = Parts.box(parent, title, detail);
        Parts.text(box, detail);
        return box;
    }

    protected static Color signed(double x) { return x > 1e-9 ? Ui.GREEN : x < -1e-9 ? Ui.RED : Ui.SECONDARY; }
}
