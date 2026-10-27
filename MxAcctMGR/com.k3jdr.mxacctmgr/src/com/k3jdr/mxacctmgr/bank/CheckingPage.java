package com.k3jdr.mxacctmgr.bank;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonObject;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Settings;
import com.k3jdr.mxacctmgr.SettingsDialog;
import com.k3jdr.mxacctmgr.port.ServiceException;
import com.k3jdr.mxacctmgr.ui.FormDialog;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Banking ▸ Checking Accounts (BankViews.swift's CheckingView): the shared check register — every check, debit and
 * deposit entered as it is written, in the paper register's order with the running balance; the bank's downloads
 * clear them, and the bank's lines left over are accepted or matched. Kept in the security master through the
 * accounts service; a change made from an older copy is refused, and the register picks up others' entries every 30
 * seconds.
 */
public final class CheckingPage extends Page {
    private List<JsonObject> accounts = List.of();
    private JsonObject register;
    private JsonObject book;
    private String selected = Settings.PREFS.get("checkingAccount", "");
    private boolean busy;
    private boolean dialogOpen;
    private boolean disposed;
    private Runnable timer;

    private Combo accountCombo;
    private Button editAccount, importButton;
    private Label message;
    private Composite problemHost, tilesHost, emptyHost, bankHost;
    private Composite entryBox, registerBox;
    private Table table;
    private Label hiddenLabel, moveText;
    private Button moveUp, moveDown, clearedButton, focusCheck, clearSort;
    private DateTime focusDate;
    // The new entry
    private DateTime entryDate;
    private Combo entryKind, entryTarget;
    private Text entryCheck, entryAmount;
    private Label entryLast4, entryError;
    private Integer entryCategory;
    // Sorting by a column (the register's own order when none)
    private int sortColumn = -1;
    private boolean sortUp = true;

    public CheckingPage(App app) {
        super(app);
    }

    @Override
    protected void build(Composite body) {
        accountBar(body);
        problemHost = Parts.column(body, 6);
        emptyHost = Parts.column(body, 6);
        tilesHost = Parts.column(body, 0);
        entryForm(body);
        registerBox(body);
        bankHost = Parts.column(body, 0);
        show();
        reload();
        timer = () -> {
            if (disposed || table.isDisposed()) return;
            if (!dialogOpen && !busy && !selected.isEmpty() && register != null) refresh();
            table.getDisplay().timerExec(30_000, timer);
        };
        body.getDisplay().timerExec(30_000, timer);
    }

    @Override
    public void dispose() {
        disposed = true;
        if (timer != null && !table.isDisposed()) table.getDisplay().timerExec(-1, timer);
    }

    // The account bar

    private void accountBar(Composite body) {
        Composite bar = Parts.row(body, 8);
        Ui.label(bar, "Account:");
        accountCombo = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
        GridData g = new GridData(SWT.FILL, SWT.CENTER, false, false);
        g.widthHint = 300;
        accountCombo.setLayoutData(g);
        accountCombo.addListener(SWT.Selection, e -> {
            int i = accountCombo.getSelectionIndex();
            if (i >= 0 && i < accounts.size()) {
                selected = J.str(accounts.get(i), "code");
                Settings.PREFS.put("checkingAccount", selected);
                refresh();
            }
        });
        QuickHelp.help(accountCombo, "Account", "The checking account whose register shows. Its register is shared: everyone who writes checks on it enters them here.");
        QuickHelp.help(Ui.button(bar, "New…", () -> accountDialog(null)), "New Checking Account", "Add the account the paper check book is for: its name and the last four digits of its number (how PNC's downloads name it).");
        editAccount = Ui.button(bar, "Edit…", () -> accountDialog(register == null ? null : J.obj(register, "account")));
        QuickHelp.help(editAccount, "Edit", "The account's name, bank, last four digits, and the day and balance its register starts with.");
        message = Ui.label(bar, "");
        message.setForeground(Ui.SECONDARY);
        message.setLayoutData(Ui.fillH());
        importButton = Ui.button(bar, "Check PNC Downloads", this::importDownloads);
        importButton.setToolTipText("Read the account's downloads from PNC Online Banking now (srm.py load pnc-bank) and match them to the register");
        QuickHelp.help(importButton, "Check PNC Downloads", importButton.getToolTipText());
        Button refresh = Ui.button(bar, "⟳", this::refresh);
        refresh.setToolTipText("Show what others entered since");
        QuickHelp.help(refresh, "Refresh", "Show what others entered since");
        new Label(bar, SWT.NONE);
    }

    // The new entry

    private void entryForm(Composite body) {
        entryBox = Parts.box(body, "New entry", "Write it as on the check book's paper register: the date, the check number (or the kind of entry), its target — the target account the money goes to or comes from, picked from those the checking register pays, with its last four digits showing — and the amount. Picking a known target fills in its usual amount and files the entry under its category; Edit Entry adds a memo or changes the category.");
        Composite row = Parts.row(entryBox, 10);
        entryDate = new DateTime(row, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
        QuickHelp.help(entryDate, "Date", "The day it was written.");
        entryKind = new Combo(row, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (String[] k : Kinds.KINDS) entryKind.add(k[1]);
        entryKind.addListener(SWT.Selection, e -> kindChanged());
        QuickHelp.help(entryKind, "Kind", "A check (with its number), a debit or card payment, a deposit, a transfer, a bank fee or interest.");
        entryCheck = new Text(row, SWT.BORDER);
        entryCheck.setMessage("Check #");
        GridData cg = new GridData(70, SWT.DEFAULT);
        entryCheck.setLayoutData(cg);
        QuickHelp.help(entryCheck, "Check #", "The check's number.");
        entryTarget = new Combo(row, SWT.DROP_DOWN);
        GridData tg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        tg.widthHint = 200;
        entryTarget.setLayoutData(tg);
        entryTarget.setToolTipText("Choose the target from the Target Accounts this register pays (with each account's last four digits); a name typed here that isn't one becomes a new target account");
        QuickHelp.help(entryTarget, "Target", entryTarget.getToolTipText());
        entryTarget.addListener(SWT.Selection, e -> {
            List<JsonObject> payees = offered();
            int i = entryTarget.getSelectionIndex();
            if (i >= 0 && i < payees.size()) {
                String name = J.str(payees.get(i), "name");
                entryTarget.getDisplay().asyncExec(() -> {
                    if (!entryTarget.isDisposed()) entryTarget.setText(name);
                    fillFromPayee();
                });
            }
        });
        entryTarget.addListener(SWT.Modify, e -> showLast4());
        entryLast4 = Ui.label(row, "        ");
        entryLast4.setForeground(Ui.SECONDARY);
        entryAmount = new Text(row, SWT.BORDER | SWT.RIGHT);
        entryAmount.setMessage("Amount");
        entryAmount.setLayoutData(new GridData(100, SWT.DEFAULT));
        entryAmount.addListener(SWT.DefaultSelection, e -> enter());
        QuickHelp.help(entryAmount, "Amount", "The amount, positive: the kind says whether it is paid or deposited. Return enters it.");
        Button enter = Ui.button(row, "Enter", this::enter);
        enter.setToolTipText("Add it to the register (Ctrl+Enter)");
        QuickHelp.help(enter, "Enter", "Add it to the register.", "Ctrl+Enter");
        Button gear = Ui.button(row, "⚙", () -> new SettingsDialog(app.shell).open());
        gear.setToolTipText("What a new entry starts with — its kind, check number and date (Settings ▸ Banking)");
        QuickHelp.help(gear, "New Entry Defaults", gear.getToolTipText());
        new Label(row, SWT.NONE);
        for (var c : new org.eclipse.swt.widgets.Control[] { entryDate, entryKind, entryCheck, entryTarget, entryAmount }) {
            c.addListener(SWT.KeyDown, e -> {
                if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.MOD1) != 0) {
                    e.doit = false;
                    enter();
                }
            });
        }
        entryError = Ui.wrap(entryBox, "");
        entryError.setForeground(Ui.RED);
        resetEntry(null);
    }

    /** A new entry as Settings ▸ Banking starts it: its kind, and the date of the entry before or today. */
    private void resetEntry(String previousDay) {
        int k = Kinds.index(Settings.newEntryKind());
        entryKind.select(k < 0 ? 1 : k);
        FormDialog.setDay(entryDate, previousDay != null && Settings.DATE_PREVIOUS.equals(Settings.newEntryDate()) ? previousDay : null);
        entryTarget.setText("");
        entryAmount.setText("");
        entryCheck.setText("");
        entryCategory = null;
        kindChanged();
    }

    private String kind() {
        int i = entryKind.getSelectionIndex();
        return i < 0 ? "debit" : Kinds.KINDS[i][0];
    }

    private void kindChanged() {
        boolean check = kind().equals("check");
        entryCheck.setEnabled(check);
        if (!check) entryCheck.setText("");
        else if (Settings.CHECK_NEXT.equals(Settings.newEntryCheckNumber()) && entryCheck.getText().isEmpty()) entryCheck.setText(nextCheck());
        entryTarget.setToolTipText(kind().equals("deposit") ? "From" : "Pay to");
    }

    /** The targets this register offers: active target accounts with checking among their funding sources. */
    private List<JsonObject> offered() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject p : J.list(book, "payees")) {
            if (J.bool(p, "active") && J.strings(p, "fundingSources").contains("checking")) out.add(p);
        }
        return out;
    }

    static String targetTitle(JsonObject p) {
        String t = J.str(p, "name", "");
        if (J.str(p, "last4") != null) t += "  ···" + J.str(p, "last4");
        String nick = J.str(p, "nickname"), inst = J.str(p, "institution");
        if (nick != null && !nick.isEmpty()) t += "  (" + nick + ")";
        else if (inst != null && !inst.isEmpty()) t += "  (" + inst + ")";
        return t;
    }

    private JsonObject chosenPayee() {
        String name = entryTarget.getText().trim();
        for (JsonObject p : offered()) if (J.str(p, "name", "").equalsIgnoreCase(name)) return p;
        return null;
    }

    private void showLast4() {
        JsonObject p = chosenPayee();
        String l4 = p == null ? null : J.str(p, "last4");
        entryLast4.setText(l4 == null ? "" : "···" + l4);
        entryLast4.setToolTipText(p == null ? "" : String.join(" · ",
                List.of(J.str(p, "institution", ""), l4 == null ? "" : "account ending " + l4).stream().filter(s -> !s.isEmpty()).toList()));
        entryLast4.getParent().layout(true);
    }

    private void fillFromPayee() {
        JsonObject p = chosenPayee();
        if (p == null) return;
        entryTarget.setText(J.str(p, "name"));
        if (entryCategory == null) entryCategory = J.integer(p, "expenseAccountId");
        if (entryAmount.getText().isBlank() && J.dbl(p, "defaultAmount") != null) entryAmount.setText(String.format("%.2f", J.dbl(p, "defaultAmount")));
        showLast4();
    }

    private void enter() {
        if (register == null) {
            entryError.setText("Choose an account");
            return;
        }
        fillFromPayee();
        Double amount = Parts.parseAmount(entryAmount.getText());
        if (amount == null || amount == 0) {
            entryError.setText("Enter the amount.");
            Page.relayout(entryError);
            return;
        }
        if (kind().equals("check") && entryCheck.getText().isBlank()) {
            entryError.setText("Enter the check number.");
            Page.relayout(entryError);
            return;
        }
        JsonObject b = new JsonObject();
        String day = FormDialog.day(entryDate);
        b.addProperty("date", day);
        b.addProperty("kind", kind());
        b.addProperty("payee", entryTarget.getText().trim());
        b.addProperty("memo", "");
        b.addProperty("amount", Math.abs(amount));
        if (!entryCheck.getText().isBlank()) b.addProperty("checkNo", entryCheck.getText().trim());
        if (entryCategory != null) b.addProperty("expenseAccountId", entryCategory);
        String code = J.str(J.obj(register, "account"), "code");
        change(() -> BankClient.add(code, b), problem -> {
            entryError.setText(problem == null ? "" : problem);
            if (problem == null) resetEntry(day);
            Page.relayout(entryError);
        });
    }

    private String nextCheck() {
        int max = -1;
        for (JsonObject e : J.list(register, "entries")) {
            try {
                String n = J.str(e, "checkNo");
                if (n != null) max = Math.max(max, Integer.parseInt(n.trim()));
            } catch (NumberFormatException ignored) {
                // a check number that isn't a number
            }
        }
        return max < 0 ? "" : String.valueOf(max + 1);
    }

    // The register

    private static final Col[] COLUMNS = { Col.r("Line", 48), Col.l("Date", 90), Col.l("Num", 60), Col.l("Target", 230),
            Col.r("Payment", 95), Col.r("Deposit", 95), Col.c("✓", 44), Col.r("Balance", 105) };

    private void registerBox(Composite body) {
        registerBox = Parts.box(body, "Register", "Every entry in the register's own order — the paper register's, line by line (Move Up / Move Down) — with the running balance in that order (over the whole register, also when earlier entries are hidden or sorted otherwise). Click a column's title to sort by it. Double-click to change one (the register is shared: a change made from an older copy is refused). Void keeps a check's number accounted for.");
        // Hide entries before …, Recent, Rows
        Composite focus = Parts.row(registerBox, 7);
        focusCheck = Ui.check(focus, "Hide entries before", Settings.PREFS.getBoolean("registerFocusOn", false), v -> {
            Settings.PREFS.putBoolean("registerFocusOn", v);
            focusDate.setEnabled(v);
            fillTable();
        });
        focusCheck.setToolTipText("Show only the recent entries — this week's or this month's payments; the balances still count every entry");
        QuickHelp.help(focusCheck, "Hide entries before", focusCheck.getToolTipText());
        focusDate = new DateTime(focus, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
        FormDialog.setDay(focusDate, focusDay());
        focusDate.setEnabled(focusCheck.getSelection());
        focusDate.addListener(SWT.Selection, e -> {
            Settings.PREFS.put("registerFocusFrom", FormDialog.day(focusDate));
            fillTable();
        });
        Button recent = new Button(focus, SWT.PUSH);
        recent.setText("Recent ▾");
        Menu recentMenu = new Menu(recent);
        recentItem(recentMenu, "Last 7 Days", LocalDate.now().minusDays(7));
        recentItem(recentMenu, "Last 30 Days", LocalDate.now().minusDays(30));
        recentItem(recentMenu, "Last 90 Days", LocalDate.now().minusDays(90));
        recentItem(recentMenu, "This Month", LocalDate.now().withDayOfMonth(1));
        recentItem(recentMenu, "This Year", LocalDate.now().withDayOfYear(1));
        new MenuItem(recentMenu, SWT.SEPARATOR);
        MenuItem all = new MenuItem(recentMenu, SWT.PUSH);
        all.setText("Show All");
        all.addListener(SWT.Selection, e -> setFocus(false, null));
        recent.addListener(SWT.Selection, e -> {
            Point p = recent.toDisplay(0, recent.getSize().y);
            recentMenu.setLocation(p);
            recentMenu.setVisible(true);
        });
        QuickHelp.help(recent, "Recent", "Show the last 7, 30 or 90 days, this month or this year — or all of the register.");
        hiddenLabel = Ui.label(focus, "");
        hiddenLabel.setForeground(Ui.SECONDARY);
        hiddenLabel.setLayoutData(Ui.fillH());
        Parts.rowsControl(focus, "register", () -> {
            Parts.rows(table, Math.min(Math.max(table.getItemCount(), 6), Settings.tableRows()));
            relayout();
        });
        // Move Up / Down, Mark Cleared
        Composite move = Parts.row(registerBox, 6);
        moveUp = Ui.button(move, "↑ Move Up", () -> moveSelected(false));
        moveDown = Ui.button(move, "↓ Move Down", () -> moveSelected(true));
        QuickHelp.help(moveUp, "Move Up", "The register keeps the paper register's order, not the dates': move an entry one line at a time. Its line is kept by the application — nothing else changes it.", "Alt+Up");
        QuickHelp.help(moveDown, "Move Down", "The register keeps the paper register's order, not the dates': move an entry one line at a time. Its line is kept by the application — nothing else changes it.", "Alt+Down");
        moveText = Ui.label(move, "");
        moveText.setForeground(Ui.SECONDARY);
        moveText.setLayoutData(Ui.fillH());
        clearSort = Ui.button(move, "Clear Sort", () -> {
            sortColumn = -1;
            table.setSortColumn(null);
            fillTable();
        });
        QuickHelp.help(clearSort, "Clear Sort", "Back to the register's own order (the paper register's), where entries can be moved.");
        clearedButton = Ui.button(move, "Mark Cleared", this::toggleCleared);
        clearedButton.setToolTipText("Select entries (Ctrl-click or Shift-click for several) and mark them cleared; when they all are, this marks them not cleared again (Ctrl+Alt+K)");
        QuickHelp.help(clearedButton, "Mark Cleared", clearedButton.getToolTipText(), "Ctrl+Alt+K");
        new Label(move, SWT.NONE);
        table = Parts.table(registerBox, SWT.MULTI, COLUMNS);
        Parts.rows(table, Settings.tableRows());
        for (int i = 0; i < COLUMNS.length; i++) {
            final int col = i;
            table.getColumn(i).addListener(SWT.Selection, e -> sortBy(col));
        }
        table.addListener(SWT.Selection, e -> updateMoveBar());
        table.addListener(SWT.DefaultSelection, e -> {
            List<JsonObject> sel = selection();
            if (sel.size() == 1) editEntry(sel.get(0));
        });
        table.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MODIFIER_MASK) == SWT.ALT && e.keyCode == SWT.ARROW_UP) {
                e.doit = false;
                moveSelected(false);
            } else if ((e.stateMask & SWT.MODIFIER_MASK) == SWT.ALT && e.keyCode == SWT.ARROW_DOWN) {
                e.doit = false;
                moveSelected(true);
            } else if ((e.stateMask & SWT.MODIFIER_MASK) == (SWT.MOD1 | SWT.MOD3) && (e.keyCode == 'k' || e.keyCode == 'K')) {
                toggleCleared();
            }
        });
        contextMenu();
        QuickHelp.provider(table, (c, p) -> {
            TableItem it = table.getItem(p);
            if (it == null || !(it.getData() instanceof JsonObject e)) {
                return new QuickHelpItem("Register", "Every entry in the register's own order with the running balance. Double-click an entry to change it; right-click for its menu.");
            }
            int col = columnAt(p.x);
            if (col == 3) return new QuickHelpItem(J.str(e, "payee", "—"), targetHover(e));
            if (col == 6) return new QuickHelpItem("✓", statusHelp(e));
            return new QuickHelpItem("Line " + J.i(e, "line") + " · " + Parts.shortDay(J.str(e, "date")), entryHelp(e));
        });
    }

    private int columnAt(int x) {
        int[] order = table.getColumnOrder();
        int left = 0;
        for (int idx : order) {
            int w = table.getColumn(idx).getWidth();
            if (x >= left && x < left + w) return idx;
            left += w;
        }
        return -1;
    }

    private void recentItem(Menu menu, String title, LocalDate from) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(title);
        mi.addListener(SWT.Selection, e -> setFocus(true, from.toString()));
    }

    private void setFocus(boolean on, String from) {
        Settings.PREFS.putBoolean("registerFocusOn", on);
        if (from != null) Settings.PREFS.put("registerFocusFrom", from);
        focusCheck.setSelection(on);
        focusDate.setEnabled(on);
        FormDialog.setDay(focusDate, focusDay());
        fillTable();
    }

    /** The day the register shows from when earlier entries are hidden (a month back until one is chosen). */
    private String focusDay() {
        String f = Settings.PREFS.get("registerFocusFrom", "");
        return f.isEmpty() ? LocalDate.now().minusMonths(1).toString() : f;
    }

    private void sortBy(int col) {
        if (sortColumn == col) sortUp = !sortUp;
        else {
            sortColumn = col;
            sortUp = true;
        }
        table.setSortColumn(table.getColumn(col));
        table.setSortDirection(sortUp ? SWT.UP : SWT.DOWN);
        fillTable();
    }

    private static Comparator<JsonObject> comparator(int col) {
        return switch (col) {
            case 0 -> Comparator.comparingInt(e -> J.i(e, "line"));
            case 1 -> Comparator.comparing(e -> J.str(e, "date", ""));
            case 2 -> Comparator.comparing(e -> J.str(e, "checkNo") != null ? J.str(e, "checkNo") : J.str(e, "kind", ""));
            case 3 -> Comparator.comparing(e -> J.str(e, "payee", "").toLowerCase());
            case 4 -> Comparator.comparingDouble(e -> J.num(e, "amount") < 0 ? -J.num(e, "amount") : 0);
            case 5 -> Comparator.comparingDouble(e -> J.num(e, "amount") > 0 ? J.num(e, "amount") : 0);
            case 6 -> Comparator.comparing(e -> J.str(e, "status", ""));
            default -> Comparator.comparingDouble(e -> J.num(e, "balance"));
        };
    }

    private void fillTable() {
        if (table.isDisposed()) return;
        List<JsonObject> entries = J.list(register, "entries");
        boolean focusOn = focusCheck.getSelection();
        String from = focusDay();
        List<JsonObject> shown = new ArrayList<>();
        for (JsonObject e : entries) if (!focusOn || J.str(e, "date", "").compareTo(from) >= 0) shown.add(e);
        int hidden = entries.size() - shown.size();
        if (sortColumn >= 0) {
            Comparator<JsonObject> c = comparator(sortColumn);
            shown.sort(sortUp ? c : c.reversed());
        }
        List<Integer> keep = new ArrayList<>();
        for (JsonObject e : selection()) keep.add(J.i(e, "id"));
        table.setRedraw(false);
        try {
            table.removeAll();
            for (JsonObject e : shown) {
                TableItem it = new TableItem(table, SWT.NONE);
                it.setData(e);
                double amount = J.num(e, "amount");
                String status = J.str(e, "status", "open");
                boolean isVoid = status.equals("void");
                String target = J.str(e, "payee", "—");
                if (J.str(e, "payeeLast4") != null) target += "  ···" + J.str(e, "payeeLast4");
                if (J.str(e, "payeeNote") != null && !J.str(e, "payeeNote").isEmpty()) target += "  ✎";
                it.setText(new String[] {
                        String.valueOf(J.i(e, "line")), Parts.shortDay(J.str(e, "date")),
                        J.str(e, "checkNo") != null ? J.str(e, "checkNo") : Kinds.label(J.str(e, "kind")),
                        isVoid ? "(void) " + target : target,
                        amount < 0 ? Parts.money2(-amount) : "", amount > 0 ? Parts.money2(amount) : "",
                        switch (status) { case "cleared" -> "✓"; case "reconciled" -> "✓✓"; case "void" -> "✕"; default -> ""; },
                        isVoid ? "" : Parts.money2(J.num(e, "balance")) });
                it.setForeground(0, Ui.SECONDARY);
                if (J.str(e, "checkNo") == null) it.setForeground(2, Ui.SECONDARY);
                if (isVoid) {
                    for (int c = 1; c < COLUMNS.length; c++) it.setForeground(c, Ui.SECONDARY);
                } else if (status.equals("open")) {
                    // An entry not cleared yet is shaded light yellow, as a pencilled line on the paper register.
                    it.setBackground(Parts.OPEN_ROW);
                }
                if (status.equals("cleared") || status.equals("reconciled")) it.setForeground(6, Ui.GREEN);
                if (!isVoid && J.num(e, "balance") < 0) it.setForeground(7, Ui.RED);
                if (keep.contains(J.i(e, "id"))) table.select(table.getItemCount() - 1);
            }
        } finally {
            table.setRedraw(true);
        }
        hiddenLabel.setText(focusOn && hidden > 0 ? hidden + " earlier entr" + (hidden == 1 ? "y" : "ies") + " hidden" : "");
        Parts.rows(table, Math.min(Math.max(shown.size(), 6), Settings.tableRows()));
        Ui.setVisible(clearSort, sortColumn >= 0);
        updateMoveBar();
        relayout();
    }

    private List<JsonObject> selection() {
        List<JsonObject> out = new ArrayList<>();
        if (table == null || table.isDisposed()) return out;
        for (TableItem it : table.getSelection()) if (it.getData() instanceof JsonObject e) out.add(e);
        return out;
    }

    private void updateMoveBar() {
        List<JsonObject> sel = selection();
        int count = J.list(register, "entries").size();
        JsonObject chosen = sel.size() == 1 ? sel.get(0) : null;
        String why = sortColumn >= 0 ? "Clear the sort to move entries: moves follow the register's own order"
                : chosen == null ? "Select one entry to move it" : null;
        moveUp.setEnabled(why == null && !busy && J.i(chosen, "line") > 1);
        moveDown.setEnabled(why == null && !busy && J.i(chosen, "line") < count);
        moveText.setText(why != null ? why : "Line " + J.i(chosen, "line") + " of " + count + ": Alt+Up and Alt+Down move it, to match the paper register");
        List<JsonObject> live = sel.stream().filter(e -> !"void".equals(J.str(e, "status"))).toList();
        List<JsonObject> open = live.stream().filter(e -> "open".equals(J.str(e, "status"))).toList();
        clearedButton.setText(live.isEmpty() ? "Mark Cleared" : !open.isEmpty() ? "Mark " + open.size() + " Cleared" : "Mark " + live.size() + " Not Cleared");
        clearedButton.setEnabled(!live.isEmpty() && !busy);
        clearedButton.getParent().layout(true);
    }

    private void contextMenu() {
        Menu menu = new Menu(table);
        table.setMenu(menu);
        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();
            List<JsonObject> chosen = selection();
            int count = J.list(register, "entries").size();
            if (chosen.size() == 1) {
                JsonObject en = chosen.get(0);
                item(menu, "Edit Entry…", () -> editEntry(en));
                if (J.integer(en, "payeeId") != null) item(menu, "Edit Note on " + J.str(en, "payee", "the Target") + "…", () -> editNote(en));
                new MenuItem(menu, SWT.SEPARATOR);
                item(menu, "Move Up", () -> move(en, false)).setEnabled(sortColumn < 0 && J.i(en, "line") > 1);
                item(menu, "Move Down", () -> move(en, true)).setEnabled(sortColumn < 0 && J.i(en, "line") < count);
                new MenuItem(menu, SWT.SEPARATOR);
                String status = J.str(en, "status", "open");
                if (status.equals("open")) item(menu, "Mark Cleared", () -> setStatus(en, "cleared"));
                if (status.equals("cleared") || status.equals("reconciled")) item(menu, "Mark Not Cleared", () -> setStatus(en, "open"));
                if (!status.equals("void")) item(menu, "Void", () -> setStatus(en, "void"));
                else item(menu, "Restore", () -> setStatus(en, "open"));
                new MenuItem(menu, SWT.SEPARATOR);
            }
            if (chosen.size() > 1) {
                List<JsonObject> live = chosen.stream().filter(x -> !"void".equals(J.str(x, "status"))).toList();
                List<JsonObject> open = live.stream().filter(x -> "open".equals(J.str(x, "status"))).toList();
                item(menu, "Mark " + open.size() + " Cleared", () -> setCleared(open, true)).setEnabled(!open.isEmpty());
                List<JsonObject> done = live.stream().filter(x -> !"open".equals(J.str(x, "status"))).toList();
                item(menu, "Mark " + done.size() + " Not Cleared", () -> setCleared(done, false)).setEnabled(!done.isEmpty());
                new MenuItem(menu, SWT.SEPARATOR);
            }
            if (!chosen.isEmpty()) {
                item(menu, chosen.size() == 1 ? "Copy Entry" : "Copy " + chosen.size() + " Entries",
                        () -> Clipboards.copyText(table.getDisplay(), text(chosen)));
            }
            item(menu, "Copy All Rows", () -> Clipboards.copyText(table.getDisplay(), com.k3jdr.mx.core.ui.TableCopy.rows(table, true)));
        });
    }

    private static MenuItem item(Menu menu, String text, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(Ui.escape(text));
        mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    /** The entries as tab-separated lines under their titles, with the category and memo the register doesn't show. */
    static String text(List<JsonObject> entries) {
        StringBuilder sb = new StringBuilder("Date\tNum\tTarget\tCategory\tMemo\tPayment\tDeposit\tStatus\tBalance");
        for (JsonObject e : entries) {
            double amount = J.num(e, "amount");
            String target = J.str(e, "payee", "") + (J.str(e, "payeeLast4") != null ? " ···" + J.str(e, "payeeLast4") : "");
            sb.append('\n').append(String.join("\t", J.str(e, "date", ""),
                    J.str(e, "checkNo") != null ? J.str(e, "checkNo") : J.str(e, "kind", ""), target, J.str(e, "expenseAccount", ""),
                    J.str(e, "memo", ""), amount < 0 ? String.format("%.2f", -amount) : "", amount > 0 ? String.format("%.2f", amount) : "",
                    J.str(e, "status", ""), String.format("%.2f", J.num(e, "balance"))));
        }
        return sb.toString();
    }

    private static String entryHelp(JsonObject e) {
        List<String> parts = new ArrayList<>();
        parts.add("Entered by " + J.str(e, "enteredBy", "—"));
        String u = J.str(e, "updatedBy");
        if (u != null && !u.equals(J.str(e, "enteredBy"))) parts.add("last changed by " + u);
        if (J.str(e, "bankName") != null) parts.add("PNC: " + J.str(e, "bankName"));
        String memo = J.str(e, "memo");
        String cat = J.str(e, "expenseAccount");
        String head = String.join(" · ", parts);
        if (cat != null) head += "\nCategory: " + cat;
        if (memo != null && !memo.isEmpty()) head += "\nMemo: " + memo;
        return head;
    }

    private static String statusHelp(JsonObject e) {
        return switch (J.str(e, "status", "open")) {
            case "cleared" -> "Cleared: the bank shows it" + (J.str(e, "bankDate") != null ? " (" + Parts.shortDay(J.str(e, "bankDate")) + ")" : "");
            case "reconciled" -> "Reconciled";
            case "void" -> "Void";
            default -> "Not cleared yet: the bank's downloads haven't shown it.";
        };
    }

    /** The target's hover text: nickname, bank and last four digits; its next payment; its note. */
    private static String targetHover(JsonObject e) {
        List<String> lines = new ArrayList<>();
        List<String> sub = new ArrayList<>();
        for (String k : new String[] { "payeeNickname", "payeeInstitution" }) if (J.str(e, k) != null && !J.str(e, k).isEmpty()) sub.add(J.str(e, k));
        if (J.str(e, "payeeLast4") != null) sub.add("account ending " + J.str(e, "payeeLast4"));
        if (!sub.isEmpty()) lines.add(String.join(" · ", sub));
        if (J.str(e, "payeeNextDate") != null || J.dbl(e, "payeeNextAmount") != null) {
            List<String> next = new ArrayList<>();
            if (J.str(e, "payeeNextDate") != null) next.add(Parts.shortDay(J.str(e, "payeeNextDate")));
            if (J.dbl(e, "payeeNextAmount") != null) next.add(Parts.money2(J.dbl(e, "payeeNextAmount")));
            lines.add("Next payment: " + String.join(" · ", next));
        }
        String note = J.str(e, "payeeNote");
        if (note != null && !note.isEmpty()) lines.add(note);
        JsonObject link = J.obj(e, "payeeLink");
        if (link != null && J.str(link, "address") != null) lines.add("Link: " + J.str(link, "address"));
        if (J.integer(e, "payeeId") != null) {
            lines.add(lines.isEmpty() ? "No note yet: the row's context menu adds one" : "(the row's context menu edits the note)");
        }
        return String.join("\n", lines);
    }

    // Showing what was read

    private void show() {
        if (problemHost.isDisposed()) return;
        boolean has = register != null;
        Ui.setVisible(entryBox.getParent(), has);
        Ui.setVisible(registerBox.getParent(), has);
        Ui.setVisible(tilesHost, has);
        Ui.setVisible(bankHost, has);
        editAccount.setEnabled(has);
        importButton.setEnabled(has && !busy);
        // Accounts
        accountCombo.removeAll();
        for (JsonObject a : accounts) {
            accountCombo.add(J.str(a, "name", "") + (J.str(a, "last4") != null ? " ···" + J.str(a, "last4") : ""));
            if (J.str(a, "code", "").equals(selected)) accountCombo.select(accountCombo.getItemCount() - 1);
        }
        accountCombo.setEnabled(!accounts.isEmpty());
        // Empty
        Page.clear(emptyHost);
        if (accounts.isEmpty() && !busy && problemHost.getChildren().length == 0 && book != null) {
            Composite box = Parts.box(emptyHost, "No checking account yet", null);
            Parts.text(box, "Add the account the paper check book is for: its name and the last four digits of its number (how PNC's downloads name it).");
            Ui.button(box, "New Checking Account…", () -> accountDialog(null));
        }
        if (has) {
            tiles(J.obj(register, "account"));
            String[] items = offered().stream().map(CheckingPage::targetTitle).toArray(String[]::new);
            String typed = entryTarget.getText();
            entryTarget.setItems(items);
            entryTarget.setText(typed);
            if (kind().equals("check") && entryCheck.getText().isEmpty() && Settings.CHECK_NEXT.equals(Settings.newEntryCheckNumber())) {
                entryCheck.setText(nextCheck());
            }
            fillTable();
            bankOnly();
        }
        relayout();
    }

    private void tiles(JsonObject a) {
        Page.clear(tilesHost);
        Composite t = Parts.tiles(tilesHost, 4);
        int open = J.i(a, "openEntries");
        Parts.tile(t, "Register balance", Parts.money2(J.num(a, "balance")), open + " entr" + (open == 1 ? "y" : "ies") + " not cleared yet", null,
                "The opening balance plus every entry not void: what the account holds once everything written has cleared.");
        Parts.tile(t, "Cleared balance", Parts.money2(J.num(a, "clearedBalance")), "entries the bank has shown", null,
                "The opening balance plus the entries the bank's downloads have matched: it should equal the bank's balance.");
        Double bank = J.dbl(a, "bankBalance");
        Color tint = bank == null ? Ui.SECONDARY : Math.abs(bank - J.num(a, "clearedBalance")) < 0.005 ? Ui.GREEN : Ui.ORANGE;
        Parts.tile(t, "Bank balance", bank == null ? "—" : Parts.money2(bank),
                J.str(a, "bankBalanceAsOf") != null ? "PNC, as of " + Parts.shortDay(J.str(a, "bankBalanceAsOf")) : "no PNC download read yet", tint,
                "The ledger balance in PNC's latest download. Green when it equals the cleared balance (reconciled).");
        int bankOnly = J.i(a, "bankOnly");
        Parts.tile(t, "From the bank", String.valueOf(bankOnly), "lines not in the register", bankOnly > 0 ? Ui.ORANGE : null,
                "The bank's lines no register entry matches yet: accept them into the register, or match each to its entry.");
    }

    private void bankOnly() {
        Page.clear(bankHost);
        List<JsonObject> lines = J.list(register, "bankOnly");
        if (lines.isEmpty()) return;
        Composite box = Parts.box(bankHost, "From PNC, not in the register", "Lines in the bank's downloads that no register entry matches: fees, card debits, automatic payments, or an entry written with a different amount. Accept adds the line to the register (cleared); Match… ties it to the entry it is.");
        Composite g = new Composite(box, SWT.NONE);
        g.setLayout(Ui.grid(6, 0, 6));
        g.setLayoutData(Ui.fillH());
        for (JsonObject l : lines) {
            Ui.label(g, Parts.shortDay(J.str(l, "date")));
            Label kind = Ui.label(g, J.str(l, "checkNo") != null ? "#" + J.str(l, "checkNo") : J.str(l, "type", ""));
            kind.setForeground(Ui.SECONDARY);
            String name = J.str(l, "name", "—");
            String payee = J.str(l, "payee");
            Label n = Ui.label(g, payee != null && !payee.equalsIgnoreCase(name) ? name + "   (target " + payee + ")" : name);
            n.setLayoutData(Ui.fillH());
            double amount = J.num(l, "amount");
            Label a = Ui.label(g, Parts.money2(amount));
            a.setForeground(amount > 0 ? Ui.GREEN : amount < 0 ? Ui.RED : Ui.SECONDARY);
            Button accept = Ui.button(g, "Accept", () -> change(() -> BankClient.accept(J.i(l, "id")), null));
            accept.setToolTipText("Add this line to the register as a cleared entry (its target found by the bank's name for it)");
            QuickHelp.help(accept, "Accept", accept.getToolTipText());
            Button match = Ui.button(g, "Match…", () -> matchDialog(l));
            match.setToolTipText("Tie this line to the register entry it is (an amount or date written differently)");
            QuickHelp.help(match, "Match…", match.getToolTipText());
        }
        Ui.background(g, Parts.BOX_BG);
    }

    // Loading and saving

    private void reload() {
        setBusy(true);
        Background.run(table, () -> {
            List<JsonObject> list = BankClient.accounts();
            JsonObject b;
            try {
                b = BankClient.book();
            } catch (ServiceException e) {
                b = new JsonObject();
            }
            String wanted = selected;
            String code = list.stream().anyMatch(a -> J.str(a, "code", "").equals(wanted)) ? wanted
                    : list.isEmpty() ? "" : J.str(list.get(0), "code");
            JsonObject r = code.isEmpty() ? null : BankClient.register(code);
            return new Object[] { list, b, code, r };
        }, out -> {
            setBusy(false);
            @SuppressWarnings("unchecked") List<JsonObject> list = (List<JsonObject>) out[0];
            accounts = list;
            book = (JsonObject) out[1];
            selected = (String) out[2];
            register = (JsonObject) out[3];
            Page.clear(problemHost);
            show();
        }, this::failed);
    }

    private void refresh() {
        if (selected.isEmpty()) {
            register = null;
            show();
            return;
        }
        String code = selected;
        Background.run(table, () -> BankClient.register(code), r -> {
            register = r;
            Page.clear(problemHost);
            show();
        }, this::failed);
    }

    private void failed(Throwable t) {
        setBusy(false);
        Page.clear(problemHost);
        if (Parts.isDown(t)) {
            register = null;
            Parts.serviceDown(problemHost, app, t.getMessage(), this::reload);
        } else {
            Parts.warning(problemHost, t.getMessage());
        }
        show();
    }

    private void setBusy(boolean on) {
        busy = on;
        if (!importButton.isDisposed()) importButton.setEnabled(!on && register != null);
        app.busy(on);
    }

    /**
     * Runs a change that answers with the register; a refusal (someone changed it since) reloads it and says why.
     * {@code done} gets null on success, else the problem.
     */
    private void change(Callable<JsonObject> work, java.util.function.Consumer<String> done) {
        setBusy(true);
        Background.run(table, () -> {
            JsonObject r = work.call();
            JsonObject b;
            try {
                b = BankClient.book();
            } catch (ServiceException e) {
                b = null;
            }
            return new JsonObject[] { r, b };
        }, out -> {
            setBusy(false);
            register = out[0];
            if (out[1] != null) book = out[1];
            message.setText("");
            show();
            if (done != null) done.accept(null);
        }, e -> {
            setBusy(false);
            message.setText(Ui.escape(e.getMessage()));
            message.getParent().layout(true);
            refresh();
            if (done != null) done.accept(e.getMessage());
        });
    }

    private void moveSelected(boolean down) {
        List<JsonObject> sel = selection();
        if (sel.size() != 1 || sortColumn >= 0 || busy) return;
        move(sel.get(0), down);
    }

    private void move(JsonObject e, boolean down) {
        change(() -> BankClient.move(J.i(e, "id"), down), null);
    }

    private void setStatus(JsonObject e, String status) {
        JsonObject b = new JsonObject();
        b.addProperty("version", J.i(e, "version"));
        b.addProperty("status", status);
        change(() -> BankClient.update(J.i(e, "id"), b), null);
    }

    private void setCleared(List<JsonObject> entries, boolean cleared) {
        if (register == null || entries.isEmpty()) return;
        String code = J.str(J.obj(register, "account"), "code");
        change(() -> BankClient.setStatus(code, entries, cleared ? "cleared" : "open"), null);
    }

    /** Marks the selected entries cleared — or, when every one of them is cleared already, not cleared. */
    private void toggleCleared() {
        List<JsonObject> live = selection().stream().filter(e -> !"void".equals(J.str(e, "status"))).toList();
        List<JsonObject> open = live.stream().filter(e -> "open".equals(J.str(e, "status"))).toList();
        if (live.isEmpty()) return;
        if (!open.isEmpty()) setCleared(open, true); else setCleared(live, false);
    }

    private void importDownloads() {
        if (register == null) return;
        String code = J.str(J.obj(register, "account"), "code");
        setBusy(true);
        Background.run(table, () -> BankClient.importDownloads(code), r -> {
            setBusy(false);
            register = r;
            JsonObject load = J.obj(r, "load");
            message.setText(load != null && J.str(load, "message") != null ? Ui.escape("PNC downloads: " + J.str(load, "message")) : "");
            show();
        }, e -> {
            setBusy(false);
            message.setText(Ui.escape(e.getMessage()));
            message.getParent().layout(true);
        });
    }

    // Dialogs

    private void open(FormDialog d) {
        dialogOpen = true;
        try {
            d.open();
        } finally {
            dialogOpen = false;
        }
    }

    private void editEntry(JsonObject e) {
        open(new EditEntryDialog(app, e, offered(), J.list(book, "expenseAccounts"), body -> {
            try {
                register = BankClient.update(J.i(e, "id"), body);
                return null;
            } catch (ServiceException ex) {
                return ex.getMessage();
            }
        }, this::refreshAfterDialog));
    }

    private void refreshAfterDialog() {
        show();
        Background.run(table, BankClient::book, b -> {
            book = b;
            show();
        }, e -> {});
    }

    private void editNote(JsonObject e) {
        Integer payee = J.integer(e, "payeeId");
        if (payee == null) return;
        open(new FormDialog(app.shell, J.str(e, "payee", "Target"), "Save") {
            private Text note;

            @Override
            protected void fields(Composite grid) {
                String sub = targetHover(e).lines().findFirst().orElse("");
                if (!sub.isEmpty()) note(sub);
                note = area("Note", J.str(e, "payeeNote", ""), 5);
                note("Where the money flows: shown on hover for every entry with this target.");
            }

            @Override
            protected Callable<String> saveWork() {
                String text = note.getText();
                return () -> {
                    BankClient.savePayeeNote(payee, text);
                    return null;
                };
            }

            @Override
            protected void saved() { refresh(); }
        });
    }

    private void accountDialog(JsonObject account) {
        open(new FormDialog(app.shell, account == null ? "New Checking Account" : "Edit " + J.str(account, "name"), account == null ? "Add" : "Save") {
            private Text code, name, institution, last4, balance;
            private DateTime opening;

            @Override
            protected void fields(Composite grid) {
                code = field("Code", account == null ? "PNC_CHECKING" : J.str(account, "code"), "A short name for it: letters, digits, _ or - (PNC_CHECKING)");
                code.setEnabled(account == null);
                name = field("Name", account == null ? "PNC Checking" : J.str(account, "name"), null);
                institution = field("Bank", account == null ? "PNC" : J.str(account, "institution"), null);
                last4 = field("Last four digits", account == null ? "" : J.str(account, "last4"), "How PNC's downloads name the account. Never the whole account number.");
                opening = date("Register starts", account == null ? null : J.str(account, "openingDate"));
                balance = field("Balance then", account == null ? "0.00" : String.format("%.2f", J.num(account, "openingBalance")), null);
                note("The register starts on that day with that balance: entries and bank lines before it are left out of the balances.");
            }

            @Override
            protected Callable<String> saveWork() {
                String c = code.getText().trim().toUpperCase();
                if (c.isEmpty() || name.getText().isBlank()) {
                    showError("Enter its code and name.");
                    return () -> "Enter its code and name.";
                }
                JsonObject b = new JsonObject();
                b.addProperty("name", name.getText().trim());
                b.addProperty("institution", institution.getText().trim());
                b.addProperty("last4", last4.getText().trim());
                b.addProperty("openingDate", day(opening));
                Double bal = Parts.parseAmount(balance.getText());
                b.addProperty("openingBalance", bal == null ? 0 : bal);
                return () -> {
                    accounts = BankClient.saveAccount(c, b);
                    selected = c;
                    Settings.PREFS.put("checkingAccount", c);
                    return null;
                };
            }

            @Override
            protected void saved() { refresh(); }
        });
    }

    private void matchDialog(JsonObject line) {
        List<JsonObject> candidates = new ArrayList<>(J.list(register, "entries").stream()
                .filter(e -> J.integer(e, "bankLineId") == null && "open".equals(J.str(e, "status"))).toList());
        double amount = J.num(line, "amount");
        candidates.sort(Comparator.comparingDouble(e -> Math.abs(J.num(e, "amount") - amount)));
        open(new FormDialog(app.shell, "Match PNC's Line", "Match") {
            private Table list;

            @Override
            protected void fields(Composite grid) {
                note(Parts.shortDay(J.str(line, "date")) + " · " + J.str(line, "name", "—") + " · " + Parts.money2(amount));
                note("The register entry it is (entries not cleared yet, closest amount first):");
                list = new Table(grid, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
                list.setHeaderVisible(true);
                for (Col c : new Col[] { Col.l("Date", 90), Col.l("Num", 60), Col.l("Target", 200), Col.r("Amount", 100) }) {
                    TableColumn tc = new TableColumn(list, c.align());
                    tc.setText(c.title());
                    tc.setWidth(c.width());
                }
                for (JsonObject e : candidates) {
                    TableItem it = new TableItem(list, SWT.NONE);
                    it.setData(e);
                    it.setText(new String[] { Parts.shortDay(J.str(e, "date")), J.str(e, "checkNo") != null ? J.str(e, "checkNo") : J.str(e, "kind", ""),
                            J.str(e, "payee", "—"), Parts.money2(J.num(e, "amount")) });
                }
                GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
                gd.heightHint = 220;
                list.setLayoutData(gd);
            }

            @Override
            protected int width() { return 520; }

            @Override
            protected Callable<String> saveWork() {
                TableItem[] sel = list.getSelection();
                if (sel.length == 0) return () -> "Choose the entry it is.";
                int txn = J.i((JsonObject) sel[0].getData(), "id");
                return () -> {
                    register = BankClient.match(J.i(line, "id"), txn);
                    return null;
                };
            }

            @Override
            protected void saved() { show(); }
        });
    }
}
