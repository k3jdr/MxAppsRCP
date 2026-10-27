package com.k3jdr.mxjobsmgr.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.NodeKind;
import com.k3jdr.mxjobsmgr.model.Specs.StepLink;

/**
 * Your own words for the palette (PaletteEdits.swift): each element can be renamed and given a description of its
 * own, and each element and each layer's header can carry a link (web page, email address, MxNoteONE page) with hover
 * text. Kept on this PC with the app's settings, the same in every jobspace. Steps added from a renamed element take
 * its new title; steps already on a canvas keep theirs.
 */
public final class PaletteEdits {
    private PaletteEdits() {}

    private static final Prefs PREFS = Prefs.of("com.k3jdr.mxjobsmgr");
    private static final String KEY = "paletteEdits";

    private record Element(String title, String summary) {}

    private static final Map<String, Element> ELEMENTS = new HashMap<>();
    private static final Map<String, StepLink> LINKS = new HashMap<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    static {
        try {
            String raw = PREFS.get(KEY, null);
            if (raw != null) {
                JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
                JsonObject els = o.getAsJsonObject("elements");
                if (els != null) for (var e : els.entrySet()) {
                    JsonObject v = e.getValue().getAsJsonObject();
                    ELEMENTS.put(e.getKey(), new Element(str(v, "title"), str(v, "summary")));
                }
                JsonObject links = o.getAsJsonObject("links");
                if (links != null) for (var e : links.entrySet()) {
                    StepLink l = StepLink.read(e.getValue().getAsJsonObject());
                    if (l != null) LINKS.put(e.getKey(), l);
                }
            }
        } catch (RuntimeException e) {
            // unreadable settings start over
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    public static void addListener(Runnable r) { LISTENERS.add(r); }

    /** The link key of a palette element or a layer header. */
    public static String kindItem(NodeKind kind) { return "kind:" + kind.raw(); }
    public static String layerItem(Layer layer) { return "layer:" + layer.number; }

    public static String title(NodeKind kind) {
        Element e = ELEMENTS.get(kind.raw());
        return e != null && e.title != null ? e.title : kind.title;
    }

    public static String summary(NodeKind kind) {
        Element e = ELEMENTS.get(kind.raw());
        return e != null && e.summary != null ? e.summary : kind.summary;
    }

    public static boolean isEdited(NodeKind kind) { return ELEMENTS.containsKey(kind.raw()); }

    public static String itemTitle(String item) {
        if (item.startsWith("kind:")) {
            NodeKind k = NodeKind.of(item.substring(5));
            return k == null ? item : title(k);
        }
        Layer l = Layer.of(Integer.parseInt(item.substring(6)));
        return "Layer " + l.number + " · " + l.title;
    }

    /** Saves an element's title and description; empty or built-in words go back to the built-in ones. */
    public static void set(NodeKind kind, String title, String summary) {
        String t = own(title, kind.title), s = own(summary, kind.summary);
        if (t == null && s == null) ELEMENTS.remove(kind.raw());
        else ELEMENTS.put(kind.raw(), new Element(t, s));
        save();
    }

    private static String own(String text, String builtIn) {
        String t = text == null ? "" : text.strip();
        return t.isEmpty() || t.equals(builtIn) ? null : t;
    }

    public static void reset(NodeKind kind) {
        ELEMENTS.remove(kind.raw());
        save();
    }

    public static StepLink link(String item) { return LINKS.get(item); }

    public static void setLink(String item, StepLink link) {
        if (link == null) LINKS.remove(item);
        else LINKS.put(item, link);
        save();
    }

    /** The link a step shows: its own, else its palette element's. */
    public static StepLink shownLink(JobNode node) {
        return node.link != null ? node.link : LINKS.get(kindItem(node.kind));
    }

    private static void save() {
        JsonObject o = new JsonObject(), els = new JsonObject(), links = new JsonObject();
        ELEMENTS.forEach((k, e) -> {
            JsonObject v = new JsonObject();
            if (e.title != null) v.addProperty("title", e.title);
            if (e.summary != null) v.addProperty("summary", e.summary);
            els.add(k, v);
        });
        LINKS.forEach((k, l) -> links.add(k, l.toJson()));
        o.add("elements", els);
        o.add("links", links);
        PREFS.put(KEY, o.toString());
        LISTENERS.forEach(Runnable::run);
    }
}
