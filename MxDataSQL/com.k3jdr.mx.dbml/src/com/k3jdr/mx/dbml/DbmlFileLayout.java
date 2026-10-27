package com.k3jdr.mx.dbml;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The end of a .dbml file as MxDataSQL writes it (DBMLLanguage.swift's DBMLFileLayout): after a marker line, its
 * own sticky notes and one comment line with the diagram's layout — where each table sits, each relationship's
 * curve, each note's frame. Other DBML tools read the notes and skip the comment.
 *
 * <p>The layout line is written exactly as the macOS app's JSONEncoder with {@code .sortedKeys} writes it: compact,
 * keys in the order Foundation sorts them, whole numbers without ".0", '/' escaped, absent values left out.
 */
public final class DbmlFileLayout {
    private DbmlFileLayout() {}

    public static final String SECTION_MARKER = "// ===== MxDataSQL diagram: notes and layout (rewritten when the diagram is saved) =====";
    public static final String PREFIX = "// @mxdatasql-layout ";

    public record Point(double x, double y) {}

    /** A relationship's line: style, control points (offsets), sides and colour; any may be null. */
    public record Curve(String style, Point c1, Point c2, String fromSide, String toSide, String color) {}

    /** A note's frame; width, height and colour may be null. */
    public record Frame(double x, double y, Double width, Double height, String color) {}

    public static final class Layout {
        public final Map<String, Point> tables = new LinkedHashMap<>();
        public final Map<String, Curve> refs = new LinkedHashMap<>();
        public final Map<String, Frame> notes = new LinkedHashMap<>();
        public Double zoom;
        /** Written by an MxNoteONE before the shared library (its boxes were taller then); read and ignored. */
        public Boolean mxnoteone;

        @Override
        public boolean equals(Object o) {
            return o instanceof Layout l && tables.equals(l.tables) && refs.equals(l.refs) && notes.equals(l.notes)
                    && Objects.equals(zoom, l.zoom) && Objects.equals(mxnoteone, l.mxnoteone);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tables, refs, notes, zoom, mxnoteone);
        }
    }

    /** The file's DBML and its diagram section (from the marker line on; null without one). */
    public record Split(String body, String section) {}

    public static Split split(String text) {
        int k = text.indexOf(SECTION_MARKER);
        if (k < 0) return new Split(text, null);
        int lineStart = k == 0 ? 0 : text.lastIndexOf('\n', k - 1) + 1;
        return new Split(text.substring(0, lineStart), text.substring(lineStart));
    }

    /** The layout line in a diagram section (null without one, or if it can't be read). */
    public static Layout layoutInSection(String section) {
        if (section == null) return null;
        for (String line : section.split("\n")) {
            if (line.startsWith(PREFIX)) return decode(line.substring(PREFIX.length()));
        }
        return null;
    }

    public static Layout layout(String text) {
        String section = split(text).section();
        return section == null ? null : layoutInSection(section);
    }

    /** The layout as its comment line. */
    public static String line(Layout layout) {
        StringBuilder s = new StringBuilder(PREFIX);
        s.append('{');
        // Top-level keys in sorted order: mxnoteone, notes, refs, tables, zoom.
        boolean first = true;
        if (layout.mxnoteone != null) {
            s.append("\"mxnoteone\":").append(layout.mxnoteone ? "true" : "false");
            first = false;
        }
        if (!first) s.append(',');
        s.append("\"notes\":");
        writeMap(s, layout.notes, DbmlFileLayout::writeFrame);
        s.append(",\"refs\":");
        writeMap(s, layout.refs, DbmlFileLayout::writeCurve);
        s.append(",\"tables\":");
        writeMap(s, layout.tables, DbmlFileLayout::writePoint);
        if (layout.zoom != null) s.append(",\"zoom\":").append(number(layout.zoom));
        s.append('}');
        return s.toString();
    }

    // MARK: Writing JSON as Foundation does

    private interface ValueWriter<T> {
        void write(StringBuilder s, T value);
    }

    private static <T> void writeMap(StringBuilder s, Map<String, T> map, ValueWriter<T> w) {
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(KEY_ORDER);
        s.append('{');
        for (int k = 0; k < keys.size(); k++) {
            if (k > 0) s.append(',');
            string(s, keys.get(k));
            s.append(':');
            w.write(s, map.get(keys.get(k)));
        }
        s.append('}');
    }

    private static void writePoint(StringBuilder s, Point p) {
        s.append("{\"x\":").append(number(p.x())).append(",\"y\":").append(number(p.y())).append('}');
    }

    private static void writeCurve(StringBuilder s, Curve c) {
        List<String> parts = new ArrayList<>();
        if (c.c1() != null) parts.add(field("c1", b -> writePoint(b, c.c1())));
        if (c.c2() != null) parts.add(field("c2", b -> writePoint(b, c.c2())));
        if (c.color() != null) parts.add(field("color", b -> string(b, c.color())));
        if (c.fromSide() != null) parts.add(field("fromSide", b -> string(b, c.fromSide())));
        if (c.style() != null) parts.add(field("style", b -> string(b, c.style())));
        if (c.toSide() != null) parts.add(field("toSide", b -> string(b, c.toSide())));
        s.append('{').append(String.join(",", parts)).append('}');
    }

    private static void writeFrame(StringBuilder s, Frame f) {
        List<String> parts = new ArrayList<>();
        if (f.color() != null) parts.add(field("color", b -> string(b, f.color())));
        if (f.height() != null) parts.add(field("height", b -> b.append(number(f.height()))));
        if (f.width() != null) parts.add(field("width", b -> b.append(number(f.width()))));
        parts.add(field("x", b -> b.append(number(f.x()))));
        parts.add(field("y", b -> b.append(number(f.y()))));
        s.append('{').append(String.join(",", parts)).append('}');
    }

    private static String field(String key, java.util.function.Consumer<StringBuilder> value) {
        StringBuilder b = new StringBuilder();
        string(b, key);
        b.append(':');
        value.accept(b);
        return b.toString();
    }

    /** A JSON string as Foundation escapes it (quotes, backslashes, '/', control characters). */
    static void string(StringBuilder s, String v) {
        s.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '/' -> s.append("\\/");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                case '\b' -> s.append("\\b");
                case '\f' -> s.append("\\f");
                default -> {
                    if (c < 0x20) s.append(String.format("\\u%04x", (int) c));
                    else s.append(c);
                }
            }
        }
        s.append('"');
    }

    /** A number as Swift's JSONEncoder writes a Double: its shortest form, without a trailing ".0". */
    static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        if (d == 0) return (1 / d < 0) ? "-0" : "0";
        double a = Math.abs(d);
        BigDecimal bd = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        if (a >= 1e-4 && a < 1e16) return bd.toPlainString();
        // Swift's description switches to exponents here: 1e+16, 1.5e-05.
        String digits = bd.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - bd.scale();
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        String exp = String.format("%02d", Math.abs(exponent));
        return (d < 0 ? "-" : "") + mantissa + "e" + (exponent < 0 ? "-" : "+") + exp;
    }

    /**
     * The order Foundation's JSONEncoder ({@code .sortedKeys}) puts keys in on macOS: a localized, case-insensitive,
     * numeric comparison (the root collation: spaces, then punctuation and symbols, then digits by value, then
     * letters), ties broken literally.
     */
    public static final Comparator<String> KEY_ORDER = DbmlFileLayout::compareKeys;

    private static final String PUNCTUATION_ORDER = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$";

    private static int weight(int c) {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return 1;
        int k = c < 128 ? PUNCTUATION_ORDER.indexOf(c) : -1;
        if (k >= 0) return 10 + k;
        if (c >= '0' && c <= '9') return 100 + (c - '0');
        if (c < 128 && Character.isLetter(c)) return 200 + (Character.toLowerCase(c) - 'a');
        return 1000 + Character.toLowerCase(c);
    }

    static int compareKeys(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (isDigit(ca) && isDigit(cb)) {
                int ei = i, ej = j;
                while (ei < a.length() && isDigit(a.charAt(ei))) ei++;
                while (ej < b.length() && isDigit(b.charAt(ej))) ej++;
                String na = stripZeros(a.substring(i, ei)), nb = stripZeros(b.substring(j, ej));
                if (na.length() != nb.length()) return na.length() < nb.length() ? -1 : 1;
                int c = na.compareTo(nb);
                if (c != 0) return c < 0 ? -1 : 1;
                i = ei;
                j = ej;
                continue;
            }
            int wa = weight(ca), wb = weight(cb);
            if (wa != wb) return wa < wb ? -1 : 1;
            i++;
            j++;
        }
        boolean aDone = i >= a.length(), bDone = j >= b.length();
        if (!aDone || !bDone) return aDone ? -1 : 1;
        return Integer.signum(a.compareTo(b));
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private static String stripZeros(String s) {
        int k = 0;
        while (k < s.length() - 1 && s.charAt(k) == '0') k++;
        return s.substring(k);
    }

    // MARK: Reading JSON as JSONDecoder does (a part that can't be read is left empty)

    static Layout decode(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null || !root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        Layout layout = new Layout();
        readMap(o.get("tables"), layout.tables, DbmlFileLayout::readPoint);
        readMap(o.get("refs"), layout.refs, DbmlFileLayout::readCurve);
        readMap(o.get("notes"), layout.notes, DbmlFileLayout::readFrame);
        layout.zoom = num(o.get("zoom"));
        JsonElement mx = o.get("mxnoteone");
        if (mx != null && mx.isJsonPrimitive() && mx.getAsJsonPrimitive().isBoolean()) layout.mxnoteone = mx.getAsBoolean();
        return layout;
    }

    private interface ValueReader<T> {
        /** The value, or throws IllegalArgumentException when it can't be read. */
        T read(JsonElement e);
    }

    private static <T> void readMap(JsonElement e, Map<String, T> into, ValueReader<T> reader) {
        if (e == null || !e.isJsonObject()) return;
        Map<String, T> read = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) read.put(entry.getKey(), reader.read(entry.getValue()));
        } catch (IllegalArgumentException ex) {
            return;
        }
        into.putAll(read);
    }

    private static Double num(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isNumber() ? p.getAsDouble() : null;
    }

    private static double requireNum(JsonObject o, String key) {
        Double d = num(o.get(key));
        if (d == null) throw new IllegalArgumentException(key);
        return d;
    }

    private static Double optionalNum(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        Double d = num(e);
        if (d == null) throw new IllegalArgumentException(key);
        return d;
    }

    private static String optionalString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key);
        return e.getAsString();
    }

    private static JsonObject object(JsonElement e) {
        if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("object");
        return e.getAsJsonObject();
    }

    private static Point readPoint(JsonElement e) {
        JsonObject o = object(e);
        return new Point(requireNum(o, "x"), requireNum(o, "y"));
    }

    private static Point optionalPoint(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        return readPoint(e);
    }

    private static Curve readCurve(JsonElement e) {
        JsonObject o = object(e);
        return new Curve(optionalString(o, "style"), optionalPoint(o, "c1"), optionalPoint(o, "c2"),
                optionalString(o, "fromSide"), optionalString(o, "toSide"), optionalString(o, "color"));
    }

    private static Frame readFrame(JsonElement e) {
        JsonObject o = object(e);
        return new Frame(requireNum(o, "x"), requireNum(o, "y"), optionalNum(o, "width"), optionalNum(o, "height"),
                optionalString(o, "color"));
    }
}
