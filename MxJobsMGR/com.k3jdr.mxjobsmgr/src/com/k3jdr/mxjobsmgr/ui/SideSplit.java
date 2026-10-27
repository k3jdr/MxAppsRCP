package com.k3jdr.mxjobsmgr.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Sash;

import com.k3jdr.mx.core.prefs.Prefs;

/**
 * A main pane and a side pane of remembered size — the inspector on the right, the run console at the bottom — with
 * a divider to drag, and the side pane shown or hidden. (The core's SplitPanes keeps the leading pane's size; the
 * inspector and the console keep the trailing one's.)
 */
public final class SideSplit extends Composite {
    private static final int SASH = 5;
    private final Composite main, side;
    private final Sash sash;
    private final boolean bottom;
    private final int min, max;
    private final Prefs prefs = Prefs.of("com.k3jdr.mxjobsmgr.split");
    private final String key;
    private int size;
    private boolean sideVisible = true;

    /** @param bottom the side pane under the main one (else on its right) */
    public SideSplit(Composite parent, String id, int min, int max, int ideal, boolean bottom) {
        super(parent, SWT.NONE);
        this.bottom = bottom;
        this.min = min;
        this.max = max;
        this.key = id;
        this.size = prefs.getInt(key, ideal);
        main = new Composite(this, SWT.NONE);
        sash = new Sash(this, bottom ? SWT.HORIZONTAL : SWT.VERTICAL);
        side = new Composite(this, SWT.NONE);
        sash.addListener(SWT.Selection, e -> {
            Rectangle r = getClientArea();
            int wanted = bottom ? r.height - e.y - SASH : r.width - e.x - SASH;
            size = clamp(wanted, bottom ? r.height : r.width);
            if (e.detail != SWT.DRAG) prefs.putInt(key, size);
            layout(true);
        });
        setLayout(new Layout() {
            @Override
            protected Point computeSize(Composite c, int wHint, int hHint, boolean flush) {
                return new Point(wHint == SWT.DEFAULT ? 400 : wHint, hHint == SWT.DEFAULT ? 300 : hHint);
            }

            @Override
            protected void layout(Composite c, boolean flush) {
                Rectangle r = getClientArea();
                if (!sideVisible) {
                    main.setBounds(r);
                    sash.setBounds(0, 0, 0, 0);
                    side.setBounds(0, 0, 0, 0);
                    return;
                }
                int total = bottom ? r.height : r.width;
                int s = clamp(size, total);
                if (bottom) {
                    main.setBounds(r.x, r.y, r.width, Math.max(0, r.height - s - SASH));
                    sash.setBounds(r.x, r.y + r.height - s - SASH, r.width, SASH);
                    side.setBounds(r.x, r.y + r.height - s, r.width, s);
                } else {
                    main.setBounds(r.x, r.y, Math.max(0, r.width - s - SASH), r.height);
                    sash.setBounds(r.x + r.width - s - SASH, r.y, SASH, r.height);
                    side.setBounds(r.x + r.width - s, r.y, s, r.height);
                }
            }
        });
    }

    private int clamp(int wanted, int total) {
        int upper = Math.max(min, Math.min(max, total - 200));
        return Math.max(min, Math.min(upper, wanted));
    }

    public Composite main() { return main; }
    public Composite side() { return side; }
    public boolean isSideVisible() { return sideVisible; }

    public void setSideVisible(boolean visible) {
        if (sideVisible == visible) return;
        sideVisible = visible;
        side.setVisible(visible);
        sash.setVisible(visible);
        layout(true);
    }
}
