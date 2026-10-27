package com.k3jdr.mx.dbml;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.k3jdr.mx.dbml.DbmlSchema.EnumType;
import com.k3jdr.mx.dbml.DbmlSchema.Group;
import com.k3jdr.mx.dbml.DbmlSchema.Ref;
import com.k3jdr.mx.dbml.DbmlSchema.StickyNote;
import com.k3jdr.mx.dbml.DbmlSchema.Table;

/**
 * A DBML diagram (DBMLDiagram.swift): the DBML, where each table (and enum) sits, how each relationship is drawn
 * (automatic, straight, or a quadratic or cubic Bézier through its control points, from the side of each table
 * chosen), the diagram's own notes, and the frames of the DBML's sticky notes. It reads and writes MxDataSQL's
 * .dbml files (the DBML, then a section with the notes and a layout comment), arranges tables (a block per table
 * group or schema, related tables together, packed in columns) and cuts out one schema.
 *
 * <p>A value as in Swift: {@link #copy()} before changing one that is shared; {@link #equals(Object)} compares
 * everything.
 */
public final class DbmlDiagram {
    public static final String ENUM_PREFIX = "enum:";
    public static final double MARGIN = 60;

    /** The DBML (without MxDataSQL's diagram section). */
    public String source;
    /** Where each table (by id) and enum ("enum:name") sits: its top-left corner. */
    public final Map<String, DbmlPoint> positions = new LinkedHashMap<>();
    /** The DBML's own sticky notes' frames, by name. */
    public final Map<String, DbmlRect> stickyFrames = new LinkedHashMap<>();
    /** Each relationship's line, by the relationship's id ({@link Ref#id()}). */
    public final Map<String, DbmlRefCurve> curves = new LinkedHashMap<>();
    /** Notes over the diagram. */
    public final List<DbmlDiagramNote> notes = new ArrayList<>();
    /** How lines without a style of their own are drawn. */
    public DbmlCurveStyle defaultStyle = DbmlCurveStyle.AUTOMATIC;
    /** Column types left out of the boxes. */
    public boolean hidesTypes;
    /** The scale the diagram was last shown at (kept in the file for MxDataSQL); null when not known. */
    public Double zoom;

    public DbmlDiagram(String source) {
        this.source = source == null ? "" : source;
    }

    public DbmlSchema schema() { return DbmlParser.parse(source); }

    public DbmlDiagram copy() {
        DbmlDiagram d = new DbmlDiagram(source);
        d.positions.putAll(positions);
        d.stickyFrames.putAll(stickyFrames);
        d.curves.putAll(curves);
        d.notes.addAll(notes);
        d.defaultStyle = defaultStyle;
        d.hidesTypes = hidesTypes;
        d.zoom = zoom;
        return d;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DbmlDiagram d && source.equals(d.source) && positions.equals(d.positions)
                && stickyFrames.equals(d.stickyFrames) && curves.equals(d.curves) && notes.equals(d.notes)
                && defaultStyle == d.defaultStyle && hidesTypes == d.hidesTypes && Objects.equals(zoom, d.zoom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, positions, stickyFrames, curves, notes, defaultStyle, hidesTypes, zoom);
    }

    public DbmlDiagramNote note(String id) {
        for (DbmlDiagramNote n : notes) if (n.id().equals(id)) return n;
        return null;
    }

    public int noteIndex(String id) {
        for (int k = 0; k < notes.size(); k++) if (notes.get(k).id().equals(id)) return k;
        return -1;
    }

    /** Swift's rounded(): to the nearest, halves away from zero. */
    static double round(double x) {
        double f = Math.floor(x), diff = x - f;
        if (diff > 0.5) return f + 1;
        if (diff < 0.5) return f;
        return x >= 0 ? f + 1 : f;
    }

    // MARK: Lines

    public DbmlRefCurve curve(Ref ref) {
        DbmlRefCurve c = curves.get(ref.id());
        return c != null ? c : DbmlRefCurve.PLAIN;
    }

