package com.k3jdr.mxjobsmgr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import jakarta.annotation.PostConstruct;

import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.IWindowCloseHandler;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.json.Json;
import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.links.AppLinks;
import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mx.core.ui.WindowState;
import com.k3jdr.mxjobsmgr.engine.RunEngine.Phase;
import com.k3jdr.mxjobsmgr.engine.SecureAccounts;
import com.k3jdr.mxjobsmgr.engine.Validator;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.NodeKind;
import com.k3jdr.mxjobsmgr.model.Samples;
import com.k3jdr.mxjobsmgr.model.Specs.StepLink;
import com.k3jdr.mxjobsmgr.ui.DiagramExport;
import com.k3jdr.mxjobsmgr.ui.Editor;
import com.k3jdr.mxjobsmgr.ui.JobsHelp;
import com.k3jdr.mxjobsmgr.ui.LinkDialog;
import com.k3jdr.mxjobsmgr.ui.Palette;
import com.k3jdr.mxjobsmgr.ui.PaletteEdits;
import com.k3jdr.mxjobsmgr.ui.ProblemsPopup;
import com.k3jdr.mxjobsmgr.ui.SubscriptionsDialog;
import com.k3jdr.mxjobsmgr.ui.TextsDialog;
import com.k3jdr.mxjobsmgr.ui.UtilityWindows;

/**
 * MxJobsMGR's window (MxJobsMGRApp.swift, ContentView.swift): the palette and Quick Help on the left, one tab per open
 * jobspace — run bar, swim-lane canvas, run console and inspector — and the menus: File (New, New from Sample, Open,
 * Open Recent, Save, Save As, Close, Revert, Export), Edit, View, Run, Diagram, Window and Help.
 */
public class MainPart {
    private static MainPart instance;
    private static final String GITHUB = "https://github.com/k3jdr/MxAppsIOS/tree/main/MxJobsMGR";
    private final Prefs prefs = Prefs.of("com.k3jdr.mxjobsmgr");

    private Shell shell;
    private CTabFolder tabs;
    private Composite right, empty;
    private StackLayout stack;
    private Label status;
    private Palette palette;
    private final List<Editor> editors = new ArrayList<>();
    private int untitledCount;
    private boolean inspectorShown;
    private boolean subscriptionsOpen;

    @PostConstruct
    public void create(Composite parent, MWindow window) {
        instance = this;
        shell = parent.getShell();
        WindowState.track(shell, LifeCycle.APP);
        inspectorShown = prefs.getBoolean("inspector", true);
        parent.setLayout(new org.eclipse.swt.layout.FillLayout());

        SplitPanes split = new SplitPanes(parent, "jobs.palette", 210, 360, 250, 600);
        Composite left = split.leading();
        left.setLayout(Ui.grid(1, 0, 0));
        left.setBackground(Ui.SIDEBAR);
        palette = new Palette(left, this);
        QuickHelp.get().createDockedPanel(left);

        right = split.trailing();
        right.setLayout(Ui.grid(1, 0, 0));
        Composite stackHost = new Composite(right, SWT.NONE);
        stackHost.setLayoutData(Ui.fill());
        stack = new StackLayout();
        stackHost.setLayout(stack);
        tabs = new CTabFolder(stackHost, SWT.BORDER | SWT.CLOSE | SWT.FLAT);
        tabs.setSimple(false);
        tabs.setMRUVisible(true);
        tabs.addListener(SWT.Selection, e -> activeChanged());
        tabs.addCTabFolder2Listener(new CTabFolder2Adapter() {
            @Override
            public void close(CTabFolderEvent e) {
                e.doit = false;
                Editor ed = (Editor) e.item.getData();
                if (ed != null) MainPart.this.close(ed);
            }
        });
        empty = buildEmpty(stackHost);
        status = new Label(right, SWT.NONE);
        status.setLayoutData(Ui.fillH());
        status.setForeground(Ui.SECONDARY);
        showStack();

        buildMenus();
        window.getContext().set(IWindowCloseHandler.class, w -> closeAll());
        QuickHelp.get().setScreen(() -> {
            Editor ed = active();
            if (subscriptionsOpen) return JobsHelp.SUBSCRIPTIONS;
            return ed == null ? JobsHelp.NO_DOCUMENT : JobsHelp.screen(ed.space, ed.issues.size());
        });
        QuickHelp.get().setSelection(() -> {
            Editor ed = active();
            return ed == null ? null : JobsHelp.selection(ed);
        });
        QuickHelp.get().addStateListener(() -> left.layout(true, true));

        String[] args = Platform.getApplicationArgs();
        Display.getCurrent().asyncExec(() -> handle(args == null ? List.of() : List.of(args)));
    }

