package com.k3jdr.mx.dbml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.dbml.DbmlDiagramNote.Swatch;

/**
 * A DBML diagram to work on (DBMLCanvasView.swift): drag tables (Shift- or Ctrl-click to select more; click a schema
 * panel's name to select all its tables) and the selection moves together; click a relationship to pick its curve
 * and drag its control points; notes over the diagram are added with a double-click on empty space, dragged, resized
 * from their corner and typed in place. Right-click for the line's curve, sides and colour, a note's colour, and the
 * Schema menu — select a schema, copy it, or export it. The app around it (the {@link DbmlCanvasHost}) hears each
 * change as one step and adds its own commands to the menu. A host with a snap grid has dragged tables and notes,
 * resized notes and arrow-key moves land on its grid, which shows as dots while dragging.
 *
 * <p>Created with {@code SWT.H_SCROLL | SWT.V_SCROLL} it scrolls its content itself (the diagram plus a margin, at
 * least {@link #setMinimumContent a screenful}); zoom with {@link #setZoom(double)} or Ctrl + mouse wheel.
 */
public class DbmlCanvas extends Canvas {
    public static final List<Swatch> LINE_COLORS = List.of(new Swatch("Blue", "#2F6FD0"), new Swatch("Green", "#2E9E5B"),
            new Swatch("Orange", "#E07B24"), new Swatch("Red", "#D0453A"), new Swatch("Purple", "#8A55C9"),
            new Swatch("Gray", "#7D8B99"));
    public static final double MIN_ZOOM = 0.2, MAX_ZOOM = 3;

    private DbmlCanvasHost host = new DbmlCanvasHost() {};
    private DbmlDiagram diagram;
    private DbmlGeometry geometry;
    private double zoom = 1;
    private DbmlSelection selection = DbmlSelection.NONE;
    private boolean showsSelection = true;
    private boolean drawsBackground = true;
    private boolean editable = true;
    private boolean wheelZoom = true;
    private String editingNote;
    private DbmlSnapGrid shownGrid;
    private Text noteEditor;
    private Font noteEditorFont;
    private double noteEditorFontZoom;
    private int scrollX, scrollY;
    private double contentMargin = 400;
    private DbmlSize minimumContent = new DbmlSize(1200, 800);
    private Menu contextMenu;
    private Drag drag;
    private String toolTip;
    private int cursorKind = SWT.CURSOR_ARROW;

    /** A drag in progress: what each move does, and what its end does (told whether the pointer moved). */
    private static final class Drag {
        final Consumer<DbmlPoint> move;
        final Consumer<Boolean> end;
        final int startX, startY;
        boolean moved;

        Drag(int startX, int startY, Consumer<DbmlPoint> move, Consumer<Boolean> end) {
            this.startX = startX;
            this.startY = startY;
            this.move = move;
            this.end = end;
        }
    }

