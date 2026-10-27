package com.k3jdr.mx.core.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.prefs.Prefs;

/**
 * Quick Help, as in every MxAppsIOS app (Shared/QuickHelp): a panel that describes what's under the pointer — a
 * button, a list, a table cell, a node — in a sentence or two, with its shortcut. Over nothing it can describe it
 * tells about what's selected, and then about the screen.
 *
 * <p>Tell it about controls with {@link #help(Control, String, String, String)} (static text) or
 * {@link #provider(Control, Provider)} (text that depends on where the pointer is, e.g. a table's row), and about
 * the window with {@link #setSelection(Supplier)} and {@link #setScreen(Supplier)}. Put the docked panel where the
 * app wants it with {@link #createDockedPanel(Composite)} (its parent must use a GridLayout). View ▸ Quick Help
 * (Ctrl+Alt+/) shows or hides it; View ▸ Float Quick Help moves it into a small window of its own; closing that
 * window docks it again. Both are remembered.
 */
public final class QuickHelp {
    public static final String KEY = "mx.quickhelp";
    /** View ▸ Quick Help's accelerator: Ctrl+Alt+/ (⌥⌘/ on macOS). */
    public static final int TOGGLE_ACCELERATOR = SWT.MOD1 | SWT.MOD3 | '/';

    /** What to say about the place the pointer is at, in the control's coordinates; null for nothing. */
    @FunctionalInterface
    public interface Provider {
        QuickHelpItem describe(Control control, Point point);
    }

    private static QuickHelp instance;

    public static QuickHelp get() {
        if (instance == null) instance = new QuickHelp(Display.getDefault());
        return instance;
    }

    /** Static help for a control: its title, a sentence or two, and its shortcut (null for none). */
    public static <C extends Control> C help(C control, String title, String detail, String shortcut) {
        control.setData(KEY, new QuickHelpItem(title, detail, shortcut));
        return control;
    }

    public static <C extends Control> C help(C control, String title, String detail) {
        return help(control, title, detail, null);
    }

    /** Help that depends on where in the control the pointer is. */
    public static <C extends Control> C provider(C control, Provider provider) {
        control.setData(KEY, provider);
        return control;
    }

    private final Display display;
    private final Prefs prefs = Prefs.of("com.k3jdr.mx.core.quickhelp");
    private final List<Panel> panels = new ArrayList<>();
    private final List<Runnable> stateListeners = new ArrayList<>();
    private Supplier<QuickHelpItem> selection = () -> null;
    private Supplier<QuickHelpItem> screen = () -> null;
    private QuickHelpItem shown;
    private Panel docked;
    private Shell floatShell;
    private boolean visible;
    private boolean floating;

    private QuickHelp(Display display) {
        this.display = display;
        this.visible = prefs.getBoolean("visible", true);
        this.floating = prefs.getBoolean("floating", false);
        Listener tracker = e -> update();
        display.addFilter(SWT.MouseMove, tracker);
        display.addFilter(SWT.MouseEnter, tracker);
        display.addFilter(SWT.FocusIn, tracker);
        display.addFilter(SWT.Selection, e -> display.asyncExec(this::update));
    }

    /** What's selected in the window, said when the pointer is over nothing with help of its own. */
    public void setSelection(Supplier<QuickHelpItem> selection) {
        this.selection = selection == null ? () -> null : selection;
        display.asyncExec(this::update);
    }

    /** The screen the window shows, said last. */
    public void setScreen(Supplier<QuickHelpItem> screen) {
        this.screen = screen == null ? () -> null : screen;
        display.asyncExec(this::update);
    }

    public boolean isVisible() { return visible; }
    public boolean isFloating() { return floating; }

    public void setVisible(boolean v) {
        visible = v;
        prefs.putBoolean("visible", v);
        sync();
    }

    public void setFloating(boolean f) {
        floating = f;
        prefs.putBoolean("floating", f);
        sync();
    }

    public void toggleVisible() { setVisible(!visible); }
    public void toggleFloating() { setFloating(!floating); }

    /** Told when the panel is shown, hidden, floated or docked (to relayout the window). */
    public void addStateListener(Runnable r) { stateListeners.add(r); }

    /**
     * Adds View ▸ Quick Help and View ▸ Float Quick Help to a menu, as the shared QuickHelpCommands do.
     */
    public void addMenuItems(MenuBarBuilder.MenuBuilder view) {
        view.check("&Quick Help", TOGGLE_ACCELERATOR, this::isVisible, v -> setVisible(v));
        view.check("&Float Quick Help", 0, this::isFloating, v -> setFloating(v));
    }

    /** The docked panel, created once per window; it hides itself while Quick Help is off or floating. */
    public Composite createDockedPanel(Composite parent) {
        docked = new Panel(parent, true);
        GridData gd = new GridData(SWT.FILL, SWT.END, true, false);
        gd.heightHint = 118;
        docked.root.setLayoutData(gd);
        docked.root.addListener(SWT.Dispose, e -> {
            panels.remove(docked);
            docked = null;
        });
        sync();
        return docked.root;
    }

