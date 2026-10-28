package com.k3jdr.mxacctmgr.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Settings;
import com.k3jdr.mxacctmgr.ui.FormDialog;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Banking ▸ Expense Ledgers (ExpenseViews.swift): funded ledgers of purpose spending — equipment and software, a home
 * office, subscriptions, training, travel — each item on a budget line, and the transfers that fund it. Register (with
 * New item, Void / Restore and Delete), Budget, Subscriptions, Assets, Training and Travel, through the accounts
 * service; the ledger is shared, so a change made from an older copy is refused.
 */
public final class ExpenseLedgersPage extends Page {
    private static final String[][] TABS = { { "register", "Register" }, { "budget", "Budget" }, { "subscriptions", "Subscriptions" },
            { "assets", "Assets" }, { "training", "Training" }, { "travel", "Travel" } };
    private static final String[][] ITEM_KINDS = { { "expense", "Item bought" }, { "refund", "Refund" }, { "adjustment", "Adjustment" } };
    private static final String[][] RECURRENCES = { { "none", "Once" }, { "monthly", "Monthly" }, { "yearly", "Yearly" } };

    private List<JsonObject> ledgers = List.of();
    private JsonObject data;
    private String selected = Settings.PREFS.get("expenseLedger", "");
    private String tab = Settings.PREFS.get("expenseLedgerTab", "register");
    private boolean disposed, dialogOpen, busy;
    private Runnable timer;

    private Combo ledgerCombo;
    private Label message;
    private Composite problemHost, tilesHost, tabsRow, tabHost;
    private Table register;

    public ExpenseLedgersPage(App app) {
        super(app);
    }

    @Override
    protected void build(Composite body) {
        Composite bar = Parts.row(body, 4);
        Ui.label(bar, "Expense ledger:");
        ledgerCombo = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
        GridData g = new GridData(SWT.FILL, SWT.CENTER, false, false);
        g.widthHint = 320;
        ledgerCombo.setLayoutData(g);
        ledgerCombo.addListener(SWT.Selection, e -> {
            int i = ledgerCombo.getSelectionIndex();
            if (i >= 0 && i < ledgers.size()) {
                selected = J.str(ledgers.get(i), "code");
                Settings.PREFS.put("expenseLedger", selected);
                refresh();
            }
        });
        QuickHelp.help(ledgerCombo, "Expense ledger", "The ledger shown: each is funded spending for a purpose, with its budget lines and the transfers that fund it.");
        message = Ui.label(bar, "");
        message.setForeground(Ui.SECONDARY);
        message.setLayoutData(Ui.fillH());
        Button refresh = Ui.button(bar, "⟳", this::refresh);
        refresh.setToolTipText("Show what others entered since");
        QuickHelp.help(refresh, "Refresh", "Show what others entered since");
        problemHost = Parts.column(body, 6);
        tilesHost = Parts.column(body, 6);
        tabsRow = Parts.row(body, TABS.length + 1);
        for (String[] t : TABS) {
            Button b = new Button(tabsRow, SWT.TOGGLE);
            b.setText(t[1]);
            b.setData(t[0]);
            b.addListener(SWT.Selection, e -> {
                tab = t[0];
                Settings.PREFS.put("expenseLedgerTab", tab);
                showTab();
            });
            QuickHelp.help(b, t[1], "Shows the ledger's " + t[1].toLowerCase() + ".");
        }
        new Label(tabsRow, SWT.NONE);
        tabHost = Parts.column(body, 10);
        Ui.setVisible(tabsRow, false);
        reload();
        timer = () -> {
            if (disposed || ledgerCombo.isDisposed()) return;
            if (!dialogOpen && !busy && data != null) refresh();
            ledgerCombo.getDisplay().timerExec(30_000, timer);
        };
        body.getDisplay().timerExec(30_000, timer);
    }

    @Override
    public void dispose() {
        disposed = true;
        if (timer != null && !ledgerCombo.isDisposed()) ledgerCombo.getDisplay().timerExec(-1, timer);
    }

