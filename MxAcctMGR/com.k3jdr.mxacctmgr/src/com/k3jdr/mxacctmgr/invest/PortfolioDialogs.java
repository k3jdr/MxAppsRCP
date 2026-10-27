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
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.MainPart;
import com.k3jdr.mxacctmgr.Screen;
import com.k3jdr.mxacctmgr.port.AccountType;
import com.k3jdr.mxacctmgr.port.AccountsClient;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.Holding;
import com.k3jdr.mxacctmgr.port.Portfolio;
import com.k3jdr.mxacctmgr.port.PortfolioStyle;
import com.k3jdr.mxacctmgr.port.SRMAccountSummary;
import com.k3jdr.mxacctmgr.port.SRMBenchmarkInfo;
import com.k3jdr.mxacctmgr.port.Security;
import com.k3jdr.mxacctmgr.port.TaxLot;
import com.k3jdr.mxacctmgr.ui.Parts;

/** File ▸ Open Portfolio… and New Portfolio… (PortfolioSheets.swift): one portfolio open at a time, in the security master. */
public final class PortfolioDialogs {
    private PortfolioDialogs() {}

    public static void openPortfolio(App app) {
        new OpenDialog(app).open();
    }

    public static void newPortfolio(App app) {
        new NewDialog(app).open();
    }

    /** The personal accounts in the security master — value, version, when and by whom last saved. */
    private static final class OpenDialog extends Dialog {
        private final App app;
        private Table table;
        private Label status;
        private Button samples, closed;
        private List<SRMAccountSummary> all = List.of();

        OpenDialog(App app) {
            super(app.shell);
            this.app = app;
        }

        @Override
        protected void configureShell(Shell s) {
            super.configureShell(s);
            s.setText("Open Portfolio");
        }

        @Override
        protected boolean isResizable() { return true; }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite c = (Composite) super.createDialogArea(parent);
            Ui.wrap(c, "Portfolios kept in the security master (srm.portfolio), through the accounts service. One is open at a time.");
            Composite row = Parts.row(c, 3);
            samples = Ui.check(row, "Show the sample accounts", false, v -> fill());
            closed = Ui.check(row, "Show closed portfolios", false, v -> fill());
            status = Ui.label(row, "Reading the accounts…");
            status.setLayoutData(Ui.fillH());
            table = Parts.table(c, SWT.SINGLE, Parts.Col.l("Code", 130), Parts.Col.l("Name", 150), Parts.Col.l("Type", 80),
                    Parts.Col.l("Benchmark", 80), Parts.Col.r("Lots", 45), Parts.Col.r("Value", 100), Parts.Col.r("Version", 55),
                    Parts.Col.l("Saved", 170), Parts.Col.l("Status", 90));
            GridData gd = (GridData) table.getLayoutData();
            gd.heightHint = 300;
            gd.widthHint = 900;
            gd.grabExcessVerticalSpace = true;
            table.addListener(SWT.DefaultSelection, e -> okPressed());
            QuickHelp.help(table, "Open Portfolio", "The personal accounts in the security master. Double-click one, or select it and Open.");
            Background.run(table, AccountsClient::accountList, list -> {
                all = list;
                status.setText(list.size() + " accounts");
                fill();
            }, e -> status.setText(e.getMessage()));
            return c;
        }

