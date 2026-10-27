package com.k3jdr.mxacctmgr.bank;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Reading the accounts service's JSON: a field as text, number or list, null when absent or null. */
public final class J {
    private J() {}

    public static JsonObject obj(JsonElement e) { return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject(); }

    public static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || e.isJsonNull()) return null;
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public static String str(JsonObject o, String k, String def) {
        String s = str(o, k);
        return s == null ? def : s;
    }

    public static Double dbl(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static double num(JsonObject o, String k) {
        Double d = dbl(o, k);
        return d == null ? 0 : d;
    }

    public static Integer integer(JsonObject o, String k) {
        Double d = dbl(o, k);
        return d == null ? null : (int) Math.round(d);
    }

    public static int i(JsonObject o, String k) {
        Integer v = integer(o, k);
        return v == null ? 0 : v;
    }

    public static boolean bool(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    public static List<JsonObject> list(JsonObject o, String k) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) out.add(x.getAsJsonObject());
        return out;
    }

    public static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return out;
    }

    public static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    /** Adds a text field, or JSON null for an empty one. */
    public static void put(JsonObject o, String k, String v) {
        if (v == null) o.add(k, com.google.gson.JsonNull.INSTANCE);
        else o.addProperty(k, v);
    }

    public static void put(JsonObject o, String k, Double v) {
        if (v == null) o.add(k, com.google.gson.JsonNull.INSTANCE);
        else o.addProperty(k, v);
    }
}