    private void reload() {
        busy = true;
        app.busy(true);
        Background.run(ledgerCombo, () -> {
            List<JsonObject> list = J.list(BankClient.ledgers(), "ledgers");
            String wanted = selected;
            String code = list.stream().anyMatch(l -> J.str(l, "code", "").equals(wanted)) ? wanted : list.isEmpty() ? "" : J.str(list.get(0), "code");
            JsonObject book = code.isEmpty() ? null : BankClient.ledger(code);
            return new Object[] { list, code, book };
        }, out -> {
            busy = false;
            app.busy(false);
            @SuppressWarnings("unchecked") List<JsonObject> list = (List<JsonObject>) out[0];
            ledgers = list;
            selected = (String) out[1];
            data = (JsonObject) out[2];
            Page.clear(problemHost);
            show();
        }, this::failed);
    }

    private void refresh() {
        if (selected.isEmpty()) return;
        String code = selected;
        Background.run(ledgerCombo, () -> BankClient.ledger(code), d -> {
            data = d;
            Page.clear(problemHost);
            show();
        }, this::failed);
    }

    private void failed(Throwable t) {
        busy = false;
        app.busy(false);
        Page.clear(problemHost);
        if (Parts.isDown(t)) {
            data = null;
            Parts.serviceDown(problemHost, app, t.getMessage(), this::reload);
        } else {
            Parts.warning(problemHost, t.getMessage());
        }
        show();
    }

    private void show() {
        if (ledgerCombo.isDisposed()) return;
        ledgerCombo.removeAll();
        for (JsonObject l : ledgers) {
            ledgerCombo.add(J.str(l, "name", ""));
            if (J.str(l, "code", "").equals(selected)) ledgerCombo.select(ledgerCombo.getItemCount() - 1);
        }
        ledgerCombo.setEnabled(!ledgers.isEmpty());
        Page.clear(tilesHost);
        boolean has = data != null;
        Ui.setVisible(tabsRow, has);
        if (has) {
            tiles();
            for (JsonObject a : J.list(data, "alerts")) {
                Label l = Ui.wrap(tilesHost, ("warning".equals(J.str(a, "level")) ? "⚠ " : "🔔 ") + J.str(a, "text", ""));
                l.setForeground("warning".equals(J.str(a, "level")) ? Ui.ORANGE : Ui.SECONDARY);
            }
        } else if (ledgers.isEmpty() && problemHost.getChildren().length == 0) {
            Composite box = Parts.box(tilesHost, "No expense ledger yet", null);
            Parts.text(box, "Add one for a purpose — a computer and its software, a home office, training, travel — with the account that funds it. (New Expense Ledger… is in MxAcctMGR for macOS.)");
        }
        showTab();
    }

    private void tiles() {
        JsonObject l = J.obj(data, "ledger");
        Composite t = Parts.tiles(tilesHost, 5);
        Double budget = J.dbl(l, "budget");
        int lines = J.list(data, "lines").size();
        Parts.tile(t, "Spent", Parts.money2(J.num(l, "spent")), budget != null ? "of a " + Parts.money2(budget) + " budget" : lines + " budget line" + (lines == 1 ? "" : "s"),
                budget != null && J.num(l, "spent") > budget ? Ui.RED : null, "Every item bought, less refunds (not void, not scheduled).");
        double scheduled = J.num(l, "scheduled");
        Parts.tile(t, "Funded", Parts.money2(J.num(l, "funded")), scheduled > 0 ? Parts.money2(scheduled) + " more scheduled" : "no transfers scheduled", null,
                "The funding transfers made so far; the scheduled ones are still to come.");
        balanceTile(t, "Balance today", J.num(l, "balance"), "Spent and not funded yet (red), or funded ahead (green): the opening balance plus every entry made so far.");
        balanceTile(t, "Projected", J.num(l, "projected"), "The balance once the scheduled transfers are made.");
        int subs = J.list(data, "subscriptions").size();
        Parts.tile(t, "Subscriptions", Parts.money2(J.num(data, "subscriptionsAnnual")), "a year, " + subs + " recurring", null,
                "What the monthly and yearly items cost a year, at their latest charge.");
    }

