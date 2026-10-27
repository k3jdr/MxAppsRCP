package com.k3jdr.mxdatahub;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Rows.PricePoint;

/**
 * A line of closes over dates — a security's prices, a benchmark's index level — drawn with SWT's GC, with the
 * axis not starting at zero (Swift Charts' {@code .automatic(includesZero: false)}). The pointer shows the close of
 * the day under it.
 */
final class LineChart extends Canvas {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);
    private List<PricePoint> points = List.of();
    private String caption = "";
    private int hover = -1;

    LineChart(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);
        setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        addPaintListener(e -> paint(e.gc));
        addListener(SWT.MouseMove, e -> {
            int i = indexAt(e.x);
            if (i != hover) {
                hover = i;
                redraw();
            }
        });
        addListener(SWT.MouseExit, e -> {
            hover = -1;
            redraw();
        });
    }

    void setPoints(List<PricePoint> points, String caption) {
        this.points = points == null ? List.of() : points;
        this.caption = caption == null ? "" : caption;
        hover = -1;
        redraw();
    }

    List<PricePoint> points() { return points; }

    /** The point under the pointer, for Quick Help. */
    PricePoint pointAt(int x) {
        int i = indexAt(x);
        return i < 0 ? null : points.get(i);
    }

    private Rectangle plot() {
        Rectangle r = getClientArea();
        return new Rectangle(r.x + 8, r.y + 18, Math.max(1, r.width - 8 - 64), Math.max(1, r.height - 18 - 22));
    }

    private int indexAt(int x) {
        if (points.size() < 2) return -1;
        Rectangle p = plot();
        if (x < p.x || x > p.x + p.width) return -1;
        LocalDate first = points.get(0).date(), last = points.get(points.size() - 1).date();
        long span = Math.max(1, ChronoUnit.DAYS.between(first, last));
        long day = Math.round((x - p.x) / (double) p.width * span);
        int best = -1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            long diff = Math.abs(ChronoUnit.DAYS.between(first, points.get(i).date()) - day);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private void paint(GC gc) {
        gc.setAntialias(SWT.ON);
        gc.setFont(Ui.small());
        Rectangle area = getClientArea();
        if (points.size() < 2) {
            gc.setForeground(Ui.SECONDARY);
            String text = points.isEmpty() ? "No prices" : "One price only";
            Point ext = gc.textExtent(text);
            gc.drawText(text, area.x + (area.width - ext.x) / 2, area.y + (area.height - ext.y) / 2, true);
            return;
        }
        Rectangle p = plot();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (PricePoint pt : points) {
            min = Math.min(min, pt.close());
            max = Math.max(max, pt.close());
        }
        double[] ticks = niceTicks(min, max);
        double lo = ticks[0], hi = ticks[ticks.length - 1];
        if (hi <= lo) hi = lo + 1;
        LocalDate first = points.get(0).date(), last = points.get(points.size() - 1).date();
        long span = Math.max(1, ChronoUnit.DAYS.between(first, last));

        // Grid and the axis labels on the right.
        gc.setLineWidth(1);
        for (double t : ticks) {
            int y = p.y + p.height - (int) Math.round((t - lo) / (hi - lo) * p.height);
            gc.setForeground(Ui.BORDER);
            gc.setLineStyle(SWT.LINE_DOT);
            gc.drawLine(p.x, y, p.x + p.width, y);
            gc.setLineStyle(SWT.LINE_SOLID);
            gc.setForeground(Ui.SECONDARY);
            String label = Fmt.number(t, t >= 1000 ? 0 : 2);
            gc.drawText(label, p.x + p.width + 6, y - gc.getFontMetrics().getHeight() / 2, true);
        }
        // Month labels along the bottom.
        gc.setForeground(Ui.SECONDARY);
        LocalDate m = first.withDayOfMonth(1).plusMonths(1);
        long months = ChronoUnit.MONTHS.between(first, last);
        int step = (int) Math.max(1, Math.ceil(months / Math.max(1.0, p.width / 70.0)));
        for (; !m.isAfter(last); m = m.plusMonths(step)) {
            int x = p.x + (int) Math.round(ChronoUnit.DAYS.between(first, m) / (double) span * p.width);
            gc.setForeground(Ui.BORDER);
            gc.drawLine(x, p.y + p.height, x, p.y + p.height + 3);
            gc.setForeground(Ui.SECONDARY);
            String label = m.format(MONTH);
            int w = gc.textExtent(label).x;
            if (x - w / 2 >= area.x && x + w / 2 <= p.x + p.width) gc.drawText(label, x - w / 2, p.y + p.height + 4, true);
        }
        gc.setForeground(Ui.BORDER);
        gc.drawLine(p.x, p.y + p.height, p.x + p.width, p.y + p.height);

        // The line.
        int[] poly = new int[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            PricePoint pt = points.get(i);
            poly[2 * i] = p.x + (int) Math.round(ChronoUnit.DAYS.between(first, pt.date()) / (double) span * p.width);
            poly[2 * i + 1] = p.y + p.height - (int) Math.round((pt.close() - lo) / (hi - lo) * p.height);
        }
        gc.setForeground(Ui.ACCENT);
        gc.setLineWidth(2);
        gc.drawPolyline(poly);
        gc.setLineWidth(1);

        // The caption, or the day under the pointer.
        gc.setForeground(Ui.SECONDARY);
        String top = caption;
        if (hover >= 0) {
            PricePoint pt = points.get(hover);
            int x = poly[2 * hover], y = poly[2 * hover + 1];
            gc.setForeground(Ui.BORDER);
            gc.drawLine(x, p.y, x, p.y + p.height);
            gc.setBackground(Ui.ACCENT);
            gc.fillOval(x - 3, y - 3, 7, 7);
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
            top = pt.date() + "   " + Fmt.number(pt.close(), 2);
        }
        gc.drawText(top, p.x, area.y + 1, true);
    }

    /** Round axis values around [min, max] (four to six of them). */
    static double[] niceTicks(double min, double max) {
        if (max - min < 1e-9) {
            double pad = Math.max(1e-6, Math.abs(min) * 0.01);
            min -= pad;
            max += pad;
        }
        double range = max - min;
        double rough = range / 4;
        double mag = Math.pow(10, Math.floor(Math.log10(rough)));
        double norm = rough / mag;
        double step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag;
        double lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step;
        int n = (int) Math.round((hi - lo) / step) + 1;
        double[] out = new double[Math.max(2, n)];
        for (int i = 0; i < out.length; i++) out[i] = lo + i * step;
        return out;
    }
}