    private Composite buildEmpty(Composite host) {
        Composite c = new Composite(host, SWT.NONE);
        c.setLayout(Ui.grid(1, 40, 12));
        c.setBackground(Ui.WHITE);
        Label t = Ui.label(c, "No jobspace open", Ui.heading());
        t.setBackground(Ui.WHITE);
        Label d = Ui.label(c, "Design the day-to-day financial market data operation as a three-layer swim-lane diagram, saved as a .jobspace file.");
        d.setForeground(Ui.SECONDARY);
        d.setBackground(Ui.WHITE);
        Composite b = new Composite(c, SWT.NONE);
        b.setBackground(Ui.WHITE);
        b.setLayout(Ui.grid(3, 0, 8));
        QuickHelp.help(Ui.button(b, "New Jobspace", this::newDocument), "New", "An empty jobspace with the three lanes.", "Ctrl+N");
        QuickHelp.help(Ui.button(b, "New from Sample…", this::newFromSample), "New from Sample…", JobsHelp.SAMPLES.detail(), "Ctrl+Alt+N");
        QuickHelp.help(Ui.button(b, "Open…", this::open), "Open…", "Opens a .jobspace file (or a .json file holding a jobspace).", "Ctrl+O");
        return c;
    }

    private void showStack() {
        stack.topControl = editors.isEmpty() ? empty : tabs;
        tabs.getParent().layout(true);
    }

    // MARK: Menus

