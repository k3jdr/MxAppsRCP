package com.k3jdr.mx.dbml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Display;

/**
 * Draws a diagram as both apps show it (DBMLDiagram.swift's DBMLRenderer), in diagram points, with a GC whose
 * transform maps diagram points to pixels: schema panels, relationship lines with a crow's foot at a "many" end and a
 * bar at a "one" end, table and enum boxes with key and link marks, notes, and the selected line's control points.
 */
public final class DbmlRenderer {
    private DbmlRenderer() {}

    public static final RGB WHITE = new RGB(255, 255, 255);
    public static final RGB BLACK = new RGB(0, 0, 0);
    public static final RGB ACCENT = DbmlGeometry.BLUE;
    /** labelColor (black at 85%) on white. */
    public static final RGB LABEL = new RGB(38, 38, 38);
    /** secondaryLabelColor (black at 50%) on white. */
    public static final RGB SECONDARY = new RGB(128, 128, 128);

    /** Around the diagram in its pictures. */
    public static final double PICTURE_MARGIN = 24;
    /** The most pixels a picture of a diagram gets (a big diagram is drawn at a lower scale to stay under it). */
    public static final double MAX_PIXELS = 12_000_000;

    // MARK: Fonts (sizes as the macOS app's points, which are pixels at 100%)

    private record Fonts(Font title, Font name, Font key, Font type, Font note, Font panel) {}

    private static final Map<Device, Fonts> FONTS = new HashMap<>();

    private static Fonts fonts(Device device) {
        return FONTS.computeIfAbsent(device, dev -> {
            Fonts f = new Fonts(font(dev, "Segoe UI Semibold", 12, SWT.NORMAL), font(dev, "Segoe UI", 11, SWT.NORMAL),
                    font(dev, "Segoe UI Semibold", 11, SWT.NORMAL), font(dev, "Consolas", 10, SWT.NORMAL),
                    font(dev, "Segoe UI", 12, SWT.NORMAL), font(dev, "Segoe UI Semibold", 13, SWT.NORMAL));
            if (dev instanceof Display display) {
                display.disposeExec(() -> {
                    Fonts gone = FONTS.remove(display);
                    if (gone != null) for (Font x : List.of(gone.title, gone.name, gone.key, gone.type, gone.note, gone.panel)) x.dispose();
                });
            }
            return f;
        });
    }

    /** A font whose em is {@code px} diagram points (SWT heights are typographic points: 3/4 of a pixel at 96 dpi). */
    private static Font font(Device dev, String name, double px, int style) {
        return new Font(dev, new FontData(name, (int) Math.round(px * 0.75), style));
    }

    /** The font notes are typed in, {@code zoom} times its size (for the in-place editor). */
    public static FontData noteFontData(double zoom) {
        return new FontData("Segoe UI", Math.max(5, (int) Math.round(12 * 0.75 * zoom)), SWT.NORMAL);
    }

    private static Color color(RGB c) {
        return new Color(c);
    }

    // MARK: Drawing

    /** Everything, in diagram points, into a GC (its transform maps diagram points to the device). */
    public static void draw(GC gc, DbmlGeometry g, DbmlSelection selection, String editingNote) {
        if (selection == null) selection = DbmlSelection.NONE;
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        gc.setInterpolation(SWT.HIGH);
        for (DbmlGeometry.Group group : g.groups) drawPanel(gc, group);
        for (DbmlGeometry.Line line : g.lines) {
            if (line.ref().id().equals(selection.ref())) continue;
            boolean touching = selection.tables().contains(line.ref().from().table()) || selection.tables().contains(line.ref().to().table());
            drawLine(gc, line, false, touching);
        }
        for (DbmlGeometry.Box box : g.boxes) drawBox(gc, box, selection.tables().contains(box.key()));
        if (selection.ref() != null) {
            DbmlGeometry.Line line = g.line(selection.ref());
            if (line != null) {
                drawLine(gc, line, true, false);
                drawHandles(gc, line);
            }
        }
        for (DbmlGeometry.Sticky s : g.stickies) drawSticky(gc, s, s.id().equals(selection.note()), s.id().equals(editingNote));
        gc.setAlpha(255);
    }

    private static void stroke(GC gc, double width) {
        gc.setLineAttributes(new LineAttributes((float) width, SWT.CAP_ROUND, SWT.JOIN_ROUND));
    }

    private static void dashed(GC gc, double width, float... dash) {
        gc.setLineAttributes(new LineAttributes((float) width, SWT.CAP_FLAT, SWT.JOIN_MITER, SWT.LINE_CUSTOM, dash, 0, 10));
    }