    public DbmlCurveStyle style(Ref ref) {
        DbmlRefCurve c = curves.get(ref.id());
        return c != null && c.style() != null ? c.style() : defaultStyle;
    }

    private void putCurve(String id, DbmlRefCurve c) {
        if (c == null || c.isPlain()) curves.remove(id);
        else curves.put(id, c);
    }

    /**
     * A line drawn another way: quadratic starts bowed away from the straight line (so its point is easy to grab),
     * cubic from the automatic curve.
     */
    public void setStyle(DbmlCurveStyle style, String refID) {
        if (schema().ref(refID) == null) return;
        DbmlGeometry geometry = new DbmlGeometry(this);
        DbmlGeometry.Line line = geometry.line(refID);
        if (line == null) return;
        DbmlRefCurve c = curves.getOrDefault(refID, DbmlRefCurve.PLAIN).withStyle(style).withControls(null, null);
        switch (style) {
            case QUADRATIC -> {
                double dx = line.end().x() - line.start().x(), dy = line.end().y() - line.start().y();
                double len = Math.max(1, Math.sqrt(dx * dx + dy * dy));
                c = c.withControls(new DbmlPoint(-dy / len * 70, dx / len * 70), null);
            }
            case CUBIC -> {
                List<DbmlPoint> auto = DbmlGeometry.automaticControls(line.start(), line.end(), line.startSide(), line.endSide());
                c = c.withControls(new DbmlPoint(auto.get(0).x() - line.start().x(), auto.get(0).y() - line.start().y()),
                        new DbmlPoint(auto.get(1).x() - line.end().x(), auto.get(1).y() - line.end().y()));
            }
            default -> { }
        }
        if (style == defaultStyle && c.fromSide() == null && c.toSide() == null && c.color() == null
                && (style == DbmlCurveStyle.AUTOMATIC || style == DbmlCurveStyle.STRAIGHT)) {
            c = c.withStyle(null);
        }
        putCurve(refID, c);
    }

    /** Moves a quadratic or cubic line's control point {@code index} (0 or 1) to {@code point}. */
    public void moveControl(int index, String refID, DbmlPoint point, DbmlGeometry geometry) {
        if (geometry == null) geometry = new DbmlGeometry(this);
        DbmlGeometry.Line line = geometry.line(refID);
        if (line == null) return;
        DbmlRefCurve c = curves.getOrDefault(refID, DbmlRefCurve.PLAIN);
        switch (line.style()) {
            case QUADRATIC -> {
                DbmlPoint mid = new DbmlPoint((line.start().x() + line.end().x()) / 2, (line.start().y() + line.end().y()) / 2);
                c = c.withStyle(DbmlCurveStyle.QUADRATIC).withControls(new DbmlPoint(point.x() - mid.x(), point.y() - mid.y()), c.c2());
            }
            case CUBIC -> {
                DbmlPoint p1 = index == 0 ? point : line.controls().get(0), p2 = index == 1 ? point : line.controls().get(1);
                c = c.withStyle(DbmlCurveStyle.CUBIC).withControls(new DbmlPoint(p1.x() - line.start().x(), p1.y() - line.start().y()),
                        new DbmlPoint(p2.x() - line.end().x(), p2.y() - line.end().y()));
            }
            default -> {
                return;
            }
        }
        curves.put(refID, c);
    }

    /** A line as it was before it was shaped (its curve, sides and colour; the diagram's way of drawing lines). */
    public void resetCurve(String refID) {
        curves.remove(refID);
    }

    public void setSide(DbmlSide side, boolean start, String refID) {
        DbmlRefCurve c = curves.getOrDefault(refID, DbmlRefCurve.PLAIN);
        c = start ? c.withFromSide(side) : c.withToSide(side);
        if (c.style() == DbmlCurveStyle.QUADRATIC || c.style() == DbmlCurveStyle.CUBIC) {
            curves.put(refID, c.withControls(null, null));
            setStyle(c.style(), refID);
            return;
        }
        putCurve(refID, c);
    }