    private void sync() {
        boolean showDocked = visible && !floating;
        if (docked != null && !docked.root.isDisposed()) {
            Ui.setVisible(docked.root, showDocked);
            docked.root.getParent().layout(true, true);
        }
        if (visible && floating) openFloat();
        else closeFloat();
        stateListeners.forEach(Runnable::run);
        shown = null;
        update();
    }

    private void openFloat() {
        if (floatShell != null && !floatShell.isDisposed()) return;
        Shell owner = docked != null && !docked.root.isDisposed() ? docked.root.getShell() : display.getActiveShell();
        floatShell = owner != null ? new Shell(owner, SWT.TOOL | SWT.TITLE | SWT.CLOSE | SWT.RESIZE)
                : new Shell(display, SWT.TOOL | SWT.TITLE | SWT.CLOSE | SWT.RESIZE);
        floatShell.setText("Quick Help");
        floatShell.setLayout(new FillLayout());
        new Panel(floatShell, false);
        Rectangle saved = new Rectangle(prefs.getInt("x", -1), prefs.getInt("y", -1), prefs.getInt("w", 320), prefs.getInt("h", 150));
        if (saved.x < 0 && owner != null) {
            Rectangle ob = owner.getBounds();
            saved.x = ob.x + ob.width - saved.width - 40;
            saved.y = ob.y + ob.height - saved.height - 60;
        }
        floatShell.setBounds(saved);
        floatShell.addListener(SWT.Close, e -> {
            Rectangle b = floatShell.getBounds();
            prefs.putInt("x", b.x);
            prefs.putInt("y", b.y);
            prefs.putInt("w", b.width);
            prefs.putInt("h", b.height);
            if (!display.isDisposed() && floating) display.asyncExec(() -> setFloating(false));
        });
        floatShell.open();
    }

    private void closeFloat() {
        if (floatShell != null && !floatShell.isDisposed()) {
            Shell s = floatShell;
            floatShell = null;
            s.close();
        }
    }

    /** Works out what to say now and shows it. */
    public void update() {
        if (panels.isEmpty() || !visible) return;
        QuickHelpItem item = underPointer();
        if (item == null) item = selection.get();
        if (item == null) item = screen.get();
        if (item == null) item = new QuickHelpItem("Quick Help", "Rest the pointer on something to learn what it does.", null);
        if (Objects.equals(item, shown)) return;
        shown = item;
        for (Panel p : panels) p.show(item);
    }

    private QuickHelpItem underPointer() {
        Control c = display.getCursorControl();
        if (c == null) return null;
        for (Panel p : panels) {
            if (isInside(c, p.root)) return shown;     // keep what it says while the pointer reads it
        }
        Point cursor = display.getCursorLocation();
        for (Control at = c; at != null; at = at.getParent()) {
            Object data = at.getData(KEY);
            if (data instanceof QuickHelpItem item) return item;
            if (data instanceof Provider provider) {
                QuickHelpItem item = provider.describe(at, at.toControl(cursor));
                if (item != null) return item;
            }
        }
        return null;
    }

    private static boolean isInside(Control c, Control ancestor) {
        for (Control at = c; at != null; at = at.getParent()) if (at == ancestor) return true;
        return false;
    }

    /** The panel's content: title, detail and shortcut, with Float/Dock and Close buttons. */
    private final class Panel {
        final Composite root;
        final Label title, detail, shortcut;

        Panel(Composite parent, boolean dockedPanel) {
            root = new Composite(parent, SWT.BORDER);
            root.setLayout(Ui.grid(3, 6, 2));
            root.setBackground(Ui.PANEL);
            title = new Label(root, SWT.NONE);
            title.setFont(Ui.bold());
            title.setLayoutData(Ui.fillH());
            Button flt = new Button(root, SWT.FLAT | SWT.PUSH);
            flt.setText(dockedPanel ? "⧉" : "⊟");
            flt.setToolTipText(dockedPanel ? "Float Quick Help in a window of its own" : "Dock Quick Help in the window");
            flt.addListener(SWT.Selection, e -> setFloating(dockedPanel));
            Button close = new Button(root, SWT.FLAT | SWT.PUSH);
            close.setText("✕");
            close.setToolTipText("Hide Quick Help (View ▸ Quick Help, Ctrl+Alt+/)");
            close.addListener(SWT.Selection, e -> setVisible(false));
            detail = new Label(root, SWT.WRAP);
            GridData dg = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
            dg.widthHint = 120;
            detail.setLayoutData(dg);
            shortcut = new Label(root, SWT.NONE);
            shortcut.setForeground(Ui.SECONDARY);
            shortcut.setLayoutData(Ui.span(3));
            for (Control c : root.getChildren()) if (!(c instanceof Button)) c.setBackground(Ui.PANEL);
            panels.add(this);
            root.addListener(SWT.Dispose, e -> panels.remove(this));
            if (shown != null) show(shown);
        }

        void show(QuickHelpItem item) {
            if (root.isDisposed()) return;
            title.setText(Ui.escape(item.title()));
            detail.setText(Ui.escape(item.detail()));
            shortcut.setText(item.shortcut() == null ? "" : Ui.escape(item.shortcut()));
            root.layout(true);
        }
    }
}