    /** A rounded rectangle (or one with only its top corners rounded). */
    static Path rounded(Device dev, DbmlRect r, double radius, boolean bottomRounded) {
        Path p = new Path(dev);
        float x = (float) r.x(), y = (float) r.y(), w = (float) r.width(), h = (float) r.height();
        float rr = (float) Math.max(0, Math.min(radius, Math.min(w / 2, h / 2))), d = 2 * rr;
        if (rr <= 0) {
            p.addRectangle(x, y, w, h);
            return p;
        }
        p.moveTo(x, y + rr);
        p.addArc(x, y, d, d, 180, -90);
        p.lineTo(x + w - rr, y);
        p.addArc(x + w - d, y, d, d, 90, -90);
        if (bottomRounded) {
            p.lineTo(x + w, y + h - rr);
            p.addArc(x + w - d, y + h - d, d, d, 0, -90);
            p.lineTo(x + rr, y + h);
            p.addArc(x, y + h - d, d, d, 270, -90);
        } else {
            p.lineTo(x + w, y + h);
            p.lineTo(x, y + h);
        }
        p.close();
        return p;
    }

    private static void fill(GC gc, Path p, RGB c, int alpha) {
        gc.setBackground(color(c));
        gc.setAlpha(alpha);
        gc.fillPath(p);
        gc.setAlpha(255);
    }

    private static void draw(GC gc, Path p, RGB c, int alpha) {
        gc.setForeground(color(c));
        gc.setAlpha(alpha);
        gc.drawPath(p);
        gc.setAlpha(255);
    }

    /** A soft shadow under a rounded shape (NSShadow, blur 3). */
    private static void shadow(GC gc, DbmlRect r, double radius, double dy, int alpha) {
        Device dev = gc.getDevice();
        for (int k = 3; k >= 1; k--) {
            Path p = rounded(dev, r.insetBy(-k * 0.6, -k * 0.6).offsetBy(0, dy), radius + k * 0.6, true);
            fill(gc, p, BLACK, alpha / (k + 1));
            p.dispose();
        }
    }

    public static void drawPanel(GC gc, DbmlGeometry.Group group) {
        Device dev = gc.getDevice();
        RGB tint = group.color() != null ? group.color() : BLACK;
        // secondaryLabelColor is black at 50%: its 6% and 25% are black at 3% and 12.5%.
        int fillAlpha = group.color() == null ? 8 : 26;
        int strokeAlpha = group.color() == null ? 32 : 128;
        Path path = rounded(dev, group.rect(), 14, true);
        fill(gc, path, tint, fillAlpha);
        path.dispose();
        Path border = rounded(dev, group.rect().insetBy(0.5, 0.5), 14, true);
        dashed(gc, 1, 5, 4);
        draw(gc, border, tint, strokeAlpha);
        border.dispose();
        gc.setFont(fonts(dev).panel());
        gc.setForeground(color(group.color() != null ? group.color() : SECONDARY));
        gc.drawText(group.label(), (int) Math.round(group.rect().minX() + 12), (int) Math.round(group.rect().minY() + 6), SWT.DRAW_TRANSPARENT);
    }

