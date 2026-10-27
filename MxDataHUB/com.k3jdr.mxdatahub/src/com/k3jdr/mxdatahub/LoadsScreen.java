package com.k3jdr.mxdatahub;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Rows.*;

/**
 * Load History (SRMLoadsView.swift): identifier coverage, the data sources with their plan limits, the weight history
 * (dated benchmark and portfolio snapshots) and the load audit trail (srm.load_run). Loads themselves run from Data
 * Refresh or SRM/srm.py.
 */
final class LoadsScreen extends Composite implements Screen {
    private final Hub hub;
    private final Table coverage, sources, snapshots, loads;
    private final Label snapshotNote;
    private int generation;

    LoadsScreen(Composite parent, Hub hub, MainPart main) {
        super(parent, SWT.NONE);
        this.hub = hub;
        setLayout(new FillLayout());
        SplitPanes vertical = new SplitPanes(this, "loadsVertical", 220, 2000, 300, 200, true);

        // Coverage | sources | snapshot history.
        Composite top = vertical.leading();
        top.setLayout(new FillLayout());
        SplitPanes first = new SplitPanes(top, "loadsCoverage", 340, 640, 440, 647);
        Composite cov = pane(first.leading(), "Identifier coverage");
        QuickHelpItem coverageHelp = new QuickHelpItem("Identifier Coverage",
                "How many securities have each kind of identifier (ISIN, CUSIP, FIGI, LEI…), as a share of all, and the sources they came from.");
        coverage = Tables.create(cov, true, coverageHelp, Tables.col("Identifier", 170), Tables.num("Securities", 80),
                Tables.col("Coverage", 130), Tables.col("Sources", 160));
        Tables.copyMenu(coverage);
        coverageBars(coverage);
        QuickHelp.help(cov, coverageHelp.title(), coverageHelp.detail());

        first.trailing().setLayout(new FillLayout());
        SplitPanes second = new SplitPanes(first.trailing(), "loadsSources", 300, 600, 380, 340);
        Composite src = pane(second.leading(), "Sources");
        QuickHelpItem sourcesHelp = new QuickHelpItem("Sources",
                "The data sources the loaders read, when each last loaded, and its terms and plan limits (rest the pointer on a note for all of it).");
        sources = Tables.create(src, true, sourcesHelp, Tables.col("Code", 100), Tables.col("Last load", 110), Tables.col("Terms and plan", 300));
        Tables.copyMenu(sources);
        tooltips(sources, 2);
        QuickHelp.help(src, sourcesHelp.title(), sourcesHelp.detail());

        Composite snap = pane(second.trailing(), "Snapshot history");
        QuickHelpItem snapshotHelp = new QuickHelpItem("Snapshot History",
                "The dated weight snapshots of each benchmark and portfolio: how many, from when to when, over how many months, and the longest gap. "
                        + "Attribution uses the snapshot at the start of each month, so fewer than two snapshots or a gap over a month shows in orange.");
        snapshots = Tables.create(snap, true, snapshotHelp, Tables.col("Weights of", 110), Tables.num("Snapshots", 70),
                Tables.col("From", 85), Tables.col("To", 85), Tables.num("Months", 60), Tables.num("Longest gap", 80));
        Tables.copyMenu(snapshots);
        tooltips(snapshots, -1);
        snapshotNote = Ui.label(snap, "Needs migration V010 (SRM/srm.py migrate).");
        snapshotNote.setForeground(Ui.SECONDARY);
        snapshotNote.setFont(Ui.small());
        snapshotNote.setLayoutData(Ui.fillH());
        Ui.setVisible(snapshotNote, false);
        QuickHelp.help(snap, snapshotHelp.title(), snapshotHelp.detail());

        // The loads.
        Composite bottom = vertical.trailing();
        bottom.setLayout(Ui.grid(1, 10, 6));
        Composite head = new Composite(bottom, SWT.NONE);
        head.setLayout(Ui.grid(2, 0, 6));
        head.setLayoutData(Ui.fillH());
        Ui.label(head, "Loads", Ui.subheading()).setLayoutData(Ui.fillH());
        Button refresh = Ui.button(head, "⟳ Refresh", this::reload);
        QuickHelp.help(refresh, "Refresh", "Reads the coverage, sources, snapshots and loads again.");
        QuickHelpItem loadsHelp = new QuickHelpItem("Loads", "Every loader run (srm.load_run), newest first: when, which loader, how it ended, "
                + "rows read, inserted, updated and rejected, API calls, how long it took and its message.");
        loads = Tables.create(bottom, true, loadsHelp, Tables.num("#", 50), Tables.col("Started", 140), Tables.col("Loader", 150),
                Tables.col("Status", 75), Tables.num("Read", 65), Tables.num("Inserted", 65), Tables.num("Updated", 65),
                Tables.num("Rejected", 65), Tables.num("API calls", 65), Tables.col("Time", 70), Tables.col("Message", 400));
        Tables.copyMenu(loads);
        tooltips(loads, 10);
        Composite foot = new Composite(bottom, SWT.NONE);
        foot.setLayout(Ui.grid(2, 0, 8));
        foot.setLayoutData(Ui.fillH());
        Label note = Ui.label(foot, "Refresh data in Data Refresh, or with SRM/srm.py load all; automate it in MxJobsMGR.");
        note.setForeground(Ui.SECONDARY);
        note.setFont(Ui.small());
        Button toRefresh = Ui.button(foot, "Data Refresh", () -> main.show(Section.REFRESH));
        QuickHelp.help(toRefresh, "Data Refresh", "Goes to Data Refresh, to see how fresh each category is and run its loaders.", "Ctrl+5");
    }

