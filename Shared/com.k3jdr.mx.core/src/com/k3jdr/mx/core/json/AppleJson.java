package com.k3jdr.mx.core.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * JSON exactly as the macOS apps' {@code JSONEncoder} writes it, so a file saved on Windows is byte for byte what the
 * Mac app saves (and diffs cleanly in git):
 *
 * <ul>
 * <li>{@code .prettyPrinted}: two-space indent, {@code "key" : value}, and empty collections as {@code [\n\n  ]}
 * (checked against jobspaces and workspaces the Mac apps wrote);</li>
 * <li>{@code .sortedKeys}: keys in code-point order;</li>
 * <li>slashes escaped as {@code \/} unless the encoder had {@code .withoutEscapingSlashes};</li>
 * <li>whole numbers without a fraction (300, not 300.0), others in their shortest form; non-ASCII as it is.</li>
 * </ul>
 */
public final class AppleJson {
    private AppleJson() {}

    /** The encoder's output formatting. */
    public record Format(boolean pretty, boolean sortedKeys, boolean escapeSlashes) {
        /** {@code [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]}: MxJobsMGR's jobspaces. */
        public static final Format PRETTY_SORTED = new Format(true, true, false);
        /** {@code [.prettyPrinted, .sortedKeys]}: MxDataSQL's workspaces, MxNoteONE's notebook.json. */
        public static final Format PRETTY_SORTED_ESCAPED = new Format(true, true, true);
        /** {@code [.sortedKeys, .withoutEscapingSlashes]}: compact, as placeholders and service calls take it. */
        public static final Format COMPACT_SORTED = new Format(false, true, false);
        /** The default encoder: compact, keys in the order they were added, slashes escaped. */
        public static final Format COMPACT = new Format(false, false, true);
    }

    public static String write(JsonElement e, Format format) {
        StringBuilder sb = new StringBuilder(1024);
        write(sb, e, 0, format);
        return sb.toString();
    }

    /** Pretty, sorted, slashes as they are (a jobspace). */
    public static String write(JsonElement e) {
        return write(e, Format.PRETTY_SORTED);
    }

    /** Compact with sorted keys, slashes as they are. */
    public static String compact(JsonElement e) {
        return write(e, Format.COMPACT_SORTED);
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("  ");
    }

    private static void write(StringBuilder sb, JsonElement e, int level, Format f) {
        if (e == null || e.isJsonNull()) {
            sb.append("null");
        } else if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) sb.append(p.getAsBoolean());
            else if (p.isNumber()) sb.append(number(p.getAsNumber()));
            else string(sb, p.getAsString(), f.escapeSlashes());
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            sb.append('[');
            if (f.pretty()) {
                sb.append('\n');
                if (a.isEmpty()) sb.append('\n');
                for (int i = 0; i < a.size(); i++) {
                    indent(sb, level + 1);
                    write(sb, a.get(i), level + 1, f);
                    sb.append(i < a.size() - 1 ? ",\n" : "\n");
                }
                indent(sb, level);
            } else {
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) sb.append(',');
                    write(sb, a.get(i), level, f);
                }
            }
            sb.append(']');
        } else {
            JsonObject o = e.getAsJsonObject();
            List<String> keys = new ArrayList<>(o.keySet());
            if (f.sortedKeys()) keys.sort(null);
            sb.append('{');
            if (f.pretty()) {
                sb.append('\n');
                if (keys.isEmpty()) sb.append('\n');
                for (int i = 0; i < keys.size(); i++) {
                    indent(sb, level + 1);
                    string(sb, keys.get(i), f.escapeSlashes());
                    sb.append(" : ");
                    write(sb, o.get(keys.get(i)), level + 1, f);
                    sb.append(i < keys.size() - 1 ? ",\n" : "\n");
                }
                indent(sb, level);
            } else {
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) sb.append(',');
                    string(sb, keys.get(i), f.escapeSlashes());
                    sb.append(':');
                    write(sb, o.get(keys.get(i)), level, f);
                }
            }
            sb.append('}');
        }
    }

    /** Whole numbers without a fraction (300, not 300.0); others in their shortest form (0.4). */
    public static String number(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) return n.toString();
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        try {
            return new BigDecimal(n instanceof BigDecimal ? n.toString() : Double.toString(d)).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return Double.toString(d);
        }
    }

    /** A Double as Swift encodes it: 120 for 120.0, 0.5 for 0.5. */
    public static JsonPrimitive number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return new JsonPrimitive(0);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return new JsonPrimitive((long) d);
        return new JsonPrimitive(new BigDecimal(Double.toString(d)));
    }

    /** A JSON string literal, slashes as they are. */
    public static void string(StringBuilder sb, String s) {
        string(sb, s, false);
    }

    public static void string(StringBuilder sb, String s, boolean escapeSlashes) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '/' -> sb.append(escapeSlashes ? "\\/" : "/");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        string(sb, s);
        return sb.toString();
    }

    /** A JSON object from alternating keys and values. */
    public static JsonObject object(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.add((String) kv[i], toJson(kv[i + 1]));
        return o;
    }

    /** A Java value (string, number, boolean, map, iterable, JSON) as JSON. */
    public static JsonElement toJson(Object v) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof JsonElement e) return e;
        if (v instanceof String s) return new JsonPrimitive(s);
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            m.forEach((k, val) -> o.add(String.valueOf(k), toJson(val)));
            return o;
        }
        if (v instanceof Iterable<?> it) {
            JsonArray a = new JsonArray();
            it.forEach(x -> a.add(toJson(x)));
            return a;
        }
        return new JsonPrimitive(String.valueOf(v));
    }
}