    public static void drawBox(GC gc, DbmlGeometry.Box box, boolean selected) {
        Device dev = gc.getDevice();
        Fonts f = fonts(dev);
        DbmlRect r = box.rect();
        shadow(gc, r, 7, 1, 40);
        Path shape = rounded(dev, r, 7, true);
        fill(gc, shape, WHITE, 255);
        shape.dispose();
        Path header = rounded(dev, new DbmlRect(r.minX(), r.minY(), r.width(), DbmlGeometry.HEADER_HEIGHT), 7, false);
        fill(gc, header, box.color(), 255);
        header.dispose();
        // Header: the table's name (schema.name outside public), and an index mark.
        double titleWidth = r.width() - 16;
        if (box.indexCount() > 0) {
            drawIndexMark(gc, r.maxX() - 8 - 11, r.minY() + DbmlGeometry.HEADER_HEIGHT / 2);
            titleWidth -= 11 + 4;
        }
        gc.setFont(f.title());
        gc.setForeground(color(WHITE));
        int th = gc.getFontMetrics().getHeight();
        gc.drawText(fit(gc, box.title(), titleWidth), (int) Math.round(r.minX() + 8),
                (int) Math.round(r.minY() + (DbmlGeometry.HEADER_HEIGHT - th) / 2), SWT.DRAW_TRANSPARENT);
        // Rows: a key on the primary key, a link on foreign keys; the name, and the type at the right.
        for (int k = 0; k < box.rows().size(); k++) {
            DbmlGeometry.Row row = box.rows().get(k);
            double y = r.minY() + DbmlGeometry.HEADER_HEIGHT + k * DbmlGeometry.ROW_HEIGHT;
            double cx = r.minX() + 8 + 6, cy = y + DbmlGeometry.ROW_HEIGHT / 2;
            if (row.pk()) drawKey(gc, cx, cy);
            else if (row.fk()) drawLink(gc, cx, cy);
            double right = r.maxX() - 8;
            if (!row.type().isEmpty()) {
                gc.setFont(f.type());
                gc.setForeground(color(SECONDARY));
                double w = Math.min(gc.textExtent(row.type()).x, r.width() * 0.55);
                String type = fit(gc, row.type(), w);
                int h = gc.getFontMetrics().getHeight();
                gc.drawText(type, (int) Math.round(right - gc.textExtent(type).x), (int) Math.round(y + (DbmlGeometry.ROW_HEIGHT - h) / 2),
                        SWT.DRAW_TRANSPARENT);
                right -= w + 8;
            }
            double x = r.minX() + 8 + 12 + 4;
            gc.setFont(row.pk() ? f.key() : f.name());
            gc.setForeground(color(LABEL));
            int h = gc.getFontMetrics().getHeight();
            gc.drawText(fit(gc, row.name(), Math.max(10, right - x)), (int) Math.round(x), (int) Math.round(y + (DbmlGeometry.ROW_HEIGHT - h) / 2),
                    SWT.DRAW_TRANSPARENT);
        }
        double w = selected ? 2.5 : 1;
        Path border = rounded(dev, r.insetBy(w / 2, w / 2), 7 - w / 2, true);
        stroke(gc, w);
        if (selected) draw(gc, border, ACCENT, 255);
        else draw(gc, border, BLACK, 58);
        border.dispose();
    }

    /** "key.fill": a round head with a hole and a shaft with two teeth. */
    private static void drawKey(GC gc, double cx, double cy) {
        Device dev = gc.getDevice();
        Path p = new Path(dev);
        p.addArc((float) (cx - 5.5), (float) (cy - 3.5), 7, 7, 0, 360);
        p.addRectangle((float) (cx), (float) (cy - 0.9), 5.5f, 1.8f);
        p.addRectangle((float) (cx + 2.4), (float) (cy + 0.5), 1.4f, 2.2f);
        p.addRectangle((float) (cx + 4.1), (float) (cy + 0.5), 1.4f, 1.8f);
        fill(gc, p, DbmlGeometry.YELLOW, 255);
        p.dispose();
        Path hole = new Path(dev);
        hole.addArc((float) (cx - 3.6), (float) (cy - 1.1), 2.2f, 2.2f, 0, 360);
        fill(gc, hole, WHITE, 255);
        hole.dispose();
    }

    /** "link": two chain links, slanted. */
    private static void drawLink(GC gc, double cx, double cy) {
        Device dev = gc.getDevice();
        Transform saved = new Transform(dev);
        gc.getTransform(saved);
        Transform t = new Transform(dev);
        gc.getTransform(t);
        t.translate((float) cx, (float) cy);
        t.rotate(-45);
        gc.setTransform(t);
        stroke(gc, 1.3);
        for (float ox : new float[] { -2.6f, 2.6f }) {
            Path p = rounded(dev, new DbmlRect(ox - 3.3, -1.9, 6.6, 3.8), 1.9, true);
            draw(gc, p, SECONDARY, 255);
            p.dispose();
        }
        gc.setTransform(saved);
        t.dispose();
        saved.dispose();
    }

    /** "list.number": three short lines with marks, for a table with indexes. */
    private static void drawIndexMark(GC gc, double x, double cy) {
        Device dev = gc.getDevice();
        Path p = new Path(dev);
        for (int k = -1; k <= 1; k++) {
            float y = (float) (cy + k * 3);
            p.addRectangle((float) x, y - 0.6f, 1.6f, 1.2f);
            p.addRectangle((float) x + 3.2f, y - 0.6f, 7.8f, 1.2f);
        }
        fill(gc, p, WHITE, 204);
        p.dispose();
    }