    private void buildMenus() {
        MenuBarBuilder mb = new MenuBarBuilder(shell);
        MenuBarBuilder.MenuBuilder file = mb.menu("&File");
        file.item("&New", SWT.MOD1 | 'N', this::newDocument)
                .item("New from &Sample…", SWT.MOD1 | SWT.MOD3 | 'N', this::newFromSample);
        MenuBarBuilder.MenuBuilder newSample = file.submenu("New Sa&mple");
        for (ServiceCatalog.Sample s : ServiceCatalog.SAMPLES) newSample.item(Ui.escape(s.area() + ": " + s.title()), () -> openSample(s.id(), null, null));
        newSample.end()
                .item("&Open…", SWT.MOD1 | 'O', this::open)
                .dynamic("Open &Recent", m -> {
                    for (String f : prefs.getList("recent")) m.item(Ui.escape(f), () -> openFile(Path.of(f)));
                })
                .separator()
                .item("&Close", SWT.MOD1 | 'W', () -> withActive(this::close), this::hasActive)
                .item("&Save", SWT.MOD1 | 'S', () -> withActive(ed -> save(ed, null)), this::hasActive)
                .item("Save &As…", SWT.MOD1 | SWT.SHIFT | 'S', () -> withActive(ed -> saveAs(ed, null)), this::hasActive)
                .item("Re&vert to Saved", 0, () -> withActive(this::revert), () -> active() != null && active().file != null && active().isDirty())
                .separator()
                .item("Export Diagram as &PNG…", 0, () -> withActive(ed -> DiagramExport.savePNG(shell, ed.space)), this::hasActive)
                .item("Export Diagram as &draw.io SVG…", 0, () -> withActive(ed -> DiagramExport.saveDrawioSVG(shell, ed.space)), this::hasActive)
                .separator()
                .item("E&xit", SWT.MOD3 | SWT.F4, shell::close);

        MenuBarBuilder.MenuBuilder edit = mb.menu("&Edit");
        edit.item("&Undo", SWT.MOD1 | 'Z', () -> withActive(Editor::undo), () -> active() != null && active().canUndo())
                .item("&Redo", SWT.MOD1 | SWT.SHIFT | 'Z', () -> withActive(Editor::redo), () -> active() != null && active().canRedo())
                .separator();
        EditActions.addStandardItems(edit, () -> new EditActions.Target() {
            @Override public boolean canSelectAll() { return active() != null; }
            @Override public void selectAll() { withActive(Editor::selectAll); }
            @Override public boolean canDelete() { return active() != null && active().hasSelection(); }
            @Override public void delete() { withActive(Editor::deleteSelection); }
        });
        edit.separator()
                .item("D&uplicate Nodes", SWT.MOD1 | 'D', () -> withActive(Editor::duplicateSelection), () -> active() != null && !active().selectedNodes.isEmpty())
                .item("&Delete Selection", 0, () -> withActive(Editor::deleteSelection), () -> active() != null && active().hasSelection())
                .item("Select All &Nodes", SWT.MOD1 | SWT.MOD3 | 'A', () -> withActive(Editor::selectAll), this::hasActive);

        QuickHelp.get().addMenuItems(mb.menu("&View"));

        MenuBarBuilder.MenuBuilder run = mb.menu("&Run");
        // Run becomes Continue while a run is paused, as on the Mac (F8 continues too).
        run.item(() -> active() != null && active().run.canContinue() ? "&Continue" : "&Run", SWT.MOD1 | 'R',
                        () -> withActive(ed -> {
                            if (ed.run.canContinue()) ed.run.continueRun();
                            else ed.startRun();
                        }),
                        () -> active() != null && !(active().run.isActive() && !active().run.canContinue()))
                .item("Co&ntinue", SWT.F8, () -> withActive(ed -> ed.run.continueRun()), () -> active() != null && active().run.canContinue())
                .item("&Step", SWT.F6, () -> withActive(Editor::stepRun),
                        () -> active() != null && active().run.phase() != Phase.RUNNING && active().run.phase() != Phase.WAITING)
                .item("&Pause", 0, () -> withActive(ed -> ed.run.pause()), () -> active() != null && active().run.phase() == Phase.RUNNING)
                .item("S&top", SWT.MOD1 | '.', () -> withActive(ed -> ed.run.stop()), () -> active() != null && active().run.isActive())
                .separator()
                .item("Toggle &Breakpoint", SWT.MOD1 | '\\', () -> withActive(Editor::toggleBreakpoint), () -> active() != null && !active().selectedNodes.isEmpty())
                .check("Breakpoints &Active", SWT.MOD1 | 'Y', () -> active() != null && active().space.run.breakpointsEnabled,
                        v -> withActive(Editor::toggleBreakpoints))
                .separator()
                .item(() -> active() != null && active().isConsoleShown() ? "Hide Run C&onsole" : "Show Run C&onsole",
                        SWT.MOD1 | SWT.SHIFT | 'Y', () -> withActive(ed -> ed.setConsoleShown(!ed.isConsoleShown())), this::hasActive)
                .item("C&lear Run Results", 0, () -> withActive(ed -> ed.run.reset()), () -> active() != null && !active().run.isActive());

        MenuBarBuilder.MenuBuilder diagram = mb.menu("&Diagram");
        MenuBarBuilder.MenuBuilder addNode = diagram.submenu("&Add Node");
        for (Layer layer : Layer.values()) {
            addNode.item("Layer " + layer.number + " · " + layer.title, 0, () -> { }, () -> false);
            for (NodeKind kind : NodeKind.in(layer)) addNode.item("    " + kind.title, () -> withEditor(ed -> ed.add(kind)));
            if (layer != Layer.DATA) addNode.separator();
        }
        addNode.end()
                .separator()
                .item("Zoom &In", SWT.MOD1 | '=', () -> withActive(Editor::zoomIn), this::hasActive)
                .item("Zoom &Out", SWT.MOD1 | '-', () -> withActive(Editor::zoomOut), this::hasActive)
                .item("Actual &Size", SWT.MOD1 | '0', () -> withActive(Editor::actualSize), this::hasActive)
                .separator()
                .item("Show &Problems", SWT.MOD1 | SWT.SHIFT | 'K', () -> withActive(this::showProblems), this::hasActive)
                .item("API Su&bscriptions…", SWT.MOD1 | SWT.MOD3 | 'K', () -> withActive(ed -> showSubscriptions(ed, null)), this::hasActive)
                .check("&Inspector", SWT.MOD1 | SWT.MOD3 | 'I', () -> inspectorShown, this::setInspectorShown);

        mb.menu("&Window")
                .item("Layer 3 &Services", SWT.MOD1 | SWT.MOD3 | 'L', () -> showServices(null))
                .separator()
                .item("Open MxData&HUB", () -> {
                    if (!AppLinks.open(new AppLink.DataRefresh())) Program.launch("https://github.com/k3jdr/MxAppsIOS/tree/main/MxDataHUB");
                })
                .item("Open MxAcct&MGR", () -> {
                    if (!AppLinks.open(new AppLink.MyPortfolio(null))) Program.launch("https://github.com/k3jdr/MxAppsIOS/tree/main/MxAcctMGR");
                });

        mb.menu("&Help")
                .item("&Adding API Keys", () -> showKeysHelp(null))
                .item("&Register mxjobsmgr:// Links", this::registerLinks)
                .separator()
                .item("MxJobsMGR on &GitHub", () -> Program.launch(GITHUB))
                .item("A&bout MxJobsMGR", this::about);
        mb.install();
    }

