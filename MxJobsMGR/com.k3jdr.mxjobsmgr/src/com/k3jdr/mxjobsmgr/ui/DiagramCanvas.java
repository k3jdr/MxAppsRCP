package com.k3jdr.mxjobsmgr.ui;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.ScrollBar;

import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxjobsmgr.model.JobLink;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Metrics;
import com.k3jdr.mxjobsmgr.model.NodeKind;
import com.k3jdr.mxjobsmgr.model.Specs.StepLink;

/**
 * The swim-lane editor (DiagramCanvas.swift): drag nodes to move them (10-point grid, kept in their lane); drag a node's
 * ● handle onto another node to link them; click to select, Shift-click to add, drag on empty space to rubber-band
 * select; click a link to select it; Delete removes, arrow keys nudge (Shift = 100), Escape deselects; Ctrl+wheel
 * zooms; palette steps are dropped here.
 */
public final class DiagramCanvas {
    /** What the palette puts on the clipboard of a drag: this prefix and the kind's raw value. */
    public static final String DRAG_PREFIX = "mxjobsmgr-kind:";

    private final Editor editor;
    final Canvas canvas;
    private final DiagramPainter.View view = new DiagramPainter.View();

    // Interaction
    private enum Mode { NONE, PRESS_NODE, DRAG_NODES, LINK, PRESS_EMPTY, RUBBER }

    private Mode mode = Mode.NONE;
    private String pressNode;
    private boolean pressShift;
    private double startX, startY;
    private Set<String> rubberBase = Set.of();

