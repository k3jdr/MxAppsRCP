package com.k3jdr.mxdatahub;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Rows.*;

/**
 * Securities (SRMSecuritiesView.swift): search by name or any identifier (ISIN, CUSIP, SEDOL, FIGI, ticker,
 * TICKER:MIC, CIK, LEI), filter by type, benchmark and active only, sort and filter the columns Excel-style (on the
 * server), page through 1,000 at a time, and see a security's prices, identifiers with their history, issuer,
 * listings, classification, benchmark weights and corporate actions.
 */
final class SecuritiesScreen extends Composite implements Screen {
    private static final String[] COLUMNS = { "Ticker", "Name", "Type", "ISIN", "CUSIP", "Composite FIGI", "Sector", "MIC" };

    private final Hub hub;
    private final MainPart main;
    private final Text search;
    private final Combo typeCombo, inCombo;
    private final Button activeOnly;
    private final Table results;
    private final Label rowsText;
    private final Button previous, next;
    private final Composite sortLine;
    private final Label sortLabel, filterLabel;
    private final Link clearSort, clearFilters;

    private final Composite detailHost;
    private final StackLayout detailStack = new StackLayout();
    private final Placeholder noSelection;
    private final ScrolledComposite scroller;
    private final Composite content;
    private final Text name;
    private final Label subtitle;
    private final LineChart chart;
    private final Table identifiers;
    private final StyledText facts;

    private String query = "";
    private String typeFilter = "All";
    private String benchmarkFilter = "";
    private boolean active = true;
    private GridOptions options = GridOptions.empty();
    private int offset, total;
    private List<SecurityRow> rows = List.of();
    private Long selectedId;
    private SecurityDetail detail;
    private int searchGeneration, detailGeneration;
    private boolean searched;
    private List<String> typesShown = List.of(), benchmarksShown = List.of();

