package com.k3jdr.mxacctmgr.pages;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Screen;
import com.k3jdr.mxacctmgr.Settings;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.Portfolio;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.text.Lessons;
import com.k3jdr.mxacctmgr.text.ReportCatalog;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;

/**
 * Knowledge Library pages: the Report Library (ReportsHubViews.swift — every report by tier as a card, If you had to
 * pick five, the driver matrix, each card linking to the real report at its publisher), Sample Portfolios
 * (SamplesView.swift) and the Report Walkthrough's first step (the report and its official sources; the simulated
 * steps are not on Windows yet).
 */
public final class LibraryPages {
    private LibraryPages() {}

    static String reportId() { return Settings.PREFS.get("report", "jobs"); }

    static void openReport(App app, String id) {
        Settings.PREFS.put("report", id);
        app.go(Screen.REPORT_WALKTHROUGH);
    }

    /** A report's card: publisher, schedule, key metrics, why PMs care, drivers, impact, the real report. */
    static void card(Composite parent, App app, ReportCatalog.Report r, boolean walkthroughButton) {
        Composite c = new Composite(parent, SWT.BORDER);
        c.setLayout(Ui.grid(1, 10, 4));
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 300;
        c.setLayoutData(gd);
        c.setBackground(Parts.BOX_BG);
        c.setBackgroundMode(SWT.INHERIT_DEFAULT);
        Ui.label(c, r.title(), Settings.heading());
        if (r.topFive() != null) {
            Label b = Ui.label(c, String.format(ReportCatalog.TOP_FIVE_BADGE_FORMAT, r.topFive()));
            b.setForeground(Ui.ACCENT);
        }
        Parts.secondary(c, r.publisher());
        Parts.secondary(c, r.schedule());
        Parts.text(c, ReportCatalog.KEY_METRICS_PREFIX + String.join(", ", r.keyMetrics()));
        Parts.text(c, String.join(" ", r.whyCare()));
        Parts.caption(c, String.join(ReportCatalog.DRIVER_SEPARATOR, r.drivers().stream().map(d -> d.label).toList()) + "   ·   impact "
                + ReportCatalog.IMPACT_WORDS.get(Math.max(0, Math.min(3, r.impact()))));
        if (!r.sources().isEmpty()) {
            ReportCatalog.Source s = r.sources().get(0);
            Link link = new Link(c, SWT.WRAP);
            link.setText("<a>" + Ui.escape(String.format(ReportCatalog.SOURCE_LINK_FORMAT, s.publisher(), s.title())) + "</a>");
            link.setToolTipText(String.format(ReportCatalog.SOURCE_LINK_HELP_FORMAT, s.publisher(), s.url()));
            link.addListener(SWT.Selection, e -> Program.launch(s.url()));
            link.setLayoutData(Ui.fillH());
            QuickHelp.help(link, ReportCatalog.OFFICIAL_SOURCE_TITLE, String.format(ReportCatalog.OFFICIAL_SOURCE_FORMAT, r.title(), s.title(), s.publisher()));
        }
        if (walkthroughButton) {
            Button w = Ui.button(c, ReportCatalog.OPEN_WALKTHROUGH, () -> openReport(app, r.id()));
            w.setToolTipText(ReportCatalog.OPEN_WALKTHROUGH_HELP);
        }
        QuickHelp.help(c, r.title(), String.format(ReportCatalog.REPORT_CARD_QUICK_HELP_FORMAT, r.publisher(), r.schedule(),
                String.join(", ", r.keyMetrics()), String.join(" ", r.whyCare())));
        menu(c, r);
    }

    private static void menu(Composite c, ReportCatalog.Report r) {
        if (r.sources().isEmpty()) return;
        Menu m = new Menu(c);
        ReportCatalog.Source s = r.sources().get(0);
        MenuItem open = new MenuItem(m, SWT.PUSH);
        open.setText(Ui.escape(String.format(ReportCatalog.OPEN_OFFICIAL_SOURCE_FORMAT, s.publisher())));
        open.addListener(SWT.Selection, e -> Program.launch(s.url()));
        MenuItem copy = new MenuItem(m, SWT.PUSH);
        copy.setText(ReportCatalog.COPY_OFFICIAL_SOURCE);
        copy.addListener(SWT.Selection, e -> Clipboards.copyText(c.getDisplay(), s.url()));
        c.setMenu(m);
    }

    /** Reports ▸ Report Library. */
    public static final class ReportLibrary extends Page {
        public ReportLibrary(App app) {
            super(app);
        }