    private void registerLinks() {
        String result = AppLinks.registerSchemes(LifeCycle.APP, AppLink.JOBS_SCHEME, AppLink.LEGACY_JOBS_SCHEME);
        MessageBox box = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
        box.setText("Register mxjobsmgr:// Links");
        box.setMessage(result + "\n\nMxDataHUB's Workflows and Data Refresh ▸ Automate in MxJobsMGR… open their samples here through these links.");
        box.open();
    }

    private void about() {
        MessageBox box = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
        box.setText("About MxJobsMGR");
        box.setMessage("MxJobsMGR — " + com.k3jdr.mx.core.Mx.FAMILY + "\n\nDesign the day-to-day financial market data operation as a three-layer swim-lane "
                + "diagram saved as JSON jobspace files, with API subscriptions, checks, and a run engine with approvals, breakpoints "
                + "and mock or live Layer 3 calls.\n\nJobspace files are the macOS app's: the same file opens on either system.");
        box.open();
    }

    // MARK: Editors

    public Editor active() {
        CTabItem item = tabs == null || tabs.isDisposed() ? null : tabs.getSelection();
        return item == null ? null : (Editor) item.getData();
    }

    private boolean hasActive() { return active() != null; }

    /** Whether there is a jobspace to add to (one is made when there is none). */
    public boolean hasEditor() { return true; }

    private void withActive(Consumer<Editor> action) {
        Editor ed = active();
        if (ed != null) action.accept(ed);
    }

    /** Acts on the front jobspace, making a new one first when none is open (palette, Services window). */
    public void withEditor(Consumer<Editor> action) {
        Editor ed = active();
        if (ed == null) ed = addEditor(new Jobspace(), null);
        action.accept(ed);
    }

