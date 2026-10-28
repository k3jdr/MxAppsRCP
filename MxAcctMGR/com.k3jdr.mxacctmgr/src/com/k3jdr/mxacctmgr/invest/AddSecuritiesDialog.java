package com.k3jdr.mxacctmgr.invest;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.port.AccountsClient;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.SRMSecurity;
import com.k3jdr.mxacctmgr.ui.FormDialog;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * My Holdings ▸ Add Securities… (AddSecuritiesSheet.swift): searches the security master by ticker, name, ISIN or
 * CUSIP — stocks, funds, bond funds — 200 at a time (Show More), largest first or sorted by a column (in the database,
 * across every match); the selected security's facts; and adds shares with a tax lot (cost and purchase date).
 */
final class AddSecuritiesDialog extends Dialog {
    private static final String[][] KINDS = { { "all", "All" }, { "stocks", "Stocks" }, { "funds", "Funds" }, { "bonds", "Bond funds" } };
    private static final String[] SORTS = { "ticker", "name", "type", "sector", "exchange", "price", "asOf", "marketCap" };

    private final App app;
    private final AccountsClient.Search search = new AccountsClient.Search();
    private final List<SRMSecurity> found = new ArrayList<>();
    private int total;
    private Text query, shares, cost;
    private Combo kind;
    private Table table;
    private Label status, detail, added;
    private Button more;
    private DateTime acquired;
    private long started;