        @Override
        protected void build(Composite body) {
            Composite five = Parts.box(body, ReportCatalog.TOP_FIVE_TITLE, ReportCatalog.TOP_FIVE_HELP);
            Parts.text(five, ReportCatalog.TOP_FIVE_TEXT);
            Composite row = Parts.tiles(five, ReportCatalog.TOP_FIVE.size());
            for (ReportCatalog.Report r : ReportCatalog.TOP_FIVE) {
                Button b = Ui.button(row, "#" + r.topFive() + "  " + r.title(), () -> openReport(app, r.id()));
                b.setLayoutData(Ui.fillH());
                b.setToolTipText(String.format(ReportCatalog.OPEN_WALKTHROUGH_HELP_FORMAT, r.title()));
                QuickHelp.help(b, r.title(), String.join(" ", r.whyCare()) + " " + ReportCatalog.TOP_FIVE_QUICK_HELP_END);
            }
            Composite morning = Parts.box(body, ReportCatalog.MORNING_READING_TITLE, ReportCatalog.MORNING_READING_HELP);
            Parts.text(morning, ReportCatalog.MORNING_READING_TEXT);
            Composite links = Parts.row(morning, 4);
            Ui.button(links, ReportCatalog.OPEN_MORNING_BRIEFING, () -> app.go(Screen.MORNING_BRIEFING));
            Ui.button(links, ReportCatalog.OPEN_VIX, () -> app.go(Screen.VIX)).setToolTipText(ReportCatalog.OPEN_VIX_HELP);
            Ui.button(links, ReportCatalog.OPEN_COMPUTE, () -> app.go(Screen.COMPUTE)).setToolTipText(ReportCatalog.OPEN_COMPUTE_HELP);
            new Label(links, SWT.NONE);
            for (ReportCatalog.Tier t : ReportCatalog.TIERS) {
                Composite tier = Parts.box(body, t.title(), t.description());
                Parts.text(tier, t.description()).setForeground(Ui.SECONDARY);
                Composite grid = new Composite(tier, SWT.NONE);
                GridLayout gl = new GridLayout(3, true);
                gl.marginWidth = 0;
                gl.horizontalSpacing = 10;
                gl.verticalSpacing = 10;
                grid.setLayout(gl);
                grid.setLayoutData(Ui.fillH());
                for (ReportCatalog.Report r : ReportCatalog.reportsInTier(t.id())) card(grid, app, r, true);
            }
            Composite matrix = Parts.box(body, ReportCatalog.DRIVER_MATRIX_TITLE, ReportCatalog.DRIVER_MATRIX_HELP);
            Parts.Col[] cols = new Parts.Col[ReportCatalog.DRIVERS.size() + 2];
            cols[0] = Parts.Col.l(ReportCatalog.DRIVER_MATRIX_REPORT, 260);
            for (int i = 0; i < ReportCatalog.DRIVERS.size(); i++) cols[i + 1] = Parts.Col.c(ReportCatalog.DRIVERS.get(i).label, 120);
            cols[cols.length - 1] = Parts.Col.l(ReportCatalog.DRIVER_MATRIX_IMPACT, 80);
            Table t = Parts.table(matrix, SWT.MULTI, cols);
            for (ReportCatalog.Report r : ReportCatalog.REPORTS) {
                String[] cells = new String[cols.length];
                cells[0] = r.title();
                for (int i = 0; i < ReportCatalog.DRIVERS.size(); i++) cells[i + 1] = r.drivers().contains(ReportCatalog.DRIVERS.get(i)) ? ReportCatalog.DRIVER_MATRIX_YES : "";
                cells[cols.length - 1] = ReportCatalog.IMPACT_WORDS.get(Math.max(0, Math.min(3, r.impact())));
                TableItem it = new TableItem(t, SWT.NONE);
                it.setData(r);
                it.setText(cells);
            }
            Parts.rows(t, ReportCatalog.REPORTS.size());
            t.addListener(SWT.DefaultSelection, e -> {
                TableItem[] sel = t.getSelection();
                if (sel.length == 1) openReport(app, ((ReportCatalog.Report) sel[0].getData()).id());
            });
            QuickHelp.provider(t, (c, p) -> {
                TableItem it = t.getItem(p);
                if (it == null) return null;
                ReportCatalog.Report r = (ReportCatalog.Report) it.getData();
                return new QuickHelpItem(r.title(), r.publisher() + " · " + r.schedule() + ". " + ReportCatalog.REPORT_LINK_HINT + " (double-click). Impact: "
                        + ReportCatalog.IMPACT_HELP.get(Math.max(0, Math.min(3, r.impact()))) + ".");
            });
        }
    }

    /** Library ▸ Sample Portfolios. */
    public static final class Samples extends Page {
        public Samples(App app) {
            super(app);
        }

        @Override
        public void stateChanged() {
            // The samples arrive from the security master after the page opens.
            if (loaded != app.port.accountsLoaded || !app.port.selectedID().equals(selected)) {
                Composite body = this.body;
                if (body != null && !body.isDisposed()) {
                    Page.clear(body);
                    build(body);
                    relayout();
                }
            }
        }

        private Composite body;
        private boolean loaded;
        private String selected;

        @Override
        protected void build(Composite body) {
            this.body = body;
            loaded = app.port.accountsLoaded;
            selected = app.port.selectedID();
            Composite grid = new Composite(body, SWT.NONE);
            GridLayout gl = new GridLayout(2, true);
            gl.marginWidth = 0;
            gl.horizontalSpacing = 14;
            gl.verticalSpacing = 14;
            grid.setLayout(gl);
            grid.setLayoutData(Ui.fillH());
            for (Portfolio p : app.port.portfolios()) card(grid, p);
        }

