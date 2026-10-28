package com.k3jdr.mxacctmgr.ui;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.port.Fmt;

/**
 * A small line chart (Swift Charts' LineMark / PointMark): points with x labels, an optional dashed rule (a budget)
 * and an optional shaded band (a rebalance band), axis titles; Quick Help reads the point under the pointer.
 */
public final class LineChart extends Canvas {
    public record Pt(String label, double y) {}

    private static final Color BAND = new Color(new RGB(210, 240, 215));
    private final List<Pt> points;
    private final Double rule, bandLo, bandHi;
    private final String xTitle, yTitle;
    private final int highlight;

    public LineChart(Composite parent, List<Pt> points, Double rule, Double bandLo, Double bandHi, String xTitle, String yTitle, int highlight, int height) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.points = points;
        this.rule = rule;
        this.bandLo = bandLo;
        this.bandHi = bandHi;
        this.xTitle = xTitle;
        this.yTitle = yTitle;
        this.highlight = highlight;
        setBackground(parent.getBackground());
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.heightHint = height;
        gd.widthHint = 200;
        setLayoutData(gd);
        addListener(SWT.Paint, e -> paint(e.gc));
        QuickHelp.provider(this, (c, p) -> {
            int i = index(p.x);
            if (i < 0) return null;
            Pt pt = points.get(i);
            return new QuickHelpItem(xTitle + " " + pt.label(), yTitle + ": " + Fmt.fixed(pt.y(), 2) + (rule != null ? " (rule " + Fmt.fixed(rule, 2) + ")" : ""));
        });
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        return new Point(wHint == SWT.DEFAULT ? 360 : wHint, ((GridData) getLayoutData()).heightHint);
    }

    private static final int LEFT = 44, RIGHT = 12, TOP = 10, BOTTOM = 34;

    private int index(int x) {
        if (points.isEmpty()) return -1;
        int w = getClientArea().width - LEFT - RIGHT;
        double step = points.size() > 1 ? (double) w / (points.size() - 1) : w;
        int i = (int) Math.round((x - LEFT) / Math.max(step, 1));
        return i >= 0 && i < points.size() ? i : -1;
    }

    private void paint(GC gc) {
        gc.setAntialias(SWT.ON);
        gc.setFont(Ui.small());
        int w = getClientArea().width, h = getClientArea().height;
        int pw = w - LEFT - RIGHT, ph = h - TOP - BOTTOM;
        if (points.isEmpty() || pw < 10 || ph < 10) return;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Pt p : points) {
            lo = Math.min(lo, p.y());
            hi = Math.max(hi, p.y());
        }
        for (Double d : new Double[] { rule, bandLo, bandHi }) if (d != null) {
            lo = Math.min(lo, d);
            hi = Math.max(hi, d);
        }
        lo = Math.min(lo, 0);
        if (hi - lo < 1e-9) hi = lo + 1;
        hi += (hi - lo) * 0.05;
        double fhi = hi, flo = lo;
        java.util.function.DoubleToIntFunction yOf = v -> TOP + (int) Math.round((fhi - v) / (fhi - flo) * ph);
        java.util.function.IntUnaryOperator xOf = i -> LEFT + (points.size() > 1 ? (int) Math.round((double) i * pw / (points.size() - 1)) : pw / 2);
        if (bandLo != null && bandHi != null) {
            gc.setBackground(BAND);
            int y1 = yOf.applyAsInt(bandHi), y2 = yOf.applyAsInt(bandLo);
            gc.fillRectangle(LEFT, y1, pw, Math.max(1, y2 - y1));
        }
        gc.setForeground(Ui.BORDER);
        gc.drawLine(LEFT, TOP, LEFT, TOP + ph);
        gc.drawLine(LEFT, TOP + ph, LEFT + pw, TOP + ph);
        gc.setForeground(Ui.SECONDARY);
        for (int k = 0; k <= 4; k++) {
            double v = lo + (hi - lo) * k / 4;
            int y = yOf.applyAsInt(v);
            gc.drawString(Fmt.fixed(v, 1), 2, y - 7, true);
        }
        if (rule != null) {
            gc.setForeground(Bars.ORANGE);
            gc.setLineStyle(SWT.LINE_DASH);
            int y = yOf.applyAsInt(rule);
            gc.drawLine(LEFT, y, LEFT + pw, y);
            gc.setLineStyle(SWT.LINE_SOLID);
        }
        gc.setForeground(Bars.BLUE);
        gc.setLineWidth(2);
        for (int i = 1; i < points.size(); i++) {
            gc.drawLine(xOf.applyAsInt(i - 1), yOf.applyAsInt(points.get(i - 1).y()), xOf.applyAsInt(i), yOf.applyAsInt(points.get(i).y()));
        }
        gc.setLineWidth(1);
        int labelEvery = Math.max(1, points.size() / 10);
        for (int i = 0; i < points.size(); i++) {
            int x = xOf.applyAsInt(i), y = yOf.applyAsInt(points.get(i).y());
            boolean outside = bandLo != null && bandHi != null && (points.get(i).y() < bandLo || points.get(i).y() > bandHi);
            gc.setBackground(outside ? Bars.RED : Bars.BLUE);
            int r = i == highlight ? 5 : 3;
            gc.fillOval(x - r, y - r, 2 * r, 2 * r);
            if (i % labelEvery == 0 || i == points.size() - 1) {
                gc.setForeground(Ui.SECONDARY);
                String l = points.get(i).label();
                gc.drawString(l, x - gc.stringExtent(l).x / 2, TOP + ph + 3, true);
            }
        }
        gc.setForeground(Ui.SECONDARY);
        gc.drawString(xTitle, LEFT + pw / 2 - gc.stringExtent(xTitle).x / 2, h - 15, true);
        gc.drawString(yTitle, LEFT + 4, TOP - 2, true);
    }
}
