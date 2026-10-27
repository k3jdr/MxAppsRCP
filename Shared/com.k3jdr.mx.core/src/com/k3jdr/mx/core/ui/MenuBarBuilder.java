package com.k3jdr.mx.core.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.jface.action.LegacyActionTools;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * The window's menu bar (the macOS apps' CommandMenus), built in code:
 *
 * <pre>
 * MenuBarBuilder mb = new MenuBarBuilder(shell);
 * mb.menu("&amp;File")
 *     .item("&amp;Open…", SWT.MOD1 | 'O', this::open)
 *     .item("&amp;Save", SWT.MOD1 | 'S', this::save, () -&gt; dirty)
 *     .separator()
 *     .dynamic("Open &amp;Recent", m -&gt; recent.forEach(f -&gt; m.item(f, () -&gt; open(f))))
 *     .check("Show &amp;Hidden Files", SWT.MOD1 | SWT.SHIFT | '.', () -&gt; hidden, v -&gt; setHidden(v));
 * mb.install();
 * </pre>
 *
 * Enablement and check marks are refreshed each time a menu opens. Accelerators work while the window is active;
 * the Edit menu's standard items delegate to the focused text field (see {@link EditActions}). Don't give
 * unmodified keys other than function keys (Delete, Space, Return) as accelerators: they would be taken from the
 * text fields. Handle those in the widget's key listener.
 */
public final class MenuBarBuilder {
    private final Shell shell;
    private final Menu bar;

    public MenuBarBuilder(Shell shell) {
        this.shell = shell;
        this.bar = new Menu(shell, SWT.BAR);
    }

    /** A top-level menu; {@code end()} on it is optional. */
    public MenuBuilder menu(String label) {
        MenuItem header = new MenuItem(bar, SWT.CASCADE);
        header.setText(label);
        Menu menu = new Menu(shell, SWT.DROP_DOWN);
        header.setMenu(menu);
        return new MenuBuilder(null, menu);
    }

    public Menu install() {
        shell.setMenuBar(bar);
        return bar;
    }

    public Menu bar() { return bar; }

    /** The text shown for an accelerator, e.g. "Ctrl+Shift+N". */
    public static String acceleratorText(int accelerator) {
        return accelerator == 0 ? "" : LegacyActionTools.convertAccelerator(accelerator);
    }

    /** Builds one drop-down menu (or a context menu: {@code new MenuBuilder(null, control.getMenu())}). */
    public static final class MenuBuilder {
        private final MenuBuilder parent;
        private final Menu menu;
        private final List<Runnable> refreshers = new ArrayList<>();

        public MenuBuilder(MenuBuilder parent, Menu menu) {
            this.parent = parent;
            this.menu = menu;
            menu.addListener(SWT.Show, e -> refreshers.forEach(Runnable::run));
        }

        public Menu swtMenu() { return menu; }

        public MenuBuilder item(String label, Runnable action) {
            return item(label, 0, action, null);
        }

        public MenuBuilder item(String label, int accelerator, Runnable action) {
            return item(label, accelerator, action, null);
        }

        /** An item, enabled while {@code enabled} answers true (always when null). */
        public MenuBuilder item(String label, int accelerator, Runnable action, BooleanSupplier enabled) {
            MenuItem mi = new MenuItem(menu, SWT.PUSH);
            setText(mi, label, accelerator);
            mi.addListener(SWT.Selection, e -> safely(mi, action));
            if (enabled != null) {
                refreshers.add(() -> {
                    if (!mi.isDisposed()) mi.setEnabled(enabled.getAsBoolean());
                });
            }
            return this;
        }

        /** A check item: ticked while {@code selected} answers true; choosing it calls {@code toggle} with the new state. */
        public MenuBuilder check(String label, int accelerator, BooleanSupplier selected, Consumer<Boolean> toggle) {
            MenuItem mi = new MenuItem(menu, SWT.CHECK);
            setText(mi, label, accelerator);
            mi.addListener(SWT.Selection, e -> safely(mi, () -> toggle.accept(mi.getSelection())));
            refreshers.add(() -> {
                if (!mi.isDisposed()) mi.setSelection(selected.getAsBoolean());
            });
            mi.setSelection(selected.getAsBoolean());
            return this;
        }

        /** A radio item: shown chosen while {@code selected} answers true. */
        public MenuBuilder radio(String label, BooleanSupplier selected, Runnable choose) {
            MenuItem mi = new MenuItem(menu, SWT.RADIO);
            setText(mi, label, 0);
            mi.addListener(SWT.Selection, e -> {
                if (mi.getSelection()) safely(mi, choose);
            });
            refreshers.add(() -> {
                if (!mi.isDisposed()) mi.setSelection(selected.getAsBoolean());
            });
            return this;
        }

        public MenuBuilder separator() {
            new MenuItem(menu, SWT.SEPARATOR);
            return this;
        }

        /** A submenu; call {@code end()} on the returned builder to come back to this one. */
        public MenuBuilder submenu(String label) {
            MenuItem mi = new MenuItem(menu, SWT.CASCADE);
            mi.setText(label);
            Menu sub = new Menu(menu);
            mi.setMenu(sub);
            return new MenuBuilder(this, sub);
        }

        /** A submenu filled anew each time it opens (Open Recent, the databases, History). */
        public MenuBuilder dynamic(String label, Consumer<MenuBuilder> filler) {
            MenuItem mi = new MenuItem(menu, SWT.CASCADE);
            mi.setText(label);
            Menu sub = new Menu(menu);
            mi.setMenu(sub);
            sub.addListener(SWT.Show, e -> {
                for (MenuItem old : sub.getItems()) old.dispose();
                MenuBuilder b = new MenuBuilder(this, sub);
                filler.accept(b);
                b.refreshers.forEach(Runnable::run);
                if (sub.getItemCount() == 0) {
                    MenuItem none = new MenuItem(sub, SWT.PUSH);
                    none.setText("(none)");
                    none.setEnabled(false);
                }
            });
            return this;
        }

        /** The submenu's parent builder (or this one for a top-level menu). */
        public MenuBuilder end() {
            return parent != null ? parent : this;
        }

        private static void setText(MenuItem mi, String label, int accelerator) {
            if (accelerator != 0) {
                mi.setText(label + "\t" + acceleratorText(accelerator));
                mi.setAccelerator(accelerator);
            } else {
                mi.setText(label);
            }
        }

        private static void safely(MenuItem mi, Runnable action) {
            try {
                action.run();
            } catch (RuntimeException ex) {
                Shell s = mi.getDisplay().getActiveShell();
                ErrorDialog.show(s != null ? s : mi.getParent().getShell(), "Something went wrong", ex);
            }
        }
    }
}
