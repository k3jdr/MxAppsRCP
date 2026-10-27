package com.k3jdr.mxacctmgr.bank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonObject;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Settings;
import com.k3jdr.mxacctmgr.port.ServiceException;
import com.k3jdr.mxacctmgr.ui.FormDialog;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Banking ▸ Target Accounts (BankViews.swift's TargetAccountsView): where the registers' money goes to or comes from —
 * a utility, a card, a brokerage, a person — with its category, the registers that pay it (Funding), this year's and
 * every year's payments and the last one. The rows aren't edited in place: their context menu renames one or edits
 * its details; New Target Account…, Add from Card Registers… and New Category… are above the table.
 */
public final class TargetAccountsPage extends Page {
    private static final Col[] COLUMNS = { Col.l("Target account", 260), Col.l("Category", 150), Col.l("Funding", 120),
            Col.r("This year", 100), Col.r("Total paid", 110), Col.r("Last paid", 95) };

    private JsonObject book;
    private Composite problemHost;
    private Composite tableBox;
    private Table table;
    private Label paid;
    private Button showInactive;
    private int sortColumn = -1;
    private boolean sortUp = true;

    public TargetAccountsPage(App app) {
        super(app);
    }

    @Override
    protected void build(Composite body) {
        problemHost = Parts.column(body, 6);
        tableBox = Parts.box(body, "Target accounts", "Where the money goes to or comes from: its category, the registers that pay it (Funding: checking, credit card), this year's payments, every payment in all years (Total paid) and the last one. Click a column's title to sort by it. Right-click a row (anywhere on it) for Edit Target Account Name… (names are unique) and Edit Target Details… — nickname, status, identifier, next payment, logon, notes, address, funding sources. This year and Last paid are calculated from the registers.");
        Composite bar = Parts.row(tableBox, 8);
        QuickHelp.help(Ui.button(bar, "New Target Account…", () -> details(null)), "New Target Account",
                "Every detail of a new target account: its name, nickname, description, status, identifier, bank, last four digits, category, usual amount, next payment, logon, notes, address and funding sources.");
        Button cards = Ui.button(bar, "Add from Card Registers…", this::addFromCards);
        cards.setToolTipText("Make the credit card entries' targets (as the card company names them) target accounts the card registers offer");
        QuickHelp.help(cards, "Add from Card Registers", cards.getToolTipText());
        Button category = Ui.button(bar, "New Category…", this::newCategory);
        category.setToolTipText("A category the registers file entries under (Utilities, Insurance …): each target account is in one");
        QuickHelp.help(category, "New Category", category.getToolTipText());
        showInactive = Ui.check(bar, "Show inactive and closed", false, v -> fill());
        QuickHelp.help(showInactive, "Show inactive and closed", "Also lists the target accounts no register offers any more (inactive) and the closed ones, kept with their entries.");
        paid = Ui.label(bar, "");
        paid.setForeground(Ui.SECONDARY);
        paid.setLayoutData(Ui.fillH());
        Parts.rowsControl(bar, "table", this::fill);
        table = Parts.table(tableBox, SWT.SINGLE, COLUMNS);
        for (int i = 0; i < COLUMNS.length; i++) {
            final int col = i;
            table.getColumn(i).addListener(SWT.Selection, e -> {
                if (sortColumn == col) sortUp = !sortUp;
                else {
                    sortColumn = col;
                    sortUp = true;
                }
                table.setSortColumn(table.getColumn(col));
                table.setSortDirection(sortUp ? SWT.UP : SWT.DOWN);
                fill();
            });
        }
        table.addListener(SWT.DefaultSelection, e -> {
            JsonObject p = selected();
            if (p != null) details(p);
        });
        menu();
        QuickHelp.provider(table, (c, pt) -> {
            TableItem it = table.getItem(pt);
            if (it == null || !(it.getData() instanceof JsonObject p)) return null;
            List<String> lines = new ArrayList<>();
            List<String> sub = new ArrayList<>();
            for (String k : new String[] { "nickname", "institution" }) if (J.str(p, k) != null && !J.str(p, k).isEmpty()) sub.add(J.str(p, k));
            if (J.str(p, "last4") != null) sub.add("account ending " + J.str(p, "last4"));
            if (!sub.isEmpty()) lines.add(String.join(" · ", sub));
            if (J.str(p, "description") != null) lines.add(J.str(p, "description"));
            if (J.str(p, "notes") != null && !J.str(p, "notes").isEmpty()) lines.add(J.str(p, "notes"));
            lines.add(funding(p).isEmpty() ? "No register offers it as a target." : "Funding: " + funding(p));
            lines.add("Right-click for Edit Target Account Name… and Edit Target Details….");
            return new QuickHelpItem(J.str(p, "name", ""), String.join("\n", lines));
        });
        Ui.setVisible(tableBox.getParent(), false);
        load();
    }

    private JsonObject selected() {
        TableItem[] sel = table.getSelection();
        return sel.length == 1 && sel[0].getData() instanceof JsonObject p ? p : null;
    }

    static String funding(JsonObject p) {
        List<String> s = J.strings(p, "fundingSources");
        List<String> out = new ArrayList<>();
        if (s.contains("checking")) out.add("Checking");
        if (s.contains("card")) out.add("Credit card");
        return String.join(", ", out);
    }

    private void menu() {
        Menu menu = new Menu(table);
        table.setMenu(menu);
        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();
            JsonObject p = selected();
            if (p == null) return;
            item(menu, "Edit Target Account Name…", () -> rename(p));
            item(menu, "Edit Target Details…", () -> details(p));
            JsonObject link = J.obj(p, "link");
            String logon = J.str(p, "logonUrl");
            if ((link != null && J.str(link, "address") != null) || (logon != null && !logon.isBlank())) new MenuItem(menu, SWT.SEPARATOR);
            if (link != null && J.str(link, "address") != null) item(menu, "Open Link", () -> Program.launch(address(J.str(link, "address"))));
            if (logon != null && !logon.isBlank()) item(menu, "Open the Logon Page", () -> Program.launch(address(logon)));
        });
    }

    static String address(String a) {
        String t = a.trim();
        if (t.contains("@") && !t.contains("/")) return "mailto:" + t;
        return t.matches("(?i)^[a-z][a-z0-9+.-]*:.*") ? t : "https://" + t;
    }

    private static MenuItem item(Menu menu, String text, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(Ui.escape(text));
        mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    private void load() {
        app.busy(true);
        Background.run(table, BankClient::book, b -> {
            app.busy(false);
            book = b;
            Page.clear(problemHost);
            Ui.setVisible(tableBox.getParent(), true);
            fill();
        }, e -> {
            app.busy(false);
            Page.clear(problemHost);
            if (Parts.isDown(e)) Parts.serviceDown(problemHost, app, e.getMessage(), this::load);
            else Parts.warning(problemHost, e.getMessage());
            relayout();
        });
    }

    private void fill() {
        if (table.isDisposed() || book == null) return;
        Map<Integer, String> names = new HashMap<>();
        for (JsonObject a : J.list(book, "expenseAccounts")) names.put(J.i(a, "id"), J.str(a, "name", ""));
        List<JsonObject> rows = new ArrayList<>();
        for (JsonObject p : J.list(book, "payees")) if (J.bool(p, "active") || showInactive.getSelection()) rows.add(p);
        if (sortColumn >= 0) {
            Comparator<JsonObject> c = switch (sortColumn) {
                case 0 -> Comparator.comparing(p -> J.str(p, "name", "").toLowerCase());
                case 1 -> Comparator.comparing(p -> names.getOrDefault(J.integer(p, "expenseAccountId"), "").toLowerCase());
                case 2 -> Comparator.comparing(TargetAccountsPage::funding);
                case 3 -> Comparator.comparingDouble(p -> Math.abs(J.num(p, "yearTotal")));
                case 4 -> Comparator.comparingDouble(p -> Math.abs(J.num(p, "totalPaid")));
                default -> Comparator.comparing(p -> J.str(p, "lastPaid", ""));
            };
            rows.sort(sortUp ? c : c.reversed());
        }
        int keep = selected() == null ? -1 : J.i(selected(), "id");
        double year = 0;
        table.setRedraw(false);
        try {
            table.removeAll();
            for (JsonObject p : rows) {
                TableItem it = new TableItem(table, SWT.NONE);
                it.setData(p);
                String status = J.str(p, "status", "active");
                double y = J.num(p, "yearTotal"), total = J.num(p, "totalPaid");
                year += Math.max(0, y);
                JsonObject link = J.obj(p, "link");
                String name = J.str(p, "name", "") + (link != null && J.str(link, "address") != null ? "  🔗" : "")
                        + (status.equals("active") ? "" : "   " + status);
                it.setText(new String[] { name, names.getOrDefault(J.integer(p, "expenseAccountId"), "—"), funding(p),
                        y == 0 ? "" : Parts.money2(Math.abs(y)), total == 0 ? "" : Parts.money2(Math.abs(total)), Parts.shortDay(J.str(p, "lastPaid")) });
                if (!status.equals("active")) it.setForeground(0, Ui.SECONDARY);
                it.setForeground(1, Ui.SECONDARY);
                it.setForeground(5, Ui.SECONDARY);
                if (y < 0) it.setForeground(3, Ui.GREEN);
                if (total < 0) it.setForeground(4, Ui.GREEN);
                if (J.i(p, "id") == keep) table.select(table.getItemCount() - 1);
            }
        } finally {
            table.setRedraw(true);
        }
        paid.setText("Paid in " + J.i(book, "year") + ": " + Parts.money2(year));
        Parts.rows(table, Math.min(Math.max(rows.size(), 6), Settings.tableRows()));
        relayout();
    }

    // Changes

    private List<String> names() {
        return J.list(book, "payees").stream().map(p -> J.str(p, "name", "")).toList();
    }

    private void rename(JsonObject p) {
        new NameDialog(app.shell, "Edit Target Account Name", "Account name", J.str(p, "name", ""), names(),
                "There is already a target account named %s: names are unique, whatever their case.",
                "The registers show the new name for every entry with this target.", name -> {
                    JsonObject b = new JsonObject();
                    b.addProperty("version", J.i(p, "version"));
                    b.addProperty("name", name);
                    try {
                        book = BankClient.savePayee(J.i(p, "id"), b);
                        return null;
                    } catch (ServiceException e) {
                        return e.getMessage();
                    }
                }).open();
        fill();
    }

    private void details(JsonObject p) {
        new TargetDialog(app, p, J.list(book, "expenseAccounts"), (id, body) -> {
            try {
                book = BankClient.savePayee(id, body);
                if (id != 0) return id;
                String name = J.str(body, "name", "").toLowerCase();
                return J.list(book, "payees").stream().filter(x -> J.str(x, "name", "").equalsIgnoreCase(name)).map(x -> J.i(x, "id"))
                        .findFirst().orElse(null);
            } catch (ServiceException e) {
                throw new IllegalStateException(e.getMessage());
            }
        }).open();
        fill();
    }

    private void newCategory() {
        new FormDialog(app.shell, "New Category", "Add") {
            private Text name;
            private Combo kind;

            @Override
            protected void fields(Composite grid) {
                name = field("Name", "", "A category the registers file entries under (Utilities, Insurance …)");
                kind = combo("Kind", new String[] { "Expense", "Income", "Transfer" }, 0);
            }

            @Override
            protected Callable<String> saveWork() {
                String n = name.getText().trim();
                String k = new String[] { "expense", "income", "transfer" }[Math.max(0, kind.getSelectionIndex())];
                if (n.isEmpty()) return () -> "Enter its name.";
                JsonObject b = new JsonObject();
                b.addProperty("name", n);
                b.addProperty("kind", k);
                return () -> {
                    book = BankClient.saveExpenseAccount(0, b);
                    return null;
                };
            }
        }.open();
        fill();
    }

    /** Add from Card Registers…: the targets credit card entries carry without a target account. */
    private void addFromCards() {
        new FormDialog(app.shell, "Add from Card Registers", "Add") {
            private Table list;
            private Label count;

            @Override
            protected int width() { return 640; }

            @Override
            protected void fields(Composite grid) {
                note("The targets of credit card entries that aren't target accounts yet, as the card company names them. Those ticked become target accounts that the card registers offer, each in the category its entries mostly have, with those entries linked to it; a card line with the same name later is filed to it too. The card company's own lines (payments, interest, fees, adjustments) start unticked.");
                count = new Label(grid, SWT.NONE);
                count.setLayoutData(Page.span(2));
                list = new Table(grid, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.V_SCROLL);
                list.setHeaderVisible(true);
                for (Col c : new Col[] { Col.l("Target (as the card company names it)", 260), Col.r("Entries", 60), Col.r("Total", 95),
                        Col.l("Last", 90), Col.l("Kind", 110) }) {
                    TableColumn tc = new TableColumn(list, c.align());
                    tc.setText(c.title());
                    tc.setWidth(c.width());
                }
                org.eclipse.swt.layout.GridData gd = new org.eclipse.swt.layout.GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
                gd.heightHint = 280;
                list.setLayoutData(gd);
                list.addListener(SWT.Selection, e -> counts());
                count.setText("Reading the card registers…");
                Background.run(list, BankClient::cardTargets, targets -> {
                    for (JsonObject t : targets) {
                        TableItem it = new TableItem(list, SWT.NONE);
                        it.setData(t);
                        it.setText(new String[] { J.str(t, "name", ""), String.valueOf(J.i(t, "entries")), Parts.money2(J.num(t, "total")),
                                Parts.shortDay(J.str(t, "last")), String.join(", ", J.strings(t, "kinds")) });
                        it.setChecked(J.bool(t, "merchant"));
                    }
                    if (targets.isEmpty()) count.setText("The card registers' entries all have target accounts.");
                    else counts();
                }, e -> count.setText(e.getMessage()));
            }

            private void counts() {
                long n = java.util.Arrays.stream(list.getItems()).filter(TableItem::getChecked).count();
                count.setText(n + " of " + list.getItemCount() + " selected");
            }

            @Override
            protected Callable<String> saveWork() {
                List<String> chosen = new ArrayList<>();
                for (TableItem it : list.getItems()) if (it.getChecked()) chosen.add(J.str((JsonObject) it.getData(), "name"));
                if (chosen.isEmpty()) return null;
                return () -> {
                    BankClient.addCardTargets(chosen);
                    return null;
                };
            }

            @Override
            protected void saved() { load(); }
        }.open();
    }
}