    private static void balanceTile(Composite t, String title, double value, String help) {
        Parts.tile(t, title, Parts.money2(Math.abs(value)), value > 0.005 ? "spent, not funded" : value < -0.005 ? "funded ahead" : "even",
                value > 0.005 ? Ui.RED : Ui.GREEN, help);
    }

    private void showTab() {
        for (var c : tabsRow.getChildren()) if (c instanceof Button b && b.getData() != null) b.setSelection(tab.equals(b.getData()));
        Page.clear(tabHost);
        register = null;
        if (data != null) {
            switch (tab) {
                case "budget" -> budget();
                case "subscriptions" -> subscriptions();
                case "assets" -> assets();
                case "training" -> training();
                case "travel" -> travel();
                default -> {
                    entryForm();
                    register();
                }
            }
        }
        relayout();
    }

    // Register

    private void entryForm() {
        Composite box = Parts.box(tabHost, "New item", "What was bought: the date, its budget line, the item, where from and what it cost. Monthly or yearly makes it a subscription; Needs approval waits for someone else to approve it. Funding transfers go in with Fund… (MxAcctMGR for macOS).");
        Composite row = Parts.row(box, 10);
        DateTime date = new DateTime(row, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
        Combo kind = new Combo(row, SWT.READ_ONLY);
        for (String[] k : ITEM_KINDS) kind.add(k[1]);
        kind.select(0);
        Combo line = new Combo(row, SWT.READ_ONLY);
        line.add("Line");
        List<JsonObject> lines = J.list(data, "lines").stream().filter(l -> J.bool(l, "active")).toList();
        for (JsonObject l : lines) line.add(J.str(l, "name", ""));
        line.select(0);
        Text item = new Text(row, SWT.BORDER);
        item.setMessage("Item");
        GridData ig = new GridData(SWT.FILL, SWT.CENTER, true, false);
        ig.widthHint = 160;
        item.setLayoutData(ig);
        Text vendor = new Text(row, SWT.BORDER);
        vendor.setMessage("Vendor");
        vendor.setLayoutData(new GridData(110, SWT.DEFAULT));
        Text amount = new Text(row, SWT.BORDER | SWT.RIGHT);
        amount.setMessage("Amount");
        amount.setLayoutData(new GridData(90, SWT.DEFAULT));
        Combo recurrence = new Combo(row, SWT.READ_ONLY);
        for (String[] r : RECURRENCES) recurrence.add(r[1]);
        recurrence.select(0);
        Button approval = Ui.check(row, "Needs approval", false, v -> {});
        Label error = new Label(box, SWT.WRAP);
        error.setForeground(Ui.RED);
        error.setLayoutData(Ui.fillH());
        Runnable enter = () -> {
            Double a = Parts.parseAmount(amount.getText());
            if (a == null || a == 0 || item.getText().isBlank()) {
                error.setText("Enter the item and its amount.");
                Page.relayout(error);
                return;
            }
            JsonObject b = new JsonObject();
            b.addProperty("date", FormDialog.day(date));
            b.addProperty("kind", ITEM_KINDS[kind.getSelectionIndex()][0]);
            b.addProperty("description", item.getText().trim());
            b.addProperty("vendor", vendor.getText().trim());
            if (line.getSelectionIndex() > 0) b.addProperty("lineId", J.i(lines.get(line.getSelectionIndex() - 1), "id"));
            else b.add("lineId", JsonNull.INSTANCE);
            b.add("tripId", JsonNull.INSTANCE);
            b.addProperty("amount", a);
            b.addProperty("recurrence", RECURRENCES[recurrence.getSelectionIndex()][0]);
            b.addProperty("approval", approval.getSelection() ? "pending" : "none");
            String code = selected;
            change(() -> BankClient.addLedgerEntry(code, b), problem -> {
                if (error.isDisposed()) return;
                error.setText(problem == null ? "" : problem);
            });
        };
        Button go = Ui.button(row, "Enter", enter);
        amount.addListener(SWT.DefaultSelection, e -> enter.run());
        QuickHelp.help(go, "Enter", "Add it to the ledger.");
        QuickHelp.help(row, "New item", "What was bought: the date, its budget line, the item, where from and what it cost.");
    }

    private static final Col[] REGISTER = { Col.l("Date", 90), Col.l("Line", 160), Col.l("Item", 240), Col.l("Vendor", 110),
            Col.r("Spent", 95), Col.r("Funded", 95), Col.l("✓", 90), Col.r("Balance", 100) };

    private void register() {
        Composite box = Parts.box(tabHost, "Register", "Every entry in date order with the running balance. Double-click to change one; the ledger is shared, so a change made from an older copy is refused. Right-click to void an entry (kept on record, out of the balance) or delete one or several for good (also the Delete key), after a confirmation.");
        Composite bar = Parts.row(box, 3);
        Label hint = Ui.label(bar, "");
        hint.setLayoutData(Ui.fillH());
        register = Parts.table(box, SWT.MULTI, REGISTER);
        Table t = register;
        Parts.rowsControl(bar, "register", () -> {
            if (!t.isDisposed()) {
                Parts.rows(t, Math.min(Math.max(t.getItemCount(), 8), Settings.tableRows()));
                relayout();
            }
        });
        List<JsonObject> entries = J.list(data, "entries");
        for (JsonObject e : entries) {
            TableItem it = new TableItem(t, SWT.NONE);
            it.setData(e);
            double amount = J.num(e, "amount");
            String status = J.str(e, "status", "actual");
            boolean isVoid = status.equals("void");
            String item = J.str(e, "description", "") + ("none".equals(J.str(e, "recurrence", "none")) ? ""
                    : "monthly".equals(J.str(e, "recurrence")) ? "  / mo" : "  / yr") + (J.str(e, "url") != null ? "  🔗" : "")
                    + (J.str(e, "notes") != null && !J.str(e, "notes").isEmpty() ? "  ✎" : "");
            List<String> marks = new ArrayList<>();
            switch (J.str(e, "approval", "none")) {
                case "pending" -> marks.add("⌛ pending");
                case "approved" -> marks.add("✓ approved");
                case "rejected" -> marks.add("✕ rejected");
                default -> { }
            }
            if (status.equals("scheduled")) marks.add("scheduled");
            if (isVoid) marks.add("void");
            if (J.integer(e, "bankTxnId") != null) marks.add("check");
            if (J.integer(e, "cardTxnId") != null) marks.add("card");
            it.setText(new String[] { Parts.shortDay(J.str(e, "date")), "funding".equals(J.str(e, "kind")) ? "Funding" : J.str(e, "line", "—"),
                    isVoid ? "(void) " + item : item, J.str(e, "vendor") != null ? J.str(e, "vendor") : J.str(e, "payee", ""),
                    amount > 0 ? Parts.money2(amount) : "", amount < 0 ? Parts.money2(-amount) : "", String.join(", ", marks),
                    isVoid ? "" : Parts.money2(Math.abs(J.num(e, "balance"))) });
            if (isVoid || status.equals("scheduled")) for (int c = 0; c < REGISTER.length; c++) it.setForeground(c, Ui.SECONDARY);
            if (amount < 0) it.setForeground(5, Ui.GREEN);
            if (!isVoid && J.num(e, "balance") > 0.005) it.setForeground(7, Ui.RED);
            Color lc = color(J.str(e, "lineColor"));
            if (lc != null) it.setForeground(1, lc);
        }
        hint.setText(entries.size() + " entries");
        Parts.rows(t, Math.min(Math.max(entries.size(), 8), Settings.tableRows()));
        t.addListener(SWT.DefaultSelection, ev -> {
            List<JsonObject> sel = selection(t);
            if (sel.size() == 1) editEntry(sel.get(0));
        });
        t.addListener(SWT.KeyDown, ev -> {
            if (ev.keyCode == SWT.DEL && (ev.stateMask & SWT.MODIFIER_MASK) == 0) {
                List<JsonObject> sel = selection(t);
                if (!sel.isEmpty()) delete(sel);
            }
        });
        Menu menu = new Menu(t);
        t.setMenu(menu);
        menu.addListener(SWT.Show, ev -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();
            List<JsonObject> chosen = selection(t);
            if (chosen.size() == 1) {
                JsonObject e = chosen.get(0);
                item(menu, "Edit Entry…", () -> editEntry(e));
                if (J.str(e, "url") != null) item(menu, "Open Link", () -> Program.launch(J.str(e, "url")));
                new MenuItem(menu, SWT.SEPARATOR);
                if (!"void".equals(J.str(e, "status"))) item(menu, "Void", () -> setStatus(e, "void"));
                else item(menu, "Restore", () -> setStatus(e, "actual"));
                if ("scheduled".equals(J.str(e, "status"))) item(menu, "Mark Made", () -> setStatus(e, "actual"));
            }
            if (!chosen.isEmpty()) {
                item(menu, chosen.size() == 1 ? "Delete Entry…" : "Delete " + chosen.size() + " Entries…", () -> delete(chosen));
                new MenuItem(menu, SWT.SEPARATOR);
                item(menu, chosen.size() == 1 ? "Copy Entry" : "Copy " + chosen.size() + " Entries", () -> TableCopy.copySelected(t));
            }
            item(menu, "Copy All Rows", () -> Clipboards.copyText(t.getDisplay(), TableCopy.rows(t, true)));
        });
        QuickHelp.provider(t, (c, p) -> {
            TableItem it = t.getItem(p);
            if (it == null || !(it.getData() instanceof JsonObject e)) return null;
            List<String> lines = new ArrayList<>();
            lines.add("Entered by " + J.str(e, "enteredBy", "—") + (J.str(e, "updatedBy") != null ? " · last changed by " + J.str(e, "updatedBy") : ""));
            if (J.str(e, "notes") != null) lines.add(J.str(e, "notes"));
            if (J.str(e, "url") != null) lines.add(J.str(e, "url"));
            if (J.str(e, "trip") != null) lines.add("Trip: " + J.str(e, "trip"));
            if (J.integer(e, "bankTxnId") != null) lines.add("In " + J.str(e, "bankAccount", "checking") + " on " + Parts.shortDay(J.str(e, "bankDate")));
            if (J.integer(e, "cardTxnId") != null) lines.add("Charged to " + J.str(e, "cardAccount", "a card"));
            return new QuickHelpItem(J.str(e, "description", ""), String.join("\n", lines));
        });
    }

