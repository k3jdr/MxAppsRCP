package com.k3jdr.mxjobsmgr.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Reading with the Mac app's tolerance ({@code c.value(key, fallback)}): a key that is missing or holds a value of
 * the wrong type gives the fallback, so files from earlier versions keep opening. And the writers' small helpers.
 */
public final class J {
    private J() {}

    public static String newId() {
        return UUID.randomUUID().toString().toUpperCase();
    }

    private static JsonPrimitive prim(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
    }

    public static String str(JsonObject o, String key, String fallback) {
        JsonPrimitive p = prim(o, key);
        return p != null && p.isString() ? p.getAsString() : fallback;
    }

    /** A string or null (an optional that is absent or invalid). */
    public static String optStr(JsonObject o, String key) {
        return str(o, key, null);
    }

    public static int integer(JsonObject o, String key, int fallback) {
        Integer v = optInt(o, key);
        return v != null ? v : fallback;
    }

    public static Integer optInt(JsonObject o, String key) {
        JsonPrimitive p = prim(o, key);
        if (p == null || !p.isNumber()) return null;
        double d = p.getAsDouble();
        if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) return null;
        return (int) d;
    }

    public static double dbl(JsonObject o, String key, double fallback) {
        JsonPrimitive p = prim(o, key);
        return p != null && p.isNumber() ? p.getAsDouble() : fallback;
    }

    public static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonPrimitive p = prim(o, key);
        return p != null && p.isBoolean() ? p.getAsBoolean() : fallback;
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** A UUID as the Mac app writes it (upper case); the fallback when absent or not a UUID. */
    public static String uuid(JsonObject o, String key, String fallback) {
        String s = str(o, key, null);
        if (s == null) return fallback;
        try {
            return UUID.fromString(s).toString().toUpperCase();
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** A list read element by element; an element that can't be read makes the whole list the fallback (empty). */
    public static <T> List<T> list(JsonObject o, String key, Function<JsonObject, T> reader) {
        List<T> out = new ArrayList<>();
        if (o == null) return out;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonObject()) return new ArrayList<>();
            T t = reader.apply(item.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    public static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) return new ArrayList<>();
            out.add(item.getAsString());
        }
        return out;
    }

    public static <E extends Enum<E> & Raw> E enumOf(JsonObject o, String key, E fallback) {
        String s = str(o, key, null);
        if (s == null) return fallback;
        for (E e : fallback.getDeclaringClass().getEnumConstants()) if (e.raw().equals(s)) return e;
        return fallback;
    }

    /** An enum with the raw value the Mac app writes. */
    public interface Raw {
        String raw();
    }

    // Writing

    public static JsonArray array(List<? extends Writable> items) {
        JsonArray a = new JsonArray();
        for (Writable w : items) a.add(w.toJson());
        return a;
    }

    public static JsonArray stringArray(List<String> items) {
        JsonArray a = new JsonArray();
        for (String s : items) a.add(s);
        return a;
    }

    /** Something written as a JSON object. */
    public interface Writable {
        JsonObject toJson();
    }
}
