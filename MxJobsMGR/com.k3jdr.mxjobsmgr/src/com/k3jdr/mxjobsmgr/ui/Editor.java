package com.k3jdr.mxjobsmgr.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.MainPart;
import com.k3jdr.mxjobsmgr.engine.RunEngine;
import com.k3jdr.mxjobsmgr.engine.Validator;
import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.Metrics;
import com.k3jdr.mxjobsmgr.model.NodeKind;
import com.k3jdr.mxjobsmgr.model.Specs.HttpMethod;
import com.k3jdr.mxjobsmgr.model.Specs.KeyValue;
import com.k3jdr.mxjobsmgr.model.Specs.StepLink;

/**
 * One open jobspace (EditorState.swift + ContentView.swift): the document, its file and unsaved changes, selection,
 * zoom, undo, run engine and checks, and its tab — run bar, canvas, run console and inspector.
 */
public final class Editor {
    public final MainPart main;
    public Jobspace space;
    public Path file;
    public String untitledName;
    private boolean dirty;

    public final Set<String> selectedNodes = new LinkedHashSet<>();
    public String selectedLink;
    public double zoom = 1;
    public final RunEngine run = new RunEngine();
    public List<Validator.Issue> issues = List.of();
    /** The subscription to show first when API Subscriptions opens. */
    public String subscriptionToEdit;

    // UI
    public CTabItem tab;
    public final Composite root;
    private final SideSplit inspectorSplit, consoleSplit;
    public final RunBar runBar;
    public final PromptBanner banner;
    public final DiagramCanvas canvas;
    public final RunConsole console;
    public final Inspector inspector;

    // Undo
    private final Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private String editKey;
    private long editTime;
    private boolean refreshPending;
    private RunEngine.NodeState lastSelectedState;

    public Editor(MainPart main, Composite parent, Jobspace space, Path file, String untitledName) {
        this.main = main;
        this.space = space;
        this.file = file;
        this.untitledName = untitledName;

        inspectorSplit = new SideSplit(parent, "inspector", 300, 560, 380, false);
        root = inspectorSplit;
        Composite center = inspectorSplit.main();
        center.setLayout(Ui.grid(1, 0, 0));
        runBar = new RunBar(center, this);
        runBar.root.setLayoutData(Ui.fillH());
        banner = new PromptBanner(center, this);
        consoleSplit = new SideSplit(center, "console", 120, 600, 230, true);
        consoleSplit.setLayoutData(Ui.fill());
        consoleSplit.main().setLayout(new org.eclipse.swt.layout.FillLayout());
        canvas = new DiagramCanvas(consoleSplit.main(), this);
        consoleSplit.side().setLayout(new org.eclipse.swt.layout.FillLayout());
        console = new RunConsole(consoleSplit.side(), this);
        consoleSplit.setSideVisible(false);
        inspectorSplit.side().setLayout(new org.eclipse.swt.layout.FillLayout());
        inspector = new Inspector(inspectorSplit.side(), this);
        inspectorSplit.setSideVisible(main.inspectorShown());

        run.setListener(this::runChanged);
        revalidate();
        pushLive();
        inspector.rebuild();
        runBar.update();
        canvas.updateScrollBars();
    }

    // MARK: Document

    public String title() {
        if (file != null) {
            String n = file.getFileName().toString();
            return n.toLowerCase().endsWith(".jobspace") ? n.substring(0, n.length() - 9) : n;
        }
        return untitledName;
    }

    public boolean isDirty() { return dirty; }

    public void setDirty(boolean d) {
        dirty = d;
        main.updateTitles(this);
    }

    /** Replaces the whole document (open, revert, undo). */
    public void replace(Jobspace newSpace) {
        space = newSpace;
        selectedNodes.retainAll(idsOf(space));
        if (space.link(selectedLink) == null) selectedLink = null;
        structureChanged();
    }

    private static Set<String> idsOf(Jobspace s) {
        Set<String> ids = new HashSet<>();
        for (JobNode n : s.nodes) ids.add(n.id);
        return ids;
    }

    // MARK: Changes

