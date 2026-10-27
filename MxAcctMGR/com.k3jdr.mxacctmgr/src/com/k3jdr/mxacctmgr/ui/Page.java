package com.k3jdr.mxacctmgr.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Screen;
import com.k3jdr.mxacctmgr.Settings;
import com.k3jdr.mxacctmgr.text.Lessons;

/**
 * A screen (StepPage.swift): its title, the line under it, the "Why this step" lesson, the step's controls and results,
 * and Previous / Next along the guide — in a column that scrolls as a document, or (for a page with panes of its own)
 * filling the area. Subclasses build their content in {@link #build(Composite)}.
 */
public abstract class Page {
    private static final String SCROLLER = "mx.page.scroller";

    protected final App app;
    protected final Screen screen;
    private Composite root;
    private ScrolledComposite scroller;
    private Composite column;

    protected Page(App app) {
        this.app = app;
        this.screen = app.screen();
    }

    /** The content: a one-column grid to fill. */
    protected abstract void build(Composite body);

    /** True for a page with its own panes (it fills the area; nothing scrolls around it). */
    protected boolean fills() { return false; }

    /** The page title (the screen's, unless the page says otherwise). */
    protected String title() { return app.title(screen, true); }

    /** The lesson and the line under the title (Lessons, by the screen's key). */
    protected Lessons.Lesson lesson() { return Lessons.of(app.lessonKey(screen)); }

    /** Whether the page shows Previous / Next at its foot. */
    protected boolean footer() { return true; }

    /** Called when the page goes away (timers, listeners). */
    public void dispose() {}

    /** Told when the app's state changed (accounts loaded, a result came in, the selected account changed). */
    public void stateChanged() {}

    /** What Edit ▸ Copy / Select All do when the focus is not in a text field: the focused table's rows. */
    public EditActions.Target editTarget() {
        return new EditActions.Target() {
            Table focused() {
                Control c = Display.getCurrent().getFocusControl();
                return c instanceof Table t && !t.isDisposed() ? t : null;
            }

            @Override public boolean canCopy() { Table t = focused(); return t != null && t.getSelectionCount() > 0; }
            @Override public void copy() { Table t = focused(); if (t != null) TableCopy.copySelected(t); }
            @Override public boolean canSelectAll() { Table t = focused(); return t != null && (t.getStyle() & SWT.MULTI) != 0; }
            @Override public void selectAll() { Table t = focused(); if (t != null) t.selectAll(); }
        };
    }

    public Control root() { return root; }

    /** Builds the page into {@code host} (a FillLayout). */
    public final void create(Composite host) {
        if (fills()) {
            root = new Composite(host, SWT.NONE);
            root.setLayout(Ui.grid(1, 16, 10));
            header(root);
            Composite body = new Composite(root, SWT.NONE);
            body.setLayout(Ui.grid(1, 0, 10));
            body.setLayoutData(Ui.fill());
            build(body);
            return;
        }
        scroller = new ScrolledComposite(host, SWT.V_SCROLL | SWT.H_SCROLL);
        root = scroller;
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        scroller.getVerticalBar().setIncrement(24);
        column = new Composite(scroller, SWT.NONE);
        column.setLayout(Ui.grid(1, 20, 14));
        scroller.setContent(column);
        scroller.setData(SCROLLER, (Runnable) this::relayout);
        header(column);
        Composite body = new Composite(column, SWT.NONE);
        body.setLayout(Ui.grid(1, 0, 14));
        body.setLayoutData(Ui.fillH());
        build(body);
        if (footer()) Parts.footer(column, app);
        scroller.addListener(SWT.Resize, e -> relayout());
        PageText.page(column);
    }

    private void header(Composite parent) {
        Text t = Parts.value(parent, title());
        t.setFont(Settings.pageTitle());
        t.setLayoutData(Ui.fillH());
        Lessons.Lesson lesson = lesson();
        if (lesson != null && Settings.showsIntroductions() && lesson.subtitle() != null && !lesson.subtitle().isEmpty()) {
            Text sub = Parts.text(parent, lesson.subtitle(), Settings.pageSubtitle());
            sub.setForeground(Ui.SECONDARY);
        }
        beforeLesson(parent);
        if (lesson != null && Settings.showsLessons() && lesson.points() != null && !lesson.points().isEmpty()) {
            Parts.lessonCard(parent, app, screen.id, lesson);
        }
    }

    /** Above the lesson: the Investments steps put the My Portfolio bar here. */
    protected void beforeLesson(Composite parent) {}

    /** Lays the page out again after its content changed (data loaded, a section folded). */
    public void relayout() {
        if (scroller == null) {
            if (root != null && !root.isDisposed()) root.layout(true, true);
            return;
        }
        if (scroller.isDisposed()) return;
        Rectangle area = scroller.getClientArea();
        int width = Math.max(area.width, 760);
        column.layout(true, true);
        Point size = column.computeSize(width, SWT.DEFAULT);
        scroller.setMinSize(width, size.y);
        column.layout(true, true);
    }

    /** Lays out the page that holds {@code c} again. */
    public static void relayout(Control c) {
        for (Control at = c; at != null; at = at.getParent()) {
            if (at.getData(SCROLLER) instanceof Runnable r) {
                r.run();
                return;
            }
            if (at.getParent() == null || at instanceof org.eclipse.swt.widgets.Shell) {
                if (at instanceof Composite comp) comp.layout(true, true);
                return;
            }
        }
    }

    /** Disposes a composite's children (to rebuild a section). */
    public static void clear(Composite c) {
        for (Control child : c.getChildren()) child.dispose();
    }

    /** A grid cell that spans {@code n} columns and fills the width. */
    public static GridData span(int n) { return Ui.span(n); }
}