    public void setColor(String hex, String refID) {
        putCurve(refID, curves.getOrDefault(refID, DbmlRefCurve.PLAIN).withColor(hex));
    }

    /** Every line drawn one way (their own shapes and styles dropped; sides and colours kept). */
    public void setAllStyles(DbmlCurveStyle style) {
        defaultStyle = style;
        dropShapes();
    }

    private void dropShapes() {
        Map<String, DbmlRefCurve> kept = new LinkedHashMap<>();
        for (Map.Entry<String, DbmlRefCurve> e : curves.entrySet()) {
            DbmlRefCurve c = e.getValue().withStyle(null).withControls(null, null);
            if (!c.isPlain()) kept.put(e.getKey(), c);
        }
        curves.clear();
        curves.putAll(kept);
    }

    // MARK: Notes

    /** A new note over the diagram at a point, with a name MxDataSQL would give it; returns its id. */
    public String addNote(DbmlPoint point, String text) {
        Set<String> taken = new HashSet<>();
        for (DbmlDiagramNote n : notes) taken.add(n.id());
        for (StickyNote n : schema().notes()) taken.add(n.name());
        int n = notes.size() + 1;
        while (taken.contains("mx_note_" + n)) n++;
        String id = "mx_note_" + n;
        notes.add(new DbmlDiagramNote(id, text == null ? "" : text,
                new DbmlRect(new DbmlPoint(Math.max(0, round(point.x())), Math.max(0, round(point.y()))), DbmlDiagramNote.SIZE), null));
        return id;
    }

    public String addNote(DbmlPoint point) {
        return addNote(point, "");
    }

    // MARK: Arrangement

    public void arrange() {
        arrange(false);
    }