    /**
     * Call before changing the document: remembers it for Undo. Edits with the same {@code key} within two seconds
     * (typing in one field) undo together.
     */
    public void willChange(String key) {
        long now = System.currentTimeMillis();
        if (key != null && key.equals(editKey) && now - editTime < 2000) {
            editTime = now;
            return;
        }
        editKey = key;
        editTime = now;
        String snapshot = space.toText();
        if (!snapshot.equals(undo.peek())) undo.push(snapshot);
        while (undo.size() > 200) undo.removeLast();
        redo.clear();
    }

    /** After a change of values: dirty, checks, canvas, run bar. */
    public void changed() {
        setDirty(true);
        revalidate();
        pushLive();
        canvas.redraw();
        runBar.update();
        main.documentChanged(this);
    }

    /** After a change of what the inspector shows (nodes added or removed, settings that show other fields). */
    public void structureChanged() {
        changed();
        canvas.updateScrollBars();
        inspector.rebuild();
        selectionChangedQuietly();
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.push(space.toText());
        editKey = null;
        replace(Jobspace.parse(undo.pop()));
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.push(space.toText());
        editKey = null;
        replace(Jobspace.parse(redo.pop()));
    }

    public void revalidate() {
        issues = Validator.check(space);
    }

    /** What the run reads mid-run: breakpoints and the step delay. */
    private void pushLive() {
        Set<String> bps = new HashSet<>();
        for (JobNode n : space.nodes) if (n.breakpoint) bps.add(n.id);
        run.setLive(new RunEngine.Live(bps, space.run.breakpointsEnabled, space.run.stepDelay));
    }

    // MARK: Selection

    public boolean hasSelection() { return !selectedNodes.isEmpty() || selectedLink != null; }

    public void select(String id, boolean extending) {
        selectedLink = null;
        if (extending) {
            if (!selectedNodes.remove(id)) selectedNodes.add(id);
        } else {
            selectedNodes.clear();
            selectedNodes.add(id);
        }
        selectionChanged();
    }

    public void selectNodes(Collection<String> ids) {
        selectedLink = null;
        selectedNodes.clear();
        selectedNodes.addAll(ids);
        selectionChanged();
    }

    public void selectLink(String id) {
        selectedNodes.clear();
        selectedLink = id;
        selectionChanged();
    }

    public void clearSelection() {
        if (!hasSelection()) return;
        selectedNodes.clear();
        selectedLink = null;
        selectionChanged();
    }

    /** Selects a step and shows it (Problems, the run log). */
    public void reveal(String nodeId) {
        JobNode n = space.node(nodeId);
        if (n == null) return;
        select(nodeId, false);
        canvas.reveal(n);
    }

    private void selectionChanged() {
        inspector.rebuild();
        selectionChangedQuietly();
    }

    private void selectionChangedQuietly() {
        canvas.redraw();
        console.selectionChanged();
        lastSelectedState = selectedState();
        QuickHelp.get().update();
    }

    private RunEngine.NodeState selectedState() {
        return selectedNodes.size() == 1 ? run.state(selectedNodes.iterator().next()) : null;
    }

    // MARK: Commands

    public void deleteSelection() {
        if (!hasSelection()) return;
        willChange(null);
        space.delete(new HashSet<>(selectedNodes), selectedLink == null ? Set.of() : Set.of(selectedLink));
        selectedNodes.clear();
        selectedLink = null;
        structureChanged();
    }

    public void duplicateSelection() {
        if (selectedNodes.isEmpty()) return;
        willChange(null);
        Set<String> copies = space.duplicate(selectedNodes);
        selectedNodes.clear();
        selectedNodes.addAll(copies);
        structureChanged();
    }

    public void selectAll() {
        selectNodes(idsOf(space));
    }

    public void nudge(double dx, double dy) {
        if (selectedNodes.isEmpty()) return;
        willChange("nudge");
        for (JobNode n : space.nodes) {
            if (!selectedNodes.contains(n.id)) continue;
            double[] p = Metrics.place(n.x + dx, n.y + dy, n.layer());
            n.x = p[0];
            n.y = p[1];
        }
        changed();
        canvas.updateScrollBars();
    }