    private static Composite pane(Composite parent, String title) {
        parent.setLayout(Ui.grid(1, 10, 6));
        Ui.label(parent, title, Ui.subheading());
        return parent;
    }

    /** A bar of the share in the Coverage column, before its percentage. */
    private static void coverageBars(Table t) {
        t.addListener(SWT.PaintItem, e -> {
            if (e.index != 2 || !(e.item.getData() instanceof CoverageRow r)) return;
            TableItem item = (TableItem) e.item;
            Rectangle b = item.getBounds(2);
            int w = 56, h = 6, x = b.x + 4, y = b.y + (b.height - h) / 2;
            e.gc.setBackground(Ui.BORDER);
            e.gc.fillRoundRectangle(x, y, w, h, 4, 4);
            e.gc.setBackground(Ui.ACCENT);
            e.gc.fillRoundRectangle(x, y, (int) Math.round(Math.min(1, r.percent() / 100) * w), h, 4, 4);
        });
    }

    /** The full text of a long cell (or the row's note) as the table's tooltip. */
    private static void tooltips(Table t, int column) {
        t.addListener(SWT.MouseMove, e -> {
            TableItem item = t.getItem(new org.eclipse.swt.graphics.Point(e.x, e.y));
            String tip = null;
            if (item != null) {
                Object tag = item.getData("tip");
                if (tag instanceof String s) tip = s;
                else if (column >= 0 && item.getBounds(column).contains(e.x, e.y)) tip = item.getText(column);
            }
            if (tip != null && tip.isEmpty()) tip = null;
            if (!java.util.Objects.equals(tip, t.getToolTipText())) t.setToolTipText(tip);
        });
    }

    /** Reads the coverage, sources, snapshots and loads again. */
    void reload() {
        if (!hub.isConnected() || !Boolean.TRUE.equals(hub.available)) return;
        int gen = ++generation;
        hub.run(this, hub.db::audit, a -> {
            if (gen != generation || isDisposed()) return;
            Tables.fill(coverage, a.coverage(), r -> new String[] { r.name(), Fmt.count(r.securities()),
                    "                " + Fmt.plain(r.percent()) + "%", r.sources() });
            Tables.fill(sources, a.sources(), r -> new String[] { r.code(), r.lastLoad(), r.note() });
            Tables.fill(snapshots, a.snapshots(), r -> new String[] { r.code(), Fmt.count(r.snapshots()), r.first(), r.last(),
                    Fmt.count(r.months()), r.maxGapDays() == null ? "—" : r.maxGapDays() + " d" });
            for (TableItem item : snapshots.getItems()) {
                if (!(item.getData() instanceof SnapshotCoverageRow r)) continue;
                if (r.snapshots() < 2) item.setForeground(1, Ui.ORANGE);
                if (r.maxGapDays() != null && r.maxGapDays() > 35) item.setForeground(5, Ui.ORANGE);
                item.setData("tip", (r.kind().equals("benchmark") ? "Benchmark" : "Portfolio")
                        + (r.drifted() > 0 ? " · " + r.drifted() + " carried forward by portfolio-snapshots" : "")
                        + " · longest stretch between snapshots; over a month means a month used an older one");
            }
            Ui.setVisible(snapshotNote, a.snapshots().isEmpty());
            snapshotNote.getParent().layout(true);
            Tables.fill(loads, a.loads(), r -> new String[] { Long.toString(r.id()), r.started(), r.loader(), r.status(),
                    Fmt.count(r.read()), Fmt.count(r.inserted()), Fmt.count(r.updated()), Fmt.count(r.rejected()),
                    Fmt.count(r.apiCalls()), r.duration(), r.message() });
            for (TableItem item : loads.getItems()) {
                if (!(item.getData() instanceof LoadRow r)) continue;
                item.setForeground(3, switch (r.status()) {
                    case "succeeded" -> Ui.GREEN;
                    case "partial" -> Ui.ORANGE;
                    case "running" -> ActivityPanel.BLUE;
                    default -> Ui.RED;
                });
            }
        });
    }

    @Override
    public void shown() { reload(); }

    @Override
    public Control control() { return this; }

}
