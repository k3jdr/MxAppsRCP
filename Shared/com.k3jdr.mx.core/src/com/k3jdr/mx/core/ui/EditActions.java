package com.k3jdr.mx.core.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

/**
 * The Edit menu's Cut, Copy, Paste and Select All: in a text field they act on its text; elsewhere (a table, a
 * canvas) on what the app's {@link Target} says.
 */
public final class EditActions {
    private EditActions() {}

    /** What the window's Edit commands do when the focus is not in a text field. Override what applies. */
    public interface Target {
        default boolean canCut() { return false; }
        default void cut() {}
        default boolean canCopy() { return false; }
        default void copy() {}
        default boolean canPaste() { return false; }
        default void paste() {}
        default boolean canSelectAll() { return false; }
        default void selectAll() {}
        default boolean canDelete() { return false; }
        default void delete() {}
    }

    /** Adds Undo-free standard items — Cut, Copy, Paste, Select All — to an Edit menu. */
    public static MenuBarBuilder.MenuBuilder addStandardItems(MenuBarBuilder.MenuBuilder edit, java.util.function.Supplier<Target> target) {
        edit.item("Cu&t", SWT.MOD1 | 'X', () -> cut(target.get()), () -> focusIsText() || t(target).canCut());
        edit.item("&Copy", SWT.MOD1 | 'C', () -> copy(target.get()), () -> focusIsText() || t(target).canCopy());
        edit.item("&Paste", SWT.MOD1 | 'V', () -> paste(target.get()), () -> focusIsText() || t(target).canPaste());
        edit.item("Select &All", SWT.MOD1 | 'A', () -> selectAll(target.get()), () -> focusIsText() || t(target).canSelectAll());
        return edit;
    }

    private static Target t(java.util.function.Supplier<Target> s) {
        Target t = s.get();
        return t != null ? t : new Target() {};
    }

    public static boolean focusIsText() {
        Control c = Display.getCurrent() == null ? null : Display.getCurrent().getFocusControl();
        return c instanceof Text || c instanceof StyledText || c instanceof Combo || c instanceof Spinner;
    }

    public static void cut(Target fallback) {
        Control c = Display.getCurrent().getFocusControl();
        if (c instanceof Text t) t.cut();
        else if (c instanceof StyledText t) t.cut();
        else if (c instanceof Combo t) t.cut();
        else if (fallback != null) fallback.cut();
    }

    public static void copy(Target fallback) {
        Control c = Display.getCurrent().getFocusControl();
        if (c instanceof Text t) t.copy();
        else if (c instanceof StyledText t) t.copy();
        else if (c instanceof Combo t) t.copy();
        else if (fallback != null) fallback.copy();
    }

    public static void paste(Target fallback) {
        Control c = Display.getCurrent().getFocusControl();
        if (c instanceof Text t) t.paste();
        else if (c instanceof StyledText t) t.paste();
        else if (c instanceof Combo t) t.paste();
        else if (fallback != null) fallback.paste();
    }

    public static void selectAll(Target fallback) {
        Control c = Display.getCurrent().getFocusControl();
        if (c instanceof Text t) t.selectAll();
        else if (c instanceof StyledText t) t.selectAll();
        else if (c instanceof Combo t) t.setSelection(new org.eclipse.swt.graphics.Point(0, t.getText().length()));
        else if (fallback != null) fallback.selectAll();
    }
}
