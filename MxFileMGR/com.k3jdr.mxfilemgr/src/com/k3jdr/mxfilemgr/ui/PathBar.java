package com.k3jdr.mxfilemgr.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.workspace.BrowserTab;

/**
 * The clickable breadcrumb of a pane's folder (PaneView.swift's PathBar): "This PC › C: › Users › me". Click a
 * part to go there; when it doesn't fit, the leading parts give way to "…" so the current folder stays in view.
 */
final class PathBar extends Canvas {
    private record Segment(String text, Path path, boolean last) {}

    private static final String SEPARATOR = "  ›  ";
    private final Consumer<Path> navigate;
    private final List<Segment> segments = new ArrayList<>();
    private final List<Rectangle> bounds = new ArrayList<>();
    private int hover = -1;
    private Path url;
    private boolean initialized;

    PathBar(Composite parent, Consumer<Path> navigate) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.navigate = navigate;
        setCursor(new Cursor(getDisplay(), SWT.CURSOR_HAND));
        addListener(SWT.Paint, e -> paint(e.gc));
        addListener(SWT.MouseMove, e -> setHover(indexAt(e.x, e.y)));
        addListener(SWT.MouseExit, e -> setHover(-1));
        addListener(SWT.MouseUp, e -> {
            if (e.button != 1) return;
            int i = indexAt(e.x, e.y);
            if (i >= 0 && i < segments.size()) navigate.accept(segments.get(i).path());
        });
        addListener(SWT.Dispose, e -> getCursor().dispose());
    }

    void setURL(Path p) {
        if (initialized && Objects.equals(p, url)) return;
        initialized = true;
        url = p;
        segments.clear();
        List<Segment> rev = new ArrayList<>();
        for (Path at = p; at != null; at = at.getParent()) rev.add(0, new Segment(BrowserTab.displayName(at), at, false));
        segments.add(new Segment("This PC", null, p == null));
        for (int i = 0; i < rev.size(); i++) segments.add(new Segment(rev.get(i).text(), rev.get(i).path(), i == rev.size() - 1));
        hover = -1;
        redraw();
    }

    private void setHover(int i) {
        if (i == hover) return;
        hover = i;
        redraw();
    }

    private int indexAt(int x, int y) {
        for (int i = 0; i < bounds.size(); i++) {
            Rectangle r = bounds.get(i);
            if (r != null && r.contains(x, y)) return i;
        }
        return -1;
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        GC gc = new GC(this);
        gc.setFont(Ui.bold());
        int h = gc.getFontMetrics().getHeight() + 6;
        gc.dispose();
        return new Point(wHint == SWT.DEFAULT ? 200 : wHint, hHint == SWT.DEFAULT ? h : hHint);
    }

    private void paint(GC gc) {
        Rectangle area = getClientArea();
        gc.setBackground(getParent().getBackground());
        gc.fillRectangle(area);
        bounds.clear();
        for (int i = 0; i < segments.size(); i++) bounds.add(null);
        Font normal = getFont(), bold = Ui.bold();
        int[] widths = new int[segments.size()];
        gc.setFont(normal);
        int sep = gc.textExtent(SEPARATOR).x;
        int ellipsis = gc.textExtent("…" + SEPARATOR).x;
        int total = 0;
        for (int i = 0; i < segments.size(); i++) {
            gc.setFont(segments.get(i).last() ? bold : normal);
            widths[i] = gc.textExtent(segments.get(i).text()).x;
            total += widths[i] + (i > 0 ? sep : 0);
        }
        // Drop leading parts until the rest fits (the current folder is always shown).
        int first = 0;
        while (total + (first > 0 ? ellipsis : 0) > area.width - 4 && first < segments.size() - 1) {
            total -= widths[first] + sep;
            first++;
        }
        int h = gc.getFontMetrics().getHeight();
        int y = Math.max(0, (area.height - h) / 2);
        int x = 2;
        gc.setFont(normal);
        if (first > 0) {
            gc.setForeground(Ui.SECONDARY);
            gc.drawText("…" + SEPARATOR, x, y, true);
            x += ellipsis;
        }
        for (int i = first; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (i > first) {
                gc.setFont(normal);
                gc.setForeground(Ui.BORDER.getRGB().red > 0 ? Ui.SECONDARY : Ui.SECONDARY);
                gc.drawText(SEPARATOR, x, y, true);
                x += sep;
            }
            gc.setFont(s.last() ? bold : normal);
            gc.setForeground(s.last() ? getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND) : i == hover ? Ui.ACCENT : Ui.SECONDARY);
            gc.drawText(s.text(), x, y, true);
            if (i == hover && !s.last()) gc.drawLine(x, y + h - 1, x + widths[i], y + h - 1);
            bounds.set(i, new Rectangle(x - 2, 0, widths[i] + 4, area.height));
            x += widths[i];
        }
    }
}
