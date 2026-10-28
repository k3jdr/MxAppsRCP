package com.k3jdr.mxacctmgr.invest;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.Holding;
import com.k3jdr.mxacctmgr.port.Security;
import com.k3jdr.mxacctmgr.port.TaxLot;
import com.k3jdr.mxacctmgr.ui.FormDialog;
import com.k3jdr.mxacctmgr.ui.Parts;

/**
 * Edit Holding… (HoldingEditor.swift): a holding's tax lots — when each was bought, how many shares, what they cost in
 * total — edited, added or removed; for cash, the amount. The holding's shares are the lots' total and its value
 * follows the latest price (cost where there is none). Kept when the portfolio is saved.
 */
final class HoldingDialog extends Dialog {
    private final App app;
    private final Holding holding;
    private final boolean cash;
    private final Double price;
    private final List<Row> rows = new ArrayList<>();
    private Composite lotsHost;
    private ScrolledComposite scroller;
    private Label totals, count, warning;
    private Text cashField;

    private final class Row {
        DateTime date;
        Text shares, cost;
        Label perShare;
        String note;
        double lastShares;
        Control[] controls;
    }

    HoldingDialog(App app, Holding holding) {
        super(app.shell);
        this.app = app;
        this.holding = holding;
        this.cash = holding.ticker.equals("CASH");
        Security s = holding.security();
        this.price = s != null && s.price > 0 ? s.price : null;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(cash ? "Edit Cash" : "Edit Holding");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(1, 14, 8));
        if (cash) {
            Composite g = new Composite(area, SWT.NONE);
            g.setLayout(Ui.grid(2, 0, 8));
            Ui.label(g, "Cash ($)");
            cashField = new Text(g, SWT.BORDER | SWT.RIGHT);
            cashField.setText(String.format("%.2f", holding.value));
            GridData cg = new GridData(160, SWT.DEFAULT);
            cashField.setLayoutData(cg);
            return area;
        }
        Security s = holding.security();
        Ui.label(area, "Edit " + (s != null ? s.displayTicker() : holding.ticker), Ui.subheading());
        Parts.secondary(area, s != null ? s.name : "");
        Parts.secondary(area, price != null ? "Latest price " + Fmt.money(price) + (s.priceDate != null ? " on " + s.priceDate : "") : "No price yet: valued at cost");
        Label intro = Ui.wrap(area, "Each tax lot: when it was bought, how many shares, and what they cost in total. The holding's shares are the lots' total. Changing a lot's shares changes its cost in proportion (a partial sale); change the cost after it to correct it.");
        intro.setForeground(Ui.SECONDARY);
        ((GridData) intro.getLayoutData()).widthHint = 600;
        scroller = new ScrolledComposite(area, SWT.V_SCROLL | SWT.BORDER);
        GridData sg = new GridData(SWT.FILL, SWT.FILL, true, true);
        sg.heightHint = 260;
        scroller.setLayoutData(sg);
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        lotsHost = new Composite(scroller, SWT.NONE);
        lotsHost.setLayout(Ui.grid(5, 6, 6));
        scroller.setContent(lotsHost);
        for (String h : new String[] { "Bought", "Shares", "Total cost ($)", "Cost / share", "" }) Parts.secondary(lotsHost, h);
        double q = holding.quantity == null ? 0 : holding.quantity;
        if (holding.lots.isEmpty()) addRow(null, q, holding.value, null);
        else for (TaxLot l : holding.lots) addRow(l.acquired, q * l.share, l.costBasis, l.note);
        Composite bar = Parts.row(area, 2);
        Button add = Ui.button(bar, "Add a Lot", () -> {
            addRow(null, 0, 0, null);
            layoutLots();
            scroller.setOrigin(0, Integer.MAX_VALUE);
        });
        QuickHelp.help(add, "Add a Lot", "Adds a tax lot: its purchase date, shares and total cost.");
        count = Parts.secondary(bar, "");
        count.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        totals = Ui.label(area, "");
        totals.setLayoutData(Ui.fillH());
        warning = Ui.label(area, "");
        warning.setForeground(Ui.ORANGE);
        warning.setLayoutData(Ui.fillH());
        layoutLots();
        return area;
    }

    private void addRow(String acquired, double shares, double cost, String note) {
        Row r = new Row();
        r.note = note;
        r.lastShares = shares;
        r.date = new DateTime(lotsHost, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
        FormDialog.setDay(r.date, acquired);
        if (note != null) r.date.setToolTipText(note);
        r.shares = new Text(lotsHost, SWT.BORDER | SWT.RIGHT);
        r.shares.setText(HoldingsPage.trimNumber(shares));
        r.shares.setLayoutData(new GridData(110, SWT.DEFAULT));
        r.cost = new Text(lotsHost, SWT.BORDER | SWT.RIGHT);
        r.cost.setText(String.format("%.2f", cost));
        r.cost.setLayoutData(new GridData(120, SWT.DEFAULT));
        r.perShare = Parts.secondary(lotsHost, "");
        r.perShare.setLayoutData(new GridData(90, SWT.DEFAULT));
        Button remove = new Button(lotsHost, SWT.PUSH | SWT.FLAT);
        remove.setText("−");
        remove.setToolTipText("Remove this lot");
        r.controls = new Control[] { r.date, r.shares, r.cost, r.perShare, remove };
        remove.addListener(SWT.Selection, e -> {
            rows.remove(r);
            for (Control c : r.controls) c.dispose();
            layoutLots();
        });
        // Scale the lot's cost with its shares, as a partial sale or a split would.
        r.shares.addListener(SWT.Modify, e -> {
            Double v = Parts.parseAmount(r.shares.getText());
            Double c = Parts.parseAmount(r.cost.getText());
            if (v != null && v >= 0 && r.lastShares > 0 && c != null) r.cost.setText(String.format("%.2f", c * v / r.lastShares));
            if (v != null) r.lastShares = v;
            update();
        });
        r.cost.addListener(SWT.Modify, e -> update());
        rows.add(r);
    }

    private void layoutLots() {
        lotsHost.layout(true, true);
        int w = scroller.getClientArea().width;
        scroller.setMinSize(lotsHost.computeSize(Math.max(w, 400), SWT.DEFAULT));
        update();
    }

    private double shares(Row r) {
        Double v = Parts.parseAmount(r.shares.getText());
        return v == null ? 0 : Math.max(0, v);
    }

    private double cost(Row r) {
        Double v = Parts.parseAmount(r.cost.getText());
        return v == null ? 0 : Math.max(0, v);
    }

    private void update() {
        if (totals == null || totals.isDisposed()) return;
        double shares = 0, cost = 0;
        for (Row r : rows) {
            shares += shares(r);
            cost += cost(r);
            r.perShare.setText(shares(r) > 0 ? Fmt.money(cost(r) / shares(r)) : "—");
        }
        String t = HoldingsPage.trimNumber(shares) + " shares    cost " + Fmt.money(cost);
        if (price != null) t += "    value " + Fmt.money(price * shares);
        if (price != null && cost > 0) t += "    gain " + Fmt.money(price * shares - cost);
        totals.setText(t);
        count.setText(rows.size() + " lot" + (rows.size() == 1 ? "" : "s"));
        warning.setText(shares == 0 ? "⚠ No shares left: saving removes the holding." : "");
        totals.getParent().layout(true);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, "Done", true);
    }

    @Override
    protected void okPressed() {
        if (cash) {
            Double v = Parts.parseAmount(cashField.getText());
            app.port.updateCash(v == null ? 0 : Math.max(0, v));
        } else {
            List<PortfolioStore.LotRow> lots = new ArrayList<>();
            for (Row r : rows) lots.add(new PortfolioStore.LotRow(FormDialog.day(r.date), shares(r), cost(r), r.note));
            app.port.updateHolding(holding.ticker, lots);
        }
        super.okPressed();
    }
}