    public void moveNodes(Set<String> ids, double dx, double dy) {
        boolean moved = false;
        Jobspace before = space.copy();
        for (JobNode n : space.nodes) {
            if (!ids.contains(n.id)) continue;
            double[] p = Metrics.place(n.x + dx, n.y + dy, n.layer());
            if (p[0] != n.x || p[1] != n.y) moved = true;
            n.x = p[0];
            n.y = p[1];
        }
        if (moved) {
            String snapshot = before.toText();
            if (!snapshot.equals(undo.peek())) undo.push(snapshot);
            redo.clear();
            editKey = null;
            changed();
            canvas.updateScrollBars();
        } else {
            canvas.redraw();
        }
    }

    public void link(String from, String to) {
        if (from.equals(to) || space.links.stream().anyMatch(l -> l.from.equals(from) && l.to.equals(to))) return;
        willChange(null);
        String id = space.addLink(from, to);
        selectedNodes.clear();
        selectedLink = id;
        structureChanged();
    }

    /** Adds a node of {@code kind} in its lane, right of the nodes already there (or at a dropped point). */
    public void add(NodeKind kind, Double px, Double py) {
        double fx = Metrics.GUTTER;
        for (JobNode n : space.nodes) if (n.layer() == kind.layer) fx = Math.max(fx, n.x);
        double[] p = px != null ? Metrics.place(px, py, kind.layer)
                : Metrics.place(fx + Metrics.NODE_W + 50, Metrics.laneCenter(kind.layer), kind.layer);
        willChange(null);
        // A renamed palette element names its new steps.
        JobNode node = new JobNode(kind, PaletteEdits.title(kind), p[0], p[1]);
        space.nodes.add(node);
        selectedLink = null;
        selectedNodes.clear();
        selectedNodes.add(node.id);
        structureChanged();
        canvas.reveal(node);
    }

    public void add(NodeKind kind) {
        add(kind, null, null);
    }

    /** The catalog service a subscription points at (by local port). */
    public static ServiceCatalog.Service serviceFor(ApiSubscription sub) {
        if (sub == null) return null;
        try {
            java.net.URI u = java.net.URI.create(sub.baseURL.trim());
            if (u.getHost() == null || !(u.getHost().equals("127.0.0.1") || u.getHost().equals("localhost")) || u.getPort() < 0) return null;
            for (ServiceCatalog.Service s : ServiceCatalog.ALL) if (s.port() == u.getPort()) return s;
        } catch (RuntimeException e) {
            // not a URL
        }
        return null;
    }

    /** Adds a Layer 3 step calling a catalog service's endpoint, creating the service's subscription if needed. */
    public void addServiceStep(ServiceCatalog.Service service, ServiceCatalog.Endpoint endpoint) {
        willChange(null);
        ApiSubscription sub = null;
        for (ApiSubscription s : space.subscriptions) {
            ServiceCatalog.Service found = serviceFor(s);
            if (found != null && found.id().equals(service.id())) sub = s;
        }
        if (sub == null) {
            sub = ApiSubscription.localService(service.port());
            sub.name = service.name();
            sub.provider = service.language() + " service on this PC (Services/" + service.folder() + ")";
            sub.projectFolder = service.projectPath().toString();
            sub.notes = service.purpose() + " Start it with: " + ServiceCatalog.startCommand();
            space.subscriptions.add(sub);
        }
        double fx = Metrics.GUTTER;
        for (JobNode n : space.nodes) if (n.layer() == Layer.DATA) fx = Math.max(fx, n.x);
        double[] p = Metrics.place(fx + Metrics.NODE_W + 50, Metrics.laneCenter(Layer.DATA), Layer.DATA);
        ServiceCatalog.Endpoint ep = endpoint != null ? endpoint : service.endpoints().isEmpty() ? null : service.endpoints().get(0);
        JobNode node = new JobNode(NodeKind.API_READ, ep != null ? ep.summary() : service.name(), p[0], p[1]);
        node.api.subscriptionID = sub.id;
        node.api.method = ep != null && ep.method().equals("POST") ? HttpMethod.POST : HttpMethod.GET;
        // "/quote?symbol=" → path /quote and an empty symbol parameter; "/refresh/{category}" keeps its placeholder.
        String raw = ep != null ? ep.path() : "/";
        String[] parts = raw.split("\\?", 2);
        node.api.path = parts[0].replace("{", "{{").replace("}", "}}");
        if (parts.length > 1) {
            for (String q : parts[1].split("&")) {
                if (!q.isEmpty()) node.api.query.add(new KeyValue(q.split("=", 2)[0], ""));
            }
        }
        if (node.api.method == HttpMethod.POST && node.api.body.isEmpty()) node.api.body = "{}";
        node.notes = service.name() + " (" + service.language() + "): " + (ep != null ? ep.summary() : "");
        node.outcome.successStatus = "200";
        space.nodes.add(node);
        selectedLink = null;
        selectedNodes.clear();
        selectedNodes.add(node.id);
        structureChanged();
        canvas.reveal(node);
    }

