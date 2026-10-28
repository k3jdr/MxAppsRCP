package com.k3jdr.mxacctmgr.pages;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonObject;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.bank.J;
import com.k3jdr.mxacctmgr.port.AccountsClient;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;
import com.k3jdr.mxacctmgr.ui.Parts.Col;

/**
 * Reporting ▸ SRM Table (Securities.swift): MxDataHUB's Securities page — the security master's securities searched
 * by name or any identifier, filtered by type, benchmark and activity, sorted by any column (in the database), a
 * thousand at a time with Previous / Next; the selected one's facts, identifiers, listings, classification,
 * benchmark membership, corporate actions and last closes as one selectable text. Read through the accounts service
 * ({@code POST /securities/browse}, {@code GET /securities/<id>/detail}).
 */
public final class SecuritiesPage extends Page {
    private static final int PAGE = 1000;
    private static final String[] COLUMNS = { "ticker", "name", "type", "sector", "mic", "price", "priceDate", "marketCap" };

    private Text query;
    private Combo type, benchmark;
    private Button active, previous, next;
    private Table table;
    private Label status;
    private Text detail;
    private Composite problemHost;
    private int offset, total;
    private String sortColumn;
    private boolean ascending = true;
    private final List<String> benchmarkCodes = new ArrayList<>();

    public SecuritiesPage(App app) {
        super(app);
    }

    @Override
    protected boolean fills() { return true; }

