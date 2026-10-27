package com.k3jdr.mxnoteone.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * A table's cells and their formatting (Models.swift TableData): plain text of every cell in {@code rows}, the
 * formatted text of cells that have formatting in {@code rich}, shading, alignment, fonts, merges, hidden rows
 * and columns, row heights and number formats. Members this port doesn't use (conditional formatting, validation,
 * filters, pivots, names and tags) are kept; indexes this port knows about follow inserted and deleted rows.
 */
public final class TableData {
    public List<List<String>> rows = new ArrayList<>();
    public List<Double> columnWidths = new ArrayList<>();
    public boolean headerRow = true;
    /** Cell shading, row by row: {r, g, b, a} or null; null for none at all. */
    public List<List<double[]>> shading;
    /** Formatted text (RTFD / RTF) of cells that have formatting; null = plain text in rows. */
    public List<List<byte[]>> rich;
    /** Each cell's own alignment ("center", "right", "justified"; null = left). */
    public List<List<String>> align;
    /** Each cell's own font ("PostScriptName size"). */
    public List<List<String>> fonts;
    public Boolean hideBorders;
    /** Merged blocks {row, column, rows, columns}. */
    public List<int[]> merges;
    public List<Integer> hiddenColumns, hiddenRows;
    private JsonObject raw = new JsonObject();

    public TableData() {}

