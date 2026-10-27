package com.k3jdr.mx.dbml;

/** What a point on the canvas is on. */
public sealed interface DbmlHit {
    /** A control point of the selected line (its id, and 0 or 1). */
    record Handle(String id, int index) implements DbmlHit {}

    /** The corner of one of the diagram's own notes (drag to resize it). */
    record NoteCorner(String id) implements DbmlHit {}

    /** A note: the diagram's own (its id) or the DBML's (its name). */
    record Note(String id) implements DbmlHit {}

    /** A table (its id) or enum ("enum:name"). */
    record Box(String key) implements DbmlHit {}

    record Line(String id) implements DbmlHit {}

    /** The name strip of a schema panel (a table group). */
    record Panel(String name) implements DbmlHit {}
}
