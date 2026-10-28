package com.k3jdr.mxacctmgr.ui;

import java.util.ArrayList;
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
 * Horizontal bars, one group per row and one bar per series (Components.swift's HBars): weights against the
 * benchmark, shares of risk, effects by sector. Negative values grow left of the zero line. Quick Help reads a row's
 * values under the pointer.
 */
public final class Bars extends Canvas {
    public record Series(String name, Color color) {}

    /** A row: its label, a value per series, and optionally a colour per value (else the series'). */
    public record Row(String label, double[] values, Color[] colors) {
        public Row(String label, double... values) { this(label, values, null); }
    }

    public static final Color BLUE = new Color(new RGB(0, 110, 220));
    public static final Color GRAY = new Color(new RGB(170, 172, 180));
    public static final Color ORANGE = new Color(new RGB(240, 140, 30));
    public static final Color PURPLE = new Color(new RGB(150, 100, 200));
    public static final Color GREEN = new Color(new RGB(40, 160, 70));
    public static final Color RED = new Color(new RGB(210, 50, 50));

    private static final int BAR = 10, GAP = 8, LEGEND = 22;
    private final List<Series> series;
    private final List<Row> rows;
    private final int labelWidth;
    private final String unit;

    /** Values are in percent ({@code unit} "%" or "pp"). */
    public Bars(Composite parent, List<Series> series, List<Row> rows, int labelWidth, String unit) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.series = series;
        this.rows = new ArrayList<>(rows);
        this.labelWidth = labelWidth;
        this.unit = unit == null ? "%" : unit;
        setBackground(parent.getBackground());
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.heightHint = height();
        gd.widthHint = 200;
        setLayoutData(gd);
        addListener(SWT.Paint, e -> paint(e.gc));
        QuickHelp.provider(this, (c, p) -> {
            int i = rowAt(p.y);
            if (i < 0) return null;
            Row r = this.rows.get(i);
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < series.size() && s < r.values().length; s++) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(series.get(s).name()).append(": ").append(fmt(r.values()[s]));
            }
            return new QuickHelpItem(r.label(), sb.toString());
        });
        StringBuilder tip = new StringBuilder();
        for (Row r : rows) {
            tip.append(r.label());
            for (double v : r.values()) tip.append('\t').append(fmt(v));
            tip.append('\n');
        }
        setData("mx.pagetext.text", tip.toString());
    }

    private int groupHeight() { return Math.max(1, series.size()) * BAR + GAP; }

    private int height() { return (series.size() > 1 ? LEGEND : 4) + rows.size() * groupHeight() + 4; }

    private int rowAt(int y) {
        int top = series.size() > 1 ? LEGEND : 4;
        if (y < top) return -1;
        int i = (y - top) / groupHeight();
        return i < rows.size() ? i : -1;
    }

    private String fmt(double v) {
        if (unit.equals("k")) return "$" + Fmt.fixed(v, 0) + "k";
        return unit.equals("pp") ? Fmt.fixed(v, 2, true) + " pp" : Fmt.fixed(v, 1, v < 0) + "%";
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        return new Point(wHint == SWT.DEFAULT ? 360 : wHint, height());
    }

    private void paint(GC gc) {
        gc.setAntialias(SWT.ON);
        gc.setFont(Ui.small());
        int w = getClientArea().width;
        int top = 4;
        if (series.size() > 1) {
            int x = labelWidth;
            for (Series s : series) {
                gc.setBackground(s.color());
                gc.fillRectangle(x, 6, 10, 10);
                gc.setForeground(Ui.SECONDARY);
                gc.drawString(s.name(), x + 14, 3, true);
                x += 14 + gc.stringExtent(s.name()).x + 16;
            }
            top = LEGEND;
        }
        double lo = 0, hi = 0;
        for (Row r : rows) for (double v : r.values()) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (hi - lo < 1e-9) hi = lo + 1;
        int valueRoom = 64;
        int plot = Math.max(20, w - labelWidth - valueRoom - 8);
        double scale = plot / (hi - lo);
        int zero = labelWidth + 4 + (int) Math.round(-lo * scale);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = top + i * groupHeight();
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            String label = r.label();
            while (label.length() > 3 && gc.stringExtent(label).x > labelWidth - 6) label = label.substring(0, label.length() - 2) + "…";
            gc.drawString(label, 0, y + (series.size() * BAR - gc.getFontMetrics().getHeight()) / 2, true);
            for (int s = 0; s < r.values().length && s < series.size(); s++) {
                double v = r.values()[s];
                Color c = r.colors() != null && s < r.colors().length && r.colors()[s] != null ? r.colors()[s] : series.get(s).color();
                gc.setBackground(c);
                int len = (int) Math.round(Math.abs(v) * scale);
                int by = y + s * BAR;
                if (v >= 0) gc.fillRectangle(zero, by + 1, Math.max(1, len), BAR - 2);
                else gc.fillRectangle(zero - len, by + 1, Math.max(1, len), BAR - 2);
                if (s == r.values().length - 1 || series.size() == 1) {
                    gc.setForeground(Ui.SECONDARY);
                }
                String t = fmt(v);
                int left = zero - len - gc.stringExtent(t).x - 4;
                int tx = v >= 0 ? zero + len + 4 : left >= labelWidth ? left : zero + 4;
                if (series.size() <= 2 || s == 0) {
                    gc.setForeground(Ui.SECONDARY);
                    gc.drawString(t, tx, by - 2, true);
                }
            }
        }
        gc.setForeground(Ui.BORDER);
        gc.drawLine(zero, top - 2, zero, top + rows.size() * groupHeight());
    }
}
