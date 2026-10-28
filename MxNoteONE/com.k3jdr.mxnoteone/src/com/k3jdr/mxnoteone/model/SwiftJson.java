package com.k3jdr.mxnoteone.model;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.k3jdr.mx.core.json.AppleJson;

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
        return AppleJson.number(d);
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

    /**
     * The text JSONEncoder writes, slashes escaped as the Mac's default encoder does: pretty ({@code " : "}, two-space
     * indent) and sorted for notebook.json, compact in insertion order elsewhere. The shared core's AppleJson does it.
     */
    public static String write(JsonElement e, boolean pretty, boolean sortKeys) {
        return AppleJson.write(e, new AppleJson.Format(pretty, sortKeys, true));
    }
}
