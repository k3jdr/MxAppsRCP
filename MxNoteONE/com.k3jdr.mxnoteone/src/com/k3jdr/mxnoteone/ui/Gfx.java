package com.k3jdr.mxnoteone.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import com.k3jdr.mxnoteone.text.CharStyle;
import com.k3jdr.mxnoteone.text.Fonts;

/**
 * Fonts, colours and the small pictures of the sidebar and ribbon. Sizes in notes are the Mac's points, where a
 * point is a pixel of the page: a font of size s is drawn s pixels high (0.75 s Windows points) so text wraps in
 * a container as it does on the Mac.
 */
public final class Gfx {
    private Gfx() {}

    /** Windows points per Mac point (96 dpi against 72). */
    public static final double PT = 0.75;

    private static final Map<String, Font> FONTS = new HashMap<>();
    private static final Map<RGB, Color> COLORS = new HashMap<>();
    private static final Map<String, Image> IMAGES = new HashMap<>();
    private static Set<String> installed;

    public static final RGB BLACK = new RGB(0, 0, 0);
    public static final RGB LINK = new RGB(26, 92, 204);
    public static final RGB SELECTED = new RGB(0, 102, 204);
    public static final RGB GRIP = new RGB(222, 226, 232);
    public static final RGB FRAME = new RGB(190, 196, 206);
    public static final RGB GRAY_TEXT = new RGB(115, 115, 115);
    public static final RGB TABLE_BORDER = new RGB(163, 163, 163);
    public static final RGB CELL_SELECTION = new RGB(204, 224, 252);

    /** Tells the font mapping which fonts Windows has. */
    public static void init(Display display) {
        if (installed != null) return;
        installed = new HashSet<>();
        for (FontData fd : display.getFontList(null, true)) installed.add(fd.getName().toLowerCase(java.util.Locale.ROOT));
        Fonts.setInstalled(f -> installed.contains(f.toLowerCase(java.util.Locale.ROOT)));
        display.disposeExec(() -> {
            FONTS.values().forEach(Font::dispose);
            IMAGES.values().forEach(Image::dispose);
        });
    }

    public static Set<String> installedFamilies() {
        Set<String> out = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (FontData fd : Display.getDefault().getFontList(null, true)) if (!fd.getName().startsWith("@")) out.add(fd.getName());
        return out;
    }

    /** A font for a family (as notes name it) at a size in the Mac's points, scaled by the zoom. */
    public static Font font(String family, double size, boolean bold, boolean italic, double zoom) {
        String win = Fonts.windowsFamily(family);
        double h = Math.max(1, size * PT * zoom);
        String key = win + "|" + Math.round(h * 4) + "|" + bold + italic;
        return FONTS.computeIfAbsent(key, k -> {
            FontData fd = new FontData(win, 10, (bold ? SWT.BOLD : 0) | (italic ? SWT.ITALIC : 0));
            fd.height = (float) (Math.round(h * 4) / 4.0);
            return new Font(Display.getDefault(), fd);
        });
    }

    public static Font font(CharStyle s, double zoom) {
        double size = s.script() != 0 ? s.size() * 0.75 : s.size();
        return font(s.font(), size, s.bold(), s.italic(), zoom);
    }

    public static Font uiFont(int delta, int style) {
        String key = "ui|" + delta + "|" + style;
        return FONTS.computeIfAbsent(key, k -> {
            FontData[] data = Display.getDefault().getSystemFont().getFontData();
            for (FontData fd : data) {
                fd.setHeight(Math.max(6, fd.getHeight() + delta));
                fd.setStyle(style);
            }
            return new Font(Display.getDefault(), data);
        });
    }

    public static Color color(RGB rgb) {
        if (rgb == null) return null;
        return COLORS.computeIfAbsent(rgb, Color::new);
    }

    public static Color color(int r, int g, int b) { return color(new RGB(r, g, b)); }

    public static RGB rgb(double[] c) {
        return c == null ? null : new RGB(clamp(c[0]), clamp(c[1]), clamp(c[2]));
    }