    @Override
    protected void build(Composite body) {
        problemHost = Parts.column(body, 6);
        SplitPanes split = new SplitPanes(body, "mxacctmgr.securities", 420, 1100, 760, 320);
        split.setLayoutData(Ui.fill());
        Composite left = split.leading();
        left.setLayout(Ui.grid(1, 0, 6));
        Composite bar = Parts.row(left, 6);
        query = new Text(bar, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        query.setMessage("Name or any identifier");
        query.setLayoutData(Ui.fillH());
        query.addListener(SWT.DefaultSelection, e -> search(0));
        QuickHelp.help(query, "Search", "By name or any identifier (ticker, ISIN, CUSIP, SEDOL, FIGI, CIK, LEI — current or past). Return searches.");
        type = new Combo(bar, SWT.READ_ONLY);
        type.add("All types");
        type.select(0);
        type.addListener(SWT.Selection, e -> search(0));
        QuickHelp.help(type, "Type", "Only securities of this type.");
        benchmark = new Combo(bar, SWT.READ_ONLY);
        benchmark.add("In any benchmark or none");
        benchmarkCodes.add("");
        benchmark.select(0);
        benchmark.addListener(SWT.Selection, e -> search(0));
        QuickHelp.help(benchmark, "In", "Only a benchmark's members on its latest date.");
        active = Ui.check(bar, "Active only", true, v -> search(0));
        QuickHelp.help(active, "Active only", "Leaves out securities that are no longer active.");
        Ui.button(bar, "Search", () -> search(0));
        new Label(bar, SWT.NONE);
        table = Parts.table(left, SWT.MULTI, Col.l("Ticker", 80), Col.l("Name", 230), Col.l("Type", 100), Col.l("Sector", 130), Col.l("MIC", 60),
                Col.r("Price", 85), Col.l("As of", 90), Col.r("Market value", 110));
        table.setLayoutData(Ui.fill());
        for (int i = 0; i < COLUMNS.length; i++) {
            final int col = i;
            table.getColumn(i).addListener(SWT.Selection, e -> {
                if (COLUMNS[col].equals(sortColumn)) ascending = !ascending;
                else {
                    sortColumn = COLUMNS[col];
                    ascending = col < 5;
                }
                table.setSortColumn(table.getColumn(col));
                table.setSortDirection(ascending ? SWT.UP : SWT.DOWN);
                search(0);
            });
        }
        table.addListener(SWT.Selection, e -> showDetail());
        QuickHelp.help(table, "Securities", "Priced securities first and the largest first; click a column's title to sort by it, across every match. Select several rows (Ctrl-click or Shift-click, Ctrl+A for the page): Ctrl+C or the context menu copies them under the column titles.");
        Composite foot = Parts.row(left, 3);
        status = Parts.secondary(foot, "");
        status.setLayoutData(Ui.fillH());
        previous = Ui.button(foot, "‹ Previous", () -> search(Math.max(0, offset - PAGE)));
        next = Ui.button(foot, "Next ›", () -> search(offset + PAGE));
        previous.setEnabled(false);
        next.setEnabled(false);
        Composite right = split.trailing();
        right.setLayout(new FillLayout());
        detail = new Text(right, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        detail.setText("Select a security for its identifiers, facts, listings, classification, benchmark membership, corporate actions and last closes.");
        QuickHelp.help(detail, "Details", "The selected security's identifiers with their dates and sources, then its issuer and security facts, listings, classification, benchmark membership and corporate actions, as one selectable text.");
        loadChoices();
        search(0);
    }

    private void loadChoices() {
        Background.run(table, () -> {
            JsonObject types = J.obj(AccountsClient.request("GET", "/security-types", null, Duration.ofSeconds(30)));
            JsonObject benchmarks = J.obj(AccountsClient.request("GET", "/benchmarks", null, Duration.ofSeconds(30)));
            return new JsonObject[] { types, benchmarks };
        }, out -> {
            for (String t : J.strings(out[0], "types")) type.add(t);
            for (JsonObject b : J.list(out[1], "benchmarks")) {
                benchmarkCodes.add(J.str(b, "code", ""));
                benchmark.add(J.str(b, "name", J.str(b, "code", "")));
            }
        }, e -> { });
    }

    private void search(int at) {
        JsonObject body = new JsonObject();
        body.addProperty("q", query.getText().trim());
        body.addProperty("type", type.getSelectionIndex() <= 0 ? "" : type.getText());
        body.addProperty("benchmark", benchmarkCodes.get(Math.max(0, benchmark.getSelectionIndex())));
        body.addProperty("activeOnly", active.getSelection());
        body.add("filters", new JsonObject());
        if (sortColumn != null) {
            JsonObject sort = new JsonObject();
            sort.addProperty("column", sortColumn);
            sort.addProperty("ascending", ascending);
            body.add("sort", sort);
        }
        body.addProperty("offset", at);
        body.addProperty("limit", PAGE);
        status.setText("Searching…");
        long started = System.currentTimeMillis();
        Background.run(table, () -> J.obj(AccountsClient.request("POST", "/securities/browse", body, Duration.ofSeconds(60))), page -> {
            Page.clear(problemHost);
            offset = at;
            total = J.i(page, "total");
            table.removeAll();
            for (JsonObject s : J.list(page, "securities")) {
                TableItem it = new TableItem(table, SWT.NONE);
                it.setData(s);
                it.setText(new String[] { J.str(s, "ticker", ""), J.str(s, "name", ""), J.str(s, "type", ""), J.str(s, "sector", ""), J.str(s, "mic", ""),
                        J.dbl(s, "price") == null ? "" : Parts.money2(J.dbl(s, "price")), J.str(s, "priceDate", ""),
                        J.dbl(s, "marketCap") == null ? "" : Fmt.money(J.dbl(s, "marketCap")) });
                if (!J.bool(s, "isActive")) it.setForeground(Ui.SECONDARY);
            }
            int shown = table.getItemCount();
            status.setText(shown == 0 ? "No securities match." : (offset + 1) + "–" + (offset + shown) + " of " + total + " · " + (System.currentTimeMillis() - started) + " ms");
            previous.setEnabled(offset > 0);
            next.setEnabled(offset + shown < total);
            status.getParent().layout(true);
        }, e -> {
            status.setText("");
            Page.clear(problemHost);
            if (Parts.isDown(e)) Parts.serviceDown(problemHost, app, e.getMessage(), () -> search(at));
            else Parts.warning(problemHost, e.getMessage());
            relayout();
        });
    }

    private void showDetail() {
        TableItem[] sel = table.getSelection();
        if (sel.length == 0) return;
        status.setText(table.getSelectionCount() > 1 ? table.getSelectionCount() + " rows selected" : status.getText());
        JsonObject s = (JsonObject) sel[sel.length - 1].getData();
        Object id = J.str(s, "id");
        detail.setText("Reading " + J.str(s, "ticker", "") + "…");
        Background.run(table, () -> J.obj(AccountsClient.request("GET", "/securities/" + id + "/detail", null, Duration.ofSeconds(60))), d -> {
            StringBuilder sb = new StringBuilder();
            JsonObject sec = J.obj(d, "security");
            sb.append(J.str(sec, "ticker", J.str(s, "ticker", ""))).append(" — ").append(J.str(sec, "name", J.str(s, "name", ""))).append('\n');
            List<JsonObject> prices = J.list(d, "prices");
            if (!prices.isEmpty()) {
                JsonObject last = prices.get(prices.size() - 1);
                sb.append("Last close ").append(Parts.money2(J.num(last, "close"))).append(" on ").append(J.str(last, "date", "")).append(" (")
                        .append(prices.size()).append(" closes)\n");
            }
            section(sb, "Identifiers", J.list(d, "identifiers"), x -> J.str(x, "type", "") + "\t" + J.str(x, "value", "") + "\t" + J.str(x, "mic", "")
                    + "\t" + J.str(x, "from", "") + " – " + J.str(x, "to", "") + "\t" + J.str(x, "source", "") + (J.bool(x, "isCurrent") ? "\tcurrent" : ""));
            section(sb, "Facts", J.list(d, "facts"), x -> J.str(x, "name", "") + ": " + J.str(x, "value", ""));
            section(sb, "Listings", J.list(d, "listings"), x -> J.str(x, "ticker", "") + " on " + J.str(x, "exchange", J.str(x, "mic", ""))
                    + (J.bool(x, "isPrimary") ? " (primary)" : "") + ", " + J.str(x, "from", "") + " – " + J.str(x, "to", ""));
            section(sb, "Classification", J.list(d, "classifications"), x -> J.str(x, "name", "") + ": " + J.str(x, "value", ""));
            section(sb, "Benchmark membership", J.list(d, "memberships"), x -> J.str(x, "name", "") + ": " + J.str(x, "value", ""));
            section(sb, "Corporate actions", J.list(d, "actions"), x -> J.str(x, "exDate", "") + "  " + J.str(x, "type", "") + "  " + J.str(x, "detail", "")
                    + "  (" + J.str(x, "source", "") + ")");
            detail.setText(sb.toString());
        }, e -> detail.setText(e.getMessage()));
    }

    private static void section(StringBuilder sb, String title, List<JsonObject> rows, java.util.function.Function<JsonObject, String> line) {
        if (rows.isEmpty()) return;
        sb.append('\n').append(title).append('\n');
        for (JsonObject r : rows) sb.append("  ").append(line.apply(r)).append('\n');
    }

    @Override
    protected com.k3jdr.mxacctmgr.text.Lessons.Lesson lesson() {
        return new com.k3jdr.mxacctmgr.text.Lessons.Lesson("", "", List.of());
    }

    /** The SRM Table has no lesson of its own; its Quick Help says what it is. */
    static String help() { return Help.screen("securities"); }
}
