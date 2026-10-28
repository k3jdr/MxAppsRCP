package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.k3jdr.mxdatasql.Catalog.ColumnInfo;
import com.k3jdr.mxdatasql.GridResult.CellEdit;
import com.k3jdr.mxdatasql.GridResult.CellKey;

/**
 * Turns the grid's pending edits into parameterized UPDATE statements (TableUpdate.swift). Values are always sent as
 * text and cast to the column type on the server ({@code $1::text::integer}), so any type with a text input works.
 */
public final class TableUpdate {
    private TableUpdate() {}

    public enum RowKeyMode {
        PRIMARY_KEY("Primary key"), ROW_ID("Row ID (ctid)"), COLUMNS("Match columns");

        public final String label;

        RowKeyMode(String label) { this.label = label; }
    }

    public static final class Options {
        public RowKeyMode keyMode = RowKeyMode.PRIMARY_KEY;
        /** Columns whose loaded values identify the row when keyMode is COLUMNS. */
        public Set<String> matchColumns = new LinkedHashSet<>();
        /** An edited cell left empty is written as NULL instead of ''. */
        public boolean emptyAsNull;
        /** Only update a row if the edited columns still hold the loaded values. */
        public boolean checkOriginalValues = true;
        /** All rows in one transaction (all or nothing) instead of row by row. */
        public boolean singleTransaction = true;
    }

    /** One UPDATE with its parameters; {@code rowLabel} describes the row key, e.g. "id = 42". */
    public record Planned(int row, String sql, List<String> binds, String rowLabel) {
        /** The statement with the parameter values in a trailing comment, as the Update dialog previews it. */
        public String preview() {
            StringBuilder sb = new StringBuilder(sql).append(";  --");
            for (int i = 0; i < binds.size(); i++) {
                String v = binds.get(i);
                sb.append(" $").append(i + 1).append('=').append(v == null ? "NULL" : "'" + v + "'");
            }
            return sb.toString();
        }
    }

    public static final class PlanError extends Exception {
        private static final long serialVersionUID = 1L;

        PlanError(String message) { super(message); }
    }

    public static List<Planned> plan(GridResult grid, Map<CellKey, CellEdit> edits, Options options) throws PlanError {
        if (grid.description == null) throw new PlanError("These rows are a read-only query result.");
        List<ColumnInfo> columns = grid.columns;
        List<String> keyColumns = new ArrayList<>();
        switch (options.keyMode) {
            case PRIMARY_KEY -> {
                keyColumns.addAll(grid.description.primaryKey);
                if (keyColumns.isEmpty()) throw new PlanError("The table has no primary key. Choose Row ID (ctid) or Match columns.");
            }
            case ROW_ID -> {
                if (grid.rowIDs.isEmpty()) throw new PlanError("Row IDs are only available when the whole table is loaded. Choose another row key.");
            }
            case COLUMNS -> {
                for (ColumnInfo c : columns) if (options.matchColumns.contains(c.name())) keyColumns.add(c.name());
                if (keyColumns.isEmpty()) throw new PlanError("Select at least one column to match rows on.");
            }
        }
        Map<Integer, TreeMap<Integer, CellEdit>> byRow = new TreeMap<>();
        edits.forEach((k, e) -> byRow.computeIfAbsent(k.row(), r -> new TreeMap<>()).put(k.column(), e));
        List<Planned> out = new ArrayList<>();
        for (var entry : byRow.entrySet()) {
            int row = entry.getKey();
            String[] original = grid.rows.get(row);
            List<String> binds = new ArrayList<>();
            List<String> assignments = new ArrayList<>();
            for (var cell : entry.getValue().entrySet()) {
                ColumnInfo col = columns.get(cell.getKey());
                String value = cell.getValue().value();
                if (options.emptyAsNull && "".equals(value)) value = null;
                assignments.add(Sql.ident(col.name()) + " = " + param(binds, value) + "::text::" + col.type());
            }
            List<String> conditions = new ArrayList<>();
            List<String> labelParts = new ArrayList<>();
            switch (options.keyMode) {
                case PRIMARY_KEY -> {
                    for (String name : keyColumns) {
                        int index = grid.columnIndex(name);
                        if (index < 0) throw new PlanError("The primary key column " + name + " is not in the grid.");
                        ColumnInfo col = columns.get(index);
                        conditions.add(Sql.ident(name) + " = " + param(binds, original[index]) + "::text::" + col.type());
                        labelParts.add(name + " = " + (original[index] == null ? "NULL" : original[index]));
                    }
                }
                case ROW_ID -> {
                    conditions.add("ctid = " + param(binds, grid.rowIDs.get(row)) + "::text::tid");
                    labelParts.add("ctid = " + grid.rowIDs.get(row));
                }
                case COLUMNS -> {
                    for (String name : keyColumns) {
                        int index = grid.columnIndex(name);
                        conditions.add(Sql.ident(name) + "::text IS NOT DISTINCT FROM " + param(binds, original[index]));
                        labelParts.add(name + " = " + (original[index] == null ? "NULL" : original[index]));
                    }
                }
            }
            if (options.checkOriginalValues) {
                for (int column : entry.getValue().keySet()) {
                    conditions.add(Sql.ident(columns.get(column).name()) + "::text IS NOT DISTINCT FROM " + param(binds, original[column]));
                }
            }
            String sql = "UPDATE " + grid.description.summary.ref.qualifiedSQL() + " SET " + String.join(", ", assignments)
                    + " WHERE " + String.join(" AND ", conditions) + " RETURNING 1";
            out.add(new Planned(row, sql, binds, String.join(", ", labelParts)));
        }
        return out;
    }

    private static String param(List<String> binds, String value) {
        binds.add(value);
        return "$" + binds.size();
    }
}
