package com.k3jdr.mxacctmgr.bank;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.google.gson.JsonObject;
import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Settings;
import com.k3jdr.mxacctmgr.port.ServiceException;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Banking ▸ Secure Accounts (SecureAccounts.swift): the products and services you hold an account with — software, data
 * services, APIs — with the service's address and API, its status, your user name and the subscription's payment and
 * renewal (srm.secure_account through the accounts service). The product key, service key and password stay in this
 * PC's secure storage, under the node every MxAppsRCP app shares: the service key is the API key MxJobsMGR's API
 * subscriptions send. Handles mxacctmgr://secure-accounts/&lt;id&gt; and /new?name=&amp;api=&amp;url= from MxJobsMGR.
 */
public final class SecureAccountsPage extends Page {
    private static final Col[] COLUMNS = { Col.l("Product", 220), Col.l("Status", 70), Col.l("Service", 170), Col.l("User name", 150),
            Col.l("Keys", 200) };

    private List<JsonObject> accounts;
    private final Map<Integer, String> stored = new HashMap<>();
    private Composite problemHost;
    private Composite tableBox;
    private Table table;
    private Label renewing;
    private Button showInactive;
    private int sortColumn = -1;
    private boolean sortUp = true;

    public SecureAccountsPage(App app) {
        super(app);
    }

