package com.k3jdr.mxacctmgr.bank;

import java.util.concurrent.Callable;
import java.util.function.BiFunction;

import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.ui.FormDialog;
import com.k3jdr.mxacctmgr.ui.Parts;

/**
 * New Secure Account, or a row's Edit Service Details (SecureAccounts.swift's SecureAccountSheet): the product, its
 * service, your logon and the subscription. The details go to the security master; then the product key, service key
 * and password go to this PC's secure storage ({@link KeyStore#secure(int)}, shared with MxJobsMGR) once the account
 * has its id — each saved again only when it changed.
 */
final class SecureDialog extends FormDialog {
    private static final String[] STATUS = { "active", "inactive", "closed" };
    private static final String[] RENEWAL = { "auto", "renew", "decline" };
    private static final String[] RENEWAL_HELP = { "Renews on its own at the renewal date", "Renewed by hand at the renewal date",
            "Left to lapse at the renewal date" };

    private final JsonObject account;
    private final String[] draft;
    private final BiFunction<Integer, JsonObject, Integer> save;
    private Text name, description, serviceUrl, serviceApi, user, productKey, serviceKey, password, paymentAmount, renewalAmount;
    private Combo status, renewal;
    private Button show, paid, renews;
    private DateTime paymentDate, renewalDate;
    private String savedProduct = "", savedService = "", savedPassword = "";

    SecureDialog(App app, JsonObject account, String[] draft, BiFunction<Integer, JsonObject, Integer> save) {
        super(app.shell, account == null ? "New Secure Account" : "Edit Service Details", account == null ? "Add" : "Save");
        this.account = account;
        this.draft = draft;
        this.save = save;
    }

    @Override
    protected boolean scrolls() { return true; }

    @Override
    protected int width() { return 520; }

    private String v(String k) { return account == null ? "" : J.str(account, k, ""); }

    @Override
    protected void fields(Composite grid) {
        if (account != null) {
            note(J.str(account, "name", ""));
            int id = J.i(account, "id");
            savedProduct = KeyStore.get(KeyStore.secure(id), KeyStore.PRODUCT_KEY);
            savedService = KeyStore.get(KeyStore.secure(id), KeyStore.SERVICE_KEY);
            savedPassword = KeyStore.get(KeyStore.secure(id), KeyStore.PASSWORD);
        }
        heading("Product");
        if (account == null) name = field("Product name", draft == null ? "" : draft[0], null);
        description = area("Description", v("description"), 3);
        productKey = secret("Product key", savedProduct, false);
        heading("Service");
        label("Service URL");
        Composite row = new Composite(grid, SWT.NONE);
        row.setLayout(Ui.grid(2, 0, 4));
        row.setLayoutData(Ui.fillH());
        serviceUrl = new Text(row, SWT.BORDER);
        serviceUrl.setText(account == null ? (draft == null ? "" : draft[2]) : v("serviceUrl"));
        serviceUrl.setMessage("https://…");
        serviceUrl.setLayoutData(Ui.fillH());
        Button open = Ui.button(row, "Open", () -> {
            if (!serviceUrl.getText().isBlank()) Program.launch(TargetAccountsPage.address(serviceUrl.getText()));
        });
        open.setToolTipText("Open the service's page in your browser");
        serviceApi = field("Service API", account == null ? (draft == null ? "" : draft[1]) : v("serviceApi"), null);
        serviceApi.setMessage("Its base URL or endpoint");
        serviceKey = secret("Service key", savedService, false);
        serviceKey.setToolTipText("Also the API key MxJobsMGR sends for the API subscriptions linked to this account");
        QuickHelp.help(serviceKey, "Service key", serviceKey.getToolTipText());
        int st = 0;
        for (int i = 0; i < STATUS.length; i++) if (STATUS[i].equals(account == null ? "active" : J.str(account, "status", "active"))) st = i;
        status = combo("Service status", new String[] { "Active", "Inactive", "Closed" }, st);
        status.setToolTipText("Inactive and closed accounts are listed only with Show inactive and closed");
        heading("Owner (your user account)");
        label("User name");
        Composite urow = new Composite(grid, SWT.NONE);
        urow.setLayout(Ui.grid(2, 0, 4));
        urow.setLayoutData(Ui.fillH());
        user = new Text(urow, SWT.BORDER);
        user.setText(v("ownerUserName"));
        user.setLayoutData(Ui.fillH());
        Ui.button(urow, "Copy", () -> Clipboards.copyText(user.getDisplay(), user.getText())).setToolTipText("Copy the user name");
        password = secret("Password", savedPassword, false);
        show = check("Show the keys and password", false, v -> mask());
        note("The product key, service key and password are kept in this PC's secure storage, never in the security master: other PCs don't see them. MxJobsMGR reads the service key from there.");
        mask();
        heading("Subscription payment");
        boolean hasPayment = account != null && (J.str(account, "paymentDate") != null || J.dbl(account, "paymentAmount") != null);
        paid = check("A payment was made", hasPayment, v -> enable());
        paymentDate = date("Payment date", account == null ? null : J.str(account, "paymentDate"));
        paymentAmount = field("Payment amount", amount("paymentAmount"), null);
        heading("Subscription renewal");
        boolean hasRenewal = account != null && (J.str(account, "renewalDate") != null || J.dbl(account, "renewalAmount") != null);
        renews = check("It renews", hasRenewal, v -> enable());
        renewalDate = date("Renewal date", account == null ? null : J.str(account, "renewalDate"));
        renewalAmount = field("Renewal amount", amount("renewalAmount"), null);
        int rs = 0;
        for (int i = 0; i < RENEWAL.length; i++) if (RENEWAL[i].equals(account == null ? "auto" : J.str(account, "renewalStatus", "auto"))) rs = i;
        renewal = combo("Renewal status", new String[] { "Auto-renew", "Renew", "Decline" }, rs);
        renewal.setToolTipText(RENEWAL_HELP[rs]);
        renewal.addListener(SWT.Selection, e -> renewal.setToolTipText(RENEWAL_HELP[Math.max(0, renewal.getSelectionIndex())]));
        enable();
    }