    public TableData(int rowCount, int columnCount, double width) {
        for (int r = 0; r < rowCount; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) row.add("");
            rows.add(row);
        }
        for (int c = 0; c < columnCount; c++) columnWidths.add(width / columnCount);
    }

    public int rowCount() { return rows.size(); }
    public int columnCount() { return rows.isEmpty() ? 0 : rows.get(0).size(); }

    public String text(int r, int c) {
        return r >= 0 && r < rows.size() && c >= 0 && c < rows.get(r).size() ? rows.get(r).get(c) : "";
    }

    public void setText(int r, int c, String s) {
        if (r >= 0 && r < rows.size() && c >= 0 && c < rows.get(r).size()) rows.get(r).set(c, s);
    }

    // MARK: JSON

    public static TableData fromJson(JsonObject o) {
        TableData t = new TableData();
        t.raw = o.deepCopy();
        JsonArray rows = SwiftJson.arr(o, "rows");
        if (rows != null) {
            for (JsonElement row : rows) {
                List<String> cells = new ArrayList<>();
                if (row.isJsonArray()) for (JsonElement c : row.getAsJsonArray()) cells.add(c.isJsonNull() ? "" : c.getAsString());
                t.rows.add(cells);
            }
        }
        JsonArray widths = SwiftJson.arr(o, "columnWidths");
        if (widths != null) for (JsonElement w : widths) t.columnWidths.add(w.getAsDouble());
        t.headerRow = SwiftJson.bool(o, "headerRow", true);
        t.shading = grid(SwiftJson.arr(o, "shading"), e -> {
            JsonObject c = e.getAsJsonObject();
            return new double[] { SwiftJson.num(c, "r", 0), SwiftJson.num(c, "g", 0), SwiftJson.num(c, "b", 0), SwiftJson.num(c, "a", 1) };
        });
        t.rich = grid(SwiftJson.arr(o, "rich"), e -> SwiftJson.unbase64(e.getAsString()));
        t.align = grid(SwiftJson.arr(o, "align"), JsonElement::getAsString);
        t.fonts = grid(SwiftJson.arr(o, "fonts"), JsonElement::getAsString);
        if (o.has("hideBorders") && !o.get("hideBorders").isJsonNull()) t.hideBorders = o.get("hideBorders").getAsBoolean();
        JsonArray merges = SwiftJson.arr(o, "merges");
        if (merges != null) {
            t.merges = new ArrayList<>();
            for (JsonElement m : merges) {
                JsonObject s = m.getAsJsonObject();
                t.merges.add(new int[] { (int) SwiftJson.num(s, "row", 0), (int) SwiftJson.num(s, "column", 0),
                        (int) SwiftJson.num(s, "rows", 1), (int) SwiftJson.num(s, "columns", 1) });
            }
        }
        t.hiddenColumns = ints(SwiftJson.arr(o, "hiddenColumns"));
        t.hiddenRows = ints(SwiftJson.arr(o, "hiddenRows"));
        t.normalize();
        return t;
    }

    private static <T> List<List<T>> grid(JsonArray a, Function<JsonElement, T> cell) {
        if (a == null) return null;
        List<List<T>> out = new ArrayList<>();
        for (JsonElement row : a) {
            List<T> cells = new ArrayList<>();
            if (row.isJsonArray()) {
                for (JsonElement e : row.getAsJsonArray()) {
                    try {
                        cells.add(e.isJsonNull() ? null : cell.apply(e));
                    } catch (RuntimeException ex) {
                        cells.add(null);
                    }
                }
            }
            out.add(cells);
        }
        return out;
    }

    private static List<Integer> ints(JsonArray a) {
        if (a == null) return null;
        List<Integer> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsInt());
        return out;
    }

    private static <T> JsonArray gridJson(List<List<T>> g, Function<T, JsonElement> cell) {
        JsonArray a = new JsonArray();
        for (List<T> row : g) {
            JsonArray r = new JsonArray();
            for (T v : row) r.add(v == null ? JsonNull.INSTANCE : cell.apply(v));
            a.add(r);
        }
        return a;
    }

    private static JsonArray intsJson(List<Integer> list) {
        JsonArray a = new JsonArray();
        for (int i : list) a.add(i);
        return a;
    }

    public JsonObject toJson() {
        normalize();
        JsonObject o = raw.deepCopy();
        JsonArray rs = new JsonArray();
        for (List<String> row : rows) {
            JsonArray r = new JsonArray();
            for (String s : row) r.add(s);
            rs.add(r);
        }
        o.add("rows", rs);
        JsonArray w = new JsonArray();
        for (double d : columnWidths) w.add(SwiftJson.number(d));
        o.add("columnWidths", w);
        o.addProperty("headerRow", headerRow);
        SwiftJson.put(o, "shading", shading == null ? null : gridJson(shading, c -> {
            JsonObject j = new JsonObject();
            j.add("r", SwiftJson.number(c[0]));
            j.add("g", SwiftJson.number(c[1]));
            j.add("b", SwiftJson.number(c[2]));
            j.add("a", SwiftJson.number(c[3]));
            return j;
        }));
        SwiftJson.put(o, "rich", rich == null ? null : gridJson(rich, b -> new JsonPrimitive(SwiftJson.base64(b))));
        SwiftJson.put(o, "align", align == null ? null : gridJson(align, JsonPrimitive::new));
        SwiftJson.put(o, "fonts", fonts == null ? null : gridJson(fonts, JsonPrimitive::new));
        SwiftJson.put(o, "hideBorders", hideBorders == null ? null : new JsonPrimitive(hideBorders));
        if (merges == null || merges.isEmpty()) {
            o.remove("merges");
        } else {
            JsonArray m = new JsonArray();
            for (int[] s : merges) {
                JsonObject j = new JsonObject();
                j.addProperty("row", s[0]);
                j.addProperty("column", s[1]);
                j.addProperty("rows", s[2]);
                j.addProperty("columns", s[3]);
                m.add(j);
            }
            o.add("merges", m);
        }
        SwiftJson.put(o, "hiddenColumns", hiddenColumns == null || hiddenColumns.isEmpty() ? null : intsJson(hiddenColumns));
        SwiftJson.put(o, "hiddenRows", hiddenRows == null || hiddenRows.isEmpty() ? null : intsJson(hiddenRows));
        return o;
    }

    public TableData copy() {
        return fromJson(toJson());
    }

    /** Members kept as read (row heights, number formats, filters…), for the views that use them. */
    public JsonObject raw() { return raw; }

    // MARK: Shape

    static <T> List<List<T>> padded(List<List<T>> grid, int rowCount, int columns) {
        List<List<T>> out = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            List<T> row = new ArrayList<>(grid != null && r < grid.size() ? grid.get(r) : List.of());
            while (row.size() > columns) row.remove(row.size() - 1);
            while (row.size() < columns) row.add(null);
            out.add(row);
        }
        return out;
    }

    /** Makes the shading, rich-text and width arrays match the table's size. */
    public void normalize() {
        int columns = 0;
        for (List<String> r : rows) columns = Math.max(columns, r.size());
        if (columns == 0 && !rows.isEmpty()) columns = 1;
        for (List<String> r : rows) while (r.size() < columns) r.add("");
        if (shading != null) shading = padded(shading, rows.size(), columns);
        if (rich != null) rich = padded(rich, rows.size(), columns);
        if (align != null) align = padded(align, rows.size(), columns);
        if (fonts != null) fonts = padded(fonts, rows.size(), columns);
        if (columnWidths.size() != columns) {
            double avg = columnWidths.isEmpty() ? 120 : columnWidths.stream().mapToDouble(d -> d).average().orElse(120);
            while (columnWidths.size() > columns) columnWidths.remove(columnWidths.size() - 1);
            while (columnWidths.size() < columns) columnWidths.add(avg);
        }
    }

    public static <T> T at(List<List<T>> grid, int r, int c) {
        return grid != null && r >= 0 && r < grid.size() && c >= 0 && c < grid.get(r).size() ? grid.get(r).get(c) : null;
    }

    public byte[] richData(int r, int c) { return at(rich, r, c); }

    public void setRich(int r, int c, byte[] data) {
        if (data == null && rich == null) return;
        rich = padded(rich, rowCount(), columnCount());
        rich.get(r).set(c, data);
        if (allNull(rich)) rich = null;
    }

    public double[] shade(int r, int c) {
        double[] s = at(shading, r, c);
        if (s != null) return s;
        return headerRow && r == 0 ? new double[] { 0.91, 0.91, 0.91, 1 } : null;
    }

    public double[] ownShade(int r, int c) { return at(shading, r, c); }

    public void setShade(int r, int c, double[] color) {
        if (color == null && shading == null) return;
        shading = padded(shading, rowCount(), columnCount());
        shading.get(r).set(c, color);
        if (allNull(shading)) shading = null;
    }

    /** Light text reads better on the cell's shading. */
    public boolean textIsLight(int r, int c) {
        double[] s = shade(r, c);
        return s != null && 0.299 * s[0] + 0.587 * s[1] + 0.114 * s[2] < 0.5;
    }

    public String cellAlign(int r, int c) { return at(align, r, c); }

    public void setCellAlign(int r, int c, String name) {
        if (name == null && align == null) return;
        align = padded(align, rowCount(), columnCount());
        align.get(r).set(c, name);
        if (allNull(align)) align = null;
    }

    public String cellFont(int r, int c) { return at(fonts, r, c); }

    public void setCellFont(int r, int c, String saved) {
        if (saved == null && fonts == null) return;
        fonts = padded(fonts, rowCount(), columnCount());
        fonts.get(r).set(c, saved);
        if (allNull(fonts)) fonts = null;
    }

    static <T> boolean allNull(List<List<T>> g) {
        for (List<T> row : g) for (T v : row) if (v != null) return false;
        return true;
    }

    // MARK: Merges

    public int[] span(int r, int c) {
        if (merges != null) for (int[] s : merges) if (r >= s[0] && r < s[0] + s[2] && c >= s[1] && c < s[1] + s[3]) return s;
        return null;
    }

    public int[] master(int r, int c) {
        int[] s = span(r, c);
        return s == null ? new int[] { r, c } : new int[] { s[0], s[1] };
    }

    public boolean isCovered(int r, int c) {
        int[] s = span(r, c);
        return s != null && (s[0] != r || s[1] != c);
    }

    // MARK: Rows and columns

    public boolean isRowHidden(int r) { return hiddenRows != null && hiddenRows.contains(r) || filtered().contains(r); }
    public boolean isColumnHidden(int c) { return hiddenColumns != null && hiddenColumns.contains(c); }

    private List<Integer> filtered() {
        JsonArray a = SwiftJson.arr(raw, "filteredRows");
        List<Integer> out = ints(a);
        return out == null ? List.of() : out;
    }

    private static List<Integer> shifted(List<Integer> list, int index, int delta) {
        if (list == null) return null;
        List<Integer> out = new ArrayList<>();
        for (int v : list) {
            if (delta < 0 && v == index) continue;
            out.add(v >= index + (delta < 0 ? 1 : 0) ? v + delta : v);
        }
        return out.isEmpty() ? null : out;
    }

    /** Keys "r" or "r,c" of a stored dictionary moved for an inserted (+1) or removed (-1) row or column. */
    private void shiftKeys(String member, boolean rowsAxis, boolean pair, int index, int delta) {
        JsonObject map = SwiftJson.obj(raw, member);
        if (map == null) return;
        JsonObject moved = new JsonObject();
        for (Map.Entry<String, JsonElement> e : map.entrySet()) {
            String[] parts = e.getKey().split(",");
            try {
                int r = Integer.parseInt(parts[0].trim());
                int c = pair && parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
                int v = rowsAxis ? r : c;
                if (delta < 0 && v == index) continue;
                if (v >= index + (delta < 0 ? 1 : 0)) v += delta;
                if (rowsAxis) r = v; else c = v;
                moved.add(pair ? r + "," + c : Integer.toString(r), e.getValue());
            } catch (RuntimeException ex) {
                moved.add(e.getKey(), e.getValue());
            }
        }
        if (moved.isEmpty()) raw.remove(member); else raw.add(member, moved);
    }

    private void shiftRowLists(int index, int delta) {
        hiddenRows = shifted(hiddenRows, index, delta);
        List<Integer> f = shifted(ints(SwiftJson.arr(raw, "filteredRows")), index, delta);
        SwiftJson.put(raw, "filteredRows", f == null ? null : intsJson(f));
        shiftKeys("rowHeightOverrides", true, false, index, delta);
        shiftKeys("numberFormats", true, true, index, delta);
        shiftKeys("rowTags", true, false, index, delta);
        shiftKeys("cellTags", true, true, index, delta);
        shiftFormulas(true, index, delta);
    }

    private void shiftColumnLists(int index, int delta) {
        hiddenColumns = shifted(hiddenColumns, index, delta);
        shiftKeys("numberFormats", false, true, index, delta);
        shiftKeys("cellTags", false, true, index, delta);
        JsonArray filters = SwiftJson.arr(raw, "filters");
        if (filters != null) {
            JsonArray out = new JsonArray();
            for (JsonElement e : filters) {
                JsonObject f = e.getAsJsonObject().deepCopy();
                int col = (int) SwiftJson.num(f, "column", 0);
                if (delta < 0 && col == index) continue;
                if (col >= index + (delta < 0 ? 1 : 0)) f.addProperty("column", col + delta);
                out.add(f);
            }
            SwiftJson.put(raw, "filters", out.isEmpty() ? null : out);
        }
        shiftFormulas(false, index, delta);
    }

    /** Hook for formulas' references to follow inserted and removed rows and columns. */
    public interface FormulaShifter {
        String shift(String formula, boolean rows, int index, int delta);
    }

    public static FormulaShifter formulaShifter;

    private void shiftFormulas(boolean rowsAxis, int index, int delta) {
        if (formulaShifter == null) return;
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                String s = row.get(c);
                if (s.startsWith("=") && s.length() > 1) row.set(c, formulaShifter.shift(s, rowsAxis, index, delta));
            }
        }
    }

    public void insertRow(int index) {
        normalize();
        int i = Math.max(0, Math.min(index, rows.size()));
        int columns = columnCount();
        List<String> row = new ArrayList<>();
        for (int c = 0; c < columns; c++) row.add("");
        rows.add(i, row);
        shiftRowLists(i, 1);
        if (shading != null) shading.add(i, nulls(columns));
        if (rich != null) rich.add(i, nulls(columns));
        if (align != null) align.add(i, nulls(columns));
        if (fonts != null) fonts.add(i, nulls(columns));
        if (merges != null) for (int[] s : merges) {
            if (s[0] >= i) s[0]++;
            else if (i < s[0] + s[2]) s[2]++;
        }
    }

    private static <T> List<T> nulls(int n) {
        List<T> l = new ArrayList<>();
        for (int k = 0; k < n; k++) l.add(null);
        return l;
    }

    /** Inserts a column; it takes the shading and alignment of the column beside it. */
    public void insertColumn(int index) {
        normalize();
        int i = Math.max(0, Math.min(index, columnCount()));
        int neighbor = i > 0 ? i - 1 : i;
        for (List<String> r : rows) r.add(i, "");
        shiftColumnLists(i, 1);
        if (shading != null) for (List<double[]> r : shading) r.add(i, neighbor < r.size() && r.get(neighbor) != null ? r.get(neighbor).clone() : null);
        if (rich != null) for (List<byte[]> r : rich) r.add(i, null);
        if (align != null) for (List<String> r : align) r.add(i, neighbor < r.size() ? r.get(neighbor) : null);
        if (fonts != null) for (List<String> r : fonts) r.add(i, neighbor < r.size() ? r.get(neighbor) : null);
        double avg = columnWidths.isEmpty() ? 120 : columnWidths.stream().mapToDouble(d -> d).average().orElse(120);
        columnWidths.add(Math.min(i, columnWidths.size()), avg);
        if (merges != null) for (int[] s : merges) {
            if (s[1] >= i) s[1]++;
            else if (i < s[1] + s[3]) s[3]++;
        }
    }

    public void removeRow(int index) {
        normalize();
        if (rows.size() <= 1 || index < 0 || index >= rows.size()) return;
        if (merges != null) for (int[] s : merges) {
            if (s[0] == index && s[2] > 1) {
                rows.get(index + 1).set(s[1], rows.get(index).get(s[1]));
                if (rich != null) rich.get(index + 1).set(s[1], rich.get(index).get(s[1]));
                if (shading != null) shading.get(index + 1).set(s[1], shading.get(index).get(s[1]));
            }
        }
        rows.remove(index);
        shiftRowLists(index, -1);
        if (shading != null) shading.remove(index);
        if (rich != null) rich.remove(index);
        if (align != null) align.remove(index);
        if (fonts != null) fonts.remove(index);
        if (merges != null) {
            List<int[]> kept = new ArrayList<>();
            for (int[] s : merges) {
                if (s[0] > index) s[0]--;
                else if (index < s[0] + s[2]) s[2]--;
                if (s[2] * s[3] > 1) kept.add(s);
            }
            merges = kept.isEmpty() ? null : kept;
        }
    }

    public void removeColumn(int index) {
        normalize();
        if (columnCount() <= 1 || index < 0 || index >= columnCount()) return;
        if (merges != null) for (int[] s : merges) {
            if (s[1] == index && s[3] > 1) {
                rows.get(s[0]).set(index + 1, rows.get(s[0]).get(index));
                if (rich != null) rich.get(s[0]).set(index + 1, rich.get(s[0]).get(index));
            }
        }
        for (List<String> r : rows) r.remove(index);
        shiftColumnLists(index, -1);
        if (merges != null) {
            List<int[]> kept = new ArrayList<>();
            for (int[] s : merges) {
                if (s[1] > index) s[1]--;
                else if (index < s[1] + s[3]) s[3]--;
                if (s[2] * s[3] > 1) kept.add(s);
            }
            merges = kept.isEmpty() ? null : kept;
        }
        if (shading != null) for (List<double[]> r : shading) r.remove(index);
        if (rich != null) for (List<byte[]> r : rich) r.remove(index);
        if (align != null) for (List<String> r : align) r.remove(index);
        if (fonts != null) for (List<String> r : fonts) r.remove(index);
        columnWidths.remove(index);
    }

    /** Puts the rows in a new order (indices of the old rows); what belongs to a row moves with it. */
    public void reorderRows(int[] order) {
        normalize();
        rows = reordered(rows, order);
        if (shading != null) shading = reordered(shading, order);
        if (rich != null) rich = reordered(rich, order);
        if (align != null) align = reordered(align, order);
        if (fonts != null) fonts = reordered(fonts, order);
        int[] newIndex = new int[order.length];
        for (int n = 0; n < order.length; n++) newIndex[order[n]] = n;
        hiddenRows = remapped(hiddenRows, newIndex);
        List<Integer> f = remapped(ints(SwiftJson.arr(raw, "filteredRows")), newIndex);
        SwiftJson.put(raw, "filteredRows", f == null ? null : intsJson(f));
        remapKeys("rowHeightOverrides", false, newIndex);
        remapKeys("numberFormats", true, newIndex);
        remapKeys("rowTags", false, newIndex);
        remapKeys("cellTags", true, newIndex);
        if (merges != null) for (int[] s : merges) if (s[2] == 1 && s[0] < newIndex.length) s[0] = newIndex[s[0]];
    }

    private static <T> List<T> reordered(List<T> list, int[] order) {
        List<T> out = new ArrayList<>();
        for (int i : order) out.add(list.get(i));
        return out;
    }

    private static List<Integer> remapped(List<Integer> list, int[] newIndex) {
        if (list == null) return null;
        List<Integer> out = new ArrayList<>();
        for (int v : list) if (v >= 0 && v < newIndex.length) out.add(newIndex[v]);
        out.sort(null);
        return out.isEmpty() ? null : out;
    }

    private void remapKeys(String member, boolean pair, int[] newIndex) {
        JsonObject map = SwiftJson.obj(raw, member);
        if (map == null) return;
        Map<String, JsonElement> moved = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : map.entrySet()) {
            String[] parts = e.getKey().split(",");
            try {
                int r = Integer.parseInt(parts[0].trim());
                if (r < 0 || r >= newIndex.length) continue;
                moved.put(pair && parts.length > 1 ? newIndex[r] + "," + parts[1].trim() : Integer.toString(newIndex[r]), e.getValue());
            } catch (RuntimeException ex) {
                moved.put(e.getKey(), e.getValue());
            }
        }
        JsonObject out = new JsonObject();
        moved.forEach(out::add);
        if (out.isEmpty()) raw.remove(member); else raw.add(member, out);
    }

    /** A row's own height (dragged), or 0 when it fits its content. */
    public double rowHeightOverride(int r) {
        return SwiftJson.num(SwiftJson.obj(raw, "rowHeightOverrides"), Integer.toString(r), 0);
    }

    public void setRowHeightOverride(int r, double h) {
        JsonObject map = SwiftJson.obj(raw, "rowHeightOverrides");
        if (map == null) {
            if (h <= 0) return;
            map = new JsonObject();
            raw.add("rowHeightOverrides", map);
        }
        if (h <= 0) map.remove(Integer.toString(r)); else map.add(Integer.toString(r), SwiftJson.number(h));
        if (map.isEmpty()) raw.remove("rowHeightOverrides");
    }

    /** A cell's number format as stored, or null for General. */
    public JsonObject numberFormat(int r, int c) {
        return SwiftJson.obj(SwiftJson.obj(raw, "numberFormats"), r + "," + c);
    }

    public void setNumberFormat(int r, int c, JsonObject format) {
        JsonObject map = SwiftJson.obj(raw, "numberFormats");
        if (map == null) {
            if (format == null) return;
            map = new JsonObject();
            raw.add("numberFormats", map);
        }
        if (format == null) map.remove(r + "," + c); else map.add(r + "," + c, format);
        if (map.isEmpty()) raw.remove("numberFormats");
    }
}