    AddSecuritiesDialog(App app) {
        super(app.shell);
        this.app = app;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Add Securities");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(1, 14, 8));
        Composite bar = Parts.row(area, 4);
        query = new Text(bar, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        query.setMessage("Ticker, name, ISIN or CUSIP");
        query.setLayoutData(Ui.fillH());
        query.addListener(SWT.DefaultSelection, e -> run(false));
        QuickHelp.help(query, "Search", "Searches the security master's active securities by ticker, name, ISIN or CUSIP. Return searches.");
        kind = new Combo(bar, SWT.READ_ONLY);
        for (String[] k : KINDS) kind.add(k[1]);
        kind.select(0);
        kind.addListener(SWT.Selection, e -> run(false));
        QuickHelp.help(kind, "Kind", "Stocks, funds, or bond and other fixed-income funds.");
        Ui.button(bar, "Search", () -> run(false));
        status = Parts.secondary(bar, "");
        GridData sg = new GridData(SWT.FILL, SWT.CENTER, false, false);
        sg.widthHint = 260;
        status.setLayoutData(sg);
        table = Parts.table(area, SWT.SINGLE, Col.l("Ticker", 80), Col.l("Name", 260), Col.l("Type", 110), Col.l("Sector", 140),
                Col.l("Exchange", 70), Col.r("Price", 85), Col.l("As of", 90), Col.r("Market value", 110));
        GridData tg = (GridData) table.getLayoutData();
        tg.heightHint = 300;
        tg.widthHint = 980;
        tg.grabExcessVerticalSpace = true;
        for (int i = 0; i < SORTS.length; i++) {
            final int col = i;
            table.getColumn(i).addListener(SWT.Selection, e -> {
                if (SORTS[col].equals(search.sort)) search.descending = !search.descending;
                else {
                    search.sort = SORTS[col];
                    search.descending = col >= 5;
                }
                table.setSortColumn(table.getColumn(col));
                table.setSortDirection(search.descending ? SWT.DOWN : SWT.UP);
                run(false);
            });
        }
        table.addListener(SWT.Selection, e -> showDetail());
        QuickHelp.help(table, "Securities", "Click any column's title to sort by it — in the database, across every match. Select a security for its sector, latest price and date, beta, market value and identifiers.");
        Composite foot = Parts.row(area, 2);
        more = Ui.button(foot, "Show More", () -> run(true));
        more.setEnabled(false);
        detail = Ui.wrap(foot, "Select a security for its details.");
        detail.setForeground(Ui.SECONDARY);
        Composite add = Parts.box(area, "Add to My Portfolio", "Shares, and a tax lot: what they cost in total (empty: at the latest price) and when they were bought.");
        Composite row = Parts.row(add, 7);
        Ui.label(row, "Shares");
        shares = new Text(row, SWT.BORDER | SWT.RIGHT);
        shares.setLayoutData(new GridData(100, SWT.DEFAULT));
        Ui.label(row, "Total cost ($)");
        cost = new Text(row, SWT.BORDER | SWT.RIGHT);
        cost.setMessage("at the price");
        cost.setLayoutData(new GridData(110, SWT.DEFAULT));
        Ui.label(row, "Bought");
        acquired = new DateTime(row, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
        Button addButton = Ui.button(row, "Add", this::add);
        QuickHelp.help(addButton, "Add", "Adds the shares to My Portfolio with a tax lot. Kept when you save (File ▸ Save, Ctrl+S).");
        added = Parts.secondary(add, "");
        added.setLayoutData(Ui.fillH());
        run(false);
        return area;
    }

    private void run(boolean append) {
        search.text = query.getText().trim();
        search.kind = KINDS[Math.max(0, kind.getSelectionIndex())][0];
        AccountsClient.Search s = search.copy();
        int offset = append ? found.size() : 0;
        started = System.currentTimeMillis();
        status.setText("Finding the securities with their latest price and market value…");
        Background.run(table, () -> AccountsClient.search(s, offset, 200, false), page -> {
            if (!append) found.clear();
            found.addAll(page.securities());
            total = page.total();
            fill();
            status.setText(found.size() + " of " + total + " shown · " + (System.currentTimeMillis() - started) + " ms");
            more.setEnabled(found.size() < total);
        }, e -> status.setText(e.getMessage()));
    }

    private void fill() {
        table.removeAll();
        for (SRMSecurity s : found) {
            TableItem it = new TableItem(table, SWT.NONE);
            it.setData(s);
            it.setText(new String[] { s.ticker(), s.name(), s.kindTitle(), s.sector() == null ? "" : s.sector(), s.mic() == null ? "" : s.mic(),
                    s.price() == null ? "—" : Parts.money2(s.price()), s.priceDate() == null ? "" : s.priceDate(),
                    s.marketCap() == null ? "" : Fmt.money(s.marketCap()) });
            if (s.price() == null) it.setForeground(5, Ui.ORANGE);
        }
    }

    private SRMSecurity selected() {
        TableItem[] sel = table.getSelection();
        return sel.length == 1 ? (SRMSecurity) sel[0].getData() : null;
    }

    private void showDetail() {
        SRMSecurity s = selected();
        if (s == null) return;
        List<String> parts = new ArrayList<>();
        parts.add(s.ticker() + " — " + s.name());
        if (s.sector() != null) parts.add(s.sector() + (s.industry() != null ? " · " + s.industry() : ""));
        parts.add(s.price() == null ? "No price yet: Load Prices after adding it" : "Price " + Parts.money2(s.price()) + (s.priceDate() != null ? " on " + s.priceDate() : ""));
        if (s.beta() != null) parts.add("beta " + Fmt.fixed(s.beta(), 2));
        if (s.marketCap() != null) parts.add("market value " + Fmt.money(s.marketCap()));
        if (s.expenseRatio() != null) parts.add("expense ratio " + Fmt.pct(s.expenseRatio(), 2));
        if (s.isin() != null) parts.add("ISIN " + s.isin());
        if (s.cusip() != null) parts.add("CUSIP " + s.cusip());
        detail.setText(Ui.escape(String.join("   ·   ", parts)));
        detail.getParent().layout(true);
    }

    private void add() {
        SRMSecurity s = selected();
        Double q = Parts.parseAmount(shares.getText());
        if (s == null || q == null || q <= 0) {
            added.setText("Select a security and enter the shares.");
            return;
        }
        Double c = Parts.parseAmount(cost.getText());
        app.port.addHolding(s, q, c, FormDialog.day(acquired));
        added.setText("Added " + HoldingsPage.trimNumber(q) + " shares of " + s.ticker() + " — saved with File ▸ Save (Ctrl+S).");
        shares.setText("");
        cost.setText("");
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Done", true);
    }
}