    public DiagramCanvas(Composite parent, Editor editor) {
        this.editor = editor;
        canvas = new Canvas(parent, SWT.H_SCROLL | SWT.V_SCROLL | SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        canvas.addListener(SWT.Paint, this::paint);
        canvas.addListener(SWT.Resize, e -> updateScrollBars());
        canvas.getHorizontalBar().addListener(SWT.Selection, e -> canvas.redraw());
        canvas.getVerticalBar().addListener(SWT.Selection, e -> canvas.redraw());
        canvas.addListener(SWT.MouseDown, this::mouseDown);
        canvas.addListener(SWT.MouseMove, this::mouseMove);
        canvas.addListener(SWT.MouseUp, this::mouseUp);
        canvas.addListener(SWT.MouseExit, e -> setHover(null));
        canvas.addListener(SWT.MouseVerticalWheel, this::wheel);
        canvas.addListener(SWT.KeyDown, this::key);
        canvas.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE || e.detail == SWT.TRAVERSE_ARROW_NEXT || e.detail == SWT.TRAVERSE_ARROW_PREVIOUS) e.doit = false;
        });
        canvas.addListener(SWT.FocusIn, e -> canvas.redraw());
        canvas.addListener(SWT.MenuDetect, this::menu);

        DropTarget drop = new DropTarget(canvas, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
        drop.setTransfer(new Transfer[] { TextTransfer.getInstance() });
        drop.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetEvent e) {
                if (e.detail == DND.DROP_DEFAULT || e.detail == DND.DROP_MOVE) e.detail = DND.DROP_COPY;
            }

            @Override
            public void dragOperationChanged(DropTargetEvent e) {
                if (e.detail == DND.DROP_DEFAULT || e.detail == DND.DROP_MOVE) e.detail = DND.DROP_COPY;
            }

            @Override
            public void drop(DropTargetEvent e) {
                if (!(e.data instanceof String s) || !s.startsWith(DRAG_PREFIX)) return;
                NodeKind kind = NodeKind.of(s.substring(DRAG_PREFIX.length()));
                if (kind == null) return;
                Point p = canvas.toControl(e.x, e.y);
                double[] d = toDiagram(p.x, p.y);
                editor.add(kind, d[0], d[1]);
                canvas.setFocus();
            }
        });

        QuickHelp.provider(canvas, (c, p) -> describe(p));
    }

    // MARK: Coordinates

    private double zoom() { return editor.zoom; }

    private double[] toDiagram(int x, int y) {
        return new double[] { (x + canvas.getHorizontalBar().getSelection()) / zoom(), (y + canvas.getVerticalBar().getSelection()) / zoom() };
    }

    public void updateScrollBars() {
        Rectangle area = canvas.getClientArea();
        int w = (int) Math.ceil(Metrics.canvasWidth(editor.space) * zoom()), h = (int) Math.ceil(Metrics.canvasHeight() * zoom());
        setBar(canvas.getHorizontalBar(), w, area.width);
        setBar(canvas.getVerticalBar(), h, area.height);
        canvas.redraw();
    }

    private static void setBar(ScrollBar bar, int content, int visible) {
        int sel = Math.min(bar.getSelection(), Math.max(0, content - visible));
        bar.setValues(sel, 0, Math.max(content, 1), Math.max(1, Math.min(visible, content)), 20, Math.max(1, visible - 20));
        bar.setVisible(content > visible);
    }

    /** Sets the zoom, keeping the diagram point under {@code (ax, ay)} (control coordinates) in place. */
    public void zoomTo(double z, int ax, int ay) {
        double[] d = toDiagram(ax, ay);
        editor.zoom = Math.min(2, Math.max(0.4, Math.round(z * 10) / 10.0));
        updateScrollBars();
        canvas.getHorizontalBar().setSelection((int) Math.round(d[0] * zoom() - ax));
        canvas.getVerticalBar().setSelection((int) Math.round(d[1] * zoom() - ay));
        canvas.redraw();
        editor.zoomChanged();
    }

    /** Scrolls so the node is in view. */
    public void reveal(JobNode node) {
        Rectangle area = canvas.getClientArea();
        ScrollBar hb = canvas.getHorizontalBar(), vb = canvas.getVerticalBar();
        int cx = (int) (node.x * zoom()), cy = (int) (node.y * zoom());
        int hw = (int) (Metrics.NODE_W * zoom()), hh = (int) (Metrics.NODE_H * zoom());
        if (cx - hw < hb.getSelection() || cx + hw > hb.getSelection() + area.width) hb.setSelection(Math.max(0, cx - area.width / 2));
        if (cy - hh < vb.getSelection() || cy + hh > vb.getSelection() + area.height) vb.setSelection(Math.max(0, cy - area.height / 2));
        canvas.redraw();
    }

    // MARK: Painting

    private void paint(Event e) {
        GC gc = e.gc;
        view.space = editor.space;
        view.selectedNodes = editor.selectedNodes;
        view.selectedLink = editor.selectedLink;
        view.issues = editor.issues;
        view.states = editor.run.states();
        view.currentNode = editor.run.currentNode();
        view.breakpointsEnabled = editor.space.run.breakpointsEnabled;
        double z = zoom();
        int ox = canvas.getHorizontalBar().getSelection(), oy = canvas.getVerticalBar().getSelection();
        Rectangle area = canvas.getClientArea();
        gc.setBackground(Colors.of(0xF4F4F6));
        gc.fillRectangle(area);
        Transform t = new Transform(canvas.getDisplay());
        t.translate(-ox, -oy);
        t.scale((float) z, (float) z);
        gc.setTransform(t);
        DiagramPainter.paint(gc, view, (e.x + ox) / z, (e.y + oy) / z, e.width / z, e.height / z);
        gc.setTransform(null);
        t.dispose();
    }

    public void redraw() {
        if (!canvas.isDisposed()) canvas.redraw();
    }

    // MARK: Hit testing (diagram coordinates)

    private JobNode nodeAt(double x, double y) {
        JobNode hit = null;
        for (JobNode n : editor.space.nodes) {
            if (Math.abs(x - n.x) <= Metrics.NODE_W / 2 && Math.abs(y - n.y) <= Metrics.NODE_H / 2) hit = n;
        }
        return hit;
    }

    private JobNode handleAt(double x, double y) {
        JobNode hit = null;
        for (JobNode n : editor.space.nodes) {
            double hx = n.x + Metrics.NODE_W / 2, hy = n.y;
            if ((x - hx) * (x - hx) + (y - hy) * (y - hy) <= 9 * 9) hit = n;
        }
        return hit;
    }

    private JobNode breakpointAt(double x, double y) {
        for (JobNode n : editor.space.nodes) {
            double bx = n.x - Metrics.NODE_W / 2 - 7, by = n.y - Metrics.NODE_H / 2 - 7;
            if (n.breakpoint && x >= bx && x <= bx + 22 && y >= by && y <= by + 14) return n;
        }
        return null;
    }

    private JobLink linkAt(double x, double y) {
        var links = editor.space.links;
        for (int i = links.size() - 1; i >= 0; i--) {
            JobLink l = links.get(i);
            JobNode a = editor.space.node(l.from), b = editor.space.node(l.to);
            if (a == null || b == null) continue;
            if (Metrics.curve(a.x, a.y, b.x, b.y).distance(x, y) <= 6) return l;
        }
        return null;
    }

    // MARK: Mouse

    private void mouseDown(Event e) {
        canvas.setFocus();
        if (e.button != 1) return;
        double[] d = toDiagram(e.x, e.y);
        startX = d[0];
        startY = d[1];
        pressShift = (e.stateMask & SWT.SHIFT) != 0;
        JobNode handle = handleAt(d[0], d[1]);
        if (handle != null) {
            mode = Mode.LINK;
            pressNode = handle.id;
            return;
        }
        JobNode node = nodeAt(d[0], d[1]);
        if (node != null) {
            mode = Mode.PRESS_NODE;
            pressNode = node.id;
            return;
        }
        mode = Mode.PRESS_EMPTY;
    }

    private void mouseMove(Event e) {
        double[] d = toDiagram(e.x, e.y);
        switch (mode) {
            case NONE -> {
                JobNode over = nodeAt(d[0], d[1]);
                if (over == null) over = handleAt(d[0], d[1]);
                setHover(over == null ? null : over.id);
                canvas.setCursor(handleAt(d[0], d[1]) != null ? canvas.getDisplay().getSystemCursor(SWT.CURSOR_CROSS) : null);
            }
            case PRESS_NODE -> {
                if (Math.hypot(d[0] - startX, d[1] - startY) * zoom() >= 3) {
                    if (!editor.selectedNodes.contains(pressNode)) editor.select(pressNode, false);
                    view.dragging = new LinkedHashSet<>(editor.selectedNodes);
                    mode = Mode.DRAG_NODES;
                    canvas.setCursor(canvas.getDisplay().getSystemCursor(SWT.CURSOR_SIZEALL));
                    dragTo(d);
                }
            }
            case DRAG_NODES -> dragTo(d);
            case LINK -> {
                if (view.pendingFrom == null && Math.hypot(d[0] - startX, d[1] - startY) * zoom() < 2) return;
                view.pendingFrom = pressNode;
                view.pendingX = d[0];
                view.pendingY = d[1];
                autoScroll(e);
                canvas.redraw();
            }
            case PRESS_EMPTY -> {
                if (Math.hypot(d[0] - startX, d[1] - startY) * zoom() >= 4) {
                    mode = Mode.RUBBER;
                    rubberBase = pressShift ? new LinkedHashSet<>(editor.selectedNodes) : Set.of();
                    rubberTo(d);
                }
            }
            case RUBBER -> rubberTo(d);
        }
    }

    private void dragTo(double[] d) {
        view.dragDx = d[0] - startX;
        view.dragDy = d[1] - startY;
        canvas.redraw();
    }

    private void rubberTo(double[] d) {
        view.rubberBand = new double[] { Math.min(startX, d[0]), Math.min(startY, d[1]), Math.abs(d[0] - startX), Math.abs(d[1] - startY) };
        canvas.redraw();
    }

    private void autoScroll(Event e) {
        Rectangle area = canvas.getClientArea();
        ScrollBar hb = canvas.getHorizontalBar(), vb = canvas.getVerticalBar();
        if (e.x > area.width - 20) hb.setSelection(hb.getSelection() + 20);
        else if (e.x < 20) hb.setSelection(hb.getSelection() - 20);
        if (e.y > area.height - 20) vb.setSelection(vb.getSelection() + 20);
        else if (e.y < 20) vb.setSelection(vb.getSelection() - 20);
    }

    private void mouseUp(Event e) {
        if (e.button != 1) return;
        double[] d = toDiagram(e.x, e.y);
        Mode m = mode;
        mode = Mode.NONE;
        canvas.setCursor(null);
        switch (m) {
            case PRESS_NODE -> editor.select(pressNode, pressShift);
            case DRAG_NODES -> {
                Set<String> ids = view.dragging;
                double dx = view.dragDx, dy = view.dragDy;
                view.dragging = Set.of();
                view.dragDx = view.dragDy = 0;
                editor.moveNodes(ids, dx, dy);
            }
            case LINK -> {
                view.pendingFrom = null;
                JobNode target = nodeAt(d[0], d[1]);
                if (target != null && !target.id.equals(pressNode)) editor.link(pressNode, target.id);
                canvas.redraw();
            }
            case PRESS_EMPTY -> {
                JobLink link = linkAt(d[0], d[1]);
                if (link != null) editor.selectLink(link.id);
                else editor.clearSelection();
            }
            case RUBBER -> {
                double[] r = view.rubberBand;
                view.rubberBand = null;
                Set<String> hits = new LinkedHashSet<>(rubberBase);
                for (JobNode n : editor.space.nodes) {
                    double nx = n.x - Metrics.NODE_W / 2, ny = n.y - Metrics.NODE_H / 2;
                    if (nx < r[0] + r[2] && nx + Metrics.NODE_W > r[0] && ny < r[1] + r[3] && ny + Metrics.NODE_H > r[1]) hits.add(n.id);
                }
                editor.selectNodes(hits);
            }
            default -> { }
        }
    }

    private void setHover(String id) {
        if (java.util.Objects.equals(id, view.hoverNode)) return;
        view.hoverNode = id;
        JobNode n = editor.space.node(id);
        StepLink link = n == null ? null : PaletteEdits.shownLink(n);
        canvas.setToolTipText(n == null ? null : link != null ? hoverText(n, link) : n.kind.summary);
        canvas.redraw();
    }

    /** The hover card of a linked step, as a tooltip: the link's words, its hover text, and where it goes. */
    static String hoverText(JobNode n, StepLink link) {
        String words = link.text != null ? link.text : n.title;
        String tip = link.tipPlain();
        return words + (link.title != null ? " — " + link.title : "") + (tip != null ? "\n" + tip : "") + "\n" + link.address
                + (n.link == null ? "\n(the " + PaletteEdits.title(n.kind) + " element's link)" : "");
    }

    private void wheel(Event e) {
        if ((e.stateMask & SWT.MOD1) != 0) {
            e.doit = false;
            zoomTo(zoom() + (e.count > 0 ? 0.1 : -0.1), e.x, e.y);
        } else if ((e.stateMask & SWT.SHIFT) != 0) {
            e.doit = false;
            ScrollBar hb = canvas.getHorizontalBar();
            hb.setSelection(hb.getSelection() - e.count * 20);
            canvas.redraw();
        }
    }

    // MARK: Keys

    private void key(Event e) {
        boolean shift = (e.stateMask & SWT.SHIFT) != 0;
        double step = shift ? 100 : Metrics.GRID;
        switch (e.keyCode) {
            case SWT.DEL, SWT.BS -> {
                if ((e.stateMask & (SWT.MOD1 | SWT.MOD3)) == 0) editor.deleteSelection();
            }
            case SWT.ARROW_UP -> editor.nudge(0, -step);
            case SWT.ARROW_DOWN -> editor.nudge(0, step);
            case SWT.ARROW_LEFT -> editor.nudge(-step, 0);
            case SWT.ARROW_RIGHT -> editor.nudge(step, 0);
            case SWT.ESC -> {
                if (mode != Mode.NONE) {
                    mode = Mode.NONE;
                    view.dragging = Set.of();
                    view.pendingFrom = null;
                    view.rubberBand = null;
                    canvas.redraw();
                } else {
                    editor.clearSelection();
                }
            }
            default -> { }
        }
    }

    // MARK: Context menu and Quick Help

    private void menu(Event e) {
        Point p = canvas.toControl(e.x, e.y);
        double[] d = e.detail == SWT.MENU_KEYBOARD ? null : toDiagram(p.x, p.y);
        JobNode node = d == null ? (editor.selectedNodes.size() == 1 ? editor.space.node(editor.selectedNodes.iterator().next()) : null) : nodeAt(d[0], d[1]);
        Menu old = canvas.getMenu();
        if (old != null) old.dispose();
        Menu menu = new Menu(canvas);
        MenuBarBuilder.MenuBuilder mb = new MenuBarBuilder.MenuBuilder(null, menu);
        if (node != null) {
            JobNode n = node;
            mb.item(n.breakpoint ? "Remove Breakpoint" : "Add Breakpoint", () -> {
                if (!editor.selectedNodes.contains(n.id)) editor.select(n.id, false);
                editor.toggleBreakpoint();
            });
            mb.item(n.enabled ? "Disable Step" : "Enable Step", () -> editor.toggleEnabled(n.id));
            mb.separator();
            mb.item("Edit Step…", () -> editor.main.editStep(editor, n.id));
            mb.item(n.link == null ? "Add Link…" : "Edit Link…", () -> editor.main.editStepLink(editor, n.id));
            StepLink shown = PaletteEdits.shownLink(n);
            if (shown != null) mb.item("Open Link", () -> LinkDialog.follow(shown.address));
            if (n.link != null) mb.item("Remove Link", () -> editor.setStepLink(n.id, null));
            mb.separator();
            mb.item("Duplicate", () -> {
                if (!editor.selectedNodes.contains(n.id)) editor.select(n.id, false);
                editor.duplicateSelection();
            });
            mb.item("Delete", () -> {
                if (!editor.selectedNodes.contains(n.id)) editor.select(n.id, false);
                editor.deleteSelection();
            });
            mb.separator();
        }
        var run = editor.run;
        mb.item(run.canContinue() ? "Continue" : "Run", 0, () -> editor.startRun(), () -> !(run.isActive() && !run.canContinue()));
        mb.item("Step", 0, () -> editor.stepRun(), () -> run.phase() != com.k3jdr.mxjobsmgr.engine.RunEngine.Phase.RUNNING
                && run.phase() != com.k3jdr.mxjobsmgr.engine.RunEngine.Phase.WAITING);
        mb.item("Stop", 0, run::stop, run::isActive);
        mb.separator();
        mb.item("Save as PNG…", () -> DiagramExport.savePNG(canvas.getShell(), editor.space));
        mb.item("Save as draw.io SVG…", () -> DiagramExport.saveDrawioSVG(canvas.getShell(), editor.space));
        canvas.setMenu(menu);
        menu.setLocation(e.x, e.y);
        menu.setVisible(true);
        e.doit = false;
    }

    private QuickHelpItem describe(Point p) {
        double[] d = toDiagram(p.x, p.y);
        if (d[0] > Metrics.canvasWidth(editor.space) || d[1] > Metrics.canvasHeight()) return null;
        if (breakpointAt(d[0], d[1]) != null) return JobsHelp.BREAKPOINT;
        if (handleAt(d[0], d[1]) != null) return JobsHelp.HANDLE;
        JobNode n = nodeAt(d[0], d[1]);
        if (n != null) return JobsHelp.node(n, editor.run.state(n.id), com.k3jdr.mxjobsmgr.engine.Validator.worst(editor.issues, n.id), editor.selectedNodes.contains(n.id));
        JobLink l = linkAt(d[0], d[1]);
        if (l != null) {
            JobNode a = editor.space.node(l.from), b = editor.space.node(l.to);
            boolean up = a != null && b != null && b.layer().number < a.layer().number;
            return new QuickHelpItem("Link: " + (a != null ? a.title : "–") + " → " + (b != null ? b.title : "–"),
                    (up ? "Points up the layers (dashed): it carries results back. " : "Points down the layers: a call. ")
                            + "Click to select it and name it in the inspector; Delete removes it.");
        }
        return JobsHelp.canvas(Metrics.layerAt(d[1]));
    }

    public void dispose() {
        Cursor c = canvas.getCursor();
        if (c != null && !c.isDisposed()) canvas.setCursor(null);
    }
}