    /**
     * Lays the tables out as MxDataSQL does: one block per table group (tables in none: a block per schema, first),
     * tables ordered so related ones sit together, packed into columns; the blocks side by side. Enums and sticky
     * notes go below. Arranging everything resets the lines' curves; with {@code onlyMissing}, what has a place
     * keeps it and the rest goes below everything.
     */
    public void arrange(boolean onlyMissing) {
        DbmlSchema s = schema();
        double gap = 70;
        Map<String, DbmlSize> sizes = new HashMap<>();
        for (Table t : s.tables()) sizes.putIfAbsent(t.name(), DbmlGeometry.size(t, hidesTypes));
        List<List<String>> blocks = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (Group g : s.groups()) {
            List<String> members = new ArrayList<>();
            for (String t : g.tables()) if (sizes.containsKey(t) && placed.add(t)) members.add(t);
            if (!members.isEmpty()) blocks.add(members);
        }
        List<List<String>> rest = new ArrayList<>();
        for (String schemaName : s.schemas()) {
            List<String> members = new ArrayList<>();
            for (Table t : s.tables()) if (t.schema().equals(schemaName) && placed.add(t.name())) members.add(t.name());
            if (!members.isEmpty()) rest.add(members);
        }
        blocks.addAll(0, rest);
        Map<String, List<String>> neighbours = new HashMap<>();
        for (Ref r : s.refs()) {
            if (r.from().table().equals(r.to().table())) continue;
            neighbours.computeIfAbsent(r.from().table(), k -> new ArrayList<>()).add(r.to().table());
            neighbours.computeIfAbsent(r.to().table(), k -> new ArrayList<>()).add(r.from().table());
        }
        double originX = MARGIN;
        double originY = MARGIN;
        if (onlyMissing) {
            DbmlGeometry geometry = new DbmlGeometry(this, false);
            Double maxY = null;
            for (DbmlGeometry.Box b : geometry.boxes) {
                if (positions.containsKey(b.key())) maxY = maxY == null ? b.rect().maxY() : Math.max(maxY, b.rect().maxY());
            }
            for (DbmlGeometry.Sticky st : geometry.stickies) maxY = maxY == null ? st.rect().maxY() : Math.max(maxY, st.rect().maxY());
            if (maxY != null) originY = maxY + gap * 1.5;
            List<List<String>> missing = new ArrayList<>();
            for (List<String> block : blocks) {
                List<String> m = new ArrayList<>();
                for (String t : block) if (!positions.containsKey(t)) m.add(t);
                if (!m.isEmpty()) missing.add(m);
            }
            blocks = missing;
        }
        double bottom = originY;
        for (List<String> block : blocks) {
            // Breadth-first from the most connected table, so neighbours end up close.
            List<String> order = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Set<String> members = new HashSet<>(block);
            List<String> sorted = new ArrayList<>(block);
            sorted.sort((a, b) -> Integer.compare(neighbours.getOrDefault(b, List.of()).size(), neighbours.getOrDefault(a, List.of()).size()));
            for (String start : sorted) {
                if (seen.contains(start)) continue;
                ArrayDeque<String> queue = new ArrayDeque<>();
                queue.add(start);
                seen.add(start);
                while (!queue.isEmpty()) {
                    String t = queue.removeFirst();
                    order.add(t);
                    for (String n : neighbours.getOrDefault(t, List.of())) if (members.contains(n) && seen.add(n)) queue.add(n);
                }
            }
            int columns = Math.max(1, (int) Math.ceil(Math.sqrt(order.size())));
            double[] heights = new double[columns];
            java.util.Arrays.fill(heights, originY);
            double[] widths = new double[columns];
            List<Object[]> assignment = new ArrayList<>();
            for (String t : order) {
                int col = 0;
                for (int k = 1; k < columns; k++) if (heights[k] < heights[col]) col = k;
                assignment.add(new Object[] { t, col, heights[col] });
                DbmlSize size = sizes.get(t);
                heights[col] += (size != null ? size.height() : 60) + gap * 0.6;
                widths[col] = Math.max(widths[col], size != null ? size.width() : 170);
            }
            double[] xs = new double[columns];
            double x = originX;
            for (int k = 0; k < columns; k++) {
                xs[k] = x;
                x += widths[k] + gap;
            }
            for (Object[] a : assignment) positions.put((String) a[0], new DbmlPoint(xs[(Integer) a[1]], (Double) a[2]));
            double highest = heights[0];
            for (double h : heights) highest = Math.max(highest, h);
            bottom = Math.max(bottom, highest - gap * 0.6);
            originX = x + gap;
        }
        // Enums, then sticky notes without a frame, in rows below.
        Rows rows = new Rows(bottom + gap, Math.max(1200, originX), gap);
        for (EnumType e : s.enums()) {
            if (!onlyMissing || !positions.containsKey(ENUM_PREFIX + e.name())) positions.put(ENUM_PREFIX + e.name(), rows.put(DbmlGeometry.size(e)));
        }
        for (StickyNote n : s.notes()) {
            if (!onlyMissing || !stickyFrames.containsKey(n.name())) {
                DbmlSize size = DbmlDiagramNote.SIZE;
                stickyFrames.put(n.name(), new DbmlRect(rows.put(size), size));
            }
        }
        if (!onlyMissing) dropShapes();
    }

    /** Places boxes in rows below the tables. */
    private static final class Rows {
        double rowX = MARGIN, rowY, rowHeight = 0;
        final double width, gap;

        Rows(double rowY, double width, double gap) {
            this.rowY = rowY;
            this.width = width;
            this.gap = gap;
        }

        DbmlPoint put(DbmlSize size) {
            if (rowX > MARGIN && rowX + size.width() > width) {
                rowX = MARGIN;
                rowY += rowHeight + gap * 0.6;
                rowHeight = 0;
            }
            DbmlPoint p = new DbmlPoint(rowX, rowY);
            rowX += size.width() + gap * 0.6;
            rowHeight = Math.max(rowHeight, size.height());
            return p;
        }
    }

    /** Whether a table, enum or sticky note has no place yet. */
    public boolean hasMissingPlaces() {
        DbmlSchema s = schema();
        for (Table t : s.tables()) if (!positions.containsKey(t.name())) return true;
        for (EnumType e : s.enums()) if (!positions.containsKey(ENUM_PREFIX + e.name())) return true;
        for (StickyNote n : s.notes()) if (!stickyFrames.containsKey(n.name())) return true;
        return false;
    }