    private static Color color(String hex) {
        if (hex == null || !hex.matches("#?[0-9a-fA-F]{6}")) return null;
        int v = Integer.parseInt(hex.replace("#", ""), 16);
        return new Color((v >> 16) & 255, (v >> 8) & 255, v & 255);
    }

    private static List<JsonObject> selection(Table t) {
        List<JsonObject> out = new ArrayList<>();
        for (TableItem it : t.getSelection()) if (it.getData() instanceof JsonObject e) out.add(e);
        return out;
    }

    private static void item(Menu menu, String text, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(Ui.escape(text));
        mi.addListener(SWT.Selection, e -> action.run());
    }

    private void setStatus(JsonObject e, String status) {
        JsonObject b = new JsonObject();
        b.addProperty("version", J.i(e, "version"));
        b.addProperty("status", status);
        change(() -> BankClient.saveLedgerEntry(J.i(e, "id"), b), null);
    }

    private void delete(List<JsonObject> chosen) {
        String title = chosen.size() == 1 ? "Delete “" + J.str(chosen.get(0), "description", "") + "” for good?" : "Delete " + chosen.size() + " entries for good?";
        List<String> lines = new ArrayList<>();
        if (chosen.size() == 1) {
            lines.add(Parts.shortDay(J.str(chosen.get(0), "date")) + " · " + Parts.money2(Math.abs(J.num(chosen.get(0), "amount"))));
        } else {
            double total = chosen.stream().mapToDouble(e -> Math.abs(J.num(e, "amount"))).sum();
            String first = chosen.stream().map(e -> J.str(e, "date", "")).min(String::compareTo).orElse("");
            String last = chosen.stream().map(e -> J.str(e, "date", "")).max(String::compareTo).orElse("");
            lines.add(Parts.money2(total) + " in all, " + Parts.shortDay(first) + " to " + Parts.shortDay(last));
        }
        lines.add("They're taken out of the ledger and the security master; this can't be undone. To keep an entry on record but out of the balance, Void it instead.");
        if (chosen.stream().anyMatch(e -> J.integer(e, "cardTxnId") != null || J.integer(e, "bankTxnId") != null)) {
            lines.add("The card charges and checks they're linked to stay in their registers.");
        }
        MessageDialog d = new MessageDialog(app.shell, title, null, String.join("\n\n", lines), MessageDialog.WARNING, 1,
                chosen.size() == 1 ? "Delete Entry" : "Delete " + chosen.size() + " Entries", "Cancel");
        if (d.open() != 0) return;
        String code = selected;
        change(() -> BankClient.deleteLedgerEntries(code, chosen), null);
    }