    @SuppressWarnings("this-escape")
    public DbmlCanvas(Composite parent, int style, DbmlDiagram diagram) {
        super(parent, style | SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        DbmlDiagram d = diagram == null ? new DbmlDiagram("") : diagram.copy();
        d.placeMissing();
        this.diagram = d;
        this.geometry = new DbmlGeometry(d);
        setBackground(new Color(DbmlRenderer.WHITE));
        addListener(SWT.Paint, this::paint);
        addListener(SWT.Resize, e -> {
            updateScrollBars();
            redraw();
        });
        addListener(SWT.MouseDown, this::mouseDown);
        addListener(SWT.MouseDoubleClick, this::mouseDoubleClick);
        addListener(SWT.MouseMove, this::mouseMove);
        addListener(SWT.MouseUp, this::mouseUp);
        addListener(SWT.MouseExit, e -> setToolTip(null));
        addListener(SWT.MenuDetect, this::menuDetect);
        addListener(SWT.KeyDown, this::keyDown);
        addListener(SWT.Traverse, e -> {
            switch (e.detail) {
                case SWT.TRAVERSE_ARROW_NEXT, SWT.TRAVERSE_ARROW_PREVIOUS, SWT.TRAVERSE_ESCAPE, SWT.TRAVERSE_RETURN -> e.doit = false;
                default -> { }
            }
        });
        addListener(SWT.MouseVerticalWheel, this::wheel);
        addListener(SWT.FocusIn, e -> redraw());
        addListener(SWT.Dispose, e -> {
            if (contextMenu != null && !contextMenu.isDisposed()) contextMenu.dispose();
            if (noteEditorFont != null) noteEditorFont.dispose();
        });
        ScrollBar h = getHorizontalBar();
        if (h != null) h.addListener(SWT.Selection, e -> scrollTo(h.getSelection(), scrollY));
        ScrollBar v = getVerticalBar();
        if (v != null) v.addListener(SWT.Selection, e -> scrollTo(scrollX, v.getSelection()));
        QuickHelp.provider(this, (c, p) -> quickHelp(point(p.x, p.y)));
        updateScrollBars();
    }

    // MARK: State

    public void setHost(DbmlCanvasHost host) {
        this.host = host != null ? host : new DbmlCanvasHost() {};
    }

    public DbmlCanvasHost getHost() { return host; }

    /** The diagram shown (a copy; change it with {@link #change} or {@link #setDiagram}). */
    public DbmlDiagram getDiagram() { return diagram.copy(); }

    /** The schema of the diagram shown. */
    public DbmlSchema getSchema() { return geometry.schema; }

    /** The diagram laid out as shown. */
    public DbmlGeometry getGeometry() { return geometry; }

    /** Shows another diagram (lays it out again; not a change the host hears of). */
    public void setDiagram(DbmlDiagram d) {
        if (d == null || d.equals(diagram)) return;
        diagram = d.copy();
        relayout();
    }

    public DbmlSelection getSelection() { return selection; }

    public void setSelection(DbmlSelection s) {
        if (s == null) s = DbmlSelection.NONE;
        if (s.equals(selection)) return;
        selection = s;
        redraw();
        host.selectionDidChange(this);
    }

    public double getZoom() { return zoom; }

    /** The scale it's drawn at, keeping the middle of what's shown in place. */
    public void setZoom(double z) {
        Rectangle ca = getClientArea();
        setZoom(z, ca.width / 2, ca.height / 2);
    }

    /** The scale it's drawn at, keeping the diagram point under (x, y) of the canvas in place. */
    public void setZoom(double z, int x, int y) {
        z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        if (Math.abs(z - zoom) < 0.0001) return;
        DbmlPoint anchor = point(x, y);
        zoom = z;
        updateScrollBars();
        scrollTo((int) Math.round(anchor.x() * zoom - x), (int) Math.round(anchor.y() * zoom - y));
        positionNoteEditor();
        setToolTip(null);
        redraw();
    }

    public boolean isEditable() { return editable; }
    public void setEditable(boolean editable) { this.editable = editable; }

    /** Off for printing and pictures of the page. */
    public void setShowsSelection(boolean shows) {
        showsSelection = shows;
        redraw();
    }

    public void setDrawsBackground(boolean draws) {
        drawsBackground = draws;
        redraw();
    }

    /** Ctrl + mouse wheel zooms (on by default). */
    public void setWheelZoom(boolean on) { wheelZoom = on; }

    /** The margin scrolled to around the diagram, and the least content size (both in diagram points). */
    public void setContentMargin(double margin) {
        contentMargin = margin;
        updateScrollBars();
    }

    public void setMinimumContent(DbmlSize size) {
        minimumContent = size;
        updateScrollBars();
    }

    public String getEditingNote() { return editingNote; }

    /** What's shown, in diagram points. */
    public DbmlRect visibleArea() {
        Rectangle ca = getClientArea();
        return new DbmlRect(scrollX / zoom, scrollY / zoom, ca.width / zoom, ca.height / zoom);
    }

    /** The size the canvas needs at its zoom, with a margin around the diagram (at least {@code minimum}), in pixels. */
    public DbmlSize contentSize(double margin, DbmlSize minimum) {
        return new DbmlSize(Math.max(minimum.width(), Math.ceil((geometry.extent.maxX() + margin) * zoom)),
                Math.max(minimum.height(), Math.ceil((geometry.extent.maxY() + margin) * zoom)));
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        DbmlSize s = contentSize(contentMargin, new DbmlSize(0, 0));
        return new Point(wHint != SWT.DEFAULT ? wHint : (int) s.width(), hHint != SWT.DEFAULT ? hHint : (int) s.height());
    }

    private void relayout() {
        if (diagram.hasMissingPlaces()) {
            DbmlDiagram d = diagram.copy();
            d.placeMissing();
            diagram = d;
        }
        geometry = new DbmlGeometry(diagram);
        Set<String> keys = new HashSet<>();
        for (DbmlGeometry.Box b : geometry.boxes) keys.add(b.key());
        DbmlSelection s = selection;
        if (!keys.containsAll(s.tables())) {
            Set<String> kept = new HashSet<>(s.tables());
            kept.retainAll(keys);
            s = new DbmlSelection(kept, s.ref(), s.note());
        }
        if (s.ref() != null && geometry.line(s.ref()) == null) s = new DbmlSelection(s.tables(), null, s.note());
        if (s.note() != null && geometry.sticky(s.note()) == null) s = new DbmlSelection(s.tables(), s.ref(), null);
        setSelection(s);
        positionNoteEditor();
        updateScrollBars();
        redraw();
    }

    // MARK: Scrolling

    private void updateScrollBars() {
        if (isDisposed()) return;
        Rectangle ca = getClientArea();
        DbmlSize size = contentSize(contentMargin, new DbmlSize(Math.max(minimumContent.width() * zoom, ca.width),
                Math.max(minimumContent.height() * zoom, ca.height)));
        int w = (int) size.width(), h = (int) size.height();
        ScrollBar hb = getHorizontalBar(), vb = getVerticalBar();
        scrollX = hb == null ? 0 : Math.max(0, Math.min(scrollX, w - ca.width));
        scrollY = vb == null ? 0 : Math.max(0, Math.min(scrollY, h - ca.height));
        if (hb != null) hb.setValues(scrollX, 0, Math.max(w, 1), Math.max(1, ca.width), 20, Math.max(1, ca.width - 40));
        if (vb != null) vb.setValues(scrollY, 0, Math.max(h, 1), Math.max(1, ca.height), 20, Math.max(1, ca.height - 40));
    }

    /** Scrolls so (x, y) of the content (in pixels at the zoom) is at the top left. */
    public void scrollTo(int x, int y) {
        ScrollBar hb = getHorizontalBar(), vb = getVerticalBar();
        int nx = hb == null ? 0 : Math.max(0, Math.min(x, hb.getMaximum() - hb.getThumb()));
        int ny = vb == null ? 0 : Math.max(0, Math.min(y, vb.getMaximum() - vb.getThumb()));
        if (nx == scrollX && ny == scrollY) return;
        scrollX = nx;
        scrollY = ny;
        if (hb != null) hb.setSelection(scrollX);
        if (vb != null) vb.setSelection(scrollY);
        positionNoteEditor();
        redraw();
    }

    public Point getScroll() { return new Point(scrollX, scrollY); }

    private void wheel(Event e) {
        if (wheelZoom && (e.stateMask & SWT.MOD1) != 0) {
            e.doit = false;
            setZoom(zoom * (e.count > 0 ? 1.25 : 1 / 1.25), e.x, e.y);
            host.zoomDidChange(this);
        } else if ((e.stateMask & SWT.SHIFT) != 0 && getHorizontalBar() != null) {
            e.doit = false;
            scrollTo(scrollX - e.count * 20, scrollY);
        }
    }

    // MARK: Drawing

    private void paint(Event e) {
        GC gc = e.gc;
        if (drawsBackground) {
            gc.setBackground(new Color(DbmlRenderer.WHITE));
            gc.fillRectangle(e.x, e.y, e.width, e.height);
        }
        if (shownGrid != null && shownGrid.step() >= 4) {
            gc.setBackground(new Color(new RGB(115, 158, 217)));
            gc.setAlpha(115);
            double step = shownGrid.step();
            double top = e.y + scrollY, left = e.x + scrollX;
            for (double y = shownGrid.origin().y() + Math.floor((top - shownGrid.origin().y()) / step) * step; y <= top + e.height; y += step) {
                for (double x = shownGrid.origin().x() + Math.floor((left - shownGrid.origin().x()) / step) * step; x <= left + e.width; x += step) {
                    gc.fillRectangle((int) Math.round(x - scrollX) - 1, (int) Math.round(y - scrollY) - 1, 2, 2);
                }
            }
            gc.setAlpha(255);
        }
        Transform t = new Transform(getDisplay());
        try {
            t.translate(-scrollX, -scrollY);
            t.scale((float) zoom, (float) zoom);
            gc.setTransform(t);
            DbmlRenderer.draw(gc, geometry, showsSelection ? selection : DbmlSelection.NONE, editingNote);
            gc.setTransform(null);
        } finally {
            t.dispose();
        }
    }

    // MARK: Finding things

    /** The diagram point at (x, y) of the canvas. */
    public DbmlPoint point(int x, int y) {
        return new DbmlPoint((x + scrollX) / zoom, (y + scrollY) / zoom);
    }

    /** What a diagram point is on (null: nothing). */
    public DbmlHit hit(DbmlPoint p) {
        double reach = 7 / zoom;
        if (selection.ref() != null) {
            DbmlGeometry.Line line = geometry.line(selection.ref());
            if (line != null && line.style().isBezier()) {
                for (int k = 0; k < line.controls().size(); k++) {
                    DbmlPoint c = line.controls().get(k);
                    if (Math.hypot(c.x() - p.x(), c.y() - p.y()) <= reach + 2) return new DbmlHit.Handle(selection.ref(), k);
                }
            }
        }
        for (int k = geometry.stickies.size() - 1; k >= 0; k--) {
            DbmlGeometry.Sticky s = geometry.stickies.get(k);
            if (s.own() && s.id().equals(selection.note()) && Math.hypot(s.rect().maxX() - p.x(), s.rect().maxY() - p.y()) <= 12 / zoom) {
                return new DbmlHit.NoteCorner(s.id());
            }
            if (s.rect().contains(p)) return new DbmlHit.Note(s.id());
        }
        for (int k = geometry.boxes.size() - 1; k >= 0; k--) {
            DbmlGeometry.Box b = geometry.boxes.get(k);
            if (b.rect().contains(p)) return new DbmlHit.Box(b.key());
        }
        String line = lineNear(p, 5 / zoom);
        if (line != null) return new DbmlHit.Line(line);
        for (int k = geometry.groups.size() - 1; k >= 0; k--) {
            DbmlGeometry.Group g = geometry.groups.get(k);
            if (new DbmlRect(g.rect().minX(), g.rect().minY(), g.rect().width(), 30).contains(p)) return new DbmlHit.Panel(g.name());
        }
        return null;
    }

    /** The relationship whose line passes nearest a point, within {@code distance}. */
    public String lineNear(DbmlPoint p, double distance) {
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (DbmlGeometry.Line line : geometry.lines) {
            double d = DbmlGeometry.distance(p, line.flattened());
            if (d <= distance && (best == null || d < bestD)) {
                best = line.ref().id();
                bestD = d;
            }
        }
        return best;
    }

    private List<String> groupTables(String name) {
        for (DbmlGeometry.Group g : geometry.groups) if (g.name().equals(name)) return g.tables();
        return List.of();
    }

    /** The schema of what's at a point: a table's or enum's, a panel's tables', or (elsewhere) none. */
    public String schemaAt(DbmlHit hit) {
        return switch (hit) {
            case DbmlHit.Box b -> {
                DbmlGeometry.Box box = geometry.box(b.key());
                yield box != null ? box.schema() : null;
            }
            case DbmlHit.Panel p -> {
                for (String t : groupTables(p.name())) {
                    DbmlSchema.Table table = geometry.schema.table(t);
                    if (table != null) yield table.schema();
                }
                yield null;
            }
            case DbmlHit.Line l -> lineSchema(l.id());
            case DbmlHit.Handle h -> lineSchema(h.id());
            case null, default -> null;
        };
    }

    private String lineSchema(String id) {
        DbmlGeometry.Line line = geometry.line(id);
        if (line == null) return null;
        DbmlSchema.Table t = geometry.schema.table(line.ref().from().table());
        return t != null ? t.schema() : null;
    }

    // MARK: Changes

    private void commit(String name, DbmlDiagram old) {
        if (diagram.equals(old)) return;
        host.didChange(this, old, name);
    }

    /** A change as one step called {@code name} (the host hears of it). */
    public void change(String name, Consumer<DbmlDiagram> body) {
        DbmlDiagram old = diagram.copy();
        DbmlDiagram d = diagram.copy();
        body.accept(d);
        setDiagram(d);
        commit(name, old);
    }

    private void live(Consumer<DbmlDiagram> body) {
        DbmlDiagram d = diagram.copy();
        body.accept(d);
        if (d.equals(diagram)) return;
        diagram = d;
        relayout();
        host.didChangeLive(this);
    }

    // MARK: Snap to grid

    private void setShownGrid(DbmlSnapGrid grid) {
        if (Objects.equals(grid, shownGrid)) return;
        shownGrid = grid;
        redraw();
    }

    /** A diagram point put on the grid (the grid is in canvas coordinates, the diagram scaled by the zoom). */
    private DbmlPoint snapped(DbmlPoint p, DbmlSnapGrid grid) {
        return new DbmlPoint((grid.origin().x() + DbmlDiagram.round((p.x() * zoom - grid.origin().x()) / grid.step()) * grid.step()) / zoom,
                (grid.origin().y() + DbmlDiagram.round((p.y() * zoom - grid.origin().y()) / grid.step()) * grid.step()) / zoom);
    }

    /**
     * A drag's offset, changed so {@code anchor} (the dragged thing's top-left corner) lands on the host's grid; the
     * grid shows while it snaps.
     */
    private double[] snapOffset(double dx, double dy, DbmlPoint anchor) {
        DbmlSnapGrid grid = anchor == null ? null : host.snapGrid(this);
        if (grid == null) {
            setShownGrid(null);
            return new double[] { dx, dy };
        }
        setShownGrid(grid);
        DbmlPoint target = snapped(new DbmlPoint(anchor.x() + dx, anchor.y() + dy), grid);
        return new double[] { target.x() - anchor.x(), target.y() - anchor.y() };
    }

    /** An arrow key's move with a grid: to the next grid line that way (from where the anchor is, on a line or not). */
    private double[] gridStep(DbmlPoint anchor, double dx, double dy, DbmlSnapGrid grid) {
        return new double[] { next(anchor.x(), grid.origin().x(), dx, grid) - anchor.x(), next(anchor.y(), grid.origin().y(), dy, grid) - anchor.y() };
    }

    private double next(double v, double origin, double dir, DbmlSnapGrid grid) {
        if (dir == 0) return v;
        double u = (v * zoom - origin) / grid.step();
        double n = dir > 0 ? Math.floor(u + 0.001) + 1 : Math.ceil(u - 0.001) - 1;
        return (origin + n * grid.step()) / zoom;
    }

    // MARK: Mouse

    private void mouseDown(Event e) {
        if (!editable || e.button != 1) return;
        host.wasClicked(this);
        endNoteEditing(true);
        forceFocus();
        setToolTip(null);
        if (e.count >= 2) return;      // the double-click follows
        DbmlPoint p = point(e.x, e.y);
        boolean adding = (e.stateMask & (SWT.SHIFT | SWT.MOD1)) != 0;
        DbmlHit hit = hit(p);
        switch (hit) {
            case DbmlHit.Handle h -> dragControl(h.id(), h.index(), e);
            case DbmlHit.NoteCorner c -> resizeNote(c.id(), e);
            case DbmlHit.Note n -> {
                setSelection(DbmlSelection.ofNote(n.id()));
                dragNote(n.id(), e);
            }
            case DbmlHit.Box b -> {
                if (adding) {
                    Set<String> tables = new HashSet<>(selection.tables());
                    if (!tables.remove(b.key())) tables.add(b.key());
                    setSelection(DbmlSelection.ofTables(tables));
                    if (!selection.tables().contains(b.key())) return;
                } else if (!selection.tables().contains(b.key())) {
                    setSelection(DbmlSelection.ofTables(Set.of(b.key())));
                }
                dragTables(selection.tables(), b.key(), e);
            }
            case DbmlHit.Line l -> setSelection(DbmlSelection.ofRef(l.id()));
            case DbmlHit.Panel pn -> {
                Set<String> tables = new HashSet<>(groupTables(pn.name()));
                Set<String> keys = new HashSet<>();
                for (DbmlGeometry.Box b : geometry.boxes) keys.add(b.key());
                tables.retainAll(keys);
                if (adding) tables.addAll(selection.tables());
                setSelection(DbmlSelection.ofTables(tables));
                dragTables(selection.tables(), null, e);
            }
            case null -> {
                if (!adding) setSelection(DbmlSelection.NONE);
            }
        }
    }

    private void mouseDoubleClick(Event e) {
        if (!editable || e.button != 1) return;
        host.wasClicked(this);
        endNoteEditing(true);
        forceFocus();
        doubleClick(point(e.x, e.y));
    }

    private void doubleClick(DbmlPoint p) {
        switch (hit(p)) {
            case DbmlHit.Note n -> editIfOwn(n.id());
            case DbmlHit.NoteCorner c -> editIfOwn(c.id());
            case DbmlHit.Box b -> {
                DbmlGeometry.Box box = geometry.box(b.key());
                if (box != null) host.showSourceLine(this, box.line());
            }
            case DbmlHit.Line l -> showLineSource(l.id());
            case DbmlHit.Handle h -> showLineSource(h.id());
            case DbmlHit.Panel pn -> { }
            case null -> addNote(new DbmlPoint(p.x() - 20, p.y() - 20), "");
        }
    }

    private void editIfOwn(String id) {
        DbmlGeometry.Sticky s = geometry.sticky(id);
        if (s != null && s.own()) editNote(id);
    }

    private void showLineSource(String id) {
        DbmlGeometry.Line line = geometry.line(id);
        if (line != null) host.showSourceLine(this, line.ref().line());
    }

    private void mouseMove(Event e) {
        if (drag != null) {
            if ((e.stateMask & SWT.BUTTON1) == 0) {
                finishDrag();
                return;
            }
            if (!drag.moved && e.x == drag.startX && e.y == drag.startY) return;
            drag.moved = true;
            autoscroll(e.x, e.y);
            drag.move.accept(point(e.x, e.y));
            return;
        }
        if (!editable) return;
        DbmlPoint p = point(e.x, e.y);
        setCursorFor(hit(p));
        setToolTip(toolTip(p));
    }

    private void mouseUp(Event e) {
        if (e.button == 1 && drag != null) finishDrag();
    }

    private void finishDrag() {
        Drag d = drag;
        drag = null;
        if (d != null) d.end.accept(d.moved);
    }

    /** Scrolls while a drag is beyond the canvas's edge. */
    private void autoscroll(int x, int y) {
        Rectangle ca = getClientArea();
        int dx = x < 0 ? Math.max(-40, x) : x > ca.width ? Math.min(40, x - ca.width) : 0;
        int dy = y < 0 ? Math.max(-40, y) : y > ca.height ? Math.min(40, y - ca.height) : 0;
        if (dx != 0 || dy != 0) {
            updateScrollBars();
            scrollTo(scrollX + dx, scrollY + dy);
        }
    }

    /**
     * Moves tables together; with a snap grid, the table dragged by ({@code lead}, else the group's top-left corner)
     * lands on it and the others keep their places around it.
     */
    private void dragTables(Set<String> keys, String lead, Event e) {
        DbmlPoint start = point(e.x, e.y);
        Map<String, DbmlPoint> origins = new HashMap<>();
        for (String k : keys) {
            DbmlPoint p = diagram.positions.get(k);
            if (p != null) origins.put(k, p);
        }
        DbmlPoint anchor = lead != null ? origins.get(lead) : null;
        if (anchor == null && !origins.isEmpty()) {
            double x = Double.MAX_VALUE, y = Double.MAX_VALUE;
            for (DbmlPoint p : origins.values()) {
                x = Math.min(x, p.x());
                y = Math.min(y, p.y());
            }
            anchor = new DbmlPoint(x, y);
        }
        DbmlDiagram old = diagram.copy();
        DbmlPoint a = anchor;
        Set<String> moving = Set.copyOf(keys);
        drag = new Drag(e.x, e.y, p -> {
            double[] off = snapOffset(p.x() - start.x(), p.y() - start.y(), a);
            live(d -> d.move(moving, origins, off[0], off[1]));
        }, moved -> {
            setShownGrid(null);
            if (moved) commit(moving.size() == 1 ? "Move Table" : "Move Tables", old);
        });
    }

    private void dragNote(String id, Event e) {
        DbmlPoint start = point(e.x, e.y);
        DbmlDiagram old = diagram.copy();
        DbmlDiagramNote ownNote = diagram.note(id);
        DbmlRect own = ownNote != null ? ownNote.frame() : null;
        DbmlGeometry.Sticky st = geometry.sticky(id);
        DbmlRect sticky = st != null && !st.own() ? st.rect() : null;
        drag = new Drag(e.x, e.y, p -> {
            double[] off = snapOffset(p.x() - start.x(), p.y() - start.y(), own != null ? own.origin() : sticky != null ? sticky.origin() : null);
            live(d -> {
                int i = d.noteIndex(id);
                if (own != null && i >= 0) {
                    DbmlDiagramNote n = d.notes.get(i);
                    d.notes.set(i, n.withFrame(n.frame().withOrigin(new DbmlPoint(Math.max(0, DbmlDiagram.round(own.minX() + off[0])),
                            Math.max(0, DbmlDiagram.round(own.minY() + off[1]))))));
                } else if (sticky != null) {
                    d.stickyFrames.put(id, new DbmlRect(new DbmlPoint(Math.max(0, DbmlDiagram.round(sticky.minX() + off[0])),
                            Math.max(0, DbmlDiagram.round(sticky.minY() + off[1]))), sticky.size()));
                }
            });
        }, moved -> {
            setShownGrid(null);
            if (moved) commit("Move Note", old);
        });
    }

    private void resizeNote(String id, Event e) {
        DbmlDiagramNote note = diagram.note(id);
        if (note == null) return;
        DbmlRect frame = note.frame();
        DbmlPoint start = point(e.x, e.y);
        DbmlDiagram old = diagram.copy();
        drag = new Drag(e.x, e.y, p -> {
            // With a grid, the corner dragged lands on it.
            double[] off = snapOffset(p.x() - start.x(), p.y() - start.y(), new DbmlPoint(frame.maxX(), frame.maxY()));
            live(d -> {
                int i = d.noteIndex(id);
                if (i < 0) return;
                DbmlDiagramNote n = d.notes.get(i);
                d.notes.set(i, n.withFrame(n.frame().withSize(new DbmlSize(Math.max(120, DbmlDiagram.round(frame.width() + off[0])),
                        Math.max(60, DbmlDiagram.round(frame.height() + off[1]))))));
            });
        }, moved -> {
            setShownGrid(null);
            if (moved) commit("Resize Note", old);
        });
    }

    private void dragControl(String id, int k, Event e) {
        DbmlDiagram old = diagram.copy();
        drag = new Drag(e.x, e.y, p -> live(d -> d.moveControl(k, id, p, geometry)), moved -> {
            if (moved) commit("Bend Line", old);
        });
    }

    // MARK: Notes

    /** Adds a note at a diagram point, selects it and starts typing in it. */
    public void addNote(DbmlPoint at, String text) {
        String[] id = new String[1];
        change("Add Note", d -> id[0] = d.addNote(at, text));
        setSelection(DbmlSelection.ofNote(id[0]));
        editNote(id[0]);
    }

    public void deleteNote(String id) {
        endNoteEditing(false);
        change("Delete Note", d -> d.notes.removeIf(n -> n.id().equals(id)));
    }

    public void setNoteColor(String hex, String id) {
        change("Note Color", d -> {
            int i = d.noteIndex(id);
            if (i >= 0) d.notes.set(i, d.notes.get(i).withColor(DbmlDiagramNote.PALETTE.get(0).hex().equals(hex) ? null : hex));
        });
    }

    /** Typing in one of the diagram's notes, in place. */
    public void editNote(String id) {
        DbmlDiagramNote note = diagram.note(id);
        if (note == null) return;
        endNoteEditing(true);
        setSelection(DbmlSelection.ofNote(id));
        editingNote = id;
        Text tv = new Text(this, SWT.MULTI | SWT.WRAP);
        tv.setText(note.text().replace("\r\n", "\n").replace("\n", Text.DELIMITER));
        DbmlGeometry.Sticky s = geometry.sticky(id);
        tv.setBackground(new Color(s != null ? DbmlRenderer.noteColor(s) : DbmlRenderer.blend(DbmlGeometry.YELLOW, 0.28)));
        tv.setForeground(new Color(DbmlRenderer.LABEL));
        noteEditor = tv;
        positionNoteEditor();
        tv.addListener(SWT.FocusOut, ev -> getDisplay().asyncExec(() -> {
            if (noteEditor == tv && !tv.isDisposed() && !tv.isFocusControl()) endNoteEditing(true);
        }));
        tv.addListener(SWT.Traverse, ev -> {
            if (ev.detail == SWT.TRAVERSE_ESCAPE) {
                ev.doit = false;
                ev.detail = SWT.TRAVERSE_NONE;
                getDisplay().asyncExec(() -> {
                    if (noteEditor == tv && !isDisposed()) {
                        endNoteEditing(true);
                        setFocus();
                    }
                });
            }
        });
        tv.setFocus();
        tv.selectAll();
        redraw();
    }

    private void positionNoteEditor() {
        if (noteEditor == null || noteEditor.isDisposed() || editingNote == null) return;
        DbmlDiagramNote note = diagram.note(editingNote);
        if (note == null) return;
        DbmlRect f = note.frame();
        noteEditor.setBounds((int) Math.round(f.minX() * zoom + 5 - scrollX), (int) Math.round(f.minY() * zoom + 4 - scrollY),
                (int) Math.max(20, f.width() * zoom - 10), (int) Math.max(20, f.height() * zoom - 8));
        if (noteEditorFont == null || noteEditorFontZoom != zoom) {
            Font old = noteEditorFont;
            noteEditorFont = new Font(getDisplay(), DbmlRenderer.noteFontData(zoom));
            noteEditorFontZoom = zoom;
            noteEditor.setFont(noteEditorFont);
            if (old != null) old.dispose();
        }
    }

    /** Ends typing in a note, keeping what was typed as one step (unless {@code commitText} is false). */
    public void endNoteEditing(boolean commitText) {
        if (noteEditor == null || editingNote == null) return;
        Text tv = noteEditor;
        String id = editingNote;
        String text = tv.isDisposed() ? null : tv.getText().replace("\r\n", "\n");
        boolean hadFocus = !tv.isDisposed() && tv.isFocusControl();
        noteEditor = null;
        editingNote = null;
        if (!tv.isDisposed()) tv.dispose();
        if (noteEditorFont != null) {
            noteEditorFont.dispose();
            noteEditorFont = null;
        }
        DbmlDiagramNote n = diagram.note(id);
        if (commitText && text != null && n != null && !n.text().equals(text)) {
            change("Edit Note", d -> {
                int i = d.noteIndex(id);
                if (i >= 0) d.notes.set(i, d.notes.get(i).withText(text));
            });
        }
        redraw();
        if (hadFocus && !isDisposed()) setFocus();
    }

    // MARK: Schemas

    /** Selects every table (and enum) of a schema. */
    public void selectSchema(String name) {
        Set<String> keys = new HashSet<>();
        for (DbmlGeometry.Box b : geometry.boxes) if (b.schema().equals(name)) keys.add(b.key());
        setSelection(DbmlSelection.ofTables(keys));
    }

    public void selectAllTables() {
        Set<String> keys = new HashSet<>();
        for (DbmlGeometry.Box b : geometry.boxes) keys.add(b.key());
        setSelection(DbmlSelection.ofTables(keys));
    }

    /** Copy Schema: the schema's DBML (with its layout) as text, a picture of it, and what the app adds. */
    public void copySchema(String name) {
        DbmlDiagram part = diagram.subset(name, host.diagramTitle());
        List<Object> data = new ArrayList<>();
        List<Transfer> transfers = new ArrayList<>();
        data.add(part.fileText());
        transfers.add(TextTransfer.getInstance());
        try {
            ImageData picture = DbmlRenderer.picture(getDisplay(), part, 2);
            data.add(picture);
            transfers.add(ImageTransfer.getInstance());
        } catch (SWTError | SWTException | IllegalArgumentException ex) {
            // The text alone, then.
        }
        host.copySchema(this, part, name, data, transfers);
        Clipboard cb = new Clipboard(getDisplay());
        try {
            cb.setContents(data.toArray(), transfers.toArray(new Transfer[0]));
        } finally {
            cb.dispose();
        }
    }

    public void exportSchema(String name) {
        host.exportSchema(this, diagram.subset(name, host.diagramTitle()), name);
    }

    private void schemaMenu(Menu parent, String name) {
        int count = 0;
        for (DbmlSchema.Table t : geometry.schema.tables()) if (t.schema().equals(name)) count++;
        Menu m = cascade(parent, "Schema “" + name + "” (" + count + " table" + (count == 1 ? "" : "s") + ")");
        item(m, "Select Schema", () -> selectSchema(name));
        item(m, "Copy Schema", () -> copySchema(name));
        String title = host.schemaExportTitle();
        if (title != null) item(m, title, () -> exportSchema(name));
    }

    // MARK: Menu

    private void menuDetect(Event e) {
        e.doit = false;
        if (!editable || drag != null) return;
        host.wasClicked(this);
        endNoteEditing(true);
        Point at;
        if (e.detail == SWT.MENU_KEYBOARD) {
            Rectangle ca = getClientArea();
            at = new Point(ca.width / 2, ca.height / 2);
        } else {
            at = toControl(e.x, e.y);
        }
        Menu menu = menu(point(at.x, at.y));
        Point screen = toDisplay(at);
        menu.setLocation(screen);
        menu.setVisible(true);
    }

    /** The right-click menu at a diagram point (selecting what's there). */
    public Menu menu(DbmlPoint p) {
        if (contextMenu != null && !contextMenu.isDisposed()) contextMenu.dispose();
        Menu menu = new Menu(this);
        contextMenu = menu;
        DbmlHit found = hit(p);
        String lineID = found instanceof DbmlHit.Line l ? l.id() : found instanceof DbmlHit.Handle h ? h.id() : null;
        String noteID = found instanceof DbmlHit.Note n ? n.id() : found instanceof DbmlHit.NoteCorner c ? c.id() : null;
        if (lineID != null) {
            setSelection(DbmlSelection.ofRef(lineID));
            addLineItems(menu, lineID);
        } else if (noteID != null) {
            setSelection(DbmlSelection.ofNote(noteID));
            DbmlDiagramNote note = diagram.note(noteID);
            if (note != null) {
                String id = noteID;
                item(menu, "Edit Note", () -> editNote(id));
                Menu cm = cascade(menu, "Note Color");
                String current = note.color() != null ? note.color() : DbmlDiagramNote.PALETTE.get(0).hex();
                for (Swatch s : DbmlDiagramNote.PALETTE) check(cm, s.name(), current.equals(s.hex()), () -> setNoteColor(s.hex(), id));
                item(menu, "Delete Note", () -> deleteNote(id));
            } else {
                item(menu, "Note “" + noteID + "” is part of the DBML (read-only)", null).setEnabled(false);
            }
        } else if (found instanceof DbmlHit.Box b) {
            if (!selection.tables().contains(b.key())) setSelection(DbmlSelection.ofTables(Set.of(b.key())));
        }
        if (menu.getItemCount() > 0) new MenuItem(menu, SWT.SEPARATOR);
        // The schema of what's here — or, elsewhere, each schema.
        List<String> schemas = geometry.schema.schemas();
        String name = schemaAt(found);
        if (name != null) {
            schemaMenu(menu, name);
        } else if (!schemas.isEmpty()) {
            if (schemas.size() == 1) {
                schemaMenu(menu, schemas.get(0));
            } else {
                Menu all = cascade(menu, "Schemas");
                for (String s : schemas) schemaMenu(all, s);
            }
        }
        item(menu, "Select All Tables", this::selectAllTables);
        new MenuItem(menu, SWT.SEPARATOR);
        if (found == null || found instanceof DbmlHit.Panel) item(menu, "Add Note Here", () -> addNote(p, ""));
        item(menu, "Arrange Tables", () -> change("Arrange Tables", DbmlDiagram::arrange));
        check(menu, "Show Column Types", !diagram.hidesTypes, () -> change("Column Types", d -> d.hidesTypes = !d.hidesTypes));
        Menu lm = cascade(menu, "All Lines");
        boolean noOwnStyles = diagram.curves.values().stream().allMatch(c -> c.style() == null);
        for (DbmlCurveStyle style : DbmlCurveStyle.values()) {
            check(lm, style.title(), diagram.defaultStyle == style && noOwnStyles, () -> change("Line Style", d -> d.setAllStyles(style)));
        }
        int before = menu.getItemCount();
        MenuItem sep = new MenuItem(menu, SWT.SEPARATOR);
        host.addMenuItems(this, menu, p, found);
        if (menu.getItemCount() == before + 1) sep.dispose();
        return menu;
    }

    public void addLineItems(Menu menu, String id) {
        DbmlGeometry.Line line = geometry.line(id);
        if (line == null) return;
        item(menu, line.ref().text() + " (" + line.ref().kindTitle() + ")", null).setEnabled(false);
        for (DbmlCurveStyle style : DbmlCurveStyle.values()) {
            check(menu, style.title(), line.style() == style, () -> change("Line Style", d -> d.setStyle(style, id)));
        }
        item(menu, "Reset Curve", () -> change("Reset Line", d -> d.resetCurve(id)));
        DbmlRefCurve saved = diagram.curves.get(id);
        DbmlSide from = saved != null ? saved.fromSide() : null, to = saved != null ? saved.toSide() : null;
        Menu sm = cascade(menu, "Sides");
        check(sm, "Start on the Left", from == DbmlSide.LEFT, () -> change("Line Side", d -> d.setSide(DbmlSide.LEFT, true, id)));
        check(sm, "Start on the Right", from == DbmlSide.RIGHT, () -> change("Line Side", d -> d.setSide(DbmlSide.RIGHT, true, id)));
        check(sm, "End on the Left", to == DbmlSide.LEFT, () -> change("Line Side", d -> d.setSide(DbmlSide.LEFT, false, id)));
        check(sm, "End on the Right", to == DbmlSide.RIGHT, () -> change("Line Side", d -> d.setSide(DbmlSide.RIGHT, false, id)));
        new MenuItem(sm, SWT.SEPARATOR);
        check(sm, "Automatic", from == null && to == null, () -> change("Line Side", d -> {
            d.setSide(null, true, id);
            d.setSide(null, false, id);
        }));
        Menu cm = cascade(menu, "Line Color");
        String color = saved != null ? saved.color() : null;
        check(cm, "Automatic", color == null, () -> change("Line Color", d -> d.setColor(null, id)));
        for (Swatch s : LINE_COLORS) check(cm, s.name(), s.hex().equals(color), () -> change("Line Color", d -> d.setColor(s.hex(), id)));
    }

    private static String menuText(String s) {
        return s.replace("&", "&&");
    }

    private static MenuItem item(Menu m, String text, Runnable action) {
        MenuItem mi = new MenuItem(m, SWT.PUSH);
        mi.setText(menuText(text));
        if (action != null) mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    private static MenuItem check(Menu m, String text, boolean checked, Runnable action) {
        MenuItem mi = new MenuItem(m, SWT.CHECK);
        mi.setText(menuText(text));
        mi.setSelection(checked);
        mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    private static Menu cascade(Menu parent, String text) {
        MenuItem mi = new MenuItem(parent, SWT.CASCADE);
        mi.setText(menuText(text));
        Menu sub = new Menu(parent);
        mi.setMenu(sub);
        return sub;
    }

    // MARK: Keys

    private void keyDown(Event e) {
        if (!editable) return;
        double step = (e.stateMask & SWT.SHIFT) != 0 ? 10 : 1;
        switch (e.keyCode) {
            case SWT.DEL, SWT.BS -> deleteSelection();
            case SWT.ESC -> setSelection(DbmlSelection.NONE);
            case SWT.ARROW_LEFT -> nudge(-step, 0);
            case SWT.ARROW_RIGHT -> nudge(step, 0);
            case SWT.ARROW_UP -> nudge(0, -step);
            case SWT.ARROW_DOWN -> nudge(0, step);
            default -> {
                if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'a' || e.keyCode == 'A')) selectAllTables();
            }
        }
    }

    /** Whether Delete does something: a selected note of the diagram's own, or a reshaped relationship. */
    public boolean canDeleteSelection() {
        if (selection.note() != null && diagram.note(selection.note()) != null) return true;
        return selection.ref() != null && diagram.curves.containsKey(selection.ref());
    }

    /** Delete: the selected note goes; a selected relationship is drawn the automatic way again. */
    public void deleteSelection() {
        if (selection.note() != null && diagram.note(selection.note()) != null) {
            deleteNote(selection.note());
            return;
        }
        String id = selection.ref();
        if (id != null && diagram.curves.containsKey(id)) change("Reset Line", d -> d.resetCurve(id));
    }

    /** Arrow keys: the selected tables, or note, by {@code dx}, {@code dy} (with a snap grid: to its next line). */
    public void nudge(double dx, double dy) {
        DbmlSnapGrid grid = host.snapGrid(this);
        if (!selection.tables().isEmpty()) {
            Set<String> keys = selection.tables();
            Map<String, DbmlPoint> origins = new HashMap<>();
            for (String k : keys) if (diagram.positions.containsKey(k)) origins.put(k, diagram.positions.get(k));
            double[] off = { dx, dy };
            if (grid != null && !origins.isEmpty()) {
                double x = Double.MAX_VALUE, y = Double.MAX_VALUE;
                for (DbmlPoint p : origins.values()) {
                    x = Math.min(x, p.x());
                    y = Math.min(y, p.y());
                }
                off = gridStep(new DbmlPoint(x, y), dx, dy, grid);
            }
            double[] o = off;
            change(keys.size() == 1 ? "Move Table" : "Move Tables", d -> d.move(keys, new HashMap<>(d.positions), o[0], o[1]));
            return;
        }
        String id = selection.note();
        DbmlDiagramNote note = id != null ? diagram.note(id) : null;
        if (note != null) {
            double[] off = grid != null ? gridStep(note.frame().origin(), dx, dy, grid) : new double[] { dx, dy };
            change("Move Note", d -> {
                int i = d.noteIndex(id);
                DbmlDiagramNote n = d.notes.get(i);
                d.notes.set(i, n.withFrame(n.frame().withOrigin(new DbmlPoint(Math.max(0, n.frame().minX() + off[0]),
                        Math.max(0, n.frame().minY() + off[1])))));
            });
        }
    }

    // MARK: Pointer, tips and Quick Help

    private void setCursorFor(DbmlHit hit) {
        int kind = switch (hit) {
            case DbmlHit.Handle h -> SWT.CURSOR_CROSS;
            case DbmlHit.NoteCorner c -> SWT.CURSOR_SIZESE;
            case DbmlHit.Note n -> SWT.CURSOR_SIZEALL;
            case DbmlHit.Box b -> SWT.CURSOR_SIZEALL;
            case DbmlHit.Panel p -> SWT.CURSOR_SIZEALL;
            case DbmlHit.Line l -> SWT.CURSOR_HAND;
            case null -> SWT.CURSOR_ARROW;
        };
        if (kind == cursorKind) return;
        cursorKind = kind;
        setCursor(kind == SWT.CURSOR_ARROW ? null : getDisplay().getSystemCursor(kind));
    }

    private void setToolTip(String tip) {
        if (Objects.equals(tip, toolTip)) return;
        toolTip = tip;
        if (!isDisposed()) setToolTipText(tip);
    }

    /** A table's note (or name), a column's type, keys, default and note, or what a note is for. */
    public String toolTip(DbmlPoint p) {
        for (int k = geometry.stickies.size() - 1; k >= 0; k--) {
            DbmlGeometry.Sticky s = geometry.stickies.get(k);
            if (s.rect().contains(p)) return s.own() ? "Double-click to edit; drag to move" : "Sticky note " + s.id() + " from the DBML file";
        }
        DbmlGeometry.Box box = null;
        for (int k = geometry.boxes.size() - 1; k >= 0 && box == null; k--) if (geometry.boxes.get(k).rect().contains(p)) box = geometry.boxes.get(k);
        if (box == null) return null;
        int k = DbmlGeometry.row(box, p);
        if (k < 0) return box.note() != null ? box.note() : box.title();
        DbmlGeometry.Row row = box.rows().get(k);
        List<String> parts = new ArrayList<>();
        parts.add(row.name() + (row.type().isEmpty() ? "" : " " + row.type()));
        if (row.pk()) parts.add("primary key");
        if (row.notNull() && !row.pk()) parts.add("not null");
        if (row.unique()) parts.add("unique");
        if (row.defaultValue() != null) parts.add("default " + row.defaultValue());
        String tip = String.join(", ", parts);
        if (row.note() != null) tip += "\n" + row.note();
        return tip;
    }

    /** What Quick Help says about a diagram point. */
    public QuickHelpItem quickHelp(DbmlPoint p) {
        DbmlHit hit = hit(p);
        switch (hit) {
            case DbmlHit.Box b -> {
                DbmlGeometry.Box box = geometry.box(b.key());
                if (box == null) return null;
                if (box.kind() == DbmlGeometry.BoxKind.ENUMERATION) {
                    return new QuickHelpItem(box.title(), String.join(", ", box.rows().stream().map(DbmlGeometry.Row::name).toList()) + ". Drag it to move it.");
                }
                long refs = geometry.lines.stream().filter(l -> l.ref().from().table().equals(b.key()) || l.ref().to().table().equals(b.key())).count();
                int cols = box.rows().size();
                return new QuickHelpItem("Table " + box.title(), cols + " column" + (cols == 1 ? "" : "s") + ", " + refs + " relationship"
                        + (refs == 1 ? "" : "s") + ". " + (box.note() != null ? box.note() + "\n" : "")
                        + "Drag it to move it (Shift- or Ctrl-click for more tables: they move together); right-click for its schema.",
                        "Arrow keys nudge the selection, Ctrl+A selects every table");
            }
            case DbmlHit.Line l -> {
                return lineHelp(l.id());
            }
            case DbmlHit.Handle h -> {
                return lineHelp(h.id());
            }
            case DbmlHit.Note n -> {
                return noteHelp(n.id());
            }
            case DbmlHit.NoteCorner c -> {
                return noteHelp(c.id());
            }
            case DbmlHit.Panel pn -> {
                DbmlGeometry.Group g = null;
                for (DbmlGeometry.Group x : geometry.groups) if (x.name().equals(pn.name())) g = x;
                return new QuickHelpItem("Schema " + (g != null ? g.label() : pn.name()),
                        "Click its name to select all its tables (drag to move them together); right-click to select, copy or export the schema.");
            }
            case null -> {
                return new QuickHelpItem("Diagram",
                        "Drag tables and notes; click a relationship to reshape it; double-click empty space for a note; right-click a schema to select, copy or export it; right-click for Arrange Tables and line styles.",
                        "Ctrl + mouse wheel zooms");
            }
        }
    }

    private QuickHelpItem lineHelp(String id) {
        DbmlGeometry.Line line = geometry.line(id);
        if (line == null) return null;
        return new QuickHelpItem("Ref " + line.ref().text(), "A " + line.ref().kindTitle() + " relationship, drawn "
                + line.style().title().toLowerCase() + ". Click it, then drag its control points (Bézier curves) to bend it; right-click for its curve, sides and colour.",
                "Delete sets a selected relationship back to Automatic");
    }

    private QuickHelpItem noteHelp(String id) {
        DbmlGeometry.Sticky s = geometry.sticky(id);
        if (s != null && !s.own()) {
            return new QuickHelpItem("Sticky note " + id, "A sticky note written in the DBML file (by another tool): drag it to move it; it is read-only here.");
        }
        return new QuickHelpItem("Note", "A note over the diagram: drag to move, drag its corner to resize, double-click to type; right-click for its colour. Saved as a DBML sticky note.",
                "Delete removes the selected note");
    }
}
