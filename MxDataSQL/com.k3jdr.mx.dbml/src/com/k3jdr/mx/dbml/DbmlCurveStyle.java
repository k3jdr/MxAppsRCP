package com.k3jdr.mx.dbml;

/** How a relationship's line runs between its tables. */
public enum DbmlCurveStyle {
    /** A smooth S-curve from the tables' positions. */
    AUTOMATIC("automatic", "Automatic"),
    STRAIGHT("straight", "Straight Line"),
    /** One control point (kept as an offset from the middle of the ends). */
    QUADRATIC("quadratic", "Quadratic Bézier"),
    /** Two control points (kept as offsets from the start and from the end). */
    CUBIC("cubic", "Cubic Bézier");

    private final String raw;
    private final String title;

    DbmlCurveStyle(String raw, String title) {
        this.raw = raw;
        this.title = title;
    }

    /** The name in the layout comment ("automatic", "straight", "quadratic", "cubic"). */
    public String raw() { return raw; }

    public String title() { return title; }

    public boolean isBezier() { return this == QUADRATIC || this == CUBIC; }

    public static DbmlCurveStyle fromRaw(String s) {
        for (DbmlCurveStyle v : values()) if (v.raw.equals(s)) return v;
        return null;
    }
}
