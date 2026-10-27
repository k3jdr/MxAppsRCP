package com.k3jdr.mxnoteone.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * JSON exactly as the Mac app's JSONEncoder writes it, so a notebook keeps the same files on both systems: dates as
 * ISO 8601 in UTC without fractions (the Mac's .iso8601 decoder rejects fractions), Data as base64, "/" escaped,
 * whole numbers without ".0", and — for notebook.json — pretty printed with sorted keys and " : ".
 */
public final class SwiftJson {
    private SwiftJson() {}

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    public static JsonObject parse(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    public static String date(Instant t) {
        return ISO.format(t.truncatedTo(ChronoUnit.SECONDS));
    }

    public static Instant parseDate(String s) {
        if (s == null || s.isBlank()) return Instant.now();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(s);
            } catch (RuntimeException e2) {
                return Instant.now();
            }
        }
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    public static String base64(byte[] data) { return Base64.getEncoder().encodeToString(data); }

    public static byte[] unbase64(String s) {
        try {
            return Base64.getMimeDecoder().decode(s);
        } catch (RuntimeException e) {
            return new byte[0];
        }
    }

    // Accessors that tolerate missing or null members.

    public static String str(JsonObject o, String key, String def) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    public static double num(JsonObject o, String key, double def) {
        JsonElement e = o == null ? null : o.get(key);
        try {
            return e == null || e.isJsonNull() ? def : e.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o == null ? null : o.get(key);
        try {
            return e == null || e.isJsonNull() ? def : e.getAsBoolean();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    public static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** A number as Swift writes a Double: 120 for 120.0, 0.5, no exponent for ordinary values. */
    public static JsonPrimitive number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return new JsonPrimitive(0);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return new JsonPrimitive((long) d);
        return new JsonPrimitive(new BigDecimal(Double.toString(d)));
    }

    /** Sets a member, or removes it for null (Swift leaves out nil optionals). */
    public static void put(JsonObject o, String key, JsonElement value) {
        if (value == null || value.isJsonNull()) o.remove(key);
        else o.add(key, value);
    }

    public static void putStr(JsonObject o, String key, String value) {
        put(o, key, value == null ? null : new JsonPrimitive(value));
    }

    public static JsonElement orNull(JsonElement e) { return e == null ? JsonNull.INSTANCE : e; }

    // MARK: Writing

    public static String write(JsonElement e, boolean pretty, boolean sortKeys) {
        StringBuilder sb = new StringBuilder(4096);
        write(sb, e, pretty, sortKeys, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, JsonElement e, boolean pretty, boolean sort, int depth) {
        if (e == null || e.isJsonNull()) {
            sb.append("null");
        } else if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isString()) string(sb, p.getAsString());
            else if (p.isBoolean()) sb.append(p.getAsBoolean());
            else sb.append(numberText(p));
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) sb.append(',');
                if (pretty) {
                    sb.append('\n');
                    indent(sb, depth + 1);
                }
                write(sb, a.get(i), pretty, sort, depth + 1);
            }
            if (pretty) {
                sb.append('\n');
                indent(sb, depth);
            }
            sb.append(']');
        } else {
            JsonObject o = e.getAsJsonObject();
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(o.entrySet());
            if (sort) entries.sort(Map.Entry.comparingByKey());
            if (entries.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> en : entries) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) {
                    sb.append('\n');
                    indent(sb, depth + 1);
                }
                string(sb, en.getKey());
                sb.append(pretty ? " : " : ":");
                write(sb, en.getValue(), pretty, sort, depth + 1);
            }
            if (pretty) {
                sb.append('\n');
                indent(sb, depth);
            }
            sb.append('}');
        }
    }

    private static String numberText(JsonPrimitive p) {
        Number n = p.getAsNumber();
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) return n.toString();
        double d = n.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        String s = new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return s;
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '/' -> sb.append("\\/");
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
}
