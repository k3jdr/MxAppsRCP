package com.k3jdr.mxacctmgr.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.ui.FormDialog;
import com.k3jdr.mxacctmgr.ui.Parts;

/**
 * New Target Account… (every detail) and a row's Edit Target Details… (the details alone: the name, bank, last four
 * digits, category, usual amount and bank names stay as they are) — TargetAccounts.swift's TargetAccountSheet. The
 * password and user keys are kept in this PC's secure storage, never in the security master.
 */
final class TargetDialog extends FormDialog {
    private static final String[][] STATUSES = { { "active", "Active" }, { "inactive", "Inactive" }, { "closed", "Closed" } };

    private final JsonObject payee;
    private final List<JsonObject> categories;
    /** Saves (id 0 creates) and answers the account's id, or throws with why it couldn't. */
    private final BiFunction<Integer, JsonObject, Integer> save;
    private final boolean detailsOnly;
    private Text name, nickname, description, reference, institution, last4, amount, nextAmount, logon, user, password, keys, notes,
            bankNames, address1, address2, city, state, zip, phone;
    private Combo status, category;
    private Button due, checking, card, show;
    private DateTime nextDate;
    private Label keysHidden, fundingNote;
    private String savedPassword = "", savedKeys = "";

    TargetDialog(App app, JsonObject payee, List<JsonObject> categories, BiFunction<Integer, JsonObject, Integer> save) {
        super(app.shell, payee == null ? "New Target Account" : "Edit Target Details", payee == null ? "Add" : "Save");
        this.payee = payee;
        this.categories = categories;
        this.save = save;
        this.detailsOnly = payee != null;
    }

    @Override
    protected boolean scrolls() { return true; }

    @Override
    protected int width() { return 520; }

    private String v(String k) { return payee == null ? "" : J.str(payee, k, ""); }

