package com.k3jdr.mx.core.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Sash;

import com.k3jdr.mx.core.prefs.Prefs;

/**
 * Two panes side by side with a divider to drag (MxAppsIOS/Shared/SplitPanes.swift): the leading pane has the
 * width the divider sets — remembered per {@code id}, kept within its range and to what leaves the trailing pane its
 * minimum — and the trailing pane takes the rest, also when the window is resized.
 *
 * <pre>
 * SplitPanes split = new SplitPanes(parent, "securities", 260, 600, 340, 400);
 * buildList(split.leading());
 * buildDetail(split.trailing());
 * </pre>
 *
 * With {@code vertical} the leading pane is the top one and the sizes are heights.
 */
public final class SplitPanes extends Composite {
    private static final int SASH = 5;
    private final Composite leading;
    private final Composite trailing;
    private final Sash sash;
    private final boolean vertical;
    private final int min, max, trailingMin;
    private final Prefs prefs = Prefs.of("com.k3jdr.mx.core.split");
    private final String key;
    private int size;

    public SplitPanes(Composite parent, String id, int min, int max, int ideal, int trailingMin) {
        this(parent, id, min, max, ideal, trailingMin, false);
    }

    public SplitPanes(Composite parent, String id, int min, int max, int ideal, int trailingMin, boolean vertical) {
        super(parent, SWT.NONE);
        this.vertical = vertical;
        this.min = min;
        this.max = max;
        this.trailingMin = trailingMin;
        this.key = id;
        this.size = prefs.getInt(key, ideal);
        leading = new Composite(this, SWT.NONE);
        trailing = new Composite(this, SWT.NONE);
        sash = new Sash(this, vertical ? SWT.HORIZONTAL : SWT.VERTICAL);
        sash.addListener(SWT.Selection, e -> {
            int total = vertical ? getClientArea().height : getClientArea().width;
            int wanted = vertical ? e.y : e.x;
            size = clamp(wanted, total);
            if (e.detail != SWT.DRAG) prefs.putInt(key, size);
            layout(true);
        });
        sash.addListener(SWT.MouseDoubleClick, e -> {
            size = clamp(ideal, vertical ? getClientArea().height : getClientArea().width);
            prefs.putInt(key, size);
            layout(true);
        });
        setLayout(new Layout() {
            @Override
            protected Point computeSize(Composite c, int wHint, int hHint, boolean flush) {
                Point a = leading.computeSize(SWT.DEFAULT, SWT.DEFAULT, flush);
                Point b = trailing.computeSize(SWT.DEFAULT, SWT.DEFAULT, flush);
                return vertical ? new Point(Math.max(a.x, b.x), size + SASH + trailingMin)
                        : new Point(size + SASH + trailingMin, Math.max(a.y, b.y));
            }

            @Override
            protected void layout(Composite c, boolean flush) {
                Rectangle r = getClientArea();
                int total = vertical ? r.height : r.width;
                int s = clamp(size, total);
                if (vertical) {
                    leading.setBounds(r.x, r.y, r.width, s);
                    sash.setBounds(r.x, r.y + s, r.width, SASH);
                    trailing.setBounds(r.x, r.y + s + SASH, r.width, Math.max(0, r.height - s - SASH));
                } else {
                    leading.setBounds(r.x, r.y, s, r.height);
                    sash.setBounds(r.x + s, r.y, SASH, r.height);
                    trailing.setBounds(r.x + s + SASH, r.y, Math.max(0, r.width - s - SASH), r.height);
                }
            }
        });
    }

    private int clamp(int wanted, int total) {
        int upper = Math.max(min, Math.min(max, total - trailingMin - SASH));
        return Math.max(min, Math.min(upper, wanted));
    }

    public Composite leading() { return leading; }
    public Composite trailing() { return trailing; }
    public boolean isVertical() { return vertical; }
}
