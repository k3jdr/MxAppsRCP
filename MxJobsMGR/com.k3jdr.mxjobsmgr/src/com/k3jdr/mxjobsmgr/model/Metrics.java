package com.k3jdr.mxjobsmgr.model;

/** The diagram's layout (EditorState.swift Metrics): lanes, gutter, node size, grid, and link curves. */
public final class Metrics {
    private Metrics() {}

    public static final double LANE_HEIGHT = 240;
    /** Width of the lane title column on the left of the canvas. */
    public static final double GUTTER = 170;
    public static final double NODE_W = 190, NODE_H = 78;
    public static final double GRID = 10;
    public static final double MIN_CANVAS_WIDTH = 2200;

    public static double laneTop(Layer layer) { return (layer.number - 1) * LANE_HEIGHT; }
    public static double laneCenter(Layer layer) { return laneTop(layer) + LANE_HEIGHT / 2; }

    public static double canvasWidth(Jobspace space) {
        double maxX = 0;
        for (JobNode n : space.nodes) maxX = Math.max(maxX, n.x);
        return Math.max(MIN_CANVAS_WIDTH, maxX + 600);
    }

    public static double canvasHeight() { return LANE_HEIGHT * 3; }

    /** Keeps a node centre inside its own lane and right of the gutter, snapped to the grid. */
    public static double[] place(double px, double py, Layer layer) {
        double halfW = NODE_W / 2, halfH = NODE_H / 2;
        double top = laneTop(layer) + halfH + 14;
        double bottom = laneTop(layer) + LANE_HEIGHT - halfH - 14;
        double x = Math.max(GUTTER + halfW + 20, px);
        double y = Math.min(Math.max(py, top), bottom);
        return new double[] { Math.round(x / GRID) * GRID, Math.round(y / GRID) * GRID };
    }

    public static Layer layerAt(double y) {
        return Layer.of(Math.min(3, Math.max(1, (int) Math.floor(y / LANE_HEIGHT) + 1)));
    }

    /** A link's cubic curve: bottom→top when the target lane is lower, top→bottom when higher, side to side within a lane. */
    public record Curve(double sx, double sy, double c1x, double c1y, double c2x, double c2y, double ex, double ey) {
        public double midX() { return (sx + 3 * c1x + 3 * c2x + ex) / 8; }
        public double midY() { return (sy + 3 * c1y + 3 * c2y + ey) / 8; }
        public double arrowAngle() { return Math.atan2(ey - c2y, ex - c2x); }

        /** The arrow head's two back corners. */
        public double[] arrowHead() {
            double len = 11, spread = Math.PI / 7, a = arrowAngle();
            return new double[] { ex, ey, ex - len * Math.cos(a - spread), ey - len * Math.sin(a - spread),
                    ex - len * Math.cos(a + spread), ey - len * Math.sin(a + spread) };
        }

        public double[] point(double t) {
            double u = 1 - t;
            double x = u * u * u * sx + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * ex;
            double y = u * u * u * sy + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * ey;
            return new double[] { x, y };
        }

        /** The distance from a point to the curve (sampled). */
        public double distance(double px, double py) {
            double best = Double.MAX_VALUE;
            double[] prev = point(0);
            for (int i = 1; i <= 40; i++) {
                double[] p = point(i / 40.0);
                best = Math.min(best, segmentDistance(px, py, prev[0], prev[1], p[0], p[1]));
                prev = p;
            }
            return best;
        }

        private static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
            double x = ax + t * dx - px, y = ay + t * dy - py;
            return Math.sqrt(x * x + y * y);
        }
    }

    public static Curve curve(double ax, double ay, double bx, double by) {
        double w = NODE_W / 2, h = NODE_H / 2;
        double dy = by - ay;
        double sx, sy, ex, ey, c1x, c1y, c2x, c2y;
        if (dy > NODE_H) {
            sx = ax; sy = ay + h; ex = bx; ey = by - h;
            double k = Math.max(40, (ey - sy) / 2);
            c1x = sx; c1y = sy + k; c2x = ex; c2y = ey - k;
        } else if (dy < -NODE_H) {
            sx = ax; sy = ay - h; ex = bx; ey = by + h;
            double k = Math.max(40, (sy - ey) / 2);
            c1x = sx; c1y = sy - k; c2x = ex; c2y = ey + k;
        } else {
            boolean right = bx >= ax;
            sx = ax + (right ? w : -w); sy = ay;
            ex = bx + (right ? -w : w); ey = by;
            double k = Math.max(30, Math.abs(ex - sx) / 2) * (right ? 1 : -1);
            c1x = sx + k; c1y = sy; c2x = ex - k; c2y = ey;
        }
        return new Curve(sx, sy, c1x, c1y, c2x, c2y, ex, ey);
    }
}