    /** Gives every table, enum and sticky note without a place one (all arranged when none has a place yet). */
    public void placeMissing() {
        if (!hasMissingPlaces()) return;
        boolean anyPlaced = false;
        for (Table t : schema().tables()) if (positions.containsKey(t.name())) anyPlaced = true;
        arrange(anyPlaced);
    }

    // MARK: Moving

    /** Moves tables (and enums) by an offset from where they were ({@code start}). */
    public void move(Set<String> keys, Map<String, DbmlPoint> start, double dx, double dy) {
        for (String key : keys) {
            DbmlPoint p = start.get(key);
            if (p == null) continue;
            positions.put(key, new DbmlPoint(Math.max(0, round(p.x() + dx)), Math.max(0, round(p.y() + dy))));
        }
    }

    /** Everything moved so the diagram starts at the margin (a group's panel above its tables included). */
    public void moveToMargin() {
        DbmlRect extent = new DbmlGeometry(this).extent;
        double dx = MARGIN / 2 - extent.minX(), dy = MARGIN / 2 - extent.minY();
        if (!(Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5)) return;
        positions.replaceAll((k, p) -> new DbmlPoint(p.x() + dx, p.y() + dy));
        stickyFrames.replaceAll((k, f) -> f.offsetBy(dx, dy));
        notes.replaceAll(n -> n.withFrame(n.frame().offsetBy(dx, dy)));
    }

    // MARK: Files (MxDataSQL's format)

    /** A diagram from a .dbml file: the DBML, and from MxDataSQL's section its notes and layout. */
    public static DbmlDiagram fromFileText(String text) {
        DbmlFileLayout.Split split = DbmlFileLayout.split(text == null ? "" : text);
        DbmlDiagram d = new DbmlDiagram(split.body());
        if (split.section() != null) d.applySection(split.section());
        return d;
    }

    /** Takes the notes and layout of a diagram section (MxDataSQL's, or an export's). */
    public void applySection(String section) {
        List<StickyNote> own = DbmlParser.parse(section).notes();
        DbmlFileLayout.Layout layout = DbmlFileLayout.layoutInSection(section);
        if (layout == null) {
            for (StickyNote n : own) {
                if (note(n.name()) == null) {
                    notes.add(new DbmlDiagramNote(n.name(), n.text(), new DbmlRect(new DbmlPoint(MARGIN, MARGIN), DbmlDiagramNote.SIZE), null));
                }
            }
            return;
        }
        applyLayout(layout, own);
    }

    public void applyLayout(DbmlFileLayout.Layout layout, List<StickyNote> ownNotes) {
        for (Map.Entry<String, DbmlFileLayout.Point> e : layout.tables.entrySet()) {
            positions.put(e.getKey(), new DbmlPoint(e.getValue().x(), e.getValue().y()));
        }
        for (Map.Entry<String, DbmlFileLayout.Curve> e : layout.refs.entrySet()) {
            DbmlFileLayout.Curve c = e.getValue();
            DbmlCurveStyle style = DbmlCurveStyle.fromRaw(c.style());
            if (style == DbmlCurveStyle.AUTOMATIC) style = null;
            DbmlRefCurve curve = new DbmlRefCurve(style, point(c.c1()), point(c.c2()), DbmlSide.fromRaw(c.fromSide()),
                    DbmlSide.fromRaw(c.toSide()), c.color());
            putCurve(e.getKey(), curve);
        }
        Set<String> fileNotes = new HashSet<>();
        for (StickyNote n : schema().notes()) fileNotes.add(n.name());
        for (Map.Entry<String, DbmlFileLayout.Frame> e : layout.notes.entrySet()) {
            if (fileNotes.contains(e.getKey())) stickyFrames.put(e.getKey(), rect(e.getValue()));
        }
        if (ownNotes != null) {
            for (StickyNote n : ownNotes) {
                if (note(n.name()) != null) continue;
                DbmlFileLayout.Frame f = layout.notes.get(n.name());
                notes.add(new DbmlDiagramNote(n.name(), n.text(),
                        f != null ? rect(f) : new DbmlRect(new DbmlPoint(MARGIN, MARGIN), DbmlDiagramNote.SIZE), f != null ? f.color() : null));
            }
        }
        if (layout.zoom != null) zoom = layout.zoom;
    }

