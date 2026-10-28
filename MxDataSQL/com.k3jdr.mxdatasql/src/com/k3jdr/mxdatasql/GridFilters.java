package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Excel-style sorting and filtering run on the server (Shared/GridFilters/GridFilters.swift): the grid's query gets a
 * WHERE and ORDER BY, so paging, row counts, Export CSV (all rows) and Update see the same rows. Filter values are
 * always sent as parameters.
 */
public final class GridFilters {
    private GridFilters() {}

    public enum FilterOperator {
        EQUALS("equals"), NOT_EQUALS("does not equal"), CONTAINS("contains"), NOT_CONTAINS("does not contain"),
        BEGINS_WITH("begins with"), ENDS_WITH("ends with"), GREATER("is greater than"),
        GREATER_OR_EQUAL("is greater than or equal to"), LESS("is less than"), LESS_OR_EQUAL("is less than or equal to"),
        BETWEEN("is between"), IS_EMPTY("is empty"), IS_NOT_EMPTY("is not empty");

        public final String label;

        FilterOperator(String label) { this.label = label; }

        public boolean needsValue() { return this != IS_EMPTY && this != IS_NOT_EMPTY; }
        public boolean needsSecondValue() { return this == BETWEEN; }
    }

    /** Keep rows whose value is one of {@code values} (null = NULL), or that match a condition. */
    public sealed interface ColumnFilter permits ValuesFilter, ConditionFilter {}

    public record ValuesFilter(Set<String> values) implements ColumnFilter {}

    public record ConditionFilter(FilterOperator op, String value1, String value2) implements ColumnFilter {}

    public record GridSort(String column, boolean ascending) {}

    /** Sorting and filters of one grid, keyed by column name. */
    public static final class GridQueryOptions {
        public final Map<String, ColumnFilter> filters = new TreeMap<>();
        public GridSort sort;

        public boolean isEmpty() { return filters.isEmpty() && sort == null; }

        public GridQueryOptions copy() {
            GridQueryOptions o = new GridQueryOptions();
            o.filters.putAll(filters);
            o.sort = sort;
            return o;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GridQueryOptions o && o.filters.equals(filters) && Objects.equals(o.sort, sort);
        }

        @Override
        public int hashCode() { return Objects.hash(filters, sort); }
    }

    /** The parameter list shared by the pieces of one statement ($1, $2, …). */
    public static final class SqlBinds {
        public final List<String> values = new ArrayList<>();

        public String add(String value) {
            values.add(value);
            return "$" + values.size();
        }
    }

    public record DistinctValue(String value, long count) {}

    /** {@code $1::text::<type>} only for type names that are safe to paste; otherwise null (compare as text). */
    static String typedParam(String param, String type) {
        String t = type.toLowerCase();
        if (t.isEmpty() || !t.matches("^[a-z0-9_ \\[\\](),.\"]+$") || t.startsWith("oid ")) return null;
        return param + "::text::" + t;
    }

    /** One filter as a condition on the column expression {@code expr}. */
    public static String condition(ColumnFilter filter, String expr, String type, SqlBinds binds) {
        String text = expr + "::text";
        if (filter instanceof ValuesFilter vf) {
            List<String> parts = new ArrayList<>();
            List<String> nonNull = new ArrayList<>();
            for (String v : vf.values()) if (v != null) nonNull.add(v);
            nonNull.sort(null);
            if (!nonNull.isEmpty()) {
                List<String> params = new ArrayList<>();
                for (String v : nonNull) params.add(binds.add(v));
                parts.add(text + " IN (" + String.join(", ", params) + ")");
            }
            if (vf.values().contains(null)) parts.add(expr + " IS NULL");
            return parts.isEmpty() ? "false" : "(" + String.join(" OR ", parts) + ")";
        }
        ConditionFilter cf = (ConditionFilter) filter;
        String v1 = cf.value1(), v2 = cf.value2();
        return switch (cf.op()) {
            case IS_EMPTY -> "(" + expr + " IS NULL OR " + text + " = '')";
            case IS_NOT_EMPTY -> "(" + expr + " IS NOT NULL AND " + text + " <> '')";
            case EQUALS -> text + " = " + binds.add(v1);
            case NOT_EQUALS -> "(" + expr + " IS NULL OR " + text + " <> " + binds.add(v1) + ")";
            case CONTAINS -> "strpos(lower(" + text + "), lower(" + binds.add(v1) + ")) > 0";
            case NOT_CONTAINS -> "(" + expr + " IS NULL OR strpos(lower(" + text + "), lower(" + binds.add(v1) + ")) = 0)";
            case BEGINS_WITH -> "starts_with(lower(" + text + "), lower(" + binds.add(v1) + "))";
            case ENDS_WITH -> {
                String p = binds.add(v1);
                yield "right(lower(" + text + "), length(" + p + ")) = lower(" + p + ")";
            }
            case GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL -> {
                String sign = switch (cf.op()) {
                    case GREATER -> ">";
                    case GREATER_OR_EQUAL -> ">=";
                    case LESS -> "<";
                    default -> "<=";
                };
                String p = binds.add(v1);
                String typed = typedParam(p, type);
                yield typed != null ? expr + " " + sign + " " + typed : text + " " + sign + " " + p;
            }
            case BETWEEN -> {
                String a = binds.add(v1), b = binds.add(v2);
                String ta = typedParam(a, type), tb = typedParam(b, type);
                yield ta != null && tb != null ? expr + " BETWEEN " + ta + " AND " + tb : text + " BETWEEN " + a + " AND " + b;
            }
        };
    }

    /**
     * " WHERE …" for the filters on {@code columns} (empty when there are none); filters on other names are ignored,
     * and so is {@code except}.
     */
    public static String whereClause(GridQueryOptions options, List<Catalog.ColumnInfo> columns, String except,
                                     UnaryOperator<String> expr, SqlBinds binds) {
        Map<String, String> types = new TreeMap<>();
        for (Catalog.ColumnInfo c : columns) types.putIfAbsent(c.name(), c.type());
        List<String> parts = new ArrayList<>();
        for (var e : options.filters.entrySet()) {
            String name = e.getKey();
            if (name.equals(except) || !types.containsKey(name)) continue;
            parts.add(condition(e.getValue(), expr.apply(name), types.get(name), binds));
        }
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    /** "&lt;expr&gt; ASC NULLS LAST" for the sort column, or null. */
    public static String orderTerm(GridQueryOptions options, UnaryOperator<String> expr) {
        if (options.sort == null) return null;
        return expr.apply(options.sort.column()) + (options.sort.ascending() ? " ASC" : " DESC") + " NULLS LAST";
    }

    /** "Sort A to Z", "Sort Smallest to Largest" or "Sort Oldest to Newest" by the column's type. */
    public static String[] sortLabels(String type) {
        String t = type.toLowerCase();
        for (String k : new String[] { "int", "numeric", "decimal", "real", "double", "float", "money", "serial" }) {
            if (t.contains(k)) return new String[] { "Sort Smallest to Largest", "Sort Largest to Smallest" };
        }
        if (t.contains("date") || t.contains("time")) return new String[] { "Sort Oldest to Newest", "Sort Newest to Oldest" };
        return new String[] { "Sort A to Z", "Sort Z to A" };
    }

    public static Set<String> valueSet() { return new HashSet<>(); }
}
