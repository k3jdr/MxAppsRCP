package com.k3jdr.mx.core.prefs;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

/**
 * An app's remembered settings (the macOS apps' UserDefaults): an instance-scope preference node, kept in the
 * app's own data folder and written as soon as a value changes.
 */
public final class Prefs {
    private final Preferences node;

    private Prefs(Preferences node) {
        this.node = node;
    }

    /** The node of a bundle or an area, e.g. {@code Prefs.of("com.k3jdr.mxdatasql")}. */
    public static Prefs of(String nodeName) {
        return new Prefs(InstanceScope.INSTANCE.getNode(nodeName));
    }

    /** A child node, e.g. per screen or per table. */
    public Prefs child(String name) {
        return new Prefs(node.node(name));
    }

    public String get(String key, String def) { return node.get(key, def); }
    public int getInt(String key, int def) { return node.getInt(key, def); }
    public long getLong(String key, long def) { return node.getLong(key, def); }
    public double getDouble(String key, double def) { return node.getDouble(key, def); }
    public boolean getBoolean(String key, boolean def) { return node.getBoolean(key, def); }

    public boolean has(String key) { return node.get(key, null) != null; }

    public void put(String key, String value) {
        if (value == null) node.remove(key); else node.put(key, value);
        flush();
    }
    public void putInt(String key, int value) { node.putInt(key, value); flush(); }
    public void putLong(String key, long value) { node.putLong(key, value); flush(); }
    public void putDouble(String key, double value) { node.putDouble(key, value); flush(); }
    public void putBoolean(String key, boolean value) { node.putBoolean(key, value); flush(); }

    public void remove(String key) { node.remove(key); flush(); }

    /** A list of strings, kept as a JSON array (recent files, column orders, open tabs). */
    public List<String> getList(String key) {
        List<String> out = new ArrayList<>();
        String raw = node.get(key, null);
        if (raw == null || raw.isBlank()) return out;
        try {
            JsonArray array = JsonParser.parseString(raw).getAsJsonArray();
            array.forEach(e -> out.add(e.getAsString()));
        } catch (RuntimeException e) {
            // an unreadable value starts the list over
        }
        return out;
    }

    public void putList(String key, List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        put(key, array.toString());
    }

    /** Puts {@code value} first in the list under {@code key}, keeping at most {@code max} entries. */
    public List<String> pushRecent(String key, String value, int max) {
        List<String> list = getList(key);
        list.remove(value);
        list.add(0, value);
        while (list.size() > max) list.remove(list.size() - 1);
        putList(key, list);
        return list;
    }

    public void flush() {
        try {
            node.flush();
        } catch (BackingStoreException e) {
            // settings are a convenience: the app keeps working with the values in memory
        }
    }
}
