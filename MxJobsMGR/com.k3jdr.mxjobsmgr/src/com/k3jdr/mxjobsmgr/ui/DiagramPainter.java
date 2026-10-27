package com.k3jdr.mxjobsmgr.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;

import com.k3jdr.mxjobsmgr.engine.RunEngine.NodeState;
import com.k3jdr.mxjobsmgr.engine.Validator;
import com.k3jdr.mxjobsmgr.model.JobLink;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.Metrics;
import com.k3jdr.mxjobsmgr.model.NodeSummary;

/**
 * Everything drawn on the canvas (DiagramCanvas.swift DiagramContent, LanesView, NodeView): the three lanes with
 * their titles, grid dots, the links (calls down solid, results up dashed) with their labels, and the nodes with kind,
 * title, summary, run state or problem badge, breakpoint tag and ● link handle. Also draws the PNG export.
 */
public final class DiagramPainter {
    private DiagramPainter() {}

    /** What to draw, and in which state. */
    public static final class View {
        public Jobspace space;
        public Set<String> selectedNodes = Set.of();
        public String selectedLink;
        public List<Validator.Issue> issues = List.of();
        public Map<String, NodeState> states = Map.of();
        public String currentNode;
        public boolean breakpointsEnabled = true;
        public boolean interactive = true;
        public String hoverNode;
        /** Nodes being dragged, and by how much. */
        public Set<String> dragging = Set.of();
        public double dragDx, dragDy;
        /** A link being drawn from a node's handle to the pointer. */
        public String pendingFrom;
        public double pendingX, pendingY;
        /** The rubber band, x, y, w, h. */
        public double[] rubberBand;

        public double x(JobNode n) { return n.x + (dragging.contains(n.id) ? dragDx : 0); }
        public double y(JobNode n) { return n.y + (dragging.contains(n.id) ? dragDy : 0); }
    }

    private static final Map<String, Font> FONTS = new HashMap<>();

    /** A font of {@code points} (scaled with the diagram) and style. */
    static Font font(Device device, double points, int style) {
        String key = points + ":" + style;
        return FONTS.computeIfAbsent(key, k -> {
            Display d = Display.getDefault();
            String name = d.getSystemFont().getFontData()[0].getName();
            org.eclipse.swt.graphics.FontData fd = new org.eclipse.swt.graphics.FontData(name, 10, style);
            fd.height = (float) points;
            Font f = new Font(d, fd);
            d.disposeExec(f::dispose);
            return f;
        });
    }

    static Path roundRect(Device device, double x, double y, double w, double h, double r) {
        Path p = new Path(device);
        float fx = (float) x, fy = (float) y, fw = (float) w, fh = (float) h, d = (float) (2 * r);
        p.moveTo(fx + (float) r, fy);
        p.lineTo(fx + fw - (float) r, fy);
        p.addArc(fx + fw - d, fy, d, d, 90, -90);
        p.lineTo(fx + fw, fy + fh - (float) r);
        p.addArc(fx + fw - d, fy + fh - d, d, d, 0, -90);
        p.lineTo(fx + (float) r, fy + fh);
        p.addArc(fx, fy + fh - d, d, d, 270, -90);
        p.lineTo(fx, fy + (float) r);
        p.addArc(fx, fy, d, d, 180, -90);
        p.close();
        return p;
    }

