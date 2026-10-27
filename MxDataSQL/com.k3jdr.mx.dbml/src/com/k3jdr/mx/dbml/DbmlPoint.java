package com.k3jdr.mx.dbml;

/** A point in diagram coordinates (top-left origin, y down), as CGPoint in the macOS library. */
public record DbmlPoint(double x, double y) {
    public static final DbmlPoint ZERO = new DbmlPoint(0, 0);

    public DbmlPoint plus(double dx, double dy) {
        return new DbmlPoint(x + dx, y + dy);
    }

    public double distance(DbmlPoint p) {
        return Math.hypot(x - p.x, y - p.y);
    }
}