    private void editEntry(JsonObject e) {
        List<JsonObject> lines = J.list(data, "lines");
        dialogOpen = true;
        try {
            new FormDialog(app.shell, "Edit Entry", "Save") {
                private DateTime date;
                private Combo line, recurrence;
                private Text item, vendor, amount, url, notes;

                @Override
                protected void fields(Composite grid) {
                    note("Entered by " + J.str(e, "enteredBy", "—") + (J.str(e, "updatedBy") != null ? " · last changed by " + J.str(e, "updatedBy") : "")
                            + " · version " + J.i(e, "version"));
                    date = date("Date", J.str(e, "date"));
                    String[] names = new String[lines.size() + 1];
                    names[0] = "None";
                    int sel = 0;
                    for (int i = 0; i < lines.size(); i++) {
                        names[i + 1] = J.str(lines.get(i), "name", "");
                        if (J.integer(e, "lineId") != null && J.i(e, "lineId") == J.i(lines.get(i), "id")) sel = i + 1;
                    }
                    line = combo("Line", names, sel);
                    item = field("Item", J.str(e, "description", ""), null);
                    vendor = field("Vendor", J.str(e, "vendor", ""), null);
                    amount = field("Amount", String.format("%.2f", J.num(e, "amount")), "Positive is spent; negative lowers the balance (funding, refunds).");
                    int r = 0;
                    for (int i = 0; i < RECURRENCES.length; i++) if (RECURRENCES[i][0].equals(J.str(e, "recurrence", "none"))) r = i;
                    recurrence = combo("Every", new String[] { "Once", "Monthly", "Yearly" }, r);
                    url = field("Link", J.str(e, "url", ""), null);
                    notes = area("Notes", J.str(e, "notes", ""), 3);
                }

                @Override
                protected Callable<String> saveWork() {
                    Double a = Parts.parseAmount(amount.getText());
                    if (a == null) return () -> "Enter the amount.";
                    JsonObject b = new JsonObject();
                    b.addProperty("version", J.i(e, "version"));
                    b.addProperty("date", day(date));
                    if (line.getSelectionIndex() > 0) b.addProperty("lineId", J.i(lines.get(line.getSelectionIndex() - 1), "id"));
                    else b.add("lineId", JsonNull.INSTANCE);
                    b.addProperty("description", item.getText().trim());
                    b.addProperty("vendor", vendor.getText().trim());
                    b.addProperty("amount", a);
                    b.addProperty("recurrence", RECURRENCES[Math.max(0, recurrence.getSelectionIndex())][0]);
                    b.addProperty("url", url.getText().trim());
                    b.addProperty("notes", notes.getText());
                    return () -> {
                        data = BankClient.saveLedgerEntry(J.i(e, "id"), b);
                        return null;
                    };
                }
            }.open();
        } finally {
            dialogOpen = false;
        }
        show();
    }

