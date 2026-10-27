package com.k3jdr.mxdatahub;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Rows.SectorWeight;

/** A benchmark's weights by sector as horizontal bars, with each bar's percentage beside it (SRMBenchmarksView). */
final class BarChart extends Canvas {
    static final int ROW = 22;
    private List<SectorWeight> bars = List.of();

    BarChart(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);
        setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        addPaintListener(e -> paint(e.gc));
    }

    void setBars(List<SectorWeight> bars) {
        this.bars = bars == null ? List.of() : bars.stream().limit(12).toList();
        redraw();
    }

    /** The bar at y, for Quick Help. */
    SectorWeight barAt(int y) {
        int i = (y - 4) / ROW;
        return i >= 0 && i < bars.size() ? bars.get(i) : null;
    }

    int preferredHeight() { return Math.min(12, Math.max(1, bars.size())) * ROW + 30; }

    private void paint(GC gc) {
        gc.setAntialias(SWT.ON);
        gc.setFont(Ui.small());
        Rectangle area = getClientArea();
        if (bars.isEmpty()) return;
        int labelWidth = 0;
        for (SectorWeight s : bars) labelWidth = Math.max(labelWidth, gc.textExtent(s.sector()).x);
        labelWidth = Math.min(labelWidth, area.width / 2);
        double max = bars.stream().mapToDouble(SectorWeight::weight).max().orElse(1);
        int x0 = area.x + labelWidth + 10;
        int width = Math.max(10, area.width - labelWidth - 10 - 52);
        int fh = gc.getFontMetrics().getHeight();
        for (int i = 0; i < bars.size(); i++) {
            SectorWeight s = bars.get(i);
            int y = area.y + 4 + i * ROW;
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
            String label = s.sector();
            while (label.length() > 3 && gc.textExtent(label).x > labelWidth) label = label.substring(0, label.length() - 2) + "…";
            gc.drawText(label, x0 - 6 - gc.textExtent(label).x, y + (ROW - 4 - fh) / 2, true);
            int w = max <= 0 ? 0 : (int) Math.round(s.weight() / max * width);
            gc.setBackground(Ui.ACCENT);
            gc.fillRectangle(x0, y + 2, Math.max(1, w), ROW - 8);
            gc.setForeground(Ui.SECONDARY);
            gc.drawText(Fmt.percent(s.weight(), 1), x0 + w + 4, y + (ROW - 4 - fh) / 2, true);
        }
        gc.setForeground(Ui.SECONDARY);
        String axis = "% of benchmark";
        Point ext = gc.textExtent(axis);
        gc.drawText(axis, x0 + (width - ext.x) / 2, area.y + 6 + bars.size() * ROW, true);
    }
}
