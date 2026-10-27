package com.k3jdr.mx.dbml;

/** A rectangle in diagram coordinates (CGRect): its top-left corner and size. */
public record DbmlRect(double x, double y, double width, double height) {

    public DbmlRect(DbmlPoint origin, DbmlSize size) {
        this(origin.x(), origin.y(), size.width(), size.height());
    }

    public double minX() { return x; }
    public double minY() { return y; }
    public double maxX() { return x + width; }
    public double maxY() { return y + height; }
    public double midX() { return x + width / 2; }
    public double midY() { return y + height / 2; }

    public DbmlPoint origin() { return new DbmlPoint(x, y); }
    public DbmlSize size() { return new DbmlSize(width, height); }

    public DbmlRect withOrigin(DbmlPoint p) { return new DbmlRect(p.x(), p.y(), width, height); }
    public DbmlRect withSize(DbmlSize s) { return new DbmlRect(x, y, s.width(), s.height()); }

    /** Both rectangles together (this one when {@code o} is null). */
    public DbmlRect union(DbmlRect o) {
        if (o == null) return this;
        double x0 = Math.min(minX(), o.minX()), y0 = Math.min(minY(), o.minY());
        double x1 = Math.max(maxX(), o.maxX()), y1 = Math.max(maxY(), o.maxY());
        return new DbmlRect(x0, y0, x1 - x0, y1 - y0);
    }

    /** Smaller by dx and dy on each side (larger for negative values). */
    public DbmlRect insetBy(double dx, double dy) {
        return new DbmlRect(x + dx, y + dy, width - dx * 2, height - dy * 2);
    }

    public DbmlRect offsetBy(double dx, double dy) {
        return new DbmlRect(x + dx, y + dy, width, height);
    }

    public boolean contains(DbmlPoint p) {
        return p.x() >= x && p.x() < x + width && p.y() >= y && p.y() < y + height;
    }

    public boolean intersects(DbmlRect o) {
        return o != null && minX() <= o.maxX() && o.minX() <= maxX() && minY() <= o.maxY() && o.minY() <= maxY();
    }

    /** The union of rectangles, null when there are none (CGRect.null). */
    public static DbmlRect union(Iterable<DbmlRect> rects) {
        DbmlRect all = null;
        for (DbmlRect r : rects) all = all == null ? r : all.union(r);
        return all;
    }
}
