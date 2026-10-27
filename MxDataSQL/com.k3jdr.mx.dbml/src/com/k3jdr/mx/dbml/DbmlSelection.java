package com.k3jdr.mx.dbml;

import java.util.Set;

/** What's selected on a diagram: tables (and enums) by key, a relationship by id, or a note by id. */
public record DbmlSelection(Set<String> tables, String ref, String note) {
    public static final DbmlSelection NONE = new DbmlSelection(Set.of(), null, null);

    public DbmlSelection {
        tables = tables == null ? Set.of() : Set.copyOf(tables);
    }

    public static DbmlSelection ofTables(Set<String> tables) { return new DbmlSelection(tables, null, null); }
    public static DbmlSelection ofRef(String id) { return new DbmlSelection(Set.of(), id, null); }
    public static DbmlSelection ofNote(String id) { return new DbmlSelection(Set.of(), null, id); }

    public boolean isEmpty() { return tables.isEmpty() && ref == null && note == null; }
}