    public static void drawLine(GC gc, DbmlGeometry.Line line, boolean selected, boolean highlighted) {
        Device dev = gc.getDevice();
        RGB c = selected ? ACCENT : highlighted ? DbmlGeometry.ORANGE : line.color() != null ? line.color() : BLACK;
        int alpha = selected || highlighted || line.color() != null ? 255 : 128;
        Path path = new Path(dev);
        DbmlPoint[] b = line.bezier();
        path.moveTo((float) b[0].x(), (float) b[0].y());
        if (line.style() == DbmlCurveStyle.STRAIGHT) path.lineTo((float) b[3].x(), (float) b[3].y());
        else path.cubicTo((float) b[1].x(), (float) b[1].y(), (float) b[2].x(), (float) b[2].y(), (float) b[3].x(), (float) b[3].y());
        stroke(gc, selected || highlighted ? 2.4 : 1.3);
        draw(gc, path, c, alpha);
        path.dispose();
        // Crow's foot at a "many" end, a bar at a "one" end.
        Path ends = new Path(dev);
        mark(ends, line.start(), line.startTangent(), line.ref().fromMany());
        mark(ends, line.end(), line.endTangent(), line.ref().toMany());
        stroke(gc, selected || highlighted ? 2 : 1.2);
        draw(gc, ends, c, alpha);
        ends.dispose();
    }

    private static void mark(Path p, DbmlPoint point, DbmlPoint d, boolean many) {
        double nx = -d.y(), ny = d.x();
        if (many) {
            double tx = point.x() + d.x() * 13, ty = point.y() + d.y() * 13;
            for (double s : new double[] { -7, 0, 7 }) {
                p.moveTo((float) tx, (float) ty);
                p.lineTo((float) (point.x() + nx * s), (float) (point.y() + ny * s));
            }
        } else {
            double cx = point.x() + d.x() * 9, cy = point.y() + d.y() * 9;
            p.moveTo((float) (cx + nx * 6), (float) (cy + ny * 6));
            p.lineTo((float) (cx - nx * 6), (float) (cy - ny * 6));
        }
    }

    /** The selected quadratic / cubic line's control points, on guides to the ends they pull from. */
    public static void drawHandles(GC gc, DbmlGeometry.Line line) {
        if (!line.style().isBezier()) return;
        Device dev = gc.getDevice();
        Path guides = new Path(dev);
        DbmlPoint s = line.start(), e = line.end(), c0 = line.controls().get(0);
        if (line.style() == DbmlCurveStyle.QUADRATIC) {
            guides.moveTo((float) s.x(), (float) s.y());
            guides.lineTo((float) c0.x(), (float) c0.y());
            guides.lineTo((float) e.x(), (float) e.y());
        } else {
            DbmlPoint c1 = line.controls().get(1);
            guides.moveTo((float) s.x(), (float) s.y());
            guides.lineTo((float) c0.x(), (float) c0.y());
            guides.moveTo((float) e.x(), (float) e.y());
            guides.lineTo((float) c1.x(), (float) c1.y());
        }
        dashed(gc, 1, 4, 3);
        draw(gc, guides, ACCENT, 153);
        guides.dispose();
        for (DbmlPoint c : line.controls()) {
            Path handle = new Path(dev);
            handle.addArc((float) (c.x() - 6.5), (float) (c.y() - 6.5), 13, 13, 0, 360);
            fill(gc, handle, WHITE, 255);
            handle.dispose();
            Path ring = new Path(dev);
            ring.addArc((float) (c.x() - 5.5), (float) (c.y() - 5.5), 11, 11, 0, 360);
            stroke(gc, 2);
            draw(gc, ring, ACCENT, 255);
            ring.dispose();
        }
    }

    /** The colour a note is drawn in: its own, else the yellow of notes (the DBML's notes grey). */
    public static RGB noteColor(DbmlGeometry.Sticky s) {
        if (!s.own()) return blend(DbmlGeometry.GRAY, 0.18);
        return s.color() != null ? s.color() : blend(DbmlGeometry.YELLOW, 0.28);
    }

    /** A colour at {@code alpha} over white. */
    public static RGB blend(RGB c, double alpha) {
        return new RGB((int) Math.round(255 + (c.red - 255) * alpha), (int) Math.round(255 + (c.green - 255) * alpha),
                (int) Math.round(255 + (c.blue - 255) * alpha));
    }