    private static DbmlPoint point(DbmlFileLayout.Point p) {
        return p == null ? null : new DbmlPoint(p.x(), p.y());
    }

    private static DbmlRect rect(DbmlFileLayout.Frame f) {
        return new DbmlRect(f.x(), f.y(), f.width() != null ? f.width() : DbmlDiagramNote.SIZE.width(),
                f.height() != null ? f.height() : DbmlDiagramNote.SIZE.height());
    }

    private static final Pattern DBML_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** The names the diagram's own notes are written with (their ids, when they can be DBML names). */
    private List<String> noteNames() {
        Set<String> taken = new HashSet<>();
        for (StickyNote n : schema().notes()) taken.add(n.name());
        List<String> names = new ArrayList<>();
        for (int n = 0; n < notes.size(); n++) {
            String id = notes.get(n).id();
            String name = DBML_NAME.matcher(id).matches() ? id : "mx_note_" + (n + 1);
            while (taken.contains(name)) name += "_";
            taken.add(name);
            names.add(name);
        }
        return names;
    }

    private static DbmlFileLayout.Point filePoint(DbmlPoint p) {
        return p == null ? null : new DbmlFileLayout.Point(p.x(), p.y());
    }

    /** The layout as MxDataSQL keeps it: positions, curves, note frames, zoom. */
    public DbmlFileLayout.Layout fileLayout() {
        DbmlFileLayout.Layout layout = new DbmlFileLayout.Layout();
        for (Map.Entry<String, DbmlPoint> e : positions.entrySet()) layout.tables.put(e.getKey(), filePoint(e.getValue()));
        for (Map.Entry<String, DbmlRefCurve> e : curves.entrySet()) {
            DbmlRefCurve c = e.getValue();
            if (c.isPlain()) continue;
            layout.refs.put(e.getKey(), new DbmlFileLayout.Curve((c.style() != null ? c.style() : DbmlCurveStyle.AUTOMATIC).raw(),
                    filePoint(c.c1()), filePoint(c.c2()), c.fromSide() != null ? c.fromSide().raw() : null,
                    c.toSide() != null ? c.toSide().raw() : null, c.color()));
        }
        if (defaultStyle != DbmlCurveStyle.AUTOMATIC) {
            // Lines drawn the diagram's way are written with that style (MxDataSQL keeps a style per line).
            for (Ref r : schema().refs()) {
                DbmlRefCurve own = curves.get(r.id());
                if (own != null && own.style() != null) continue;
                DbmlFileLayout.Curve c = layout.refs.get(r.id());
                layout.refs.put(r.id(), c == null ? new DbmlFileLayout.Curve(defaultStyle.raw(), null, null, null, null, null)
                        : new DbmlFileLayout.Curve(defaultStyle.raw(), c.c1(), c.c2(), c.fromSide(), c.toSide(), c.color()));
            }
        }
        for (Map.Entry<String, DbmlRect> e : stickyFrames.entrySet()) {
            DbmlRect f = e.getValue();
            layout.notes.put(e.getKey(), new DbmlFileLayout.Frame(f.minX(), f.minY(), f.width(), f.height(), null));
        }
        List<String> names = noteNames();
        for (int k = 0; k < notes.size(); k++) {
            DbmlDiagramNote note = notes.get(k);
            DbmlRect f = note.frame();
            layout.notes.put(names.get(k), new DbmlFileLayout.Frame(f.minX(), f.minY(), f.width(), f.height(), note.color()));
        }
        layout.zoom = zoom;
        return layout;
    }

    public String fileText() {
        return fileText(true);
    }