    private void change(Callable<JsonObject> work, java.util.function.Consumer<String> done) {
        busy = true;
        app.busy(true);
        Background.run(ledgerCombo, work, d -> {
            busy = false;
            app.busy(false);
            data = d;
            message.setText("");
            show();
            if (done != null) done.accept(null);
        }, e -> {
            busy = false;
            app.busy(false);
            message.setText(Ui.escape(e.getMessage()));
            message.getParent().layout(true);
            if (done != null) done.accept(e.getMessage());
            refresh();
        });
    }

    // Budget, subscriptions, assets, training, travel

    private void budget() {
        Composite box = Parts.box(tabHost, "Budget lines", "What the spending is for: each line with its kind, budget, what was spent and what is left. Its colour marks its entries in the register.");
        List<JsonObject> lines = J.list(data, "lines");
        Parts.secondary(box, "Total " + Parts.money2(lines.stream().mapToDouble(l -> J.num(l, "spent")).sum()));
        for (JsonObject l : lines) {
            Composite row = new Composite(box, SWT.BORDER);
            row.setLayout(Ui.grid(6, 8, 4));
            row.setLayoutData(Ui.fillH());
            Label name = Ui.label(row, "●  " + J.str(l, "name", ""), Ui.bold());
            Color c = color(J.str(l, "color"));
            if (c != null) name.setForeground(c);
            if (!J.bool(l, "active")) name.setForeground(Ui.SECONDARY);
            Parts.secondary(row, J.str(l, "kind", "") + (J.str(l, "expenseAccount") != null ? "  → " + J.str(l, "expenseAccount") : ""))
                    .setLayoutData(Ui.fillH());
            Parts.secondary(row, J.i(l, "entries") + " items");
            Ui.label(row, Parts.money2(J.num(l, "spent")), Ui.bold());
            Double b = J.dbl(l, "budget");
            Parts.secondary(row, b == null ? "" : "of " + Parts.money2(b));
            Double rem = J.dbl(l, "remaining");
            Label left = Ui.label(row, rem == null ? "" : rem >= 0 ? Parts.money2(rem) + " left" : Parts.money2(-rem) + " over");
            left.setForeground(rem != null && rem < 0 ? Ui.RED : Ui.GREEN);
            if (b != null && b > 0) {
                ProgressBar bar = new ProgressBar(row, SWT.HORIZONTAL | SWT.SMOOTH);
                bar.setMaximum(1000);
                bar.setSelection((int) Math.min(1000, 1000 * J.num(l, "spent") / b));
                if (J.num(l, "spent") > b) bar.setState(SWT.ERROR);
                GridData bg = Page.span(6);
                bg.heightHint = 8;
                bar.setLayoutData(bg);
            }
            QuickHelp.help(row, J.str(l, "name", ""), "Budget line: what was spent against its budget; this year " + Parts.money2(J.num(l, "spentThisYear")) + ".");
        }
    }