    SecuritiesScreen(Composite parent, Hub hub, MainPart main) {
        super(parent, SWT.NONE);
        this.hub = hub;
        this.main = main;
        setLayout(new FillLayout());
        String q = main.launchArgument("query");
        if (q != null) query = q;

        SplitPanes split = new SplitPanes(this, "securities", 460, 720, 560, 440);
        Composite left = split.leading();
        left.setLayout(Ui.grid(1, 10, 8));

        Composite searchRow = new Composite(left, SWT.NONE);
        searchRow.setLayout(Ui.grid(2, 0, 6));
        searchRow.setLayoutData(Ui.fillH());
        search = new Text(searchRow, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        search.setMessage("Name, ticker, ISIN, CUSIP, SEDOL, FIGI, CIK or LEI");
        search.setText(query);
        search.setLayoutData(Ui.fillH());
        search.addListener(SWT.DefaultSelection, e -> runSearch());
        Button go = Ui.button(searchRow, "Search", this::runSearch);
        for (Control c : new Control[] { search, go, searchRow }) QuickHelp.help(c, HubHelp.SEARCH.title(), HubHelp.SEARCH.detail(), HubHelp.SEARCH.shortcut());

        Composite filters = new Composite(left, SWT.NONE);
        filters.setLayout(Ui.grid(6, 0, 6));
        filters.setLayoutData(Ui.fillH());
        Ui.label(filters, "Type");
        typeCombo = new Combo(filters, SWT.READ_ONLY);
        typeCombo.addListener(SWT.Selection, e -> {
            int i = typeCombo.getSelectionIndex();
            typeFilter = i <= 0 ? "All" : typeCombo.getItem(i);
            runSearch();
        });
        QuickHelp.help(typeCombo, "Type", "Only securities of this type: common stock, ETF, ADR…");
        Ui.label(filters, "In");
        inCombo = new Combo(filters, SWT.READ_ONLY);
        inCombo.addListener(SWT.Selection, e -> {
            int i = inCombo.getSelectionIndex();
            benchmarkFilter = i <= 0 ? "" : inCombo.getItem(i);
            runSearch();
        });
        QuickHelp.help(inCombo, "In", "Only the members of a benchmark on its latest date.");
        activeOnly = Ui.check(filters, "Active only", active, on -> {
            active = on;
            runSearch();
        });
        QuickHelp.help(activeOnly, "Active Only", "Leaves out securities that are no longer active (delisted, matured, merged).");

        results = Tables.create(left, false, HubHelp.RESULTS, Tables.col("Ticker", 70), Tables.col("Name", 220),
                Tables.col("Type", 90), Tables.col("ISIN", 110), Tables.col("CUSIP", 85), Tables.col("Composite FIGI", 110),
                Tables.col("Sector", 120), Tables.col("MIC", 60));
        results.addListener(SWT.Selection, e -> {
            SecurityRow r = Tables.row(results.getSelectionCount() > 0 ? results.getSelection()[0] : null);
            select(r == null ? null : r.id(), false);
        });
        for (int i = 0; i < COLUMNS.length; i++) {
            final String column = COLUMNS[i];
            results.getColumn(i).addListener(SWT.Selection, e -> columnMenu(column, (TableColumn) e.widget));
            results.getColumn(i).setToolTipText("Sort or filter " + column + " (click)");
        }
        Menu menu = new Menu(results);
        results.setMenu(menu);
        hub.links.addMenuItems(menu, results, 0, 1, this::linkTarget, this::paintLinks);
        Tables.copyMenu(results);
        hub.links.decorate(results, 0, 1, this::linkTarget);

        Composite pageBar = new Composite(left, SWT.NONE);
        pageBar.setLayout(Ui.grid(3, 0, 6));
        pageBar.setLayoutData(Ui.fillH());
        rowsText = Ui.label(pageBar, "");
        rowsText.setForeground(Ui.SECONDARY);
        rowsText.setLayoutData(Ui.fillH());
        previous = Ui.button(pageBar, "‹ Previous", () -> page(false));
        next = Ui.button(pageBar, "Next ›", () -> page(true));
        QuickHelp.help(previous, "Previous", "Shows the previous " + Fmt.count(Db.SECURITY_PAGE_SIZE) + " securities.");
        QuickHelp.help(next, "Next", "Shows the next " + Fmt.count(Db.SECURITY_PAGE_SIZE) + " securities.");

        sortLine = new Composite(left, SWT.NONE);
        sortLine.setLayout(Ui.grid(5, 0, 10));
        sortLine.setLayoutData(Ui.fillH());
        sortLabel = Ui.label(sortLine, "");
        sortLabel.setForeground(Ui.ACCENT);
        clearSort = new Link(sortLine, SWT.NONE);
        clearSort.setText("<a>Clear Sort</a>");
        clearSort.addListener(SWT.Selection, e -> apply(options.withSort(null)));
        filterLabel = Ui.label(sortLine, "");
        filterLabel.setForeground(Ui.ACCENT);
        clearFilters = new Link(sortLine, SWT.NONE);
        clearFilters.setText("<a>Clear Filters</a>");
        clearFilters.addListener(SWT.Selection, e -> apply(options.withoutFilters()));
        QuickHelp.help(sortLine, "Sort and Filters", "The column sort and filters in force. Clear Sort and Clear Filters take them off; the search runs again.");

        // The detail on the right: one container for both states, so the previous security stays while the next loads.
        detailHost = split.trailing();
        detailHost.setLayout(detailStack);
        noSelection = new Placeholder(detailHost);
        noSelection.show("Select a security", "Search by name or any identifier.");
        scroller = new ScrolledComposite(detailHost, SWT.V_SCROLL);
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        content = new Composite(scroller, SWT.NONE);
        content.setLayout(Ui.grid(1, 14, 8));
        scroller.setContent(content);
        scroller.addListener(SWT.Resize, e -> resizeDetail());
        name = new Text(content, SWT.READ_ONLY | SWT.WRAP);
        name.setFont(Ui.heading());
        name.setLayoutData(wrap());
        subtitle = Ui.label(content, "");
        subtitle.setForeground(Ui.SECONDARY);
        chart = new LineChart(content);
        GridData chartData = new GridData(SWT.FILL, SWT.TOP, true, false);
        chartData.heightHint = 150;
        chart.setLayoutData(chartData);
        QuickHelp.provider(chart, (c, p) -> {
            PricePoint pt = chart.pointAt(p.x);
            return new QuickHelpItem("Prices", "The closes of the last 400 days on the security's listings."
                    + (pt == null ? "" : " Under the pointer: " + pt.date() + ", " + Fmt.number(pt.close(), 2) + "."));
        });
        Ui.label(content, "Identifiers", Ui.subheading());
        identifiers = Tables.create(content, true, HubHelp.SECURITY_DETAIL, Tables.col("Type", 110), Tables.col("Value", 140),
                Tables.col("MIC", 50), Tables.col("From", 80), Tables.col("To", 80), Tables.col("Source", 90));
        identifiers.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Tables.copyMenu(identifiers);
        facts = new StyledText(content, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
        facts.setLayoutData(wrap());
        facts.setBackground(content.getBackground());
        facts.setCaret(null);
        facts.setMargins(0, 0, 0, 0);
        QuickHelp.help(facts, HubHelp.SECURITY_DETAIL.title(), HubHelp.SECURITY_DETAIL.detail() + " Drag across the text to select and copy it.");
        QuickHelp.help(content, HubHelp.SECURITY_DETAIL.title(), HubHelp.SECURITY_DETAIL.detail());
        Ui.background(content, content.getBackground());
        detailStack.topControl = noSelection;

        updatePageBar();
        updateSortLine();
        hubChanged();
    }

    private static GridData wrap() {
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.widthHint = 300;
        return gd;
    }

    private Links.Target linkTarget(Object data) {
        return data instanceof SecurityRow r ? new Links.Target(r.id(), r.ticker(), r.name(), r.type()) : null;
    }

    private void paintLinks() {
        hub.links.paint(results, 0, 1, this::linkTarget);
    }

    /** Edit ▸ Link… (Ctrl+K): the link on the selected security's ticker, or its name when the pointer rests there. */
    void editLink(String field) {
        TableItem[] sel = results.getSelection();
        if (sel.length == 0) return;
        Links.Target t = linkTarget(sel[0].getData());
        if (t == null) return;
        String f = field != null ? field : hub.links.fieldAt(results, 0, 1);
        hub.links.edit(results, t, f == null ? "ticker" : f, this::paintLinks);
    }

    // MARK: Search

    /** A search from a later launch (-query). */
    void search(String text) {
        query = text == null ? "" : text;
        search.setText(query);
        runSearch();
    }

    private void runSearch() {
        query = search.getText();
        offset = 0;
        load();
    }

    private void page(boolean forward) {
        if (forward ? offset + rows.size() >= total : offset == 0) return;
        offset = forward ? offset + Db.SECURITY_PAGE_SIZE : Math.max(0, offset - Db.SECURITY_PAGE_SIZE);
        load();
    }

    private Db.SecuritySearch searchFields() {
        return new Db.SecuritySearch(query, typeFilter, benchmarkFilter, active, options, offset);
    }

    /** New sorting or column filters; the search runs again from its first page. */
    private void apply(GridOptions o) {
        options = o;
        offset = 0;
        updateSortLine();
        load();
    }

    private void load() {
        if (!hub.isConnected()) return;
        searched = true;
        int gen = ++searchGeneration;
        Db.SecuritySearch q = searchFields();
        previous.setEnabled(false);
        next.setEnabled(false);
        hub.run(this, () -> hub.db.searchSecurities(q), page -> {
            if (gen != searchGeneration) return;
            rows = page.rows();
            total = page.total();
            offset = page.offset();
            Tables.fill(results, rows, r -> new String[] { r.ticker(), r.name(), r.type(), r.isin(), r.cusip(), r.figi(), r.sector(), r.mic() });
            for (TableItem item : results.getItems()) {
                if (item.getData() instanceof SecurityRow r && !r.active()) item.setForeground(Ui.SECONDARY);
                else item.setForeground(null);
            }
            final Long before = selectedId;
            Long keep = before;
            SecurityRow first = rows.isEmpty() ? null : rows.get(0);
            if (first != null && (rows.size() == 1 || before == null || rows.stream().noneMatch(r -> r.id() == before))) keep = first.id();
            if (first == null) keep = null;
            selectInTable(keep);
            select(keep, true);
            updatePageBar();
            hub.links.load(results, rows.stream().map(SecurityRow::id).toList(), this::paintLinks);
        });
    }

    private void selectInTable(Long id) {
        if (id == null) {
            results.deselectAll();
            return;
        }
        TableItem[] items = results.getItems();
        for (int i = 0; i < items.length; i++) {
            if (items[i].getData() instanceof SecurityRow r && r.id() == id) {
                results.setSelection(i);
                results.showSelection();
                return;
            }
        }
    }

    private void updatePageBar() {
        String text;
        if (rows.isEmpty()) text = !searched ? "" : offset > 0 ? "No securities on this page" : "No securities";
        else if (offset == 0 && rows.size() == total) text = Fmt.count(total) + (total == 1 ? " security" : " securities");
        else text = Fmt.count(offset + 1) + "–" + Fmt.count(offset + rows.size()) + " of " + Fmt.count(total) + " securities";
        rowsText.setText(text);
        previous.setEnabled(offset > 0);
        next.setEnabled(offset + rows.size() < total);
        rowsText.getParent().layout(true);
    }

    private void updateSortLine() {
        boolean sorted = options.sort() != null, filtered = !options.filters().isEmpty();
        sortLabel.setText(sorted ? "sorted by " + options.sort().column() + (options.sort().ascending() ? " ↑" : " ↓") : "");
        filterLabel.setText(filtered ? Ui.escape("filtered: " + String.join(", ", options.filters().keySet())) : "");
        for (Control c : new Control[] { sortLabel, clearSort }) Ui.setVisible(c, sorted);
        for (Control c : new Control[] { filterLabel, clearFilters }) Ui.setVisible(c, filtered);
        Ui.setVisible(sortLine, sorted || filtered);
        // The headers: the sort arrow, and a mark on filtered columns.
        for (int i = 0; i < COLUMNS.length; i++) {
            TableColumn col = results.getColumn(i);
            col.setText(COLUMNS[i] + (options.filters().containsKey(COLUMNS[i]) ? " ▿" : ""));
            if (sorted && options.sort().column().equals(COLUMNS[i])) {
                results.setSortColumn(col);
                results.setSortDirection(options.sort().ascending() ? SWT.UP : SWT.DOWN);
            }
        }
        if (!sorted) results.setSortDirection(SWT.NONE);
        layout(true, true);
    }

    /** A column header's menu: sort it, or filter it by values or a condition, as in Excel. */
    private void columnMenu(String column, TableColumn col) {
        Menu m = new Menu(results.getShell(), SWT.POP_UP);
        item(m, "Sort Ascending", () -> apply(options.withSort(new GridOptions.Sort(column, true))));
        item(m, "Sort Descending", () -> apply(options.withSort(new GridOptions.Sort(column, false))));
        MenuItem clear = item(m, "Clear Sort", () -> apply(options.withSort(null)));
        clear.setEnabled(options.sort() != null && options.sort().column().equals(column));
        new MenuItem(m, SWT.SEPARATOR);
        item(m, "Filter " + column + "…", () -> filter(column));
        MenuItem clearFilter = item(m, "Clear Filter", () -> apply(options.withFilter(column, null)));
        clearFilter.setEnabled(options.filters().containsKey(column));
        m.setLocation(getDisplay().getCursorLocation());
        m.setVisible(true);
    }

    private static MenuItem item(Menu m, String text, Runnable action) {
        MenuItem mi = new MenuItem(m, SWT.PUSH);
        mi.setText(Ui.escape(text));
        mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    private void filter(String column) {
        if (!hub.isConnected()) return;
        Db.SecuritySearch q = searchFields();
        FilterDialog d = new FilterDialog(getShell(), hub, column, options.filters().get(column), () -> hub.db.securityValues(q, column));
        if (d.open() != FilterDialog.OK) return;
        apply(options.withFilter(column, d.cleared ? null : d.result));
    }

    // MARK: Detail

    private void select(Long id, boolean fromSearch) {
        boolean changed = !java.util.Objects.equals(id, selectedId);
        selectedId = id;
        if (id == null) {
            detail = null;
            showDetail();
        } else if (changed || detail == null || detail.security().id() != id) {
            loadDetail(id);
        }
        if (changed) main.placeChanged();
    }

    private void loadDetail(long id) {
        int gen = ++detailGeneration;
        hub.run(this, () -> hub.db.securityDetail(id), d -> {
            if (gen != detailGeneration) return;
            detail = d;
            showDetail();
            QuickHelp.get().update();
        });
    }

    private void showDetail() {
        if (detail == null || selectedId == null) {
            detailStack.topControl = noSelection;
            detailHost.layout(true, true);
            return;
        }
        SecurityRow s = detail.security();
        name.setText(s.name());
        subtitle.setText(Ui.escape(join(" · ", s.ticker(), s.mic(), s.type(), s.sector())));
        Ui.setVisible(chart, !detail.prices().isEmpty());
        chart.setPoints(detail.prices(), "Close, last 400 days");
        Tables.fill(identifiers, detail.identifiers(), r -> new String[] { r.type(), r.value(), r.mic(), known(r.from()),
                r.to().isEmpty() ? "current" : r.to(), r.source() });
        for (TableItem item : identifiers.getItems()) {
            if (item.getData() instanceof IdentifierRow r && !r.current()) item.setForeground(1, Ui.SECONDARY);
        }
        GridData ig = (GridData) identifiers.getLayoutData();
        ig.heightHint = Math.min(12, detail.identifiers().size() + 1) * identifiers.getItemHeight() + identifiers.getHeaderHeight() + 4;
        factsText(detail).applyTo(facts);
        detailStack.topControl = scroller;
        detailHost.layout(true, true);
        resizeDetail();
        scroller.setOrigin(0, 0);
    }

    private void resizeDetail() {
        int width = scroller.getClientArea().width;
        if (width <= 0) return;
        scroller.setMinSize(content.computeSize(width, SWT.DEFAULT));
        content.layout(true, true);
    }

    /** 1900-01-01 marks a value whose start is unknown (it was already valid at the first load). */
    private static String known(String day) { return "1900-01-01".equals(day) ? "" : day; }

    private static String join(String sep, String... parts) {
        return Stream.of(parts).filter(p -> p != null && !p.isEmpty()).collect(Collectors.joining(sep));
    }

    /** Issuer and security, listings, classification, benchmark membership and corporate actions, as one text. */
    static RichLines factsText(SecurityDetail d) {
        RichLines lines = new RichLines();
        lines.heading("Issuer and security");
        lines.facts(d.facts(), 150);
        if (!d.listings().isEmpty()) {
            lines.heading("Listings");
            List<List<RichLines.Cell>> rows = new ArrayList<>();
            for (ListingRow l : d.listings()) {
                String when = l.to().isEmpty() ? (known(l.from()).isEmpty() ? "current" : "since " + l.from()) : known(l.from()) + " – " + l.to();
                rows.add(List.of(new RichLines.Cell(l.ticker(), true, null),
                        new RichLines.Cell(l.mic() + " " + l.exchange(), false, null),
                        new RichLines.Cell(l.primary() ? "primary" : "", false, Ui.SECONDARY),
                        new RichLines.Cell(when, false, Ui.SECONDARY)));
            }
            lines.columns(rows, 80, 330, 400);
        }
        if (!d.classifications().isEmpty()) {
            lines.heading("Classification");
            lines.facts(d.classifications(), 150);
        }
        lines.heading("Benchmark membership");
        if (d.memberships().isEmpty()) lines.note("Not in any loaded benchmark");
        else lines.facts(d.memberships(), 260);
        if (!d.actions().isEmpty()) {
            lines.heading("Corporate actions");
            List<List<RichLines.Cell>> rows = new ArrayList<>();
            for (ActionRow a : d.actions()) {
                rows.add(List.of(new RichLines.Cell(a.exDate(), false, null),
                        new RichLines.Cell(Fmt.capitalized(a.type().replace('_', ' ')), false, null),
                        new RichLines.Cell(a.detail(), false, null),
                        new RichLines.Cell(a.source(), false, Ui.SECONDARY)));
            }
            lines.columns(rows, 90, 230, 420);
        }
        return lines;
    }

    // MARK: Screen

    @Override
    public void hubChanged() {
        if (isDisposed()) return;
        List<String> types = hub.securityTypes;
        if (!types.equals(typesShown)) {
            typesShown = types;
            typeCombo.removeAll();
            typeCombo.add("All types");
            types.forEach(typeCombo::add);
            int i = types.indexOf(typeFilter);
            typeCombo.select(i < 0 ? 0 : i + 1);
            if (i < 0) typeFilter = "All";
        }
        List<String> codes = hub.benchmarks.stream().map(BenchmarkRow::code).toList();
        if (!codes.equals(benchmarksShown)) {
            benchmarksShown = codes;
            inCombo.removeAll();
            inCombo.add("Any benchmark");
            codes.forEach(inCombo::add);
            int i = codes.indexOf(benchmarkFilter);
            inCombo.select(i < 0 ? 0 : i + 1);
            if (i < 0) benchmarkFilter = "";
        }
        filtersParentLayout();
    }

    private void filtersParentLayout() {
        typeCombo.getParent().layout(true);
    }

    private boolean loadedOnce;
    private Object loadedFor;

    @Override
    public void shown() {
        // A new connection searches again; otherwise the results stay as they were.
        Object connection = hub.connected;
        if (!loadedOnce || loadedFor != connection) {
            loadedOnce = true;
            loadedFor = connection;
            selectedId = null;
            detail = null;
            runSearch();
        }
        search.setFocus();
    }

    @Override
    public Control control() { return this; }

    @Override
    public Long selectedItem() { return selectedId; }

    @Override
    public void selectItem(long id) {
        if (rows.stream().noneMatch(r -> r.id() == id)) {
            // Not on this page: show it on its own.
            selectedId = id;
            results.deselectAll();
            loadDetail(id);
            return;
        }
        selectInTable(id);
        select(id, false);
    }

    @Override
    public String itemTitle(long id) {
        for (SecurityRow r : rows) if (r.id() == id) return join(" ", r.ticker(), r.name());
        if (detail != null && detail.security().id() == id) return join(" ", detail.security().ticker(), detail.security().name());
        return null;
    }

    @Override
    public QuickHelpItem selectionHelp() {
        if (!hub.isConnected() || selectedId == null || detail == null) return null;
        SecurityRow s = detail.security();
        String facts = join(" · ", s.ticker(), s.mic(), s.type(), s.sector(), s.active() ? "" : "inactive");
        return new QuickHelpItem(s.name(), facts + "\nOn the right: its price history, identifiers (grey values are past ones, with the dates they were valid), "
                + "issuer facts, listings, classification, the benchmarks it's in with its weight, and corporate actions. Select a value to copy it.");
    }
}
