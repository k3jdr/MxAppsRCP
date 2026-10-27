package com.k3jdr.mxdatahub;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.links.AppLinks;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Rows.*;

/**
 * Benchmarks (SRMBenchmarksView.swift): loaded benchmarks with constituents and weights, sector weights and the
 * index level, custom benchmarks built from another benchmark's members (equal or market-cap weighted, optionally
 * one sector), and which benchmarks the data sources can and cannot supply.
 */
final class BenchmarksScreen extends Composite implements Screen {
    private final Hub hub;
    private final MainPart main;
    private final Table list;
    private final Button track, delete;
    private final Composite right;
    private final StackLayout rightStack = new StackLayout();
    private final Placeholder noBenchmark;
    private final Composite panel;
    private final Label name, code, proxy, subtitle, proxyNote;
    private final Composite body;
    private final StackLayout bodyStack = new StackLayout();
    private final Placeholder noMembers;
    private final Composite membersBox;
    private final BarChart sectors;
    private final LineChart levels;
    private final Text filter;
    private final Table constituents;
    private final Label footer;

    private Integer selectedId;
    private List<ConstituentRow> members = List.of();
    private int generation;
    private List<BenchmarkRow> listed = List.of();

    BenchmarksScreen(Composite parent, Hub hub, MainPart main) {
        super(parent, SWT.NONE);
        this.hub = hub;
        this.main = main;
        setLayout(new FillLayout());
        SplitPanes split = new SplitPanes(this, "benchmarks", 300, 390, 380, 520);

        Composite left = split.leading();
        left.setLayout(Ui.grid(1, 10, 8));
        list = Tables.create(left, false, HubHelp.BENCHMARK_LIST, Tables.col("Code", 70), Tables.col("Name", 120),
                Tables.num("Members", 72), Tables.col("As of", 85));
        Tables.copyMenu(list);
        list.addListener(SWT.Selection, e -> {
            BenchmarkRow b = Tables.row(list.getSelectionCount() > 0 ? list.getSelection()[0] : null);
            select(b == null ? null : b.id());
        });
        Composite buttons = new Composite(left, SWT.NONE);
        buttons.setLayout(Ui.grid(4, 0, 6));
        buttons.setLayoutData(Ui.fillH());
        Button build = Ui.button(buttons, "Build Custom…", this::buildCustom);
        QuickHelp.help(build, "Build Custom Benchmark", "A benchmark from another's members on its latest date: equal weighted or by market cap, optionally one sector only.");
        Button availability = Ui.button(buttons, "Availability…", () -> new AvailabilityDialog(getShell()).open());
        QuickHelp.help(availability, "Availability", "Which benchmarks the data sources can supply, and which they can't (and why).");
        track = Ui.button(buttons, "Track…", () -> {
            BenchmarkRow b = selected();
            if (b != null && !AppLinks.open(new AppLink.Sample("passive-tracking", null, b.code())))
                hub.error("MxJobsMGR is not installed: install it beside MxDataHUB, or register its mxjobsmgr:// links.");
        });
        track.setToolTipText("Build an index-tracking portfolio for this benchmark in MxJobsMGR");
        QuickHelp.help(track, "Track", "Opens MxJobsMGR's passive tracking workflow for this benchmark: an index-tracking portfolio within a tracking-error budget.");
        delete = Ui.button(buttons, "Delete…", this::deleteBenchmark);
        delete.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        delete.setToolTipText("Only custom benchmarks built here can be deleted");
        QuickHelp.help(delete, "Delete Benchmark", "Deletes a custom benchmark built here, after asking. Loaded benchmarks can't be deleted.");

        right = split.trailing();
        right.setLayout(rightStack);
        noBenchmark = new Placeholder(right);
        noBenchmark.show("Select a benchmark", "");
        panel = new Composite(right, SWT.NONE);
        panel.setLayout(Ui.grid(1, 10, 6));
        Composite head = new Composite(panel, SWT.NONE);
        head.setLayout(Ui.grid(3, 0, 8));
        head.setLayoutData(Ui.fillH());
        name = Ui.label(head, "", Ui.subheading());
        code = Ui.label(head, "");
        code.setForeground(Ui.SECONDARY);
        proxy = Ui.label(head, " PROXY ", Ui.font(-1, SWT.BOLD));
        proxy.setBackground(new org.eclipse.swt.graphics.Color(255, 224, 178));
        subtitle = Ui.label(panel, "");
        subtitle.setForeground(Ui.SECONDARY);
        subtitle.setLayoutData(Ui.fillH());
        proxyNote = Ui.wrap(panel, "");
        proxyNote.setForeground(Ui.SECONDARY);

        body = new Composite(panel, SWT.NONE);
        body.setLayoutData(Ui.fill());
        body.setLayout(bodyStack);
        noMembers = new Placeholder(body);
        noMembers.show("No constituents loaded", "Run SRM/srm.py load ssga (S&P / Dow) or load nasdaq100.");
        membersBox = new Composite(body, SWT.NONE);
        membersBox.setLayout(Ui.grid(1, 0, 6));
        Composite charts = new Composite(membersBox, SWT.NONE);
        charts.setLayout(Ui.grid(2, 0, 12));
        charts.setLayoutData(Ui.fillH());
        sectors = new BarChart(charts);
        GridData sg = new GridData(SWT.FILL, SWT.TOP, true, false);
        sg.minimumWidth = 300;
        sectors.setLayoutData(sg);
        QuickHelp.provider(sectors, (c, p) -> {
            SectorWeight s = sectors.barAt(p.y);
            return new QuickHelpItem("Sector Weights", "The benchmark's weight in each sector (its 12 largest), from its constituents' sectors."
                    + (s == null ? "" : " " + s.sector() + ": " + Fmt.percent(s.weight(), 2) + " in " + s.count() + " members."));
        });
        levels = new LineChart(charts);
        GridData lg = new GridData(SWT.FILL, SWT.FILL, true, false);
        lg.minimumWidth = 260;
        levels.setLayoutData(lg);
        QuickHelp.provider(levels, (c, p) -> {
            PricePoint pt = levels.pointAt(p.x);
            return new QuickHelpItem("Index Level", "The index's level over the last 800 days (srm.benchmark_level)."
                    + (pt == null ? "" : " Under the pointer: " + pt.date() + ", " + Fmt.number(pt.close(), 2) + "."));
        });
        filter = new Text(membersBox, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        filter.setMessage("Filter constituents");
        filter.setLayoutData(Ui.fillH());
        filter.addListener(SWT.Modify, e -> showConstituents());
        QuickHelp.help(filter, HubHelp.CONSTITUENTS.title(), HubHelp.CONSTITUENTS.detail());
        constituents = Tables.create(membersBox, true, HubHelp.CONSTITUENTS, Tables.col("Ticker", 60), Tables.col("Name", 240),
                Tables.col("Sector", 150), Tables.num("Weight", 80), Tables.num("Shares", 100));
        Tables.copyMenu(constituents);
        footer = Ui.label(membersBox, "");
        footer.setForeground(Ui.SECONDARY);
        footer.setLayoutData(Ui.fillH());
        bodyStack.topControl = membersBox;
        rightStack.topControl = noBenchmark;
        updateButtons();
    }

    private BenchmarkRow selected() { return hub.benchmark(selectedId); }

    private void fillList() {
        listed = hub.benchmarks;
        Tables.fill(list, listed, b -> new String[] { b.code(), b.name(), Fmt.count(b.members()), b.asOf() });
        if (selectedId == null || listed.stream().noneMatch(b -> b.id() == selectedId)) {
            Integer first = listed.isEmpty() ? null : listed.get(0).id();
            selectInList(first);
            select(first);
        } else {
            selectInList(selectedId);
        }
    }

    private void selectInList(Integer id) {
        TableItem[] items = list.getItems();
        for (int i = 0; i < items.length; i++) {
            if (items[i].getData() instanceof BenchmarkRow b && id != null && b.id() == id) {
                list.setSelection(i);
                list.showSelection();
                return;
            }
        }
        list.deselectAll();
    }

    private void select(Integer id) {
        boolean changed = !java.util.Objects.equals(id, selectedId);
        selectedId = id;
        load();
        if (changed) main.placeChanged();
    }

    private void load() {
        BenchmarkRow b = selected();
        showHeader(b);
        if (b == null) return;
        int gen = ++generation;
        hub.run(this, () -> hub.db.benchmark(b.id()), d -> {
            if (gen != generation) return;
            members = d.constituents();
            sectors.setBars(d.sectors());
            ((GridData) sectors.getLayoutData()).heightHint = sectors.preferredHeight();
            ((GridData) levels.getLayoutData()).heightHint = sectors.preferredHeight();
            Ui.setVisible(levels, !d.levels().isEmpty());
            levels.setPoints(d.levels(), "Index level (" + b.code() + ")");
            showConstituents();
            membersBox.layout(true, true);
            QuickHelp.get().update();
        });
    }

    private void showHeader(BenchmarkRow b) {
        updateButtons();
        if (b == null) {
            rightStack.topControl = noBenchmark;
            right.layout(true, true);
            return;
        }
        name.setText(Ui.escape(b.name()));
        code.setText(Ui.escape(b.code()));
        Ui.setVisible(proxy, b.proxy());
        proxy.setToolTipText(b.proxyNote());
        subtitle.setText(Ui.escape(b.method().replace('_', ' ') + "-weighted · source: " + b.source()
                + (b.asOf().isEmpty() ? "" : " · " + b.members() + " members on " + b.asOf() + ", " + b.snapshots() + " snapshot" + (b.snapshots() == 1 ? "" : "s"))));
        proxyNote.setText(Ui.escape(b.proxyNote()));
        Ui.setVisible(proxyNote, !b.proxyNote().isEmpty());
        bodyStack.topControl = b.members() == 0 ? noMembers : membersBox;
        rightStack.topControl = panel;
        panel.layout(true, true);
        right.layout(true, true);
    }

    private void showConstituents() {
        String f = filter.getText().trim().toLowerCase();
        List<ConstituentRow> shown = f.isEmpty() ? members : members.stream().filter(c -> c.ticker().toLowerCase().contains(f)
                || c.name().toLowerCase().contains(f) || c.sector().toLowerCase().contains(f)).toList();
        Tables.fill(constituents, shown, c -> new String[] { c.ticker(), c.name(), c.sector(), Fmt.percent(c.weight()), Fmt.number(c.shares(), 0) });
        double total = members.stream().mapToDouble(ConstituentRow::weight).sum();
        footer.setText(shown.size() + " of " + members.size() + " · weights total " + Fmt.percent(total, 2));
    }

    private void updateButtons() {
        BenchmarkRow b = selected();
        track.setEnabled(b != null);
        delete.setEnabled(b != null && b.custom());
    }

    private void buildCustom() {
        if (!hub.isConnected()) return;
        BuildDialog d = new BuildDialog(getShell(), hub);
        if (d.open() != BuildDialog.OK) return;
        String c = d.codeValue, n = d.nameValue, parent = d.parentValue, method = d.methodValue, sector = d.sectorValue;
        hub.run(this, () -> hub.db.buildBenchmark(c, n, parent, method, sector), count -> {
            hub.info(c.toUpperCase() + ": " + count + " constituents");
            hub.refreshBenchmarks(() -> {
                hub.benchmarks.stream().filter(b -> b.code().equals(c.toUpperCase())).findFirst().ifPresent(b -> selectedId = b.id());
                fillList();
                selectInList(selectedId);
                load();
                main.placeChanged();
            });
        }, t -> {
            hub.error(com.k3jdr.mx.core.ui.ErrorDialog.describe(t));
            buildCustomAgain(d);
        });
    }

    /** After a failure the dialog opens again with what was entered (the Mac's sheet stays open). */
    private void buildCustomAgain(BuildDialog previous) {
        BuildDialog d = new BuildDialog(getShell(), hub);
        d.codeValue = previous.codeValue;
        d.nameValue = previous.nameValue;
        d.parentValue = previous.parentValue;
        d.methodValue = previous.methodValue;
        d.sectorValue = previous.sectorValue;
        if (d.open() != BuildDialog.OK) return;
        String c = d.codeValue, n = d.nameValue, parent = d.parentValue, method = d.methodValue, sector = d.sectorValue;
        hub.run(this, () -> hub.db.buildBenchmark(c, n, parent, method, sector), count -> {
            hub.info(c.toUpperCase() + ": " + count + " constituents");
            hub.refreshBenchmarks(() -> {
                hub.benchmarks.stream().filter(b -> b.code().equals(c.toUpperCase())).findFirst().ifPresent(b -> selectedId = b.id());
                fillList();
                load();
            });
        });
    }

    private void deleteBenchmark() {
        BenchmarkRow b = selected();
        if (b == null || !b.custom()) return;
        MessageBox box = new MessageBox(getShell(), SWT.ICON_WARNING | SWT.YES | SWT.NO);
        box.setText("MxDataHUB");
        box.setMessage("Delete benchmark " + b.code() + "?");
        if (box.open() != SWT.YES) return;
        hub.run(this, () -> {
            hub.db.deleteBenchmark(b.id());
            return true;
        }, ok -> {
            selectedId = null;
            hub.refreshBenchmarks(this::fillList);
        });
    }

    // MARK: Screen

    @Override
    public void shown() {
        hub.refreshBenchmarks(() -> {
            fillList();
            load();
        });
    }

    @Override
    public void hubChanged() {
        if (isDisposed()) return;
        if (!hub.benchmarks.equals(listed)) fillList();
    }

    @Override
    public Control control() { return this; }

    @Override
    public Long selectedItem() { return selectedId == null ? null : selectedId.longValue(); }

    @Override
    public void selectItem(long id) {
        selectInList((int) id);
        select((int) id);
    }

    @Override
    public String itemTitle(long id) {
        return hub.benchmarks.stream().filter(b -> b.id() == id).findFirst().map(b -> b.code() + " " + b.name()).orElse(null);
    }

    @Override
    public QuickHelpItem selectionHelp() {
        BenchmarkRow b = selected();
        if (!hub.isConnected() || b == null) return null;
        return new QuickHelpItem(b.code() + " — " + b.name(), b.method().replace('_', ' ') + "-weighted, from " + b.source()
                + (b.asOf().isEmpty() ? "; no constituents loaded." : "; " + b.members() + " members on " + b.asOf() + ".")
                + (b.proxy() ? " A proxy: " + b.proxyNote() : "")
                + " Its sector weights, index level and constituents are on the right; Track… builds a tracking portfolio in MxJobsMGR.");
    }

    // MARK: Build Custom…

    static final class BuildDialog extends PortfolioDialogs.Checked {
        private final Hub hub;
        private Text code, name;
        private Combo parent, method, sector;
        private List<BenchmarkRow> parents;
        String codeValue = "", nameValue = "", parentValue = "SP500", methodValue = "equal", sectorValue;

        BuildDialog(Shell shell, Hub hub) {
            super(shell);
            this.hub = hub;
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Build Custom Benchmark");
        }

        @Override
        protected Control createDialogArea(Composite p) {
            Composite area = (Composite) super.createDialogArea(p);
            area.setLayout(Ui.grid(2, 14, 8));
            Ui.label(area, "Build Custom Benchmark", Ui.subheading()).setLayoutData(Ui.span(2));
            Ui.label(area, "Code");
            code = new Text(area, SWT.BORDER);
            code.setMessage("SP500_EW");
            code.setLayoutData(Ui.fillH());
            code.setText(codeValue);
            Ui.label(area, "Name");
            name = new Text(area, SWT.BORDER);
            name.setMessage("S&P 500 Equal Weight");
            name.setLayoutData(Ui.fillH());
            name.setText(nameValue);
            Ui.label(area, "Members of");
            parent = new Combo(area, SWT.READ_ONLY);
            parent.setLayoutData(Ui.fillH());
            parents = hub.benchmarks.stream().filter(b -> b.members() > 0).toList();
            for (BenchmarkRow b : parents) parent.add(b.code() + " — " + b.name());
            for (int i = 0; i < parents.size(); i++) if (parents.get(i).code().equals(parentValue)) parent.select(i);
            if (parent.getSelectionIndex() < 0 && !parents.isEmpty()) parent.select(0);
            Ui.label(area, "Weighting");
            method = new Combo(area, SWT.READ_ONLY);
            method.setItems("Equal weight", "Market cap (latest FMP / Nasdaq caps)");
            method.select("cap".equals(methodValue) ? 1 : 0);
            Ui.label(area, "Sector");
            sector = new Combo(area, SWT.READ_ONLY);
            sector.add("All sectors");
            hub.sectors.forEach(sector::add);
            int si = sectorValue == null ? -1 : hub.sectors.indexOf(sectorValue);
            sector.select(si < 0 ? 0 : si + 1);
            Label note = Ui.wrap(area, "Uses the members on the latest date. Market-cap weighting needs market caps, which the FMP profile loader stores; "
                    + "members without one are left out. A sector filter needs FMP sectors.");
            note.setForeground(Ui.SECONDARY);
            note.setFont(Ui.small());
            ((GridData) note.getLayoutData()).horizontalSpan = 2;
            problemsLabel(area, 2);
            code.addListener(SWT.Modify, e -> check());
            name.addListener(SWT.Modify, e -> check());
            return area;
        }

        @Override
        List<String> problems() {
            List<String> out = new ArrayList<>();
            String c = code.getText();
            if (!c.matches("[A-Za-z0-9_]{2,30}")) out.add("Code: 2–30 letters, digits or _");
            hub.benchmarks.stream().filter(b -> b.code().equals(c.toUpperCase()) && !b.custom()).findFirst()
                    .ifPresent(b -> out.add(b.code() + " is a loaded benchmark; choose another code"));
            if (name.getText().trim().isEmpty()) out.add("Enter a name");
            if (parents.isEmpty()) out.add("No benchmark has constituents to build from");
            return out;
        }

        @Override
        protected void createButtonsForButtonBar(Composite p) {
            createButton(p, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            createButton(p, IDialogConstants.OK_ID, "Build", true);
            check();
        }

        @Override
        protected void okPressed() {
            if (!problems().isEmpty()) return;
            codeValue = code.getText();
            nameValue = name.getText();
            parentValue = parents.get(Math.max(0, parent.getSelectionIndex())).code();
            methodValue = method.getSelectionIndex() == 1 ? "cap" : "equal";
            sectorValue = sector.getSelectionIndex() <= 0 ? null : hub.sectors.get(sector.getSelectionIndex() - 1);
            super.okPressed();
        }
    }

    // MARK: Availability…

    /** Which benchmarks the subscriptions and free sources can supply, and why. */
    static final class AvailabilityDialog extends Dialog {
        private static final String[][] CAN = {
                { "S&P 500", "Members, weights and shares from State Street's daily SPY holdings (SPY fully replicates the index). Index level ^GSPC from FMP." },
                { "Dow Jones Industrial Average", "Members and weights from the DIA holdings (price-weighted). Level ^DJI from FMP." },
                { "S&P MidCap 400 / SmallCap 600", "Members and weights from the MDY / SPSM holdings. No index levels on the FMP plan." },
                { "Nasdaq-100", "Official members from Nasdaq.com with market caps; weights are plain cap weights, not Nasdaq's modified-cap weights. No ^NDX levels on the FMP plan." },
                { "Custom", "Equal- or cap-weighted subsets of any loaded benchmark, optionally by sector (Build Custom…)." } };
        private static final String[][] CANNOT = {
                { "Official S&P / Dow / Nasdaq weights", "Licensed index data. FMP's sp500-constituent, nasdaq-constituent, dowjones-constituent and historical constituent endpoints return 402 on this plan." },
                { "ETF holdings via FMP", "etf/holdings is not on the plan (402); SPDR files are read directly instead." },
                { "Russell 1000 / 2000 / 3000", "FTSE Russell data is licensed; iShares holdings downloads (IWB, IWM) were blocked when probed. Would need iShares files or a paid feed." },
                { "MSCI, FTSE, STOXX and other non-US indexes", "Licensed; no free constituent source, and the securities universe loaded here is US listings." },
                { "Constituent history before the first load", "Free sources publish only the current holdings; history accumulates one snapshot per load from now on." } };

        AvailabilityDialog(Shell shell) {
            super(shell);
            setShellStyle(getShellStyle() | SWT.RESIZE);
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Benchmark availability");
        }

        @Override
        protected Control createDialogArea(Composite p) {
            Composite area = (Composite) super.createDialogArea(p);
            area.setLayout(Ui.grid(1, 14, 10));
            Ui.label(area, "Benchmark availability", Ui.subheading());
            group(area, "Can be built", CAN, "✔", Ui.GREEN);
            group(area, "Cannot be built", CANNOT, "✖", Ui.RED);
            return area;
        }

        private static void group(Composite parent, String title, String[][] items, String mark, org.eclipse.swt.graphics.Color color) {
            Group g = new Group(parent, SWT.NONE);
            g.setText(title);
            g.setLayout(new FillLayout());
            g.setLayoutData(Ui.fillH());
            StyledText st = new StyledText(g, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
            st.setBackground(g.getBackground());
            st.setMargins(8, 6, 8, 6);
            StringBuilder sb = new StringBuilder();
            List<org.eclipse.swt.custom.StyleRange> ranges = new ArrayList<>();
            for (String[] item : items) {
                int start = sb.length();
                sb.append(mark).append("  ");
                ranges.add(new org.eclipse.swt.custom.StyleRange(start, mark.length(), color, null));
                int b = sb.length();
                sb.append(item[0]);
                ranges.add(new org.eclipse.swt.custom.StyleRange(b, item[0].length(), null, null, SWT.BOLD));
                sb.append(" — ").append(item[1]).append('\n');
            }
            st.setText(sb.toString().stripTrailing());
            st.setStyleRanges(ranges.toArray(org.eclipse.swt.custom.StyleRange[]::new));
            st.setWrapIndent(24);
        }

        @Override
        protected void createButtonsForButtonBar(Composite p) {
            createButton(p, IDialogConstants.OK_ID, "Close", true);
        }

        @Override
        protected org.eclipse.swt.graphics.Point getInitialSize() {
            return new org.eclipse.swt.graphics.Point(680, 560);
        }
    }
}