    private void readOnlyTable(String title, String help, Col[] cols, List<String[]> rows) {
        Composite box = Parts.box(tabHost, title, help);
        Table t = Parts.table(box, SWT.MULTI, cols);
        for (String[] r : rows) new TableItem(t, SWT.NONE).setText(r);
        Parts.rows(t, Math.min(Math.max(rows.size(), 4), Settings.tableRows()));
        if (rows.isEmpty()) Parts.secondary(box, "None yet.");
    }

    private void subscriptions() {
        List<String[]> rows = new ArrayList<>();
        for (JsonObject s : J.list(data, "subscriptions")) {
            rows.add(new String[] { J.str(s, "description", ""), J.str(s, "line", ""), "monthly".equals(J.str(s, "recurrence")) ? "month" : "year",
                    Parts.money2(J.num(s, "amount")), Parts.money2(J.num(s, "annualCost")), Parts.shortDay(J.str(s, "lastCharged")),
                    Parts.shortDay(J.str(s, "nextRenewal")) + (J.bool(s, "unrecorded") ? "  (renewed since)" : "") });
        }
        readOnlyTable("Subscriptions", "Every monthly or yearly item at its latest charge: what it costs a year and when it renews. A date marked “renewed since” has passed with no charge recorded.",
                new Col[] { Col.l("Item", 220), Col.l("Line", 140), Col.l("Every", 60), Col.r("Charge", 85), Col.r("A year", 90), Col.l("Last charged", 95),
                        Col.l("Renews", 150) }, rows);
    }

