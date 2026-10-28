package com.k3jdr.mxnoteone.ui;

import org.eclipse.swt.graphics.RGB;

/** The colours the ribbon and menus offer (AppSettings.swift Palette): text, highlight and table shading. */
final class Palettes {
    private Palettes() {}

    private static RGB c(double r, double g, double b) {
        return new RGB((int) Math.round(r * 255), (int) Math.round(g * 255), (int) Math.round(b * 255));
    }

    static final Object[][] TEXT = { { "Black", c(0, 0, 0) }, { "Dark Gray", c(0.35, 0.35, 0.35) }, { "Red", c(0.80, 0.10, 0.10) },
            { "Orange", c(0.90, 0.45, 0.05) }, { "Green", c(0.10, 0.55, 0.20) }, { "Blue", c(0.10, 0.35, 0.80) },
            { "Purple", c(0.45, 0.20, 0.70) }, { "Brown", c(0.50, 0.30, 0.15) } };

    static final Object[][] HIGHLIGHT = { { "Yellow", c(1.00, 0.95, 0.30) }, { "Green", c(0.60, 0.95, 0.50) },
            { "Turquoise", c(0.50, 0.92, 0.95) }, { "Pink", c(1.00, 0.65, 0.85) }, { "Blue", c(0.60, 0.78, 1.00) },
            { "Orange", c(1.00, 0.78, 0.45) }, { "Gray", c(0.82, 0.82, 0.82) } };

    static final Object[][] SHADING = { { "Light Gray", c(0.93, 0.93, 0.93) }, { "Gray", c(0.80, 0.80, 0.80) },
            { "Light Blue", c(0.85, 0.91, 0.99) }, { "Blue", c(0.62, 0.77, 0.95) }, { "Light Green", c(0.87, 0.95, 0.85) },
            { "Green", c(0.66, 0.85, 0.62) }, { "Light Yellow", c(1.00, 0.97, 0.80) }, { "Yellow", c(1.00, 0.90, 0.45) },
            { "Light Orange", c(1.00, 0.90, 0.80) }, { "Light Red", c(0.99, 0.84, 0.84) }, { "Light Purple", c(0.91, 0.87, 0.98) },
            { "Purple", c(0.47, 0.30, 0.70) }, { "Dark Blue", c(0.16, 0.31, 0.56) }, { "Dark Gray", c(0.30, 0.30, 0.32) } };
}