    /** Text cut to {@code width} with an ellipsis. */
    static String clip(GC gc, String text, int width) {
        if (text == null) return "";
        String t = text.replace('\n', ' ');
        if (gc.textExtent(t).x <= width) return t;
        int lo = 0, hi = t.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (gc.textExtent(t.substring(0, mid) + "…").x <= width) lo = mid;
            else hi = mid - 1;
        }
        return t.substring(0, lo) + "…";
    }

    private static int i(double v) { return (int) Math.round(v); }

    /** Paints the diagram in diagram coordinates (the caller sets the zoom and scroll transform). */
    public static void paint(GC gc, View v, double clipX, double clipY, double clipW, double clipH) {
        Device device = gc.getDevice();
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        double width = Metrics.canvasWidth(v.space), height = Metrics.canvasHeight();
        gc.setBackground(Colors.of(Colors.WHITE));
        gc.fillRectangle(i(clipX) - 1, i(clipY) - 1, i(clipW) + 2, i(clipH) + 2);
        paintLanes(gc, v, width, clipX, clipY, clipW, clipH);
        paintLinks(gc, v, device);
        for (JobNode node : v.space.nodes) paintNode(gc, v, node, device);
        if (v.pendingFrom != null) {
            JobNode from = v.space.node(v.pendingFrom);
            if (from != null) {
                gc.setForeground(Colors.of(Colors.ACCENT));
                gc.setLineAttributes(new LineAttributes(2, SWT.CAP_ROUND, SWT.JOIN_ROUND, SWT.LINE_CUSTOM, new float[] { 6, 4 }, 0, 10));
                gc.drawLine(i(v.x(from)), i(v.y(from)), i(v.pendingX), i(v.pendingY));
                gc.setLineAttributes(new LineAttributes(1));
            }
        }
        if (v.rubberBand != null) {
            double[] r = v.rubberBand;
            gc.setAlpha(20);
            gc.setBackground(Colors.of(Colors.ACCENT));
            gc.fillRectangle(i(r[0]), i(r[1]), i(r[2]), i(r[3]));
            gc.setAlpha(150);
            gc.setForeground(Colors.of(Colors.ACCENT));
            gc.setLineAttributes(new LineAttributes(1, SWT.CAP_FLAT, SWT.JOIN_MITER, SWT.LINE_CUSTOM, new float[] { 4, 3 }, 0, 10));
            gc.drawRectangle(i(r[0]), i(r[1]), i(r[2]), i(r[3]));
            gc.setLineAttributes(new LineAttributes(1));
            gc.setAlpha(255);
        }
    }

    private static void paintLanes(GC gc, View v, double width, double cx, double cy, double cw, double ch) {
        java.util.Set<Layer> used = new java.util.HashSet<>();
        for (JobNode n : v.space.nodes) used.add(n.layer());
        for (Layer layer : Layer.values()) {
            int top = i(Metrics.laneTop(layer)), lh = i(Metrics.LANE_HEIGHT), g = i(Metrics.GUTTER);
            gc.setBackground(Colors.tint(layer.rgb, layer.number % 2 == 0 ? 0.07 : 0.045));
            gc.fillRectangle(0, top, i(width), lh);
            gc.setBackground(Colors.tint(layer.rgb, (layer.number % 2 == 0 ? 0.07 : 0.045) + 0.12));
            gc.fillRectangle(0, top, g, lh);
            gc.setForeground(Colors.layer(layer));
            gc.setFont(font(gc.getDevice(), 7, SWT.BOLD));
            gc.drawText("LAYER " + layer.number, 12, top + 12, true);
            gc.setForeground(Colors.of(Colors.TEXT));
            gc.setFont(font(gc.getDevice(), 10, SWT.BOLD));
            gc.drawText(layer.title, 12, top + 28, true);
            gc.setForeground(Colors.of(Colors.SECONDARY));
            gc.setFont(font(gc.getDevice(), 7.5, SWT.NORMAL));
            int y = top + 50;
            for (String line : wrap(gc, layer.subtitle, g - 24)) {
                gc.drawText(line, 12, y, true);
                y += gc.getFontMetrics().getHeight();
            }
            if (!used.contains(layer) && v.interactive) {
                gc.setForeground(Colors.of(Colors.TERTIARY));
                gc.setFont(font(gc.getDevice(), 9.5, SWT.NORMAL));
                String hint = "Drag " + layer.title.toLowerCase() + " steps here from the palette";
                gc.drawText(hint, g + 40, top + lh / 2 - gc.getFontMetrics().getHeight() / 2, true);
            }
            gc.setForeground(Colors.tint(layer.rgb, 0.35));
            gc.drawLine(0, top + lh - 1, i(width), top + lh - 1);
        }
        // Grid dots
        gc.setBackground(Colors.of(Colors.blend(Colors.SECONDARY, 0xF4F4F8, 0.25)));
        double step = 20;
        double y0 = Math.max(step, Math.floor(cy / step) * step), y1 = Math.min(Metrics.canvasHeight(), cy + ch + step);
        double x0 = Math.max(Metrics.GUTTER + step, Math.floor(cx / step) * step), x1 = Math.min(width, cx + cw + step);
        for (double y = y0; y < y1; y += step) {
            for (double x = x0; x < x1; x += step) gc.fillRectangle(i(x - 0.75), i(y - 0.75), 2, 2);
        }
    }

    static List<String> wrap(GC gc, String text, int width) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && gc.textExtent(candidate).x > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static void paintLinks(GC gc, View v, Device device) {
        for (JobLink link : v.space.links) {
            JobNode a = v.space.node(link.from), b = v.space.node(link.to);
            if (a == null || b == null) continue;
            Metrics.Curve c = Metrics.curve(v.x(a), v.y(a), v.x(b), v.y(b));
            boolean selected = link.id.equals(v.selectedLink);
            boolean upward = b.layer().number < a.layer().number;
            int color = selected ? Colors.ACCENT : Colors.blend((upward ? b.layer() : a.layer()).rgb, Colors.WHITE, 0.85);
            Path p = new Path(device);
            p.moveTo((float) c.sx(), (float) c.sy());
            p.cubicTo((float) c.c1x(), (float) c.c1y(), (float) c.c2x(), (float) c.c2y(), (float) c.ex(), (float) c.ey());
            gc.setForeground(Colors.of(color));
            gc.setLineAttributes(upward
                    ? new LineAttributes(selected ? 3 : 1.8f, SWT.CAP_ROUND, SWT.JOIN_ROUND, SWT.LINE_CUSTOM, new float[] { 7, 5 }, 0, 10)
                    : new LineAttributes(selected ? 3 : 1.8f, SWT.CAP_ROUND, SWT.JOIN_ROUND));
            gc.drawPath(p);
            p.dispose();
            gc.setLineAttributes(new LineAttributes(1));
            double[] h = c.arrowHead();
            gc.setBackground(Colors.of(color));
            gc.fillPolygon(new int[] { i(h[0]), i(h[1]), i(h[2]), i(h[3]), i(h[4]), i(h[5]) });
            if (!link.label.isEmpty()) {
                gc.setFont(font(device, 7.5, SWT.NORMAL));
                Point s = gc.textExtent(link.label);
                double rx = c.midX() - s.x / 2.0 - 5, ry = c.midY() - s.y / 2.0 - 2;
                Path r = roundRect(device, rx, ry, s.x + 10, s.y + 4, 4);
                gc.setBackground(Colors.of(Colors.WHITE));
                gc.fillPath(r);
                gc.setForeground(Colors.of(Colors.blend(color, Colors.WHITE, 0.5)));
                gc.drawPath(r);
                r.dispose();
                gc.setForeground(Colors.of(Colors.SECONDARY));
                gc.drawText(link.label, i(rx + 5), i(ry + 2), true);
            }
        }
    }

    private static void paintNode(GC gc, View v, JobNode node, Device device) {
        double w = Metrics.NODE_W, h = Metrics.NODE_H;
        double x = v.x(node) - w / 2, y = v.y(node) - h / 2;
        int color = node.layer().rgb;
        boolean selected = v.selectedNodes.contains(node.id);
        NodeState state = v.states.get(node.id);
        boolean ran = state != null && state != NodeState.PENDING;
        boolean current = node.id.equals(v.currentNode);
        int alpha = node.enabled ? 255 : 128;

        // Shadow
        gc.setAlpha(node.enabled ? (selected ? 46 : 22) : 10);
        gc.setBackground(Colors.of(0x000000));
        Path shadow = roundRect(device, x + 1, y + (selected ? 3 : 2), w, h, 10);
        gc.fillPath(shadow);
        shadow.dispose();
        gc.setAlpha(alpha);

        // Current step glow
        if (current) {
            int glow = (state != null ? state : NodeState.RUNNING).rgb;
            Path g = roundRect(device, x - 4, y - 4, w + 8, h + 8, 13);
            gc.setForeground(Colors.tint(glow, 0.35));
            gc.setLineWidth(5);
            gc.drawPath(g);
            gc.setLineWidth(1);
            g.dispose();
        }

        Path body = roundRect(device, x, y, w, h, 10);
        gc.setBackground(Colors.tint(color, 0.10));
        gc.fillPath(body);
        gc.setClipping(body);
        gc.setBackground(Colors.of(color));
        gc.fillRectangle(i(x), i(y), 4, i(h));
        gc.setClipping((org.eclipse.swt.graphics.Rectangle) null);
        int border = selected ? Colors.ACCENT : ran ? state.rgb : Colors.blend(color, Colors.WHITE, 0.55);
        gc.setForeground(Colors.of(border));
        gc.setLineWidth(selected || current ? 2 : ran ? 2 : 1);
        gc.drawPath(body);
        gc.setLineWidth(1);
        body.dispose();

        // Header: symbol and kind, then the run state or the worst problem, and the link mark
        int tx = i(x + 12);
        gc.setForeground(Colors.of(color));
        gc.setFont(font(device, 8, SWT.BOLD));
        gc.drawText(node.kind.symbol, tx, i(y + 6), true);
        int symW = gc.textExtent(node.kind.symbol).x + 5;
        gc.setFont(font(device, 6.5, SWT.BOLD));
        int badgeRoom = 34;
        gc.drawText(clip(gc, node.kind.title.toUpperCase(), i(w) - 24 - symW - badgeRoom), tx + symW, i(y + 8), true);
        double bx = x + w - 10;
        if (ran) {
            gc.setFont(font(device, 8.5, SWT.BOLD));
            gc.setForeground(Colors.of(state.rgb));
            String s = state.symbol;
            bx -= gc.textExtent(s).x;
            gc.drawText(s, i(bx), i(y + 5), true);
        } else {
            Validator.Severity issue = Validator.worst(v.issues, node.id);
            if (issue != null) {
                gc.setFont(font(device, 8, SWT.NORMAL));
                gc.setForeground(Colors.of(issue.rgb));
                bx -= gc.textExtent(issue.symbol).x;
                gc.drawText(issue.symbol, i(bx), i(y + 5), true);
            }
        }
        if (v.interactive && PaletteEdits.shownLink(node) != null) {
            gc.setFont(font(device, 8, SWT.NORMAL));
            gc.setForeground(Colors.of(node.link != null ? Colors.ACCENT : Colors.SECONDARY));
            bx -= gc.textExtent("🔗").x + 3;
            gc.drawText("🔗", i(bx), i(y + 5), true);
        }

        gc.setForeground(Colors.of(Colors.TEXT));
        gc.setFont(font(device, 9, SWT.BOLD));
        gc.drawText(clip(gc, node.title, i(w) - 22), tx, i(y + 26), true);
        gc.setForeground(Colors.of(Colors.SECONDARY));
        gc.setFont(font(device, 7.5, SWT.NORMAL));
        gc.drawText(clip(gc, NodeSummary.text(node, v.space), i(w) - 22), tx, i(y + 48), true);

        // Breakpoint tag
        if (node.breakpoint) {
            Path tag = roundRect(device, x - 7, y - 7, 22, 14, 3);
            gc.setBackground(Colors.tint(Colors.BREAKPOINT, v.breakpointsEnabled ? 1 : 0.35));
            gc.fillPath(tag);
            tag.dispose();
            gc.setBackground(Colors.of(Colors.WHITE));
            gc.fillPolygon(new int[] { i(x + 1), i(y - 3), i(x + 8), i(y), i(x + 1), i(y + 3) });
        }
        gc.setAlpha(255);

        // Link handle
        if (v.interactive) {
            boolean hot = selected || node.id.equals(v.hoverNode);
            gc.setBackground(Colors.of(hot ? Colors.ACCENT : Colors.blend(color, Colors.WHITE, 0.5)));
            gc.fillOval(i(x + w - 6), i(y + h / 2 - 6), 12, 12);
            gc.setForeground(Colors.of(Colors.WHITE));
            gc.setLineWidth(2);
            gc.drawOval(i(x + w - 6), i(y + h / 2 - 6), 12, 12);
            gc.setLineWidth(1);
        }
    }
}
