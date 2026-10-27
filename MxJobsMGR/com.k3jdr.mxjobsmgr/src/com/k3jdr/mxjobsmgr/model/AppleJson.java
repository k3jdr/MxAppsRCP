package com.k3jdr.mxjobsmgr.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * JSON exactly as the macOS app's JSONEncoder writes a jobspace ({@code .prettyPrinted, .sortedKeys,
 * .withoutEscapingSlashes}): two-space indent, {@code "key" : value}, keys sorted, empty arrays as
 * {@code [\n\n  ]}, slashes and non-ASCII characters as they are. A file saved here is byte-for-byte what the Mac
 * app saves, so the same jobspace can go back and forth (and diff cleanly in git).
 */
public final class AppleJson {
    private AppleJson() {}

    public static String write(JsonElement e) {
        StringBuilder sb = new StringBuilder();
        write(sb, e, 0, true);
        return sb.toString();
    }

    /** Compact JSON with sorted keys (the {@code {{json:…}}} placeholders, the Python step's stdin). */
    public static String compact(JsonElement e) {
        StringBuilder sb = new StringBuilder();
        write(sb, e, 0, false);
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("  ");
    }

    private static void write(StringBuilder sb, JsonElement e, int level, boolean pretty) {
        if (e == null || e.isJsonNull()) {
            sb.append("null");
        } else if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) sb.append(p.getAsBoolean());
            else if (p.isNumber()) sb.append(number(p.getAsNumber()));
            else string(sb, p.getAsString());
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (!pretty) {
                sb.append('[');
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) sb.append(',');
                    write(sb, a.get(i), level, false);
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            if (a.isEmpty()) {
                sb.append('\n');
            } else {
                for (int i = 0; i < a.size(); i++) {
                    indent(sb, level + 1);
                    write(sb, a.get(i), level + 1, true);
                    sb.append(i < a.size() - 1 ? ",\n" : "\n");
                }
            }
            indent(sb, level);
            sb.append(']');
        } else {
            JsonObject o = e.getAsJsonObject();
            List<String> keys = new ArrayList<>(o.keySet());
            keys.sort(null);
            if (!pretty) {
                sb.append('{');
                boolean first = true;
                for (String k : keys) {
                    if (!first) sb.append(',');
                    first = false;
                    string(sb, k);
                    sb.append(':');
                    write(sb, o.get(k), level, false);
                }
                sb.append('}');
                return;
            }
            sb.append("{\n");
            if (keys.isEmpty()) {
                sb.append('\n');
            } else {
                for (int i = 0; i < keys.size(); i++) {
                    indent(sb, level + 1);
                    string(sb, keys.get(i));
                    sb.append(" : ");
                    write(sb, o.get(keys.get(i)), level + 1, true);
                    sb.append(i < keys.size() - 1 ? ",\n" : "\n");
                }
            }
            indent(sb, level);
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
            return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return Double.toString(d);
        }
    }

    public static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
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

    /** A JSON object from alternating keys and values (for building outputs). */
    public static JsonObject object(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.add((String) kv[i], toJson(kv[i + 1]));
        return o;
    }

    public static JsonElement toJson(Object v) {
        if (v == null) return com.google.gson.JsonNull.INSTANCE;
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
