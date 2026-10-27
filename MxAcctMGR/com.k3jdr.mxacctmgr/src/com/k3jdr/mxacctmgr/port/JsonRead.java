package com.k3jdr.mxacctmgr.port;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Reading loosely typed service answers the way the Swift casts do (`as? T`: null when the type doesn't match). */
final class JsonRead {
    private JsonRead() {}

    static JsonObject obj(JsonElement e) { return e != null && e.isJsonObject() ? e.getAsJsonObject() : null; }

    static JsonObject objOrEmpty(JsonObject o, String key) {
        JsonObject x = o == null ? null : obj(o.get(key));
        return x != null ? x : new JsonObject();
    }

    static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    static String str(JsonObject o, String key, String fallback) {
        String s = str(o, key);
        return s != null ? s : fallback;
    }

    static Double num(JsonObject o, String key) {
        if (o == null) return null;
        return num(o.get(key));
    }

    static Double num(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isNumber() ? p.getAsDouble() : null;
    }

    static double num0(JsonObject o, String key) {
        Double d = num(o, key);
        return d != null ? d : 0;
    }

    /** A whole number (as Swift's `as? Int`): null when missing or fractional. */
    static Integer integer(JsonObject o, String key) {
        Double d = num(o, key);
        return d != null && d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE ? (int) (double) d : null;
    }

    /** An array of strings; null when it isn't one. */
    static List<String> strings(JsonElement e) {
        if (e == null || !e.isJsonArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) {
            if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isString()) return null;
            out.add(x.getAsString());
        }
        return out;
    }

    static List<String> stringsOrEmpty(JsonObject o, String key) {
        List<String> s = o == null ? null : strings(o.get(key));
        return s != null ? s : new ArrayList<>();
    }

    /** An array of numbers; null when it isn't one. */
    static double[] doubles(JsonElement e) {
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) {
            Double d = num(a.get(i));
            if (d == null) return null;
            out[i] = d;
        }
        return out;
    }

    /** An array of arrays of numbers; null when it isn't one. */
    static double[][] matrix(JsonElement e) {
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        double[][] out = new double[a.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = doubles(a.get(i));
            if (out[i] == null) return null;
        }
        return out;
    }

    /** An object of numbers (in the answer's order), skipping values that aren't numbers. */
    static Map<String, Double> numberMap(JsonElement e) {
        Map<String, Double> out = new LinkedHashMap<>();
        JsonObject o = obj(e);
        if (o == null) return out;
        for (Map.Entry<String, JsonElement> x : o.entrySet()) {
            Double d = num(x.getValue());
            if (d != null) out.put(x.getKey(), d);
        }
        return out;
    }

    /** An object of numbers; null when any value isn't one (Swift's `as? [String: Double]`). */
    static Map<String, Double> strictNumberMap(JsonElement e) {
        JsonObject o = obj(e);
        if (o == null) return null;
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> x : o.entrySet()) {
            Double d = num(x.getValue());
            if (d == null) return null;
            out.put(x.getKey(), d);
        }
        return out;
    }

    /** The objects of an array (non-objects skipped); empty when it isn't one. */
    static List<JsonObject> objects(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) out.add(x.getAsJsonObject());
        return out;
    }
}