    private Editor addEditor(Jobspace space, Path file) {
        String untitled = null;
        if (file == null) {
            untitled = !space.name.isBlank() && !space.name.equals("Untitled Jobspace") ? space.name
                    : ++untitledCount == 1 ? "Untitled" : "Untitled " + untitledCount;
        }
        CTabItem item = new CTabItem(tabs, SWT.CLOSE);
        Composite host = new Composite(tabs, SWT.NONE);
        host.setLayout(new org.eclipse.swt.layout.FillLayout());
        Editor ed = new Editor(this, host, space, file, untitled);
        ed.tab = item;
        item.setData(ed);
        item.setControl(host);
        editors.add(ed);
        tabs.setSelection(item);
        showStack();
        updateTitles(ed);
        activeChanged();
        linkSecureAccounts(ed);
        selfTest(ed);
        return ed;
    }

    private boolean selfTested;

    /**
     * A developer aid for checking a build without clicking (releng smoke test): with the environment variable
     * MXJOBSMGR_SELFTEST set, the first jobspace opened is run in Mock mode ("run-mock"), its diagram is exported to a
     * folder ("export:<folder>"), API Subscriptions ("subscriptions") or Layer 3 Services ("services") opens for it, or it is saved
     * back to its file ("save"), or the editing commands are tried on it with the results written to a file
     * ("edit:<file>").
     * Nothing happens without it.
     */
    private void selfTest(Editor ed) {
        String test = System.getenv("MXJOBSMGR_SELFTEST");
        if (test == null || test.isBlank() || selfTested) return;
        selfTested = true;
        if (test.equals("run-mock")) {
            ed.space.run.data = com.k3jdr.mxjobsmgr.model.Specs.DataAction.MOCK;
            ed.space.run.stepDelay = 0.05;
            ed.changed();
            Display.getCurrent().timerExec(800, ed::startRun);
        } else if (test.equals("save") && ed.file != null) {
            ed.willChange(null);
            ed.changed();
            Display.getCurrent().timerExec(800, () -> save(ed, () -> status("Self-test: saved " + ed.file)));
        } else if (test.startsWith("edit:")) {
            Path out = Path.of(test.substring(5));
            Display.getCurrent().timerExec(800, () -> {
                StringBuilder r = new StringBuilder();
                int nodes = ed.space.nodes.size(), links = ed.space.links.size();
                ed.add(NodeKind.AGENT_TASK);
                String a = ed.selectedNodes.iterator().next();
                ed.add(NodeKind.API_READ);
                String b = ed.selectedNodes.iterator().next();
                ed.link(a, b);
                r.append("add+link: ").append(ed.space.nodes.size() == nodes + 2 && ed.space.links.size() == links + 1).append('\n');
                JobNode an = ed.space.node(a);
                double x = an.x;
                ed.select(a, false);
                ed.nudge(100, 0);
                r.append("nudge: ").append(ed.space.node(a).x == x + 100).append('\n');
                ed.duplicateSelection();
                r.append("duplicate: ").append(ed.space.nodes.size() == nodes + 3 && ed.space.node(ed.selectedNodes.iterator().next()).title.endsWith(" copy")).append('\n');
                ed.toggleBreakpoint();
                r.append("breakpoint: ").append(ed.space.node(ed.selectedNodes.iterator().next()).breakpoint).append('\n');
                ed.addServiceStep(ServiceCatalog.service("portfolio-cpp").orElseThrow(), null);
                JobNode svc = ed.space.node(ed.selectedNodes.iterator().next());
                r.append("service step: ").append(svc.api.path).append(' ').append(svc.api.method.raw()).append(' ')
                        .append(ed.space.subscription(svc.api.subscriptionID).baseURL).append('\n');
                ed.selectAll();
                ed.deleteSelection();
                r.append("delete all: ").append(ed.space.nodes.isEmpty() && ed.space.links.isEmpty()).append('\n');
                ed.undo();
                r.append("undo: ").append(ed.space.nodes.size() == nodes + 4).append('\n');
                for (int i = 0; i < 7; i++) ed.undo();
                r.append("undo to start: ").append(ed.space.nodes.size() == nodes).append(' ').append(ed.canUndo()).append('\n');
                ed.redo();
                r.append("redo: ").append(ed.space.nodes.size() == nodes + 1).append('\n');
                r.append("issues: ").append(ed.issues.size()).append('\n');
                try {
                    Files.writeString(out, r.toString(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    status("Self-test failed: " + e);
                }
            });
        } else if (test.equals("subscriptions")) {
            Display.getCurrent().timerExec(800, () -> showSubscriptions(ed, null));
        } else if (test.equals("services")) {
            Display.getCurrent().timerExec(800, () -> showServices(null));
        } else if (test.startsWith("export:")) {
            Path dir = Path.of(test.substring(7));
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve("selftest.png"), DiagramExport.pngData(ed.space, 2));
                Files.writeString(dir.resolve("selftest.drawio.svg"), DiagramExport.drawioSVG(ed.space), StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("selftest.jobspace"), ed.space.toText(), StandardCharsets.UTF_8);
                status("Self-test: exported to " + dir);
            } catch (Exception e) {
                status("Self-test export failed: " + e);
            }
        }
    }