    private void assets() {
        List<String[]> rows = new ArrayList<>();
        for (JsonObject a : J.list(data, "assets")) {
            rows.add(new String[] { J.str(a, "name", ""), String.join(" · ", List.of(J.str(a, "location", ""), J.str(a, "assignedTo", "")).stream().filter(x -> !x.isEmpty()).toList()),
                    Parts.shortDay(J.str(a, "inService")), Parts.money2(J.num(a, "cost")), J.dbl(a, "monthly") == null ? "—" : Parts.money2(J.dbl(a, "monthly")),
                    Parts.money2(J.num(a, "bookValue")), J.str(a, "warrantyEnd") == null ? "" : "to " + Parts.shortDay(J.str(a, "warrantyEnd")), J.str(a, "status", "") });
        }
        readOnlyTable("Assets", "Equipment the ledger bought: where it is and who has it, its depreciation (straight-line or declining balance over its useful life) and its warranty.",
                new Col[] { Col.l("Asset", 170), Col.l("Location", 140), Col.l("In service", 90), Col.r("Cost", 90), Col.r("Per month", 85),
                        Col.r("Book value", 95), Col.l("Warranty", 110), Col.l("Status", 80) }, rows);
    }

    private void training() {
        List<String[]> rows = new ArrayList<>();
        for (JsonObject t : J.list(data, "training")) {
            rows.add(new String[] { J.str(t, "course", ""), J.str(t, "provider", ""), J.str(t, "person", ""), J.str(t, "kind", ""),
                    String.join(" – ", List.of(Parts.shortDay(J.str(t, "startDate")), Parts.shortDay(J.str(t, "endDate"))).stream().filter(x -> !x.isEmpty()).toList()),
                    J.dbl(t, "hours") == null ? "" : String.format("%.1f", J.dbl(t, "hours")), J.dbl(t, "cost") == null ? "" : Parts.money2(J.dbl(t, "cost")),
                    J.str(t, "status", ""), J.str(t, "certificateExpires") == null ? "" : "to " + Parts.shortDay(J.str(t, "certificateExpires")) });
        }
        readOnlyTable("Training", "Courses, lessons, conferences and certifications the ledger paid for: who, the provider, dates and hours, where they stand and when a certificate expires.",
                new Col[] { Col.l("Course", 200), Col.l("Provider", 130), Col.l("Who", 80), Col.l("Kind", 90), Col.l("Dates", 170), Col.r("Hours", 56),
                        Col.r("Cost", 80), Col.l("Status", 84), Col.l("Certificate", 100) }, rows);
    }

    private void travel() {
        List<String[]> rows = new ArrayList<>();
        for (JsonObject t : J.list(data, "trips")) {
            String perDiem = J.dbl(t, "allowance") == null ? "" : Parts.money2(J.dbl(t, "allowance")) + (J.integer(t, "days") != null ? " (" + J.i(t, "days") + " days)" : "");
            rows.add(new String[] { J.str(t, "name", ""), J.str(t, "traveler", ""), J.str(t, "destination", ""),
                    String.join(" – ", List.of(Parts.shortDay(J.str(t, "departDate")), Parts.shortDay(J.str(t, "returnDate"))).stream().filter(x -> !x.isEmpty()).toList()),
                    Parts.money2(J.num(t, "spent")), perDiem, J.str(t, "status", "") });
        }
        readOnlyTable("Trips", "Trips the ledger pays for: traveler, destination, dates and per-diem allowances (meals each day, lodging each night) against what was spent on the trip.",
                new Col[] { Col.l("Trip", 180), Col.l("Traveler", 90), Col.l("Destination", 140), Col.l("Dates", 170), Col.r("Spent", 90),
                        Col.r("Per diem", 140), Col.l("Status", 80) }, rows);
    }
}
