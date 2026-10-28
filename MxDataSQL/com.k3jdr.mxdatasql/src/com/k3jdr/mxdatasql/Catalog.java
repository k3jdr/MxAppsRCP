package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.List;

/**
 * What was read from the catalog (DatabaseModels.swift): the table, view, function and procedure list, a psql
 * {@code \d}-style description, and the columns.
 */
public final class Catalog {
    private Catalog() {}

    /** A table, partitioned table, view or materialized view in DB Table Selection. */
    public static final class TableSummary {
        public final String oid;
        public final TableRef ref;
        public final String kind;
        public final long estimatedRows;
        public final String totalSize;
        /** Filled in by Count Rows (count(*)); null until counted. */
        public Long exactRows;

        public TableSummary(String oid, TableRef ref, String kind, long estimatedRows, String totalSize) {
            this.oid = oid;
            this.ref = ref;
            this.kind = kind;
            this.estimatedRows = estimatedRows;
            this.totalSize = totalSize;
        }

        public String id() { return oid; }
        public String schema() { return ref.schema(); }
        public String name() { return ref.name(); }
        public long displayRows() { return exactRows != null ? exactRows : estimatedRows; }
        public boolean isView() { return kind.equals("view") || kind.equals("materialized view"); }
        public boolean isPlainView() { return kind.equals("view"); }
    }

    /** A function or procedure (not aggregates, window functions or extensions' functions). */
    public record RoutineSummary(String oid, String schema, String name, String kind, String arguments, String returns,
                                 String language) {
        public String id() { return "proc:" + oid; }
        public boolean isProcedure() { return kind.equals("procedure"); }
        public String signature() { return name + "(" + arguments + ")"; }
        public String displayName() { return schema + "." + signature(); }
        /** "Function returning boolean, language sql." */
        public String facts() { return (isProcedure() ? "Procedure" : "Function returning " + returns) + ", language " + language + "."; }
    }

    /** A function's or procedure's code (pg_get_functiondef). */
    public record RoutineDefinition(RoutineSummary summary, String code) {}

    /** What DB Table Selection lists. */
    public enum CatalogKind {
        ALL("All"), TABLES("Tables"), VIEWS("Views"), FUNCTIONS("Functions"), PROCEDURES("Procedures");

        public final String label;

        CatalogKind(String label) { this.label = label; }
    }

    /** A row of the DB Table Selection list: a table or view, or a function or procedure. */
    public record CatalogEntry(TableSummary table, RoutineSummary routine) {
        public static CatalogEntry of(TableSummary t) { return new CatalogEntry(t, null); }
        public static CatalogEntry of(RoutineSummary r) { return new CatalogEntry(null, r); }

        public String id() { return table != null ? table.id() : routine.id(); }
        public String schema() { return table != null ? table.schema() : routine.schema(); }
        public String name() { return table != null ? table.name() : routine.signature(); }
        public String kind() { return table != null ? table.kind : routine.kind(); }
        /** Kind and qualified name, to find the entry again after the list reloads. */
        public String key() { return (table == null ? kind() : "table") + " " + schema() + "." + name(); }
    }

    public record ColumnInfo(String name, String type, String collation, boolean notNull, String defaultExpr, int attnum) {
        public ColumnInfo(String name, String type) {
            this(name, type, "", false, "", 0);
        }
    }

    /** psql's {@code \d table}: columns, indexes, checks, foreign keys and "referenced by". */
    public static final class TableDescription {
        public final TableSummary summary;
        public final List<ColumnInfo> columns;
        public final List<String> primaryKey;
        public final List<String> indexes, checks, foreignKeys, referencedBy;
        /** SELECT * FROM schema.table ORDER BY &lt;primary key&gt;, identifiers quoted by the server. */
        public final String defaultSQL;
        /** A view's SELECT (pg_get_viewdef); null for a table. */
        public final String viewDefinition;

        public TableDescription(TableSummary summary, List<ColumnInfo> columns, List<String> primaryKey, List<String> indexes,
                                List<String> checks, List<String> foreignKeys, List<String> referencedBy, String defaultSQL,
                                String viewDefinition) {
            this.summary = summary;
            this.columns = columns;
            this.primaryKey = primaryKey;
            this.indexes = indexes;
            this.checks = checks;
            this.foreignKeys = foreignKeys;
            this.referencedBy = referencedBy;
            this.defaultSQL = defaultSQL;
            this.viewDefinition = viewDefinition;
        }

        public ColumnInfo column(String name) {
            for (ColumnInfo c : columns) if (c.name().equals(name)) return c;
            return null;
        }

        /** The text without a view's definition. */
        public String psqlSummary() {
            String kindTitle = switch (summary.kind) {
                case "partitioned table" -> "Partitioned table";
                case "view" -> "View";
                case "materialized view" -> "Materialized view";
                default -> "Table";
            };
            StringBuilder out = new StringBuilder(kindTitle + " \"" + summary.ref.displayName() + "\"\n");
            List<String[]> body = new ArrayList<>();
            for (ColumnInfo c : columns) {
                body.add(new String[] { c.name(), c.type(), c.collation(), c.notNull() ? "not null" : "", c.defaultExpr() });
            }
            out.append(formatAligned(new String[] { "Column", "Type", "Collation", "Nullable", "Default" }, body));
            section(out, "Indexes", indexes);
            section(out, "Check constraints", checks);
            section(out, "Foreign-key constraints", foreignKeys);
            section(out, "Referenced by", referencedBy);
            return out.toString();
        }

        private static void section(StringBuilder out, String title, List<String> lines) {
            if (lines.isEmpty()) return;
            out.append(title).append(":\n");
            for (int i = 0; i < lines.size(); i++) {
                out.append("    ").append(lines.get(i));
                out.append(i < lines.size() - 1 ? "\n" : "\n");
            }
        }
    }

    /** psql-style aligned table: " a | b " with a "---+---" rule under the header. */
    public static String formatAligned(String[] headers, List<String[]> rows) {
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) widths[i] = headers[i].length();
        for (String[] row : rows) for (int i = 0; i < row.length; i++) widths[i] = Math.max(widths[i], row[i].length());
        StringBuilder out = new StringBuilder(" ");
        for (int i = 0; i < headers.length; i++) {
            if (i > 0) out.append(" | ");
            int left = (widths[i] - headers[i].length()) / 2;
            out.append(" ".repeat(left)).append(headers[i]).append(" ".repeat(widths[i] - headers[i].length() - left));
        }
        out.append(" \n");
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) out.append('+');
            out.append("-".repeat(widths[i] + 2));
        }
        out.append('\n');
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder(" ");
            for (int i = 0; i < row.length; i++) {
                if (i > 0) line.append(" | ");
                line.append(row[i]).append(" ".repeat(widths[i] - row[i].length()));
            }
            out.append(line.toString().stripTrailing()).append('\n');
        }
        return out.toString();
    }
}