    /** Links subscriptions waiting for a secure account to MxAcctMGR's accounts of their names (as the Mac app does on activation). */
    private void linkSecureAccounts(Editor ed) {
        boolean wants = ed.space.subscriptions.stream().anyMatch(s -> s.auth != com.k3jdr.mxjobsmgr.model.Specs.AuthStyle.NONE
                && s.auth != com.k3jdr.mxjobsmgr.model.Specs.AuthStyle.LOCAL_SERVICE);
        if (!wants) return;
        Background.run(ed.root, SecureAccounts::load, listing -> {
            if (SecureAccounts.link(ed.space, listing)) ed.structureChanged();
        }, err -> { });
    }

    private void activeChanged() {
        Editor ed = active();
        shell.setText(ed == null ? LifeCycle.APP : LifeCycle.APP + " — " + ed.tabText() + (ed.title().equals(ed.space.name) || ed.space.name.isBlank() ? "" : "  ·  " + ed.space.name));
        if (ed != null) {
            ed.runBar.update();
            ed.setInspectorShown(inspectorShown);
        }
        QuickHelp.get().update();
    }

    public void updateTitles(Editor ed) {
        if (ed.tab != null && !ed.tab.isDisposed()) {
            ed.tab.setText(Ui.escape(ed.tabText()));
            ed.tab.setToolTipText(ed.file != null ? ed.file.toString() : ed.space.name);
        }
        if (ed == active()) activeChanged();
    }

    public void documentChanged(Editor ed) {
        if (ed == active()) QuickHelp.get().update();
    }

    public boolean inspectorShown() { return inspectorShown; }

    public void setInspectorShown(boolean shown) {
        inspectorShown = shown;
        prefs.putBoolean("inspector", shown);
        for (Editor ed : editors) {
            ed.setInspectorShown(shown);
            ed.runBar.update();
        }
    }

    public void status(String text) {
        if (!status.isDisposed()) status.setText(Ui.escape(text == null ? "" : text));
    }

    // MARK: File

    public void newDocument() {
        addEditor(new Jobspace(), null);
    }

    public void newFromSample() {
        UtilityWindows.showSamples(this, shell);
    }

    /** Opens a sample as a new jobspace, for a portfolio / benchmark where it takes one (mxjobsmgr://sample/…). */
    public void openSample(String id, String portfolio, String benchmark) {
        Background.run(shell, () -> Samples.load(id, portfolio, benchmark), space -> {
            addEditor(space, null);
            shell.forceActive();
        }, err -> ErrorDialog.show(shell, "Could not open the sample", err));
    }

    public void open() {
        FileDialog fd = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
        fd.setText("Open Jobspace");
        fd.setFilterExtensions(new String[] { "*.jobspace;*.json", "*.jobspace", "*.json", "*.*" });
        fd.setFilterNames(new String[] { "Jobspaces (*.jobspace, *.json)", "Jobspace (*.jobspace)", "JSON (*.json)", "All files" });
        String last = prefs.get("lastFolder", null);
        if (last != null) fd.setFilterPath(last);
        if (fd.open() == null) return;
        prefs.put("lastFolder", fd.getFilterPath());
        for (String name : fd.getFileNames()) openFile(Path.of(fd.getFilterPath(), name));
    }