    @Override
    protected void fields(Composite grid) {
        if (payee != null) note(J.str(payee, "name", ""));
        heading("Account");
        if (!detailsOnly) name = field("Account name", "", null);
        nickname = field("Nickname", v("nickname"), "The registers' target lists show it beside the name");
        description = area("Description", v("description"), 2);
        int st = 0;
        for (int i = 0; i < STATUSES.length; i++) if (STATUSES[i][0].equals(payee == null ? "active" : J.str(payee, "status", "active"))) st = i;
        status = combo("Status", new String[] { "Active", "Inactive", "Closed" }, st);
        QuickHelp.help(status, "Status", "Active; inactive or closed — kept with their entries, offered by no register.");
        reference = field("Identifier", v("reference"), "Your account or customer number with them");
        reference.setMessage("Your account or customer number with them");
        if (!detailsOnly) {
            institution = field("Bank / company", "", null);
            last4 = field("Last four digits", "", null);
            String[] cats = new String[categories.size() + 1];
            cats[0] = "None";
            for (int i = 0; i < categories.size(); i++) cats[i + 1] = J.str(categories.get(i), "name", "");
            category = combo("Category", cats, 0);
            amount = field("Usual amount", "", "Fills in the register when it is picked as an entry's target");
        }
        heading("Next payment");
        boolean hasNext = payee != null && (J.str(payee, "nextPaymentDate") != null || J.dbl(payee, "nextPaymentAmount") != null);
        due = check("A payment is due", hasNext, v -> {
            nextDate.setEnabled(v);
            nextAmount.setEnabled(v);
        });
        nextDate = date("Date", payee == null ? null : J.str(payee, "nextPaymentDate"));
        nextAmount = field("Amount", payee != null && J.dbl(payee, "nextPaymentAmount") != null ? String.format("%.2f", J.dbl(payee, "nextPaymentAmount")) : "", "Shown on the target's hover in the registers");
        nextDate.setEnabled(hasNext);
        nextAmount.setEnabled(hasNext);
        heading("Logon");
        label("Logon URL");
        Composite lrow = new Composite(grid, SWT.NONE);
        lrow.setLayout(Ui.grid(2, 0, 4));
        lrow.setLayoutData(Ui.fillH());
        logon = new Text(lrow, SWT.BORDER);
        logon.setText(v("logonUrl"));
        logon.setMessage("https://…");
        logon.setLayoutData(Ui.fillH());
        Button open = Ui.button(lrow, "Open", () -> {
            if (!logon.getText().isBlank()) Program.launch(TargetAccountsPage.address(logon.getText()));
        });
        open.setToolTipText("Open the logon page in your browser");
        label("User name");
        Composite urow = new Composite(grid, SWT.NONE);
        urow.setLayout(Ui.grid(2, 0, 4));
        urow.setLayoutData(Ui.fillH());
        user = new Text(urow, SWT.BORDER);
        user.setText(v("userName"));
        user.setLayoutData(Ui.fillH());
        Ui.button(urow, "Copy", () -> Clipboards.copyText(user.getDisplay(), user.getText())).setToolTipText("Copy the user name");
        if (payee != null) {
            int id = J.i(payee, "id");
            savedPassword = KeyStore.get(KeyStore.target(id), KeyStore.PASSWORD);
            savedKeys = KeyStore.get(KeyStore.target(id), KeyStore.USER_KEYS);
        }
        password = secret("Password", savedPassword, false);
        password.setEchoChar('●');
        Label kl = label("User keys");
        kl.setLayoutData(new GridData(SWT.END, SWT.TOP, false, false));
        Composite krow = new Composite(grid, SWT.NONE);
        krow.setLayout(Ui.grid(1, 0, 2));
        krow.setLayoutData(Ui.fillH());
        keys = new Text(krow, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        keys.setText(savedKeys);
        GridData kg = Ui.fillH();
        kg.heightHint = keys.getLineHeight() * 3 + 6;
        keys.setLayoutData(kg);
        keys.setToolTipText("Security questions' answers, recovery codes, PINs: kept in this PC's secure storage");
        keysHidden = Ui.label(krow, "");
        keysHidden.setForeground(Ui.SECONDARY);
        show = check("Show the password and user keys", false, v -> showSecrets());
        note("The password and user keys are kept in this PC's secure storage, never in the security master: other PCs, and others using the registers, don't see them.");
        showSecrets();
        heading("Notes");
        notes = area("User notes", v("notes"), 3);
        notes.setMessage("Shown on hover in the registers");
        if (!detailsOnly) {
            bankNames = field("Bank's names for it", "", "How the bank's and card companies' downloads name this account, separated by semicolons: accepting a line remembers these");
        }
        heading("Address");
        address1 = field("Address 1", v("address1"), null);
        address2 = field("Address 2", v("address2"), null);
        city = field("City", v("city"), null);
        state = field("State", v("state"), null);
        zip = field("Zip code", v("zipCode"), null);
        phone = field("Phone number", v("phone"), null);
        heading("Funding sources");
        List<String> sources = payee == null ? List.of() : J.strings(payee, "fundingSources");
        checking = check("Recipient of checking account payments", sources.contains("checking"), x -> fundingNote());
        card = check("Recipient of credit card payments", sources.contains("card"), x -> fundingNote());
        fundingNote = note("");
        fundingNote();
    }

    private void showSecrets() {
        boolean on = show.getSelection();
        password.setEchoChar(on ? '\0' : '●');
        Ui.setVisible(keys, on);
        int lines = keys.getText().isEmpty() ? 0 : (int) keys.getText().lines().count();
        keysHidden.setText(on ? "" : keys.getText().isEmpty() ? "None" : lines + " line(s), hidden");
        Ui.setVisible(keysHidden, !on);
        relayoutFields();
    }

    private void fundingNote() {
        List<String> registers = new ArrayList<>();
        if (checking.getSelection()) registers.add("Checking Accounts register");
        if (card.getSelection()) registers.add("card registers");
        fundingNote.setText(registers.isEmpty() ? "With neither, no register offers it as a target."
                : "Offered as a target in the " + String.join(" and ", registers) + ".");
    }

    @Override
    protected Callable<String> saveWork() {
        String accountName = detailsOnly ? J.str(payee, "name", "") : name.getText().trim();
        if (accountName.isEmpty()) return () -> "Enter the account's name.";
        JsonObject b = new JsonObject();
        b.addProperty("name", accountName);
        b.addProperty("nickname", nickname.getText());
        b.addProperty("description", description.getText());
        b.addProperty("status", STATUSES[Math.max(0, status.getSelectionIndex())][0]);
        b.addProperty("reference", reference.getText());
        b.addProperty("logonUrl", logon.getText());
        b.addProperty("userName", user.getText());
        b.addProperty("address1", address1.getText());
        b.addProperty("address2", address2.getText());
        b.addProperty("city", city.getText());
        b.addProperty("state", state.getText());
        b.addProperty("zipCode", zip.getText());
        b.addProperty("phone", phone.getText());
        b.addProperty("notes", notes.getText());
        JsonArray sources = new JsonArray();
        if (checking.getSelection()) sources.add("checking");
        if (card.getSelection()) sources.add("card");
        b.add("fundingSources", sources);
        b.addProperty("nextPaymentDate", due.getSelection() ? day(nextDate) : "");
        Double next = Parts.parseAmount(nextAmount.getText());
        if (due.getSelection() && next != null) b.addProperty("nextPaymentAmount", next); else b.add("nextPaymentAmount", JsonNull.INSTANCE);
        if (!detailsOnly) {
            b.addProperty("institution", institution.getText());
            b.addProperty("last4", last4.getText());
            JsonArray names = new JsonArray();
            for (String n : bankNames.getText().split(";")) if (!n.isBlank()) names.add(n.trim());
            b.add("bankNames", names);
            int c = category.getSelectionIndex();
            if (c > 0) b.addProperty("expenseAccountId", J.i(categories.get(c - 1), "id")); else b.add("expenseAccountId", JsonNull.INSTANCE);
            Double usual = Parts.parseAmount(amount.getText());
            if (usual != null) b.addProperty("defaultAmount", usual); else b.add("defaultAmount", JsonNull.INSTANCE);
        }
        if (payee != null) b.addProperty("version", J.i(payee, "version"));
        int id = payee == null ? 0 : J.i(payee, "id");
        String pw = password.getText(), uk = keys.getText();
        return () -> {
            Integer savedId;
            try {
                savedId = save.apply(id, b);
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
            if (savedId == null) return "Saved, but its password and user keys weren't: open it again to enter them.";
            String path = KeyStore.target(savedId);
            boolean kept = (pw.equals(savedPassword) || KeyStore.set(path, KeyStore.PASSWORD, pw))
                    && (uk.equals(savedKeys) || KeyStore.set(path, KeyStore.USER_KEYS, uk));
            return kept ? null : "Saved, but secure storage didn't take the password or user keys.";
        };
    }
}