    public static void drawSticky(GC gc, DbmlGeometry.Sticky s, boolean selected, boolean editing) {
        Device dev = gc.getDevice();
        shadow(gc, s.rect(), 5, 2, 45);
        Path shape = rounded(dev, s.rect(), 5, true);
        fill(gc, shape, noteColor(s), 255);
        shape.dispose();
        double w = selected ? 2 : 1;
        Path border = rounded(dev, s.rect().insetBy(w / 2, w / 2), 5, true);
        stroke(gc, w);
        if (selected) draw(gc, border, ACCENT, 255);
        else draw(gc, border, DbmlGeometry.YELLOW, 204);
        border.dispose();
        if (!editing) {
            gc.setFont(fonts(dev).note());
            gc.setForeground(color(LABEL));
            DbmlRect inner = s.rect().insetBy(8, 8);
            int lh = gc.getFontMetrics().getHeight();
            int maxLines = Math.max(1, (int) Math.floor(inner.height() / lh));
            List<String> lines = wrap(gc, s.text(), inner.width(), maxLines);
            for (int k = 0; k < lines.size(); k++) {
                gc.drawText(lines.get(k), (int) Math.round(inner.minX()), (int) Math.round(inner.minY() + k * lh), SWT.DRAW_TRANSPARENT);
            }
        }
        if (s.own()) {
            // "arrow.down.right": the corner to drag to resize the note.
            double x = s.rect().maxX() - 8, y = s.rect().maxY() - 8;
            Path arrow = new Path(dev);
            arrow.moveTo((float) (x - 4), (float) (y - 4));
            arrow.lineTo((float) (x + 3.5), (float) (y + 3.5));
            arrow.moveTo((float) (x + 3.5), (float) (y - 1.5));
            arrow.lineTo((float) (x + 3.5), (float) (y + 3.5));
            arrow.lineTo((float) (x - 1.5), (float) (y + 3.5));
            stroke(gc, 1.6);
            draw(gc, arrow, SECONDARY, 255);
            arrow.dispose();
        }
    }

    // MARK: Text

    /** The text cut to a width with "…" (as truncatesLastVisibleLine). */
    static String fit(GC gc, String text, double width) {
        if (text == null) return "";
        if (gc.textExtent(text).x <= width) return text;
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (gc.textExtent(text.substring(0, mid) + "…").x <= width) lo = mid;
            else hi = mid - 1;
        }
        return lo == 0 ? "…" : text.substring(0, lo) + "…";
    }

    /** Word-wrapped lines of a text, at most {@code maxLines} (the last cut with "…" when more is left). */
    static List<String> wrap(GC gc, String text, double width, int maxLines) {
        List<String> out = new ArrayList<>();
        String[] paragraphs = (text == null ? "" : text).split("\n", -1);
        outer:
        for (String para : paragraphs) {
            if (para.isEmpty()) {
                out.add("");
                if (out.size() > maxLines) break;
                continue;
            }
            String rest = para;
            while (!rest.isEmpty()) {
                if (gc.textExtent(rest).x <= width) {
                    out.add(rest);
                    break;
                }
                // The longest prefix that fits, broken at a space when there is one.
                int lo = 1, hi = rest.length();
                while (lo < hi) {
                    int mid = (lo + hi + 1) / 2;
                    if (gc.textExtent(rest.substring(0, mid)).x <= width) lo = mid;
                    else hi = mid - 1;
                }
                int cut = lo;
                int space = rest.lastIndexOf(' ', cut);
                if (space > 0) cut = space;
                out.add(rest.substring(0, cut).stripTrailing());
                rest = rest.substring(cut).stripLeading();
                if (out.size() > maxLines) break outer;
            }
            if (out.size() > maxLines) break;
        }
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            String last = cut.get(maxLines - 1);
            cut.set(maxLines - 1, fit(gc, last + "…", width).replace("……", "…"));
            return cut;
        }
        return out;
    }

    // MARK: Pictures

    public static DbmlSize pictureSize(DbmlGeometry g) {
        return new DbmlSize(Math.ceil(g.extent.width() + 2 * PICTURE_MARGIN), Math.ceil(g.extent.height() + 2 * PICTURE_MARGIN));
    }

    /** The whole diagram as a picture on white (as on paper), {@code scale} pixels per point (less for a very big one). */
    public static ImageData picture(Device device, DbmlDiagram d, double scale) {
        DbmlGeometry g = new DbmlGeometry(d);
        DbmlSize size = pictureSize(g);
        double s = Math.max(0.2, Math.min(scale, Math.sqrt(MAX_PIXELS / Math.max(size.width() * size.height(), 1))));
        int w = (int) Math.ceil(size.width() * s), h = (int) Math.ceil(size.height() * s);
        Image image = new Image(device, Math.max(1, w), Math.max(1, h));
        GC gc = new GC(image);
        Transform t = new Transform(device);
        try {
            gc.setBackground(new Color(WHITE));
            gc.fillRectangle(0, 0, w, h);
            t.scale((float) s, (float) s);
            t.translate((float) (PICTURE_MARGIN - g.extent.minX()), (float) (PICTURE_MARGIN - g.extent.minY()));
            gc.setTransform(t);
            draw(gc, g, DbmlSelection.NONE, null);
            gc.setTransform(null);
        } finally {
            t.dispose();
            gc.dispose();
        }
        ImageData data = image.getImageData();
        image.dispose();
        return data;
    }
}