    /** Toggles the breakpoint on the selected steps. */
    public void toggleBreakpoint() {
        if (selectedNodes.isEmpty()) return;
        willChange(null);
        boolean turnOn = !space.nodes.stream().filter(n -> selectedNodes.contains(n.id)).allMatch(n -> n.breakpoint);
        for (JobNode n : space.nodes) if (selectedNodes.contains(n.id)) n.breakpoint = turnOn;
        if (turnOn) space.run.breakpointsEnabled = true;
        structureChanged();
    }

    public void toggleBreakpoints() {
        willChange(null);
        space.run.breakpointsEnabled = !space.run.breakpointsEnabled;
        changed();
    }

    public void toggleEnabled(String id) {
        JobNode n = space.node(id);
        if (n == null) return;
        willChange(null);
        n.enabled = !n.enabled;
        structureChanged();
    }

    public void setStepLink(String id, StepLink link) {
        JobNode n = space.node(id);
        if (n == null) return;
        willChange(null);
        n.link = link;
        structureChanged();
    }

    public void editStep(String id, String title, String notes) {
        JobNode n = space.node(id);
        if (n == null) return;
        willChange(null);
        String t = title.strip();
        n.title = t.isEmpty() ? PaletteEdits.title(n.kind) : t;
        n.notes = notes;
        structureChanged();
    }

    // MARK: View

    public void zoomIn() { canvas.zoomTo(Math.min(2, Math.round((zoom + 0.1) * 10) / 10.0), 0, 0); }
    public void zoomOut() { canvas.zoomTo(Math.max(0.4, Math.round((zoom - 0.1) * 10) / 10.0), 0, 0); }
    public void actualSize() { canvas.zoomTo(1, 0, 0); }

    void zoomChanged() {
        runBar.update();
    }

    public boolean isConsoleShown() { return consoleSplit.isSideVisible(); }

    public void setConsoleShown(boolean shown) {
        consoleSplit.setSideVisible(shown);
        runBar.update();
        banner.update();
    }

    public void setInspectorShown(boolean shown) {
        inspectorSplit.setSideVisible(shown);
    }

    // MARK: Run

    public void startRun() {
        setConsoleShown(true);
        pushLive();
        run.run(space, false);
        runChangedNow();
    }

    public void stepRun() {
        setConsoleShown(true);
        pushLive();
        run.step(space);
        runChangedNow();
    }

    /** Told by the engine (on its thread): refresh at most every 80 ms on the UI thread. */
    private void runChanged() {
        synchronized (this) {
            if (refreshPending) return;
            refreshPending = true;
        }
        Display d = root.isDisposed() ? null : root.getDisplay();
        if (d == null) return;
        d.asyncExec(() -> {
            if (root.isDisposed()) return;
            d.timerExec(80, () -> {
                synchronized (this) {
                    refreshPending = false;
                }
                if (!root.isDisposed()) runChangedNow();
            });
        });
    }

    void runChangedNow() {
        canvas.redraw();
        runBar.update();
        banner.update();
        console.update();
        RunEngine.NodeState now = selectedState();
        if (!Objects.equals(now, lastSelectedState) && !inspector.hasFocus()) {
            lastSelectedState = now;
            inspector.rebuild();
        }
    }

    public void dispose() {
        run.stop();
        run.setListener(null);
        root.dispose();
    }

    /** Shown in the tab and the window title. */
    public String tabText() {
        return (dirty ? "*" : "") + title();
    }

    public static int style() { return SWT.NONE; }
}