    /**
     * The diagram as a .dbml file: the DBML as written, then MxDataSQL's section — the diagram's notes as DBML
     * sticky notes and the layout as one comment line (other DBML tools read the notes and skip the comment).
     */
    public String fileText(boolean includingLayout) {
        String text = source;
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\r')) end--;
        StringBuilder out = new StringBuilder(text.substring(0, end));
        List<String> names = noteNames();
        List<Integer> written = new ArrayList<>();
        for (int k = 0; k < notes.size(); k++) if (!notes.get(k).text().strip().isEmpty() || includingLayout) written.add(k);
        if (!includingLayout && written.isEmpty()) return out + "\n";
        out.append("\n\n").append(DbmlFileLayout.SECTION_MARKER).append('\n');
        for (int k : written) {
            out.append("Note ").append(names.get(k)).append(" {\n  '''\n")
                    .append(notes.get(k).text().replace("'''", "\\'\\'\\'")).append("\n  '''\n}\n");
        }
        if (includingLayout) out.append(DbmlFileLayout.line(fileLayout())).append('\n');
        return out.toString();
    }

    // MARK: One schema of it

    /**
     * One schema's part, to copy or export on its own: its tables and enums (their DBML as written, refs to tables
     * elsewhere taken off), the relationships among them, its table groups (with only its tables), where they sit
     * (moved to the top left) with their curves, and the notes over them.
     *
     * @param title the diagram's name ("// The x schema of title"), or null
     */
    public DbmlDiagram subset(String name, String title) {
        DbmlSchema s = schema();
        String[] lines = source.split("\n", -1);
        List<Table> tables = new ArrayList<>();
        for (Table t : s.tables()) if (t.schema().equals(name)) tables.add(t);
        Set<String> ids = new LinkedHashSet<>();
        for (Table t : tables) ids.add(t.name());
        Set<String> shortTypes = new HashSet<>();
        for (Table t : tables) for (DbmlSchema.Column c : t.columns()) shortTypes.add(c.type().toLowerCase());
        List<EnumType> enums = new ArrayList<>();
        for (EnumType e : s.enums()) {
            String[] parts = java.util.Arrays.stream(e.name().split("\\.")).filter(p -> !p.isEmpty()).toArray(String[]::new);
            String last = parts.length > 0 ? parts[parts.length - 1] : e.name();
            if (e.schema().equals(name) || shortTypes.contains(e.name().toLowerCase()) || shortTypes.contains(last.toLowerCase())) enums.add(e);
        }
        Map<String, String> aliases = new HashMap<>();
        for (Table t : s.tables()) if (t.alias() != null) aliases.put(t.alias(), t.name());
        java.util.function.UnaryOperator<String> resolved = table -> {
            String t = aliases.getOrDefault(table, table);
            return t.startsWith("public.") ? t.substring(7) : t;
        };

        StringBuilder out = new StringBuilder("// The " + name + " schema" + (title != null ? " of " + title : "") + "\n\n");
        if (s.projectLines() != null) out.append(text(lines, s.projectLines().lower(), s.projectLines().upper())).append("\n\n");
        for (Table t : tables) {
            List<String> body = new ArrayList<>(List.of(text(lines, t.line(), Math.max(t.line(), t.endLine())).split("\n", -1)));
            // Inline refs to tables not in this schema come off (their tables aren't here).
            for (Ref r : s.refs()) {
                if (!(r.inline() && r.from().table().equals(t.name()) && !ids.contains(r.to().table()))) continue;
                int k = r.line() - t.line();
                if (k < 0 || k >= body.size()) continue;
                body.set(k, removingInlineRefs(body.get(k), tbl -> !ids.contains(resolved.apply(tbl))));
            }
            out.append(String.join("\n", body)).append("\n\n");
        }
        for (EnumType e : enums) out.append(text(lines, e.line(), Math.max(e.line(), e.endLine()))).append("\n\n");
        List<Ref> refs = new ArrayList<>();
        for (Ref r : s.refs()) if (!r.inline() && ids.contains(r.from().table()) && ids.contains(r.to().table())) refs.add(r);
        Map<String, Integer> names = new HashMap<>();
        for (Ref r : refs) if (r.name() != null) names.merge(r.name(), 1, Integer::sum);
        for (Ref r : refs) {
            String named = r.name() != null && names.get(r.name()) == 1 ? " " + r.name() : "";
            out.append("Ref").append(named).append(": ").append(r.from().text()).append(' ').append(r.op()).append(' ')
                    .append(r.to().text()).append(r.settings() != null ? " " + r.settings() : "").append('\n');
        }
        if (!refs.isEmpty()) out.append('\n');
        for (Group g : s.groups()) {
            List<String> members = g.tables().stream().filter(ids::contains).toList();
            if (members.isEmpty()) continue;
            out.append("TableGroup ").append(g.name()).append(g.color() != null ? " [color: " + g.color() + "]" : "").append(" {\n");
            for (String m : members) out.append("  ").append(m).append('\n');
            out.append("}\n\n");
        }
        DbmlDiagram part = new DbmlDiagram(out.toString().strip() + "\n");
        part.defaultStyle = defaultStyle;
        part.hidesTypes = hidesTypes;
        DbmlGeometry geometry = new DbmlGeometry(this);
        Set<String> keys = new LinkedHashSet<>(ids);
        for (EnumType e : enums) keys.add(ENUM_PREFIX + e.name());
        for (String k : keys) {
            DbmlPoint p = positions.get(k);
            if (p != null) part.positions.put(k, p);
        }
        Set<String> refIDs = new HashSet<>();
        for (Ref r : DbmlParser.parse(part.source).refs()) refIDs.add(r.id());
        for (Map.Entry<String, DbmlRefCurve> e : curves.entrySet()) if (refIDs.contains(e.getKey())) part.curves.put(e.getKey(), e.getValue());
        DbmlRect area = null;
        for (DbmlGeometry.Box b : geometry.boxes) if (keys.contains(b.key())) area = area == null ? b.rect() : area.union(b.rect());
        if (area != null) {
            DbmlRect around = area.insetBy(-40, -60);
            for (DbmlDiagramNote n : notes) if (n.frame().intersects(around)) part.notes.add(n);
        }
        part.placeMissing();
        part.moveToMargin();
        return part;
    }