    public void openFile(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        for (Editor ed : editors) {
            if (abs.equals(ed.file)) {
                tabs.setSelection(ed.tab);
                activeChanged();
                return;
            }
        }
        Background.run(shell, () -> Jobspace.parse(Files.readString(abs, StandardCharsets.UTF_8)), space -> {
            prefs.pushRecent("recent", abs.toString(), 10);
            addEditor(space, abs);
        }, err -> ErrorDialog.show(shell, "Could not open " + abs.getFileName(), (err instanceof java.nio.file.NoSuchFileException ? "The file is not there: " + abs
                : ErrorDialog.describe(err))));
    }

    /** Saves; {@code after} runs once the file is written. */
    public void save(Editor ed, Runnable after) {
        if (ed.file == null || !ed.file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jobspace")) {
            saveAs(ed, after);
            return;
        }
        write(ed, ed.file, after);
    }

    public void saveAs(Editor ed, Runnable after) {
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Save Jobspace");
        fd.setFilterExtensions(new String[] { "*.jobspace" });
        fd.setFilterNames(new String[] { "Jobspace (*.jobspace)" });
        fd.setOverwrite(true);
        String base = ed.title().replaceAll("[\\\\/:*?\"<>|]", "-");
        if (base.toLowerCase(Locale.ROOT).endsWith(".json")) base = base.substring(0, base.length() - 5);
        fd.setFileName(base + ".jobspace");
        if (ed.file != null) fd.setFilterPath(ed.file.getParent().toString());
        else if (prefs.get("lastFolder", null) != null) fd.setFilterPath(prefs.get("lastFolder", null));
        String chosen = fd.open();
        if (chosen == null) return;
        if (!chosen.toLowerCase(Locale.ROOT).endsWith(".jobspace")) chosen += ".jobspace";
        prefs.put("lastFolder", fd.getFilterPath());
        write(ed, Path.of(chosen), after);
    }

    private void write(Editor ed, Path file, Runnable after) {
        String text = ed.space.toText();
        Background.run(shell, () -> {
            Json.writeText(file, text);
            return file;
        }, saved -> {
            ed.file = saved.toAbsolutePath().normalize();
            if (ed.space.toText().equals(text)) ed.setDirty(false);
            prefs.pushRecent("recent", ed.file.toString(), 10);
            updateTitles(ed);
            status("Saved " + ed.file);
            if (after != null) after.run();
        }, err -> ErrorDialog.show(shell, "Could not save " + file.getFileName(), err));
    }

