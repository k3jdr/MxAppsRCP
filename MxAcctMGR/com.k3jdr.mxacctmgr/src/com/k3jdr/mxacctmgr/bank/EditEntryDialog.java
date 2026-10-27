package com.k3jdr.mxacctmgr.bank;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonObject;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.ui.FormDialog;
import com.k3jdr.mxacctmgr.ui.Parts;

/**
 * Edit Entry (BankViews.swift's EditEntrySheet): a register entry's date, kind, check number, target, category, memo,
 * amount and status. The register is shared: a change made from an older copy is refused, and says so.
 */
final class EditEntryDialog extends FormDialog {
    private static final String[][] STATUSES = { { "open", "Not cleared" }, { "cleared", "Cleared" }, { "reconciled", "Reconciled" }, { "void", "Void" } };

    private final JsonObject entry;
    private final List<JsonObject> payees;
    private final List<JsonObject> categories;
    private final Function<JsonObject, String> save;
    private final Runnable after;
    private DateTime date;
    private Combo kind, target, category, status;
    private Text check, memo, amount;

    EditEntryDialog(App app, JsonObject entry, List<JsonObject> payees, List<JsonObject> categories,
                    Function<JsonObject, String> save, Runnable after) {
        super(app.shell, "Edit Entry", "Save");
        this.entry = entry;
        this.payees = payees;
        this.categories = categories;
        this.save = save;
        this.after = after;
    }

    @Override
    protected void fields(Composite grid) {
        String by = "Entered by " + J.str(entry, "enteredBy", "—");
        if (J.str(entry, "updatedBy") != null) by += " · last changed by " + J.str(entry, "updatedBy");
        note(by + " · version " + J.i(entry, "version"));
        date = date("Date", J.str(entry, "date"));
        String[] kinds = new String[Kinds.KINDS.length];
        for (int i = 0; i < kinds.length; i++) kinds[i] = Kinds.KINDS[i][1];
        kind = combo("Kind", kinds, Math.max(0, Kinds.index(J.str(entry, "kind"))));
        check = field("Check #", J.str(entry, "checkNo", ""), null);
        kind.addListener(org.eclipse.swt.SWT.Selection, e -> check.setEnabled(kindCode().equals("check")));
        check.setEnabled(kindCode().equals("check"));
        target = editableCombo("Target", payees.stream().map(p -> J.str(p, "name", "")).toArray(String[]::new), J.str(entry, "payee", ""));
        target.setToolTipText("Choose the target from the Target Accounts this register pays; a name typed here that isn't one becomes a new target account");
        String[] cats = new String[categories.size() + 1];
        cats[0] = "None";
        int selected = 0;
        Integer current = J.integer(entry, "expenseAccountId");
        for (int i = 0; i < categories.size(); i++) {
            cats[i + 1] = J.str(categories.get(i), "name", "");
            if (current != null && current == J.i(categories.get(i), "id")) selected = i + 1;
        }
        category = combo("Category", cats, selected);
        memo = field("Memo", J.str(entry, "memo", ""), null);
        amount = field("Amount", String.format("%.2f", Math.abs(J.num(entry, "amount"))), null);
        String[] st = new String[STATUSES.length];
        int s = 0;
        for (int i = 0; i < STATUSES.length; i++) {
            st[i] = STATUSES[i][1];
            if (STATUSES[i][0].equals(J.str(entry, "status"))) s = i;
        }
        status = combo("Status", st, s);
        if (J.str(entry, "bankName") != null) {
            note("Matched to PNC's line \"" + J.str(entry, "bankName") + "\" of " + Parts.shortDay(J.str(entry, "bankDate"))
                    + ". Marking it not cleared or void unmatches it.");
        }
    }

    private String kindCode() {
        int i = kind.getSelectionIndex();
        return i < 0 ? "debit" : Kinds.KINDS[i][0];
    }

    @Override
    protected Callable<String> saveWork() {
        Double a = Parts.parseAmount(amount.getText());
        if (a == null) return () -> "Enter the amount.";
        JsonObject b = new JsonObject();
        b.addProperty("date", day(date));
        b.addProperty("kind", kindCode());
        b.addProperty("payee", target.getText().trim());
        b.addProperty("memo", memo.getText());
        b.addProperty("amount", Math.abs(a));
        if (!check.getText().isBlank() && kindCode().equals("check")) b.addProperty("checkNo", check.getText().trim());
        int c = category.getSelectionIndex();
        if (c > 0) b.addProperty("expenseAccountId", J.i(categories.get(c - 1), "id"));
        b.addProperty("version", J.i(entry, "version"));
        b.addProperty("status", STATUSES[Math.max(0, status.getSelectionIndex())][0]);
        return () -> save.apply(b);
    }

    @Override
    protected void saved() {
        if (after != null) after.run();
    }
}
