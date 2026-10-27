package com.k3jdr.mxnoteone.text;

/**
 * A paragraph's style (NSParagraphStyle): alignment, the first line's indent and the other lines' indent (points,
 * as RTF's \fi and \li give them), space before and after, and RTF paragraph control words this port keeps but
 * doesn't interpret (tab stops, line spacing).
 */
public record ParaStyle(int align, double firstIndent, double headIndent, double spaceBefore, double spaceAfter, String extras) {
    public static final int LEFT = 0, CENTER = 1, RIGHT = 2, JUSTIFIED = 3;

    public static final ParaStyle DEFAULT = new ParaStyle(LEFT, 0, 0, 0, 0, "");

    public ParaStyle withAlign(int a) { return new ParaStyle(a, firstIndent, headIndent, spaceBefore, spaceAfter, extras); }
    public ParaStyle withIndents(double first, double head) { return new ParaStyle(align, first, head, spaceBefore, spaceAfter, extras); }
    public ParaStyle withExtras(String x) { return new ParaStyle(align, firstIndent, headIndent, spaceBefore, spaceAfter, x == null ? "" : x); }

    public String alignName() {
        return switch (align) {
            case CENTER -> "center";
            case RIGHT -> "right";
            case JUSTIFIED -> "justified";
            default -> null;
        };
    }

    public static int alignOf(String name) {
        if (name == null) return LEFT;
        return switch (name) {
            case "center" -> CENTER;
            case "right" -> RIGHT;
            case "justified" -> JUSTIFIED;
            default -> LEFT;
        };
    }
}
