package com.k3jdr.mxdatahub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Excel-style sorting and column filters of a result grid, run on the server (MxAppsIOS/Shared/GridFilters:
 * GridQueryOptions and GridSQL). Every column compares as text.
 */
record GridOptions(Sort sort, Map<String, Filter> filters) {
    record Sort(String column, boolean ascending) {}

    enum Op {
        CONTAINS("contains"), NOT_CONTAINS("does not contain"), EQUALS("equals"), NOT_EQUALS("does not equal"),
        BEGINS_WITH("begins with"), ENDS_WITH("ends with"), IS_EMPTY("is empty"), IS_NOT_EMPTY("is not empty"),
        GREATER("is greater than"), LESS("is less than");

        final String title;

        Op(String title) { this.title = title; }

        boolean takesValue() { return this != IS_EMPTY && this != IS_NOT_EMPTY; }
    }

    /** A column's filter: a set of values (null for NULL), or a condition. */
    sealed interface Filter permits Values, Condition {}

    record Values(Set<String> values, boolean includeNull) implements Filter {}

    record Condition(Op op, String value) implements Filter {}

    static GridOptions empty() { return new GridOptions(null, Map.of()); }

    boolean isEmpty() { return sort == null && filters.isEmpty(); }

    GridOptions withSort(Sort s) { return new GridOptions(s, filters); }

    GridOptions withFilter(String column, Filter f) {
        Map<String, Filter> m = new TreeMap<>(filters);
        if (f == null) m.remove(column);
        else m.put(column, f);
        return new GridOptions(sort, m);
    }

    GridOptions withoutFilters() { return new GridOptions(sort, Map.of()); }

    /** One filter as a condition on the column expression {@code expr}. */
    static String condition(Filter filter, String expr, Sql.Binds binds) {
        String text = expr + "::text";
        if (filter instanceof Values v) {
            List<String> parts = new ArrayList<>();
            Set<String> nonNull = new TreeSet<>(v.values());
            if (!nonNull.isEmpty()) {
                parts.add(text + " IN (" + String.join(", ", nonNull.stream().map(binds::add).toList()) + ")");
            }
            if (v.includeNull()) parts.add(expr + " IS NULL");
            return parts.isEmpty() ? "false" : "(" + String.join(" OR ", parts) + ")";
        }
        Condition c = (Condition) filter;
        return switch (c.op()) {
            case IS_EMPTY -> "(" + expr + " IS NULL OR " + text + " = '')";
            case IS_NOT_EMPTY -> "(" + expr + " IS NOT NULL AND " + text + " <> '')";
            case EQUALS -> text + " = " + binds.add(c.value());
            case NOT_EQUALS -> "(" + expr + " IS NULL OR " + text + " <> " + binds.add(c.value()) + ")";
            case CONTAINS -> "strpos(lower(" + text + "), lower(" + binds.add(c.value()) + ")) > 0";
            case NOT_CONTAINS -> "(" + expr + " IS NULL OR strpos(lower(" + text + "), lower(" + binds.add(c.value()) + ")) = 0)";
            case BEGINS_WITH -> "starts_with(lower(" + text + "), lower(" + binds.add(c.value()) + "))";
            case ENDS_WITH -> {
                String p = binds.add(c.value());
                yield "right(lower(" + text + "), length(" + p + ")) = lower(" + p + ")";
            }
            case GREATER -> text + " > " + binds.add(c.value());
            case LESS -> text + " < " + binds.add(c.value());
        };
    }

    /** The filters' conditions joined with AND (empty when there are none), except {@code except}'s. */
    static String conditions(GridOptions options, Map<String, String> columns, String except, Sql.Binds binds) {
        if (options == null) return "";
        List<String> out = new ArrayList<>();
        for (String name : new TreeSet<>(options.filters().keySet())) {
            String expr = columns.get(name);
            if (name.equals(except) || expr == null) continue;
            out.add(condition(options.filters().get(name), expr, binds));
        }
        return String.join(" AND ", out);
    }

    /** "<expr> ASC NULLS LAST" for the sort column, or null. */
    static String orderTerm(GridOptions options, Map<String, String> columns) {
        if (options == null || options.sort() == null) return null;
        String expr = columns.get(options.sort().column());
        if (expr == null) return null;
        return expr + (options.sort().ascending() ? " ASC" : " DESC") + " NULLS LAST";
    }
}