    public static double[] rgba(RGB c) {
        return c == null ? null : new double[] { c.red / 255.0, c.green / 255.0, c.blue / 255.0, 1 };
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v * 255))); }

    // MARK: Pictures drawn in code

    private static Image image(String key, int w, int h, java.util.function.Consumer<GC> draw) {
        return IMAGES.computeIfAbsent(key, k -> {
            Display d = Display.getDefault();
            ImageData data = new ImageData(w, h, 32, new PaletteData(0xFF0000, 0xFF00, 0xFF));
            data.alphaData = new byte[w * h];
            Image mask = new Image(d, data);
            GC gc = new GC(mask);
            gc.setAntialias(SWT.ON);
            gc.setBackground(d.getSystemColor(SWT.COLOR_WHITE));
            gc.fillRectangle(0, 0, w, h);
            draw.accept(gc);
            gc.dispose();
            // Make white transparent.
            ImageData drawn = mask.getImageData();
            mask.dispose();
            drawn.alphaData = new byte[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    RGB p = drawn.palette.getRGB(drawn.getPixel(x, y));
                    boolean white = p.red > 250 && p.green > 250 && p.blue > 250;
                    drawn.alphaData[y * w + x] = (byte) (white ? 0 : 255);
                }
            }
            return new Image(d, drawn);
        });
    }

    /** A section's coloured tab. */
    public static Image swatch(RGB rgb) {
        return image("swatch" + rgb, 16, 16, gc -> {
            gc.setBackground(color(rgb));
            gc.fillRoundRectangle(2, 2, 12, 12, 4, 4);
        });
    }

    /** A notebook: a coloured book. */
    public static Image book(RGB rgb) {
        return image("book" + rgb, 16, 16, gc -> {
            gc.setBackground(color(rgb));
            gc.fillRoundRectangle(2, 1, 12, 14, 3, 3);
            gc.setBackground(color(250, 250, 250 - 1));
            gc.fillRectangle(5, 1, 1, 14);
        });
    }

    /** A section group: a folder. */
    public static Image folder() {
        return image("folder", 16, 16, gc -> {
            gc.setBackground(color(222, 178, 70));
            gc.fillRoundRectangle(1, 3, 7, 4, 2, 2);
            gc.fillRoundRectangle(1, 5, 14, 9, 2, 2);
        });
    }

    /** A locked section. */
    public static Image lock(RGB rgb) {
        return image("lock" + rgb, 16, 16, gc -> {
            gc.setBackground(color(rgb));
            gc.fillRoundRectangle(3, 7, 10, 8, 2, 2);
            gc.setForeground(color(rgb));
            gc.setLineWidth(2);
            gc.drawArc(5, 2, 6, 9, 0, 180);
        });
    }

    /** A colour swatch for colour menus (null: "no colour", a crossed box). */
    public static Image colorChip(RGB rgb) {
        return image("chip" + rgb, 14, 14, gc -> {
            gc.setForeground(color(120, 120, 120));
            if (rgb == null) {
                gc.drawRectangle(1, 1, 11, 11);
                gc.setForeground(color(200, 40, 40));
                gc.drawLine(2, 12, 12, 2);
            } else {
                gc.setBackground(color(rgb));
                gc.fillRectangle(1, 1, 12, 12);
                gc.drawRectangle(1, 1, 11, 11);
            }
        });
    }

    /** A ribbon button's picture: text drawn in a box (B, I, U…) with an optional colour bar under it. */
    public static Image glyph(String text, int style, RGB bar) {
        String key = "glyph" + text + style + bar;
        return image(key, 20, 20, gc -> {
            Font f = uiFont(1, style & (SWT.BOLD | SWT.ITALIC));
            gc.setFont(f);
            gc.setForeground(color(30, 30, 30));
            var ext = gc.textExtent(text);
            int x = (20 - ext.x) / 2, y = (bar != null ? 17 : 20) / 2 - ext.y / 2;
            gc.drawText(text, x, y, true);
            if ((style & SWT.UNDERLINE_SINGLE) != 0) gc.drawLine(x, y + ext.y - 2, x + ext.x, y + ext.y - 2);
            if ((style & SWT.UNDERLINE_ERROR) != 0) gc.drawLine(x - 1, y + ext.y / 2, x + ext.x + 1, y + ext.y / 2);
            if (bar != null) {
                gc.setBackground(color(bar));
                gc.fillRectangle(3, 16, 14, 3);
            }
        });
    }
}