    @Override
    protected void build(Composite body) {
        problemHost = Parts.column(body, 6);
        tableBox = Parts.box(body, "Service Product Table", "The products and services you hold an account with: the product, its status, the service and your user name. Click a column's title to sort by it. Right-click a row (anywhere on it) for Edit Service Name… (names are unique) and Edit Service Details… — description, service address and API, status, your logon, the product and service keys, the subscription's payment and renewal. Keys and passwords stay in this PC's secure storage; Keys lists which are stored. A service key is also the API key MxJobsMGR's subscriptions linked to the account send.");
        Composite bar = Parts.row(tableBox, 5);
        QuickHelp.help(Ui.button(bar, "New Secure Account…", () -> details(null, null)), "New Secure Account",
                "A product or service you hold an account with: its name and description, its service's address, API and keys, your logon, and the subscription's payment and renewal.");
        showInactive = Ui.check(bar, "Show inactive and closed", false, v -> fill());
        QuickHelp.help(showInactive, "Show inactive and closed", "Also lists the inactive and closed accounts.");
        renewing = Ui.label(bar, "");
        renewing.setForeground(Ui.SECONDARY);
        renewing.setLayoutData(Ui.fillH());
        renewing.setToolTipText("The renewal amounts of the accounts listed that aren't declined");
        QuickHelp.help(renewing, "Renewing", renewing.getToolTipText());
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
            JsonObject a = selected();
            if (a != null) details(a, null);
        });
        menu();
        QuickHelp.provider(table, (c, pt) -> {
            TableItem it = table.getItem(pt);
            if (it == null || !(it.getData() instanceof JsonObject a)) return null;
            List<String> lines = new ArrayList<>();
            if (J.str(a, "description") != null) lines.add(J.str(a, "description"));
            if (J.str(a, "serviceUrl") != null) lines.add("Service: " + J.str(a, "serviceUrl"));
            if (J.str(a, "serviceApi") != null) lines.add("API: " + J.str(a, "serviceApi"));
            if (J.str(a, "renewalDate") != null || J.dbl(a, "renewalAmount") != null) {
                lines.add("Renewal: " + String.join(" · ", List.of(Parts.shortDay(J.str(a, "renewalDate")),
                        J.dbl(a, "renewalAmount") == null ? "" : Parts.money2(J.dbl(a, "renewalAmount")), renewalTitle(J.str(a, "renewalStatus", "auto")))
                        .stream().filter(s -> !s.isEmpty()).toList()));
            }
            String keys = stored.getOrDefault(J.i(a, "id"), "");
            lines.add(keys.isEmpty() ? "No keys or password stored on this PC." : "Stored on this PC: " + keys
                    + ". The service key is the API key MxJobsMGR's subscriptions linked to this account send.");
            return new QuickHelpItem(J.str(a, "name", ""), String.join("\n", lines));
        });
        Ui.setVisible(tableBox.getParent(), false);
        load();
    }

    static String renewalTitle(String raw) {
        return switch (raw) {
            case "auto" -> "Auto-renew";
            case "renew" -> "Renew";
            case "decline" -> "Decline";
            default -> raw;
        };
    }

    /** A service address's host, as the table shows it. */
    static String host(String address) {
        if (address == null || address.isBlank()) return "";
        try {
            String h = URI.create(TargetAccountsPage.address(address)).getHost();
            return h == null ? address : h;
        } catch (RuntimeException e) {
            return address;
        }
    }

    private JsonObject selected() {
        TableItem[] sel = table.getSelection();
        return sel.length == 1 && sel[0].getData() instanceof JsonObject a ? a : null;
    }

    private void menu() {
        Menu menu = new Menu(table);
        table.setMenu(menu);
        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();
            JsonObject a = selected();
            if (a == null) return;
            item(menu, "Edit Service Name…", () -> rename(a));
            item(menu, "Edit Service Details…", () -> details(a, null));
            String url = J.str(a, "serviceUrl");
            if (url != null && !url.isBlank()) {
                new MenuItem(menu, SWT.SEPARATOR);
                item(menu, "Open the Service Page", () -> Program.launch(TargetAccountsPage.address(url)));
            }
        });
    }

    private static void item(Menu menu, String text, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(Ui.escape(text));
        mi.addListener(SWT.Selection, e -> action.run());
    }

    private void load() {
        app.busy(true);
        Background.run(table, () -> {
            List<JsonObject> list = J.list(BankClient.secureAccounts(), "secureAccounts");
            Map<Integer, String> keys = new HashMap<>();
            for (JsonObject a : list) keys.put(J.i(a, "id"), KeyStore.storedKeys(J.i(a, "id")));
            return new Object[] { list, keys };
        }, out -> {
            app.busy(false);
            @SuppressWarnings("unchecked") List<JsonObject> list = (List<JsonObject>) out[0];
            @SuppressWarnings("unchecked") Map<Integer, String> keys = (Map<Integer, String>) out[1];
            accounts = list;
            stored.clear();
            stored.putAll(keys);
            Page.clear(problemHost);
            Ui.setVisible(tableBox.getParent(), true);
            fill();
            handleRequest();
        }, e -> {
            app.busy(false);
            Page.clear(problemHost);
            if (Parts.isDown(e)) Parts.serviceDown(problemHost, app, e.getMessage(), this::load);
            else Parts.warning(problemHost, e.getMessage());
            relayout();
        });
    }

    /** An mxacctmgr://secure-accounts link from MxJobsMGR, once the list is loaded. */
    private void handleRequest() {
        AppLink request = app.secureRequest;
        if (request == null) return;
        app.secureRequest = null;
        switch (request) {
            case AppLink.SecureAccount s when s.id() != null -> {
                JsonObject a = accounts.stream().filter(x -> J.i(x, "id") == s.id()).findFirst().orElse(null);
                if (a == null) {
                    Parts.warning(problemHost, "There is no secure account " + s.id() + " any more: choose another for the subscription in MxJobsMGR.");
                    relayout();
                    return;
                }
                if (!"active".equals(J.str(a, "status"))) {
                    showInactive.setSelection(true);
                    fill();
                }
                select(J.i(a, "id"));
                details(a, null);
            }
            case AppLink.NewSecureAccount n -> {
                String name = n.name() == null ? "" : n.name();
                JsonObject a = accounts.stream().filter(x -> J.str(x, "name", "").equalsIgnoreCase(name)).findFirst().orElse(null);
                if (a != null) {
                    select(J.i(a, "id"));
                    details(a, null);
                } else {
                    details(null, new String[] { name, n.api() == null ? "" : n.api(), n.url() == null ? "" : n.url() });
                }
            }
            default -> { }
        }
    }

    private void select(int id) {
        TableItem[] items = table.getItems();
        for (int i = 0; i < items.length; i++) {
            if (items[i].getData() instanceof JsonObject a && J.i(a, "id") == id) {
                table.setSelection(i);
                table.showSelection();
            }
        }
    }

    private void fill() {
        if (table.isDisposed() || accounts == null) return;
        List<JsonObject> rows = new ArrayList<>();
        for (JsonObject a : accounts) if ("active".equals(J.str(a, "status")) || showInactive.getSelection()) rows.add(a);
        double renew = rows.stream().filter(a -> !"decline".equals(J.str(a, "renewalStatus"))).mapToDouble(a -> J.num(a, "renewalAmount")).sum();
        if (sortColumn >= 0) {
            Comparator<JsonObject> c = switch (sortColumn) {
                case 0 -> Comparator.comparing(a -> J.str(a, "name", "").toLowerCase());
                case 1 -> Comparator.comparing(a -> J.str(a, "status", ""));
                case 2 -> Comparator.comparing(a -> host(J.str(a, "serviceUrl")).toLowerCase());
                case 3 -> Comparator.comparing(a -> J.str(a, "ownerUserName", "").toLowerCase());
                default -> Comparator.comparing(a -> stored.getOrDefault(J.i(a, "id"), ""));
            };
            rows.sort(sortUp ? c : c.reversed());
        }
        int keep = selected() == null ? -1 : J.i(selected(), "id");
        table.setRedraw(false);
        try {
            table.removeAll();
            for (JsonObject a : rows) {
                TableItem it = new TableItem(table, SWT.NONE);
                it.setData(a);
                String status = J.str(a, "status", "active");
                boolean active = status.equals("active");
                it.setText(new String[] { J.str(a, "name", "") + (active ? "" : "   " + status),
                        Character.toUpperCase(status.charAt(0)) + status.substring(1), host(J.str(a, "serviceUrl")), J.str(a, "ownerUserName", ""),
                        stored.getOrDefault(J.i(a, "id"), "") });
                for (int c = active ? 2 : 0; c < COLUMNS.length; c++) it.setForeground(c, Ui.SECONDARY);
                if (J.i(a, "id") == keep) table.select(table.getItemCount() - 1);
            }
        } finally {
            table.setRedraw(true);
        }
        renewing.setText("Renewing: " + Parts.money2(renew));
        Parts.rows(table, Math.min(Math.max(rows.size(), 6), Settings.tableRows()));
        relayout();
    }

    private void rename(JsonObject a) {
        new NameDialog(app.shell, "Edit Service Name", "Product name", J.str(a, "name", ""),
                accounts.stream().map(x -> J.str(x, "name", "")).toList(),
                "There is already a service named %s: names are unique, whatever their case.", null, name -> {
                    JsonObject b = new JsonObject();
                    b.addProperty("version", J.i(a, "version"));
                    b.addProperty("name", name);
                    try {
                        accounts = J.list(BankClient.saveSecure(J.i(a, "id"), b), "secureAccounts");
                        return null;
                    } catch (ServiceException e) {
                        return e.getMessage();
                    }
                }).open();
        fill();
    }

    /** New Secure Account (with a link's name, API and address when {@code draft} is given) or Edit Service Details. */
    private void details(JsonObject a, String[] draft) {
        new SecureDialog(app, a, draft, (id, body) -> {
            try {
                JsonObject answer = BankClient.saveSecure(id, body);
                accounts = J.list(answer, "secureAccounts");
                Integer saved = J.integer(answer, "id");
                return saved != null ? saved : id;
            } catch (ServiceException e) {
                throw new IllegalStateException(e.getMessage());
            }
        }).open();
        // The keys may have changed: read which are stored again (without their values).
        Background.run(table, () -> {
            Map<Integer, String> keys = new HashMap<>();
            for (JsonObject x : accounts) keys.put(J.i(x, "id"), KeyStore.storedKeys(J.i(x, "id")));
            return keys;
        }, keys -> {
            stored.clear();
            stored.putAll(keys);
            fill();
        });
    }
}
