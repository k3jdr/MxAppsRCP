package com.k3jdr.mx.dbml;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The schema a DBML text describes (DBMLLanguage.swift's DBMLSchema): tables and their columns, enums,
 * relationships (Ref), table groups, sticky notes, the Project, and what could not be read (by line).
 * Made by {@link DbmlParser#parse(String)}; its lists are unmodifiable and parse results are shared, so it is
 * never changed after parsing.
 */
public final class DbmlSchema {

    public record Column(String name, String type, boolean pk, boolean notNull, boolean unique, boolean increment,
                         String defaultValue, String note) {
        Column withPk(boolean v) { return new Column(name, type, v, notNull, unique, increment, defaultValue, note); }
        Column withUnique(boolean v) { return new Column(name, type, pk, notNull, v, increment, defaultValue, note); }
    }

    /**
     * A table. Its key ({@link #name()}) is "name" in the public schema (or none), else "schema.name".
     */
    public record Table(String name, String alias, List<Column> columns, String note, String headerColor,
                        int indexCount, int line, int endLine) {
        public String id() { return name; }

        /** The schema it's in ("public" when none is written). */
        public String schema() {
            int k = name.lastIndexOf('.');
            return k < 0 ? "public" : name.substring(0, k);
        }

        public String shortName() {
            int k = name.lastIndexOf('.');
            return k < 0 ? name : name.substring(k + 1);
        }

        public int columnIndex(String column) {
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equals(column)) return i;
            return -1;
        }

        public boolean hasColumn(String column) { return columnIndex(column) >= 0; }
    }

    public record EnumValue(String name, String note) {}

    public record EnumType(String name, List<EnumValue> values, int line, int endLine) {
        public String schema() {
            int k = name.lastIndexOf('.');
            return k < 0 ? "public" : name.substring(0, k);
        }
    }

    /** One end of a relationship: a table and its column (or columns). */
    public record End(String table, List<String> columns) {
        public String text() {
            return table + "." + (columns.size() == 1 ? columns.get(0) : "(" + String.join(", ", columns) + ")");
        }

        End withTable(String t) { return new End(t, columns); }
    }

    /**
     * A relationship, as written: from {@code op} to, where op is {@code >} many-to-one, {@code <} one-to-many,
     * {@code -} one-to-one or {@code <>} many-to-many.
     *
     * @param inline   written inside a column's settings ([ref: > other.id]) rather than as a Ref of its own
     * @param settings its settings as written ("[delete: cascade]"), or null
     * @param key      MxNoteONE's earlier key for it ("a.b > c.d"; kept to read diagrams saved before the shared library)
     */
    public record Ref(String name, End from, End to, String op, int line, boolean inline, String settings, String key) {
        /** The relationship's id, as MxDataSQL names it in its layout: from.(columns) op to.(columns). */
        public String id() {
            return from.table() + ".(" + String.join(",", from.columns()) + ")" + op
                    + to.table() + ".(" + String.join(",", to.columns()) + ")";
        }

        public boolean fromMany() { return op.equals(">") || op.equals("<>"); }
        public boolean toMany() { return op.equals("<") || op.equals("<>"); }

        public String kindTitle() {
            return switch (op) {
                case ">" -> "many-to-one";
                case "<" -> "one-to-many";
                case "-" -> "one-to-one";
                default -> "many-to-many";
            };
        }

        public String text() { return from.text() + " " + op + " " + to.text(); }

        Ref withEnds(End f, End t) { return new Ref(name, f, t, op, line, inline, settings, key); }
        Ref withKey(String k) { return new Ref(name, from, to, op, line, inline, settings, k); }
    }

    public record Group(String name, List<String> tables, String color, int line, int endLine) {}

    public record StickyNote(String name, String text, int line, int endLine) {}

    public record Problem(int line, String message) {}

    /** Lines from {@code lower} to {@code upper}, both included (1-based). */
    public record LineRange(int lower, int upper) {}

    private final String project;
    private final LineRange projectLines;
    private final List<Table> tables;
    private final List<EnumType> enums;
    private final List<Ref> refs;
    private final List<Group> groups;
    private final List<StickyNote> notes;
    private final List<Problem> problems;

    DbmlSchema(String project, LineRange projectLines, List<Table> tables, List<EnumType> enums, List<Ref> refs,
               List<Group> groups, List<StickyNote> notes, List<Problem> problems) {
        this.project = project;
        this.projectLines = projectLines;
        this.tables = List.copyOf(tables);
        this.enums = List.copyOf(enums);
        this.refs = List.copyOf(refs);
        this.groups = List.copyOf(groups);
        this.notes = List.copyOf(notes);
        this.problems = List.copyOf(problems);
    }

    public String project() { return project; }
    public LineRange projectLines() { return projectLines; }
    public List<Table> tables() { return tables; }
    public List<EnumType> enums() { return enums; }
    public List<Ref> refs() { return refs; }
    public List<Group> groups() { return groups; }
    public List<StickyNote> notes() { return notes; }
    public List<Problem> problems() { return problems; }

    public Table table(String name) {
        for (Table t : tables) if (t.name().equals(name)) return t;
        return null;
    }

    public Ref ref(String id) {
        for (Ref r : refs) if (r.id().equals(id)) return r;
        return null;
    }

    /** The schemas its tables are in, in the order they first appear. */
    public List<String> schemas() {
        Set<String> seen = new LinkedHashSet<>();
        for (Table t : tables) seen.add(t.schema());
        return new ArrayList<>(seen);
    }

    /** The columns of a table that refer to another table (foreign keys), as MxDataSQL marks them. */
    public Set<String> foreignKeys(String table) {
        Set<String> out = new LinkedHashSet<>();
        for (Ref r : refs) if (r.from().table().equals(table) && !r.op().equals("<")) out.addAll(r.from().columns());
        for (Ref r : refs) if (r.to().table().equals(table) && r.op().equals("<")) out.addAll(r.to().columns());
        return out;
    }
}