        private void fill() {
            table.removeAll();
            for (SRMAccountSummary r : all) {
                if (r.isSample() && !samples.getSelection()) continue;
                if (r.closedDate() != null && !closed.getSelection()) continue;
                TableItem it = new TableItem(table, SWT.NONE);
                it.setData(r.code());
                double value = (r.investedValue() == null ? 0 : r.investedValue()) + (r.cashBalance() == null ? 0 : r.cashBalance());
                String saved = r.updatedAt() == null ? "" : r.updatedAt().substring(0, Math.min(16, r.updatedAt().length())).replace('T', ' ');
                if (r.updatedBy() != null) saved += " · " + r.updatedBy();
                it.setText(new String[] { r.code(), r.name(), r.accountType() == null ? "—" : r.accountType(),
                        r.benchmark() == null ? "—" : r.benchmark(), String.valueOf(r.lots() == null ? 0 : r.lots()), Fmt.money(value),
                        String.valueOf(r.version() == null ? 1 : r.version()), saved,
                        r.closedDate() != null ? "Closed " + r.closedDate() : r.code().equalsIgnoreCase(app.port.srmCode) ? "Open now" : "" });
            }
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.CLIENT_ID, "New Portfolio…", false);
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            createButton(parent, IDialogConstants.OK_ID, "Open", true);
        }

        @Override
        protected void buttonPressed(int id) {
            if (id == IDialogConstants.CLIENT_ID) {
                close();
                newPortfolio(app);
                return;
            }
            super.buttonPressed(id);
        }

        @Override
        protected void okPressed() {
            TableItem[] sel = table.getSelection();
            if (sel.length == 0) return;
            String code = (String) sel[0].getData();
            super.okPressed();
            MainPart.openPortfolioCode(code);
        }
    }

    /** A code, name, account type, approach and benchmark; no holdings or a copy of a sample's. */
    private static final class NewDialog extends Dialog {
        private final App app;
        private Text code, name;
        private Combo type, approach, benchmark, start;
        private Label error;
        private final List<Portfolio> starts = new ArrayList<>();

        NewDialog(App app) {
            super(app.shell);
            this.app = app;
        }

        @Override
        protected void configureShell(Shell s) {
            super.configureShell(s);
            s.setText("New Portfolio");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite c = (Composite) super.createDialogArea(parent);
            Label intro = Ui.wrap(c, "Created in the security master and opened. Its holdings, tax lots, preferences and every saved version are kept there.");
            ((GridData) intro.getLayoutData()).widthHint = 440;
            Composite g = new Composite(c, SWT.NONE);
            g.setLayout(Ui.grid(2, 0, 8));
            g.setLayoutData(Ui.fillH());
            Ui.label(g, "Code");
            code = new Text(g, SWT.BORDER);
            code.setMessage("e.g. IRA_ROLLOVER");
            code.setLayoutData(Ui.fillH());
            code.setToolTipText("Letters, digits, _ or -, up to 40: the portfolio's code in srm.portfolio");
            Ui.label(g, "Name");
            name = new Text(g, SWT.BORDER);
            name.setMessage("e.g. Rollover IRA");
            name.setLayoutData(Ui.fillH());
            Ui.label(g, "Account type");
            type = new Combo(g, SWT.READ_ONLY);
            for (AccountType t : AccountType.values()) type.add(t.title);
            type.select(0);
            Ui.label(g, "Approach");
            approach = new Combo(g, SWT.READ_ONLY);
            approach.setItems("Active", "Passive index");
            approach.select(app.active() ? 0 : 1);
            Ui.label(g, "Benchmark");
            benchmark = new Combo(g, SWT.READ_ONLY);
            for (SRMBenchmarkInfo b : app.port.srmBenchmarks) benchmark.add(b.code() + " — " + b.name());
            if (app.port.srmBenchmarks.isEmpty()) benchmark.add("SP500");
            benchmark.select(0);
            for (int i = 0; i < app.port.srmBenchmarks.size(); i++) if (app.port.srmBenchmarks.get(i).code().equals("SP500")) benchmark.select(i);
            Ui.label(g, "Start with");
            start = new Combo(g, SWT.READ_ONLY);
            start.add("No holdings");
            for (Portfolio p : app.port.portfolios()) {
                if (p.id.equals("mine") || p.holdings.isEmpty()) continue;
                starts.add(p);
                start.add("A copy of " + p.name);
            }
            start.select(0);
            error = Ui.wrap(c, "");
            error.setForeground(Ui.RED);
            return c;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            createButton(parent, IDialogConstants.OK_ID, "Create", true);
        }

        @Override
        protected void okPressed() {
            String c = code.getText().trim().toUpperCase();
            if (!c.matches("[A-Z0-9_-]{1,40}")) {
                error.setText("The code takes letters, digits, _ or -, up to 40.");
                error.getParent().layout(true);
                return;
            }
            AccountType t = AccountType.values()[type.getSelectionIndex()];
            PortfolioStyle a = approach.getSelectionIndex() == 0 ? PortfolioStyle.ACTIVE : PortfolioStyle.PASSIVE;
            String bench = app.port.srmBenchmarks.isEmpty() ? "SP500" : app.port.srmBenchmarks.get(benchmark.getSelectionIndex()).code();
            Portfolio sample = start.getSelectionIndex() > 0 ? starts.get(start.getSelectionIndex() - 1) : null;
            JsonObject body = new JsonObject();
            JsonArray lots = new JsonArray();
            double cash = 0;
            if (sample != null) {
                for (Holding h : sample.holdings) {
                    if (h.ticker.equals("CASH")) {
                        cash += h.value;
                        continue;
                    }
                    Security s = h.security();
                    if (s == null || s.srmID == null || s.srmID <= 0 || h.quantity == null || h.quantity <= 0) continue;
                    for (TaxLot l : h.lots) {
                        if (l.share <= 0) continue;
                        JsonObject lot = new JsonObject();
                        lot.addProperty("securityId", s.srmID);
                        lot.addProperty("acquired", l.acquired);
                        lot.addProperty("quantity", h.quantity * l.share);
                        lot.addProperty("costBasis", l.costBasis);
                        if (l.note != null && !l.note.isEmpty()) lot.addProperty("note", l.note);
                        lots.add(lot);
                    }
                }
            }
            body.addProperty("version", 0);
            body.addProperty("name", name.getText().isBlank() ? c : name.getText().trim());
            body.addProperty("accountType", t.code);
            body.addProperty("approach", a == PortfolioStyle.ACTIVE ? "active" : "passive");
            body.addProperty("benchmark", bench);
            body.addProperty("cash", cash);
            body.add("lots", lots);
            body.addProperty("by", "MxAcctMGR");
            body.addProperty("note", sample != null ? "New, from the sample " + sample.name : "New");
            getButton(IDialogConstants.OK_ID).setEnabled(false);
            Background.run(getShell(), () -> AccountsClient.save(c, body), saved -> {
                super.okPressed();
                app.port.requestOpen(c, () -> app.go(Screen.MY_PORTFOLIO));
            }, e -> {
                getButton(IDialogConstants.OK_ID).setEnabled(true);
                error.setText(e.getMessage());
                error.getParent().layout(true);
            });
        }
    }
}
