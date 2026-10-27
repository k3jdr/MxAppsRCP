package com.k3jdr.mx.dbml;

import java.util.List;

/**
 * A note over the diagram (the diagram's own: written to the file as a DBML sticky note).
 *
 * @param color its own colour (hex), else the yellow of notes (null)
 */
public record DbmlDiagramNote(String id, String text, DbmlRect frame, String color) {
    public static final DbmlSize SIZE = new DbmlSize(220, 120);

    public record Swatch(String name, String hex) {}

    public static final List<Swatch> PALETTE = List.of(new Swatch("Yellow", "#FFE680"), new Swatch("Blue", "#B8D8FF"),
            new Swatch("Green", "#BDEBB8"), new Swatch("Pink", "#FFC2D6"), new Swatch("Orange", "#FFCC99"),
            new Swatch("Gray", "#D9D9D9"));

    public DbmlDiagramNote withText(String t) { return new DbmlDiagramNote(id, t, frame, color); }
    public DbmlDiagramNote withFrame(DbmlRect f) { return new DbmlDiagramNote(id, text, f, color); }
    public DbmlDiagramNote withColor(String c) { return new DbmlDiagramNote(id, text, frame, c); }
}
