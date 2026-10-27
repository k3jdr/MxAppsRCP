package com.k3jdr.mxnoteone.table;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.k3jdr.mxnoteone.model.SwiftJson;
import com.k3jdr.mxnoteone.model.TableData;

/** A table's cell values: formulas worked out, numbers shown in their cells' number formats (Models.swift NumberFormat). */
public final class TableValues {
    private final TableData data;
    private final Map<Long, Formula.V> cache = new HashMap<>();
    private final Set<Long> evaluating = new HashSet<>();

    public TableValues(TableData data) {
        this.data = data;
    }

    private static long key(int r, int c) { return ((long) r << 20) | c; }

    public void invalidate() { cache.clear(); }

    /** A cell's value: its formula's result, or its text as a number / logical / text. */
    public Formula.V value(int r, int c) {
        if (r < 0 || r >= data.rowCount() || c < 0 || c >= data.columnCount()) return Formula.V.error("#REF!");
        int[] m = data.master(r, c);
        r = m[0];
        c = m[1];
        String raw = data.text(r, c);
        if (!Formula.isFormula(raw)) return raw.startsWith("'=") ? Formula.V.text(raw.substring(1)) : Formula.valueOfText(raw);
        long k = key(r, c);
        Formula.V cached = cache.get(k);
        if (cached != null) return cached;
        if (evaluating.contains(k)) return Formula.V.error("#CIRC!");
        evaluating.add(k);
        Formula.V result;
        try {
            result = Formula.evaluate(raw, ref -> {
                if (ref.row() < 0 || ref.row() >= data.rowCount() || ref.column() < 0 || ref.column() >= data.columnCount()) return null;
                if (data.isCovered(ref.row(), ref.column())) return Formula.V.EMPTY;
                if (evaluating.contains(key(ref.row(), ref.column()))) return Formula.V.error("#CIRC!");
                return value(ref.row(), ref.column());
            });
        } finally {
            evaluating.remove(k);
        }
        cache.put(k, result);
        return result;
    }

    /** What the cell shows: the formula's result, a number in its format, else the text as typed. */
    public String display(int r, int c) {
        String raw = data.text(r, c);
        boolean formula = Formula.isFormula(raw);
        JsonObject format = data.numberFormat(r, c);
        if (!formula && format == null) return raw.startsWith("'=") ? raw.substring(1) : raw;
        Formula.V v = value(r, c);
        if (format != null && (v.kind == Formula.V.Kind.NUMBER || v.kind == Formula.V.Kind.DATE)) return format(v.num, format);
        return formula ? v.display() : raw;
    }

    /** A number as a stored number format shows it (1,234.50 · $1,234.50 · -$5.00 · 12.5% · a date). */
    public static String format(double value, JsonObject f) {
        String kind = SwiftJson.str(f, "kind", "number");
        int decimals = (int) SwiftJson.num(f, "decimals", 2);
        boolean thousands = SwiftJson.bool(f, "thousands", true);
        if ("date".equals(kind)) return Formula.formatDate(value, SwiftJson.str(f, "pattern", "M/d/yyyy").replace("a", "a"));
        StringBuilder pattern = new StringBuilder(thousands ? "#,##0" : "0");
        if (decimals > 0) pattern.append('.').append("0".repeat(decimals));
        DecimalFormat df = new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance(Locale.US));
        df.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return switch (kind) {
            case "currency" -> {
                String digits = df.format(Math.abs(value));
                String shown = SwiftJson.str(f, "symbol", "$") + digits;
                yield value < 0 && digits.chars().anyMatch(ch -> ch >= '1' && ch <= '9') ? "-" + shown : shown;
            }
            case "percent" -> df.format(value * 100) + "%";
            default -> df.format(value);
        };
    }

    /** A cell's text as a number for sorting (numbers in order, dates by date); null when it isn't one. */
    public Double sortNumber(int r, int c) {
        Formula.V v = value(r, c);
        if (v.kind == Formula.V.Kind.NUMBER || v.kind == Formula.V.Kind.DATE) return v.num;
        return null;
    }
}