    private void revert(Editor ed) {
        if (ed.file == null) return;
        MessageBox box = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK | SWT.CANCEL);
        box.setText("Revert to Saved");
        box.setMessage("Discard the changes to “" + ed.title() + "” and go back to the saved file?");
        if (box.open() != SWT.OK) return;
        Path file = ed.file;
        Background.run(shell, () -> Jobspace.parse(Files.readString(file, StandardCharsets.UTF_8)), space -> {
            ed.willChange(null);
            ed.replace(space);
            ed.setDirty(false);
        }, err -> ErrorDialog.show(shell, "Could not read " + file.getFileName(), err));
    }

    /** Asks to save unsaved changes; answers whether the document may close now (false while a save is under way). */
    private boolean confirmClose(Editor ed, Runnable thenClose) {
        if (!ed.isDirty()) return true;
        tabs.setSelection(ed.tab);
        activeChanged();
        MessageBox box = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO | SWT.CANCEL);
        box.setText(LifeCycle.APP);
        box.setMessage("Do you want to save the changes to “" + ed.title() + "”?\n\nYour changes will be lost if you don't save them.");
        int answer = box.open();
        if (answer == SWT.CANCEL) return false;
        if (answer == SWT.NO) return true;
        save(ed, () -> {
            if (!ed.isDirty()) thenClose.run();
        });
        return false;
    }

    public void close(Editor ed) {
        if (!confirmClose(ed, () -> close(ed))) return;
        dispose(ed);
    }

    private void dispose(Editor ed) {
        editors.remove(ed);
        CTabItem item = ed.tab;
        ed.dispose();
        if (item != null && !item.isDisposed()) item.dispose();
        showStack();
        activeChanged();
    }

    /** The window's close: every document asked about in turn. */
    private boolean closeAll() {
        for (Editor ed : new ArrayList<>(editors)) {
            if (!confirmClose(ed, () -> {
                dispose(ed);
                if (editors.stream().noneMatch(Editor::isDirty)) shell.close();
            })) return false;
            dispose(ed);
        }
        return true;
    }

    // MARK: Dialogs and windows

    public void showProblems(Editor ed) {
        ProblemsPopup.show(ed);
    }

    public void showSubscriptions(Editor ed, String subscription) {
        subscriptionsOpen = true;
        try {
            new SubscriptionsDialog(shell, ed, subscription).open();
        } finally {
            subscriptionsOpen = false;
        }
        ed.revalidate();
        ed.inspector.rebuild();
        ed.runBar.update();
        ed.canvas.redraw();
    }

    public void goToIssue(Editor ed, Validator.Issue issue) {
        if (issue.nodeID() != null) ed.reveal(issue.nodeID());
        else if (issue.subscriptionID() != null) showSubscriptions(ed, issue.subscriptionID());
    }

    public void showServices(String serviceId) {
        UtilityWindows.showServices(this, shell, serviceId);
        palette.probe();
    }

    public void showKeysHelp(String providerId) {
        UtilityWindows.showKeysHelp(shell, providerId);
    }

    public void editElement(NodeKind kind) {
        TextsDialog.editElement(shell, kind);
    }

    public void editStep(Editor ed, String id) {
        JobNode node = ed.space.node(id);
        if (node == null) return;
        String[] r = TextsDialog.editStep(shell, node);
        if (r != null) ed.editStep(id, r[0], r[1]);
    }

    public void editStepLink(Editor ed, String id) {
        JobNode node = ed.space.node(id);
        if (node == null) return;
        StepLink inherited = node.link == null ? PaletteEdits.link(PaletteEdits.kindItem(node.kind)) : null;
        StepLink link = LinkDialog.edit(shell, node.title, "Step: " + node.kind.title + ", Layer " + node.layer().number, node.link, node.title,
                "Kept with the step in this jobspace: the pointer resting on the step shows its hover text, and its link mark opens it."
                        + (inherited == null ? "" : " Until then it shows its palette element's link (" + inherited.address + ")."));
        if (link != null) ed.setStepLink(id, link.address.isEmpty() ? null : link);
    }

    // MARK: Arguments and links

    private void handle(List<String> args) {
        for (String a : args) {
            if (a == null || a.isBlank() || a.startsWith("-")) continue;
            var link = AppLink.parse(a);
            if (link.isPresent()) {
                if (link.get() instanceof AppLink.Sample s) openSample(s.id(), s.portfolio(), s.benchmark());
                continue;
            }
            try {
                Path p = Path.of(a);
                if (Files.isRegularFile(p)) openFile(p);
            } catch (RuntimeException e) {
                // not a path
            }
        }
    }

    /** Arguments of a later launch (a link or a document), handed over by the running copy. */
    public static void handleArguments(List<String> args) {
        if (instance == null || instance.shell.isDisposed()) return;
        instance.shell.forceActive();
        instance.handle(args);
    }

    public static QuickHelpItem screenHelp() {
        return instance == null ? JobsHelp.NO_DOCUMENT : null;
    }

    static GridData fill() { return Ui.fill(); }
}