    private String amount(String k) {
        Double d = account == null ? null : J.dbl(account, k);
        return d == null ? "" : String.format("%.2f", d);
    }

    private void enable() {
        paymentDate.setEnabled(paid.getSelection());
        paymentAmount.setEnabled(paid.getSelection());
        renewalDate.setEnabled(renews.getSelection());
        renewalAmount.setEnabled(renews.getSelection());
    }

    private void mask() {
        char c = show.getSelection() ? '\0' : '●';
        productKey.setEchoChar(c);
        serviceKey.setEchoChar(c);
        password.setEchoChar(c);
    }

    @Override
    protected Callable<String> saveWork() {
        String product = account == null ? name.getText().trim() : J.str(account, "name", "");
        if (product.isEmpty()) return () -> "Enter the product's name.";
        JsonObject b = new JsonObject();
        b.addProperty("description", description.getText());
        b.addProperty("serviceUrl", serviceUrl.getText());
        b.addProperty("serviceApi", serviceApi.getText());
        b.addProperty("status", STATUS[Math.max(0, status.getSelectionIndex())]);
        b.addProperty("ownerUserName", user.getText());
        b.addProperty("renewalStatus", RENEWAL[Math.max(0, renewal.getSelectionIndex())]);
        b.addProperty("paymentDate", paid.getSelection() ? day(paymentDate) : "");
        b.addProperty("renewalDate", renews.getSelection() ? day(renewalDate) : "");
        Double pa = Parts.parseAmount(paymentAmount.getText()), ra = Parts.parseAmount(renewalAmount.getText());
        if (paid.getSelection() && pa != null) b.addProperty("paymentAmount", pa); else b.add("paymentAmount", JsonNull.INSTANCE);
        if (renews.getSelection() && ra != null) b.addProperty("renewalAmount", ra); else b.add("renewalAmount", JsonNull.INSTANCE);
        // Details leave the name as saved (Edit Service Name changes it).
        if (account != null) b.addProperty("version", J.i(account, "version")); else b.addProperty("name", product);
        int id = account == null ? 0 : J.i(account, "id");
        String pk = productKey.getText(), sk = serviceKey.getText(), pw = password.getText();
        return () -> {
            Integer savedId;
            try {
                savedId = save.apply(id, b);
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
            if (savedId == null || savedId == 0) return "Saved, but its keys and password weren't: open it again to enter them.";
            String path = KeyStore.secure(savedId);
            boolean kept = (pk.equals(savedProduct) || KeyStore.set(path, KeyStore.PRODUCT_KEY, pk))
                    && (sk.equals(savedService) || KeyStore.set(path, KeyStore.SERVICE_KEY, sk))
                    && (pw.equals(savedPassword) || KeyStore.set(path, KeyStore.PASSWORD, pw));
            return kept ? null : "Saved, but secure storage didn't take a key or the password.";
        };
    }
}
