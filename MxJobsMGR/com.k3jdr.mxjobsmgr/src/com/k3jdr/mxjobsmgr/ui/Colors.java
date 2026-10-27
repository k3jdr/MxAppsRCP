package com.k3jdr.mxjobsmgr.ui;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;

import com.k3jdr.mxjobsmgr.model.Layer;

/** SWT colours from 0xRRGGBB, cached (colours made from RGB need no disposing). */
public final class Colors {
    private Colors() {}

    private static final Map<Integer, Color> CACHE = new HashMap<>();

    public static final int ACCENT = 0x0A64D2;
    public static final int TEXT = 0x1D1D1F;
    public static final int SECONDARY = 0x6E6E73;
    public static final int TERTIARY = 0xA0A0A6;
    public static final int WHITE = 0xFFFFFF;
    public static final int BREAKPOINT = 0x1E6FE0;

    public static Color of(int rgb) {
        return CACHE.computeIfAbsent(rgb, k -> new Color(new RGB((k >> 16) & 0xFF, (k >> 8) & 0xFF, k & 0xFF)));
    }

    /** {@code rgb} at {@code alpha} over {@code base} (an opaque colour for a translucent one). */
    public static int blend(int rgb, int base, double alpha) {
        int r = (int) Math.round(((rgb >> 16) & 0xFF) * alpha + ((base >> 16) & 0xFF) * (1 - alpha));
        int g = (int) Math.round(((rgb >> 8) & 0xFF) * alpha + ((base >> 8) & 0xFF) * (1 - alpha));
        int b = (int) Math.round((rgb & 0xFF) * alpha + (base & 0xFF) * (1 - alpha));
        return (r << 16) | (g << 8) | b;
    }

    public static Color tint(int rgb, double alpha) {
        return of(blend(rgb, WHITE, alpha));
    }

    public static Color layer(Layer layer) {
        return of(layer.rgb);
    }
}
