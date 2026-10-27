package com.k3jdr.mx.core.ui;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/** Layout, font and colour helpers shared by the apps' screens. */
public final class Ui {
    private Ui() {}

    // Colours (SWT colours made from RGB need no disposing).
    public static final Color ACCENT = new Color(new RGB(0, 102, 204));
    public static final Color ACCENT_LIGHT = new Color(new RGB(222, 236, 252));
    public static final Color SIDEBAR = new Color(new RGB(243, 244, 247));
    public static final Color PANEL = new Color(new RGB(248, 248, 250));
    public static final Color BORDER = new Color(new RGB(214, 216, 222));
    public static final Color SECONDARY = new Color(new RGB(110, 112, 120));
    public static final Color GREEN = new Color(new RGB(30, 140, 60));
    public static final Color ORANGE = new Color(new RGB(205, 120, 0));
    public static final Color RED = new Color(new RGB(200, 40, 40));
    public static final Color YELLOW_NOTE = new Color(new RGB(255, 249, 219));
    public static final Color EDITED = new Color(new RGB(255, 243, 176));
    public static final Color WHITE = new Color(new RGB(255, 255, 255));

    private static final Map<String, Font> FONTS = new HashMap<>();

    /** The default font {@code delta} points larger, with {@code style} (SWT.BOLD, SWT.ITALIC). Cached per display. */
    public static Font font(int delta, int style) {
        Display display = Display.getDefault();
        String key = delta + ":" + style;
        return FONTS.computeIfAbsent(key, k -> {
            FontData[] data = display.getSystemFont().getFontData();
            for (FontData fd : data) {
                fd.setHeight(Math.max(6, fd.getHeight() + delta));
                fd.setStyle(style);
            }
            Font f = new Font(display, data);
            display.disposeExec(f::dispose);
            return f;
        });
    }

    public static Font bold() { return font(0, SWT.BOLD); }
    public static Font heading() { return font(5, SWT.BOLD); }
    public static Font subheading() { return font(2, SWT.BOLD); }
    public static Font small() { return font(-1, SWT.NORMAL); }

    /** A fixed-width font of the default size (Consolas on Windows). */
    public static Font mono() {
        return FONTS.computeIfAbsent("mono", k -> {
            Display display = Display.getDefault();
            int h = display.getSystemFont().getFontData()[0].getHeight();
            Font f = new Font(display, "Consolas", h + 1, SWT.NORMAL);
            display.disposeExec(f::dispose);
            return f;
        });
    }

    public static GridLayout grid(int columns, int margin, int spacing) {
        GridLayout l = new GridLayout(columns, false);
        l.marginWidth = l.marginHeight = margin;
        l.horizontalSpacing = l.verticalSpacing = spacing;
        return l;
    }

    public static GridData fill() { return new GridData(SWT.FILL, SWT.FILL, true, true); }
    public static GridData fillH() { return new GridData(SWT.FILL, SWT.CENTER, true, false); }
    public static GridData span(int columns) {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalSpan = columns;
        return gd;
    }

    /** A label whose text is shown as given ('&amp;' is not a mnemonic). */
    public static Label label(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(escape(text));
        return l;
    }

    public static Label label(Composite parent, String text, org.eclipse.swt.graphics.Font font) {
        Label l = label(parent, text);
        l.setFont(font);
        return l;
    }

    /** A wrapping label that fills the width it is given. */
    public static Label wrap(Composite parent, String text) {
        Label l = new Label(parent, SWT.WRAP);
        l.setText(escape(text));
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.widthHint = 200;
        l.setLayoutData(gd);
        return l;
    }

    /** Read-only text that can be selected and copied, as every screen's text can on macOS. */
    public static Text selectable(Composite parent, String text, int style) {
        Text t = new Text(parent, SWT.READ_ONLY | SWT.WRAP | style);
        t.setText(text == null ? "" : text);
        t.setBackground(parent.getBackground());
        return t;
    }

    public static Button button(Composite parent, String text, Runnable action) {
        Button b = new Button(parent, SWT.PUSH);
        b.setText(text);
        b.addListener(SWT.Selection, e -> action.run());
        return b;
    }

    public static Button check(Composite parent, String text, boolean selected, java.util.function.Consumer<Boolean> changed) {
        Button b = new Button(parent, SWT.CHECK);
        b.setText(text);
        b.setSelection(selected);
        b.addListener(SWT.Selection, e -> changed.accept(b.getSelection()));
        return b;
    }

    /** Text for a Label or Button: '&amp;' doubled so it shows. */
    public static String escape(String text) {
        return text == null ? "" : text.replace("&", "&&");
    }

    /** Runs on the UI thread later, unless {@code owner} is gone by then. */
    public static void async(Control owner, Runnable r) {
        if (owner == null || owner.isDisposed()) return;
        owner.getDisplay().asyncExec(() -> {
            if (!owner.isDisposed()) r.run();
        });
    }

    public static void setVisible(Control c, boolean visible) {
        c.setVisible(visible);
        if (c.getLayoutData() instanceof GridData gd) gd.exclude = !visible;
    }

    /** Recursively sets the background of a composite and its children (labels, check boxes) to {@code color}. */
    public static void background(Control c, Color color) {
        c.setBackground(color);
        if (c instanceof Composite comp && !(c instanceof org.eclipse.swt.widgets.Table) && !(c instanceof org.eclipse.swt.widgets.Tree)) {
            for (Control child : comp.getChildren()) {
                if (child instanceof Text || child instanceof org.eclipse.swt.widgets.Combo) continue;
                background(child, color);
            }
        }
    }
}