    private static String text(String[] lines, int lower, int upper) {
        int lo = Math.max(1, lower), hi = Math.min(lines.length, upper);
        if (lo > hi) return "";
        return String.join("\n", java.util.Arrays.asList(lines).subList(lo - 1, hi));
    }

    /** A column's line with its {@code ref: …} settings to the tables {@code outside} says are elsewhere taken off. */
    public static String removingInlineRefs(String line, Predicate<String> outside) {
        int open = line.indexOf('['), close = line.lastIndexOf(']');
        if (open < 0 || close < 0 || open >= close) return line;
        String inside = line.substring(open + 1, close);
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char ch : inside.toCharArray()) {
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            String p = trimSpaces(part);
            if (!p.toLowerCase().startsWith("ref")) {
                kept.add(part);
                continue;
            }
            int colon = p.indexOf(':');
            String target = colon < 0 ? "" : trim(p.substring(colon + 1), " <>-");
            String table;
            if (target.contains("(")) {
                int k = target.indexOf(".(");
                table = k >= 0 ? target.substring(0, k) : target;
            } else {
                int k = target.lastIndexOf('.');
                table = k >= 0 ? target.substring(0, k) : target;
            }
            if (!outside.test(table.replace("\"", ""))) kept.add(part);
        }
        String head = line.substring(0, open), tail = line.substring(close + 1);
        if (kept.isEmpty()) return head.replaceAll("\\s+$", "") + tail;
        List<String> trimmed = kept.stream().map(DbmlDiagram::trimSpaces).toList();
        return head + "[" + String.join(", ", trimmed) + "]" + tail;
    }

    private static String trimSpaces(String s) {
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    private static String trim(String s, String chars) {
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }
}
