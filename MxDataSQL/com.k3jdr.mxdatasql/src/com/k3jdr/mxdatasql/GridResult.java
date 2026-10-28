package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.List;

import com.k3jdr.mxdatasql.Catalog.ColumnInfo;
import com.k3jdr.mxdatasql.Catalog.TableDescription;
import com.k3jdr.mxdatasql.GridFilters.GridQueryOptions;

/**
 * Rows loaded into the DB Table grid (DatabaseModels.swift GridResult). Every value is its PostgreSQL text form
 * ({@code col::text}); null is SQL NULL.
 */
public final class GridResult {
    /** The table the rows can be edited against; null for read-only query results. */
    public TableDescription description;
    /** Table name, or "Query result". */
    public String title;
    public List<ColumnInfo> columns;
    public List<String[]> rows;
    /** ctid of each row (whole-table loads, SELECT * results), the row key for tables without a primary key. */
    public List<String> rowIDs = new ArrayList<>();
    /** Rows in the table (filtered); null when the rows came from custom SQL. */
    public Long totalRows;
    /** More rows were available than the fetch limit allowed. */
    public boolean hasMore;
    /** The SQL typed by the user, when the rows did not come from opening a table. */
    public String customSQL;
    /** Rows skipped before this page. */
    public int offset;
    /** The rows came from a statement that changed data; it is not run again to page. */
    public boolean isWriteResult;
    public GridQueryOptions options = new GridQueryOptions();
    /** Rows before filtering, when filters are active and the count is known (tables). */
    public Long unfilteredTotal;

    public record CellKey(int row, int column) {}

    /** A pending edit; {@code value == null} sets the cell to NULL. */
    public record CellEdit(String value) {}

    public boolean hasPreviousPage() { return offset > 0 && !isWriteResult; }

    public boolean hasNextPage() {
        if (isWriteResult) return false;
        if (totalRows != null) return offset + rows.size() < totalRows;
        return hasMore;
    }

    public TableRef ref() { return description == null ? null : description.summary.ref; }

    public boolean isEditable() {
        return description != null && (!description.primaryKey.isEmpty() || !rowIDs.isEmpty());
    }

    /** The key under which the user's column widths are saved in the workspace. */
    public String widthKey() { return ref() != null ? ref().displayName() : "(query)"; }

    /** Where the rows come from, for re-querying with other sorting or filters. */
    public boolean hasSource() { return customSQL != null || ref() != null; }

    /** Not on results of a statement that changed data, nor on a query column whose name appears twice. */
    public boolean canFilter(int index) {
        if (isWriteResult || !hasSource() || index < 0 || index >= columns.size()) return false;
        String name = columns.get(index).name();
        int count = 0;
        for (ColumnInfo c : columns) if (c.name().equals(name)) count++;
        return count == 1;
    }

    public int columnIndex(String name) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equals(name)) return i;
        return -1;
    }
}
