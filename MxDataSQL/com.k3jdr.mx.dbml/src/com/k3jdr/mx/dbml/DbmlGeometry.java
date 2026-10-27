package com.k3jdr.mx.dbml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.graphics.RGB;

import com.k3jdr.mx.dbml.DbmlSchema.EnumType;
import com.k3jdr.mx.dbml.DbmlSchema.EnumValue;
import com.k3jdr.mx.dbml.DbmlSchema.Ref;
import com.k3jdr.mx.dbml.DbmlSchema.StickyNote;
import com.k3jdr.mx.dbml.DbmlSchema.Table;

/**
 * The diagram laid out (DBMLDiagram.swift's DBMLGeometry): each box (table or enum), each relationship's line, each
 * schema panel (table group) and note, in diagram points.
 */
public final class DbmlGeometry {
    public enum BoxKind { TABLE, ENUMERATION }

    public record Row(String name, String type, boolean pk, boolean fk, boolean notNull, boolean unique,
                      String defaultValue, String note) {}

    /**
     * A table or enum. {@code title} is "name" in public, else "schema.name" (as MxDataSQL heads its tables).
     */
    public record Box(String key, BoxKind kind, String title, String schema, List<Row> rows, String note, RGB color,
                      DbmlRect rect, int line, int indexCount) {}

    /**
     * A relationship's line: one control point for quadratic, two for automatic and cubic, none for straight.
     */
    public record Line(Ref ref, DbmlPoint start, DbmlPoint end, DbmlSide startSide, DbmlSide endSide,
                       DbmlCurveStyle style, List<DbmlPoint> controls, RGB color) {

        /** The cubic Bézier it is drawn as: start, two control points, end (a straight line repeats its ends). */
        public DbmlPoint[] bezier() {
            return switch (style) {
                case STRAIGHT -> new DbmlPoint[] { start, start, end, end };
                case QUADRATIC -> {
                    DbmlPoint c = controls.get(0);
                    yield new DbmlPoint[] { start,
                            new DbmlPoint(start.x() + (c.x() - start.x()) * 2 / 3, start.y() + (c.y() - start.y()) * 2 / 3),
                            new DbmlPoint(end.x() + (c.x() - end.x()) * 2 / 3, end.y() + (c.y() - end.y()) * 2 / 3), end };
                }
                default -> new DbmlPoint[] { start, controls.get(0), controls.get(1), end };
            };
        }

        /** The line as a polyline (to find how far a point is from it). */
        public List<DbmlPoint> flattened() {
            List<DbmlPoint> out = new ArrayList<>();
            if (style == DbmlCurveStyle.STRAIGHT) {
                out.add(start);
                out.add(end);
                return out;
            }
            DbmlPoint[] b = bezier();
            int steps = 32;
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps, u = 1 - t;
                double x = u * u * u * b[0].x() + 3 * u * u * t * b[1].x() + 3 * u * t * t * b[2].x() + t * t * t * b[3].x();
                double y = u * u * u * b[0].y() + 3 * u * u * t * b[1].y() + 3 * u * t * t * b[2].y() + t * t * t * b[3].y();
                out.add(new DbmlPoint(x, y));
            }
            return out;
        }

        /** The direction the line leaves its start in (a unit vector pointing away from the table). */
        public DbmlPoint startTangent() {
            DbmlPoint to = style == DbmlCurveStyle.STRAIGHT ? end : controls.get(0);
            return unit(to.x() - start.x(), to.y() - start.y(), startSide == DbmlSide.LEFT ? -1 : 1);
        }

        /** The direction it comes into its end from. */
        public DbmlPoint endTangent() {
            DbmlPoint from = style == DbmlCurveStyle.STRAIGHT ? start : style == DbmlCurveStyle.QUADRATIC ? controls.get(0) : controls.get(1);
            return unit(from.x() - end.x(), from.y() - end.y(), endSide == DbmlSide.LEFT ? -1 : 1);
        }

