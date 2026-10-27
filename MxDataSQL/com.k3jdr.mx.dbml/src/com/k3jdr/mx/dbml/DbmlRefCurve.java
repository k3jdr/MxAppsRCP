package com.k3jdr.mx.dbml;

/**
 * A relationship's line as it was shaped (nothing set: the diagram's way of drawing lines, from automatic sides).
 *
 * @param style    its own style, or null for the diagram's
 * @param c1       quadratic: the control point's offset from the middle of the ends; cubic: the first control point's
 *                 offset from the start
 * @param c2       cubic: the second control point's offset from the end
 * @param fromSide the side of its start table, or null for automatic
 * @param toSide   the side of its end table, or null for automatic
 * @param color    the line's own colour (hex), or null
 */
public record DbmlRefCurve(DbmlCurveStyle style, DbmlPoint c1, DbmlPoint c2, DbmlSide fromSide, DbmlSide toSide, String color) {
    public static final DbmlRefCurve PLAIN = new DbmlRefCurve(null, null, null, null, null, null);

    public boolean isPlain() { return equals(PLAIN); }

    public DbmlRefCurve withStyle(DbmlCurveStyle s) { return new DbmlRefCurve(s, c1, c2, fromSide, toSide, color); }
    public DbmlRefCurve withControls(DbmlPoint a, DbmlPoint b) { return new DbmlRefCurve(style, a, b, fromSide, toSide, color); }
    public DbmlRefCurve withFromSide(DbmlSide s) { return new DbmlRefCurve(style, c1, c2, s, toSide, color); }
    public DbmlRefCurve withToSide(DbmlSide s) { return new DbmlRefCurve(style, c1, c2, fromSide, s, color); }
    public DbmlRefCurve withColor(String c) { return new DbmlRefCurve(style, c1, c2, fromSide, toSide, c); }
}
