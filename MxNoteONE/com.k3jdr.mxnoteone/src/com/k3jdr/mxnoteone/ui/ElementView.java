package com.k3jdr.mxnoteone.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxnoteone.model.PageElement;

/**
 * A container on the page (ElementViews.swift ElementView): a bar on top to drag it by (shown while the pointer is
 * over the container, or while it's selected or being edited), its content, and a handle on its right edge to
 * resize it. Positions and sizes are the Mac's points, scaled by the page's zoom.
 */
public abstract class ElementView extends Composite {
    public static final double GRIP = 10;
    static final int HANDLE = 6;

    final PageView page;
    final PageElement element;
    final Canvas grip;
    final Canvas handle;
    boolean hover, selected;
    private Point dragStart;
    private double[] dragFrom;
    private PageView.Snapshot dragBefore;

    ElementView(PageView page, PageElement element) {
        super(page.paper, SWT.NONE);
        this.page = page;
        this.element = element;
        grip = new Canvas(this, SWT.NONE);
        handle = new Canvas(this, SWT.NONE);
        handle.setCursor(getDisplay().getSystemCursor(SWT.CURSOR_SIZEWE));
        grip.addPaintListener(e -> paintGrip(e.gc));
        handle.addPaintListener(e -> {
            if (!framed()) return;
            Rectangle r = handle.getClientArea();
            e.gc.setForeground(Gfx.color(selected ? Gfx.SELECTED : Gfx.FRAME));
            e.gc.drawLine(r.width - 1, 0, r.width - 1, r.height);
        });
        addPaintListener(e -> {
            if (!framed()) return;
            Rectangle r = getClientArea();
            e.gc.setForeground(Gfx.color(selected ? Gfx.SELECTED : Gfx.FRAME));
            e.gc.drawRectangle(0, 0, r.width - 1, r.height - 1);
        });
        grip.addListener(SWT.MouseDown, e -> {
            if (e.button == 1) {
                page.select(this);
                dragStart = getDisplay().getCursorLocation();
                dragFrom = new double[] { element.x, element.y };
                dragBefore = page.snapshot();
            }
        });
        grip.addListener(SWT.MouseMove, e -> {
            if (dragStart == null) return;
            Point now = getDisplay().getCursorLocation();
            double x = Math.max(0, dragFrom[0] + (now.x - dragStart.x) / page.zoom);
            double y = Math.max(0, dragFrom[1] + (now.y - dragStart.y) / page.zoom);
            element.x = page.snapX(x);
            element.y = page.snapY(y);
            place();
        });
        grip.addListener(SWT.MouseUp, e -> {
            if (dragStart == null) return;
            dragStart = null;
            if (element.x != dragFrom[0] || element.y != dragFrom[1]) page.pushUndo("Move", dragBefore);
            page.contentChanged();
        });
        grip.addListener(SWT.MouseDoubleClick, e -> doubleClickGrip());
        handle.addListener(SWT.MouseDown, e -> {
            if (e.button == 1) {
                dragStart = getDisplay().getCursorLocation();
                dragFrom = new double[] { element.width };
                dragBefore = page.snapshot();
            }
        });
        handle.addListener(SWT.MouseMove, e -> {
            if (dragStart == null) return;
            Point now = getDisplay().getCursorLocation();
            setWidth(Math.max(minWidth(), page.snapX(dragFrom[0] + (now.x - dragStart.x) / page.zoom)));
        });
        handle.addListener(SWT.MouseUp, e -> {
            if (dragStart == null) return;
            dragStart = null;
            if (element.width != dragFrom[0]) page.pushUndo("Resize", dragBefore);
            page.contentChanged();
        });
        Menu menu = new Menu(grip);
        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();
            page.select(this);
            fillMenu(menu);
        });
        grip.setMenu(menu);
        QuickHelp.provider(grip, (c, p) -> new QuickHelpItem(element.kindTitle(),
                "Drag the bar to move the container; right-click it for Cut, Copy, Delete, Bring to Front and Send to Back. Click it to select the container: then Delete removes it and the arrow keys nudge it."));
        QuickHelp.help(handle, "Resize", "Drag the right edge to make the container wider or narrower.");
    }

    double zoom() { return page.zoom; }
    int px(double v) { return (int) Math.round(v * page.zoom); }

    double minWidth() { return 40; }

    /** The content's height in points for the container's width. */
    abstract double contentHeight();

    /** The content's control, laid out under the bar. */
    abstract Control content();

    /** Brings the element (the model) up to date with what's shown, and returns it. */
    PageElement snapshot() { return element; }

    /** Text for search and tags; null when the container has none. */
    String indexText() { return com.k3jdr.mxnoteone.store.PageIndexer.text(element); }

    void doubleClickGrip() {}

    boolean framed() { return hover || selected || isEditing(); }

    boolean isEditing() { return false; }

    void setFramed() {
        if (isDisposed()) return;
        redraw();
        grip.redraw();
        handle.redraw();
    }

    /** Places the container at its position and size, and lays out its parts. */
    void place() {
        element.height = Math.round((GRIP + contentHeight()) * 2) / 2.0;
        setBounds(px(element.x), px(element.y), Math.max(1, px(element.width)), Math.max(1, px(element.height)));
        int w = px(element.width), h = px(element.height), g = px(GRIP);
        grip.setBounds(0, 0, w, g);
        handle.setBounds(Math.max(0, w - HANDLE), g, HANDLE, Math.max(0, h - g));
        Control c = content();
        if (c != null) c.setBounds(1, g, Math.max(1, w - 1 - HANDLE), Math.max(1, h - g - 1));
        handle.moveAbove(null);
    }

    void setWidth(double w) {
        element.width = w;
        double oldBottom = element.y + element.height;
        place();
        page.grew(this, oldBottom);
    }

    private void paintGrip(GC gc) {
        Rectangle r = grip.getClientArea();
        if (!framed()) return;
        gc.setBackground(Gfx.color(selected ? Gfx.SELECTED : Gfx.GRIP));
        gc.fillRectangle(0, 0, r.width, r.height);
        gc.setBackground(Gfx.color(selected ? new org.eclipse.swt.graphics.RGB(220, 235, 255) : new org.eclipse.swt.graphics.RGB(150, 155, 165)));
        int cx = r.width / 2;
        for (int i = -2; i <= 2; i++) gc.fillOval(cx + i * 5 - 1, r.height / 2 - 1, 3, 3);
        grip.setCursor(getDisplay().getSystemCursor(SWT.CURSOR_SIZEALL));
    }

    /** The container's right-click menu (ElementView.contextMenu). */
    void fillMenu(Menu menu) {
        item(menu, "Cut", () -> page.cut(this));
        item(menu, "Copy", () -> page.copy(this));
        item(menu, "Delete", () -> page.remove(this, true));
        new MenuItem(menu, SWT.SEPARATOR);
        item(menu, "Bring to Front", () -> page.bringToFront(this));
        item(menu, "Send to Back", () -> page.sendToBack(this));
    }

    static MenuItem item(Menu menu, String text, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(text);
        mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    /** Leaves the page (a zoom or reload rebuilds it). */
    void zoomChanged() {}
}
