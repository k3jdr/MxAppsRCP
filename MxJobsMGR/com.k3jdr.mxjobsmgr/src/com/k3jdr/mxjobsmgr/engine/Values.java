package com.k3jdr.mxjobsmgr.engine;

import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.k3jdr.mx.core.json.AppleJson;

/** The run's JSON values (Gson trees): dotted-path lookup, text for the console and the checks. */
public final class Values {
    private Values() {}

    /**
     * Dotted path lookup; array elements by index ("filings.recent.form.0"). Keys may contain dots themselves
     * ("Global Quote.05. price"): the longest matching key wins at each level.
     */
    public static JsonElement lookup(String path, JsonElement json) {
        if (json == null) return null;
        return lookup(Arrays.asList(path.split("\\.", -1)), json);
    }

    private static JsonElement lookup(List<String> parts, JsonElement json) {
        if (parts.isEmpty()) return json;
        if (json.isJsonObject()) {
            JsonObject o = json.getAsJsonObject();
            for (int n = parts.size(); n >= 1; n--) {
                String key = String.join(".", parts.subList(0, n));
                JsonElement next = o.get(key);
                if (next != null) {
                    JsonElement found = lookup(parts.subList(n, parts.size()), next);
                    if (found != null) return found;
                }
            }
            return null;
        }
        if (json.isJsonArray()) {
            JsonArray a = json.getAsJsonArray();
            try {
                int i = Integer.parseInt(parts.get(0));
                if (i >= 0 && i < a.size()) return lookup(parts.subList(1, parts.size()), a.get(i));
            } catch (NumberFormatException e) {
                // not an index
            }
        }
        return null;
    }

    public static boolean present(JsonElement e) {
        return e != null && !e.isJsonNull();
    }

    public static boolean isNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    public static boolean isString(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    /** As the console and the prompts show a value: strings as they are, the rest as pretty JSON. */
    public static String pretty(JsonElement value) {
        if (value == null) return "";
        if (isString(value)) return value.getAsString();
        return AppleJson.write(value);
    }

    /** A value in a check's message: text, a number, true/false, null, or compact JSON (60 characters). */
    public static String describe(JsonElement value) {
        if (!present(value)) return "null";
        if (value.isJsonPrimitive()) {
            JsonPrimitive p = value.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isBoolean()) return p.getAsBoolean() ? "true" : "false";
            return AppleJson.number(p.getAsNumber());
        }
        String s = AppleJson.compact(value);
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** A value as JSON text ({{json:…}}): strings quoted and escaped, the rest compact with sorted keys. */
    public static String jsonText(JsonElement value) {
        if (!present(value)) return "null";
        if (isString(value)) return AppleJson.quote(value.getAsString());
        return AppleJson.compact(value);
    }

    /** Parses JSON text (fragments allowed); null when it isn't JSON. */
    public static JsonElement parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonElement e = JsonParser.parseString(text);
            return e;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sets {@code value} at a dotted path, creating objects on the way. */
    public static void insert(JsonElement value, String path, JsonObject into) {
        String[] parts = path.split("\\.");
        if (parts.length == 0 || parts[0].isEmpty()) return;
        JsonObject at = into;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement child = at.get(parts[i]);
            if (child == null || !child.isJsonObject()) {
                child = new JsonObject();
                at.add(parts[i], child);
            }
            at = child.getAsJsonObject();
        }
        at.add(parts[parts.length - 1], value == null ? JsonNull.INSTANCE : value);
    }

    public static double asDouble(JsonElement e, double fallback) {
        return isNumber(e) ? e.getAsDouble() : fallback;
    }

    public static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