        private void card(Composite parent, Portfolio p) {
            boolean selected = p.id.equals(app.port.selectedID());
            Composite c = new Composite(parent, SWT.BORDER);
            c.setLayout(Ui.grid(1, 12, 6));
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            gd.widthHint = 320;
            c.setLayoutData(gd);
            c.setBackground(selected ? Parts.MINE_BG : Parts.BOX_BG);
            c.setBackgroundMode(SWT.INHERIT_DEFAULT);
            Composite head = Parts.row(c, 2);
            Ui.label(head, p.name, Settings.heading()).setLayoutData(Ui.fillH());
            Label mark = Ui.label(head, selected ? "✔ in use" : "");
            mark.setForeground(Ui.GREEN);
            Parts.secondary(c, p.account.title + " · " + Fmt.money(p.total()) + " · vs " + p.benchmark + " · " + p.style.title);
            if (p.lesson != null) Parts.text(c, p.lesson);
            if (p.lookFor != null) for (String l : p.lookFor) Parts.caption(c, "👁 " + l);
            List<Portfolio.Issue> issues = p.issues();
            if (!issues.isEmpty()) {
                Label i = Ui.wrap(c, issues.size() + " check" + (issues.size() == 1 ? "" : "s") + ": " + String.join("; ", issues.stream().map(Portfolio.Issue::title).toList()));
                i.setForeground(Ui.ORANGE);
                i.setFont(Ui.small());
            }
            Composite buttons = Parts.row(c, 3);
            Button use = Ui.button(buttons, selected ? "In Use" : "Use", () -> app.port.select(p.id));
            use.setEnabled(!selected);
            QuickHelp.help(use, "Use", "Makes it the account of the report walkthroughs and the Morning Briefing.");
            if (p.id.equals("mine")) {
                Ui.button(buttons, "Open in Investments", () -> app.go(Screen.MY_PORTFOLIO));
            } else {
                Button copy = Ui.button(buttons, "Copy to My Portfolio", () -> {
                    app.port.copyToMine(p);
                    app.go(Screen.MY_PORTFOLIO);
                });
                copy.setToolTipText("Make an editable copy of these holdings as My Portfolio (replaces it)");
                copy.setEnabled(app.port.openPortfolio() != null && !p.holdings.isEmpty());
            }
            new Label(buttons, SWT.NONE);
            QuickHelp.help(c, p.name, (p.lesson == null ? "" : p.lesson) + (p.lookFor == null ? "" : " Look for: " + String.join(" ", p.lookFor)));
        }
    }

    /** Report Walkthrough: the report (its card and official sources); the walkthrough's later steps aren't ported. */
    public static final class Walkthrough extends Page {
        private final ReportCatalog.Report report = ReportCatalog.report(reportId());

        public Walkthrough(App app) {
            super(app);
        }

        @Override
        protected String title() { return report == null ? super.title() : report.title(); }

        @Override
        protected Lessons.Lesson lesson() {
            if (report == null) return super.lesson();
            return Lessons.reportWalkthrough(report.title(), report.headline() == null ? "" : report.headline().replace("{company}", "a large company"));
        }

        @Override
        protected void build(Composite body) {
            if (report == null) {
                Parts.text(body, "Choose a report in the Reports menu.");
                return;
            }
            Composite two = new Composite(body, SWT.NONE);
            GridLayout gl = new GridLayout(2, false);
            gl.marginWidth = 0;
            gl.horizontalSpacing = 14;
            two.setLayout(gl);
            two.setLayoutData(Ui.fillH());
            card(two, app, report, false);
            Composite sources = Parts.box(two, "The real report", "The publisher's own pages for the report: its latest release, release schedule, and its history or live data.");
            ((GridData) sources.getParent().getLayoutData()).verticalAlignment = SWT.FILL;
            for (ReportCatalog.Source s : report.sources()) {
                Link link = new Link(sources, SWT.WRAP);
                link.setText(Ui.escape(s.kind().label + ": ") + "<a>" + Ui.escape(s.publisher() + " — " + s.title()) + "</a>");
                link.setToolTipText(s.url());
                link.addListener(SWT.Selection, e -> Program.launch(s.url()));
                link.setLayoutData(Ui.fillH());
                QuickHelp.help(link, s.kind().label, s.url());
            }
            Composite next = Parts.box(body, "The walkthrough", Help.screen(Screen.REPORT_WALKTHROUGH.id));
            Parts.text(next, Help.screen(Screen.REPORT_WALKTHROUGH.id));
            Parts.caption(next, "The walkthrough's seven steps — Reading It, The Market's Move, Your Account, Your Reaction, The Long Run (the Behaviour Lab's simulated futures) and Takeaways — are in MxAcctMGR for macOS; on Windows this page shows the report and its official sources (see PARITY.md). Choose another report in the Reports menu.");
            Composite others = Parts.row(next, 2);
            Ui.button(others, "Report Library", () -> app.go(Screen.REPORT_LIBRARY));
            new Label(others, SWT.NONE);
        }
    }
}