        private static DbmlPoint unit(double dx, double dy, double fallback) {
            double len = Math.sqrt(dx * dx + dy * dy);
            return len < 0.001 ? new DbmlPoint(fallback, 0) : new DbmlPoint(dx / len, dy / len);
        }
    }

    /** A schema panel (a table group); its label loses MxDataSQL's "_schema". */
    public record Group(String name, String label, DbmlRect rect, RGB color, List<String> tables) {}

    /**
     * A note: the DBML's sticky note (by name), or the diagram's own ({@code own}, editable; by id).
     */
    public record Sticky(String id, String text, DbmlRect rect, boolean own, RGB color) {}

    public static final double HEADER_HEIGHT = 28;
    public static final double ROW_HEIGHT = 19;

    // macOS system colours (light appearance).
    public static final RGB BLUE = new RGB(0, 122, 255);
    public static final RGB TEAL = new RGB(48, 176, 199);
    public static final RGB INDIGO = new RGB(88, 86, 214);
    public static final RGB PURPLE = new RGB(175, 82, 222);
    public static final RGB ORANGE = new RGB(255, 149, 0);
    public static final RGB GREEN = new RGB(40, 205, 65);
    public static final RGB PINK = new RGB(255, 45, 85);
    public static final RGB BROWN = new RGB(162, 132, 94);
    public static final RGB GRAY = new RGB(142, 142, 147);
    public static final RGB YELLOW = new RGB(255, 204, 0);

    private static final RGB[] SCHEMA_PALETTE = { BLUE, TEAL, INDIGO, PURPLE, ORANGE, GREEN, PINK, BROWN };

    public final DbmlSchema schema;
    public final List<Box> boxes = new ArrayList<>();
    public final List<Line> lines = new ArrayList<>();
    public final List<Group> groups = new ArrayList<>();
    public final List<Sticky> stickies = new ArrayList<>();
    /** Everything (boxes, panels, notes, control points), in diagram points. */
    public final DbmlRect extent;

    /** A table's box size, from its text (as MxDataSQL sizes them: the boxes don't depend on fonts). */
    public static DbmlSize size(Table table, boolean hidesTypes) {
        List<String> names = table.columns().stream().map(DbmlSchema.Column::name).toList();
        List<String> types = hidesTypes ? List.of() : table.columns().stream().map(DbmlSchema.Column::type).toList();
        return size(table.name(), names, types);
    }

    public static DbmlSize size(EnumType e) {
        return size("enum " + e.name(), e.values().stream().map(EnumValue::name).toList(), List.of());
    }

    public static DbmlSize size(String title, List<String> names, List<String> types) {
        int nameChars = 0;
        for (String n : names) nameChars = Math.max(nameChars, count(n));
        nameChars = Math.max(nameChars, 4);
        int typeChars = 0;
        for (String t : types) typeChars = Math.max(typeChars, count(t));
        double body = 34 + (double) nameChars * 7 + 14 + (double) typeChars * 6.4;
        double width = Math.min(420, Math.max(Math.max(170, body), 40 + (double) count(title) * 7.4));
        return new DbmlSize(width, HEADER_HEIGHT + (double) Math.max(names.size(), 1) * ROW_HEIGHT + 6);
    }

    private static int count(String s) {
        return s.codePointCount(0, s.length());
    }

    /** A header colour per schema, so a schema's tables share one (public blue). */
    public static RGB colorForSchema(String schema) {
        if (schema.equals("public")) return SCHEMA_PALETTE[0];
        long h = 7;
        for (int cp : schema.codePoints().toArray()) h = h * 31 + cp;
        long abs = h == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(h);
        return SCHEMA_PALETTE[(int) (abs % (SCHEMA_PALETTE.length - 1)) + 1];
    }

    public DbmlGeometry(DbmlDiagram d) {
        this(d, true);
    }

    public DbmlGeometry(DbmlDiagram diagram, boolean placingMissing) {
        DbmlDiagram d = diagram;
        if (placingMissing && d.hasMissingPlaces()) {
            d = d.copy();
            d.placeMissing();
        }
        schema = d.schema();
        for (Table t : schema.tables()) {
            Set<String> fks = schema.foreignKeys(t.name());
            List<Row> rows = new ArrayList<>();
            for (DbmlSchema.Column c : t.columns()) {
                rows.add(new Row(c.name(), d.hidesTypes ? "" : c.type(), c.pk(), fks.contains(c.name()), c.notNull(), c.unique(),
                        c.defaultValue(), c.note()));
            }
            RGB color = DbmlColor.hex(t.headerColor());
            if (color == null) color = colorForSchema(t.schema());
            DbmlPoint p = d.positions.getOrDefault(t.name(), DbmlPoint.ZERO);
            boxes.add(new Box(t.name(), BoxKind.TABLE, t.name(), t.schema(), List.copyOf(rows), t.note(), color,
                    new DbmlRect(p, size(t, d.hidesTypes)), t.line(), t.indexCount()));
        }
        for (EnumType e : schema.enums()) {
            String key = DbmlDiagram.ENUM_PREFIX + e.name();
            List<Row> rows = new ArrayList<>();
            for (EnumValue v : e.values()) rows.add(new Row(v.name(), "", false, false, false, false, null, v.note()));
            DbmlPoint p = d.positions.getOrDefault(key, DbmlPoint.ZERO);
            boxes.add(new Box(key, BoxKind.ENUMERATION, "enum " + e.name(), e.schema(), List.copyOf(rows), null, GRAY,
                    new DbmlRect(p, size(e)), e.line(), 0));
        }
        Map<String, DbmlRect> frames = new HashMap<>();
        for (Box b : boxes) frames.putIfAbsent(b.key(), b.rect());
        for (DbmlSchema.Group g : schema.groups()) {
            DbmlRect box = null;
            for (String t : g.tables()) {
                DbmlRect r = frames.get(t);
                if (r != null) box = box == null ? r : box.union(r);
            }
            if (box == null) continue;
            groups.add(new Group(g.name(), g.name().replace("_schema", ""), box.insetBy(-24, -36).offsetBy(0, -8),
                    DbmlColor.hex(g.color()), g.tables()));
        }
        for (Ref ref : schema.refs()) {
            DbmlRect a = frames.get(ref.from().table()), b = frames.get(ref.to().table());
            if (a == null || b == null) continue;
            DbmlRefCurve c = d.curve(ref);
            DbmlSide s0, s1;
            if (ref.from().table().equals(ref.to().table())) {
                s0 = DbmlSide.RIGHT;
                s1 = DbmlSide.RIGHT;
            } else if (a.maxX() + 30 < b.minX()) {
                s0 = DbmlSide.RIGHT;
                s1 = DbmlSide.LEFT;
            } else if (b.maxX() + 30 < a.minX()) {
                s0 = DbmlSide.LEFT;
                s1 = DbmlSide.RIGHT;
            } else {
                // One above the other: loop out on the side with more room.
                s0 = a.midX() <= b.midX() ? DbmlSide.LEFT : DbmlSide.RIGHT;
                s1 = s0;
            }
            if (c.fromSide() != null) s0 = c.fromSide();
            if (c.toSide() != null) s1 = c.toSide();
            DbmlPoint start = new DbmlPoint(s0 == DbmlSide.RIGHT ? a.maxX() : a.minX(),
                    columnY(a, schema.table(ref.from().table()), first(ref.from().columns())));
            DbmlPoint end = new DbmlPoint(s1 == DbmlSide.RIGHT ? b.maxX() : b.minX(),
                    columnY(b, schema.table(ref.to().table()), first(ref.to().columns())));
            DbmlCurveStyle style = d.style(ref);
            List<DbmlPoint> auto = automaticControls(start, end, s0, s1);
            List<DbmlPoint> controls = switch (style) {
                case STRAIGHT -> List.of();
                case AUTOMATIC -> auto;
                case QUADRATIC -> {
                    DbmlPoint mid = new DbmlPoint((start.x() + end.x()) / 2, (start.y() + end.y()) / 2);
                    DbmlPoint o = c.c1() != null ? c.c1() : new DbmlPoint(0, -60);
                    yield List.of(new DbmlPoint(mid.x() + o.x(), mid.y() + o.y()));
                }
                case CUBIC -> List.of(c.c1() != null ? new DbmlPoint(start.x() + c.c1().x(), start.y() + c.c1().y()) : auto.get(0),
                        c.c2() != null ? new DbmlPoint(end.x() + c.c2().x(), end.y() + c.c2().y()) : auto.get(1));
            };
            lines.add(new Line(ref, start, end, s0, s1, style, controls, DbmlColor.hex(c.color())));
        }
        for (StickyNote n : schema.notes()) {
            DbmlRect f = d.stickyFrames.get(n.name());
            stickies.add(new Sticky(n.name(), n.text(), f != null ? f : new DbmlRect(DbmlPoint.ZERO, DbmlDiagramNote.SIZE), false, null));
        }
        for (DbmlDiagramNote n : d.notes) stickies.add(new Sticky(n.id(), n.text(), n.frame(), true, DbmlColor.hex(n.color())));
        DbmlRect all = null;
        for (Box b : boxes) all = b.rect().union(all);
        for (Group g : groups) all = g.rect().union(all);
        for (Sticky s : stickies) all = s.rect().union(all);
        for (Line l : lines) for (DbmlPoint c : l.controls()) all = new DbmlRect(c.x() - 8, c.y() - 8, 16, 16).union(all);
        extent = all == null ? new DbmlRect(0, 0, 200, 120) : all;
    }

    private static String first(List<String> l) {
        return l.isEmpty() ? null : l.get(0);
    }

    public static double columnY(DbmlRect frame, Table table, String column) {
        int index = column == null || table == null ? -1 : table.columnIndex(column);
        if (index < 0) return frame.minY() + HEADER_HEIGHT / 2;
        return frame.minY() + HEADER_HEIGHT + index * ROW_HEIGHT + ROW_HEIGHT / 2;
    }

    public static List<DbmlPoint> automaticControls(DbmlPoint s, DbmlPoint e, DbmlSide ss, DbmlSide es) {
        double reach = Math.max(50, Math.min(220, Math.abs(e.x() - s.x()) / 2 + (ss == es ? 60 : 0)));
        return List.of(new DbmlPoint(s.x() + (ss == DbmlSide.RIGHT ? reach : -reach), s.y()),
                new DbmlPoint(e.x() + (es == DbmlSide.RIGHT ? reach : -reach), e.y()));
    }

    public Box box(String key) {
        for (Box b : boxes) if (b.key().equals(key)) return b;
        return null;
    }

    public Line line(String id) {
        for (Line l : lines) if (l.ref().id().equals(id)) return l;
        return null;
    }

    public Sticky sticky(String id) {
        for (Sticky s : stickies) if (s.id().equals(id)) return s;
        return null;
    }

    /** The row of a box at a point (-1 on its header or below its rows). */
    public static int row(Box box, DbmlPoint p) {
        double y = p.y() - box.rect().minY() - HEADER_HEIGHT;
        if (y < 0) return -1;
        int r = (int) (y / ROW_HEIGHT);
        return r < box.rows().size() ? r : -1;
    }

    /** How far a point is from a polyline (for clicking a line). */
    public static double distance(DbmlPoint p, List<DbmlPoint> polyline) {
        double best = Double.MAX_VALUE;
        for (int k = 1; k < polyline.size(); k++) {
            DbmlPoint a = polyline.get(k - 1), q = polyline.get(k);
            double abx = q.x() - a.x(), aby = q.y() - a.y(), apx = p.x() - a.x(), apy = p.y() - a.y();
            double t = Math.max(0, Math.min(1, (apx * abx + apy * aby) / Math.max(abx * abx + aby * aby, 0.0001)));
            best = Math.min(best, Math.hypot(p.x() - (a.x() + abx * t), p.y() - (a.y() + aby * t)));
        }
        return best;
    }
}
