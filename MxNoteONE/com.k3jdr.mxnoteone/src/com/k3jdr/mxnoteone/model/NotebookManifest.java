package com.k3jdr.mxnoteone.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mxnoteone.model.Kinds.NoteColor;

/**
 * notebook.json: the notebook's name and colour, its section tree and its Recycle Bin (Models.swift
 * NotebookManifest). Members this port doesn't use (the OneDrive link) are kept as they were.
 */
public final class NotebookManifest {
    public int formatVersion = 1;
    public String id = Kinds.newId();
    public String name;
    public NoteColor color = NoteColor.PURPLE;
    public Instant created = SwiftJson.now();
    public List<SectionNode> sections = new ArrayList<>();
    public List<TrashedItem> trash = new ArrayList<>();
    private JsonObject raw = new JsonObject();

    /** A deleted page or section, kept in the notebook's Recycle Bin for 60 days. */
    public static final class TrashedItem {
        public String id = Kinds.newId();
        public boolean section;
        public Instant deleted = SwiftJson.now();
        public String name = "";
        public PageMeta page;
        public String fromSectionId;
        public String fromSectionName = "";
        public boolean encrypted;
        public SectionNode node;
        private JsonObject raw = new JsonObject();

        public static TrashedItem fromJson(JsonObject o) {
            TrashedItem t = new TrashedItem();
            t.raw = o.deepCopy();
            t.id = SwiftJson.str(o, "id", Kinds.newId());
            t.section = "section".equals(SwiftJson.str(o, "kind", "page"));
            t.deleted = SwiftJson.parseDate(SwiftJson.str(o, "deleted", null));
            t.name = SwiftJson.str(o, "name", "");
            JsonObject p = SwiftJson.obj(o, "page");
            t.page = p == null ? null : PageMeta.fromJson(p);
            t.fromSectionId = SwiftJson.str(o, "fromSectionID", null);
            t.fromSectionName = SwiftJson.str(o, "fromSectionName", "");
            t.encrypted = SwiftJson.bool(o, "encrypted", false);
            JsonObject s = SwiftJson.obj(o, "section");
            t.node = s == null ? null : SectionNode.fromJson(s);
            return t;
        }

        public JsonObject toJson() {
            JsonObject o = raw.deepCopy();
            o.addProperty("id", id);
            o.addProperty("kind", section ? "section" : "page");
            o.addProperty("deleted", SwiftJson.date(deleted));
            o.addProperty("name", name);
            SwiftJson.put(o, "page", page == null ? null : page.toJson());
            SwiftJson.putStr(o, "fromSectionID", fromSectionId);
            o.addProperty("fromSectionName", fromSectionName);
            o.addProperty("encrypted", encrypted);
            SwiftJson.put(o, "section", node == null ? null : node.toJson());
            return o;
        }
    }

    public static NotebookManifest fromJson(JsonObject o) {
        NotebookManifest m = new NotebookManifest();
        m.raw = o.deepCopy();
        m.formatVersion = (int) SwiftJson.num(o, "formatVersion", 1);
        m.id = SwiftJson.str(o, "id", Kinds.newId());
        m.name = SwiftJson.str(o, "name", "Notebook");
        m.color = NoteColor.of(SwiftJson.str(o, "color", "purple"));
        m.created = SwiftJson.parseDate(SwiftJson.str(o, "created", null));
        JsonArray s = SwiftJson.arr(o, "sections");
        if (s != null) for (JsonElement e : s) if (e.isJsonObject()) m.sections.add(SectionNode.fromJson(e.getAsJsonObject()));
        JsonArray t = SwiftJson.arr(o, "trash");
        if (t != null) for (JsonElement e : t) if (e.isJsonObject()) m.trash.add(TrashedItem.fromJson(e.getAsJsonObject()));
        return m;
    }

    public JsonObject toJson() {
        JsonObject o = raw.deepCopy();
        o.addProperty("formatVersion", formatVersion);
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("color", color.key());
        o.addProperty("created", SwiftJson.date(created));
        JsonArray s = new JsonArray();
        for (SectionNode n : sections) s.add(n.toJson());
        o.add("sections", s);
        JsonArray t = new JsonArray();
        for (TrashedItem i : trash) t.add(i.toJson());
        o.add("trash", t);
        return o;
    }

    /** True when the notebook is synced with OneNote on OneDrive (the Mac app's ☁︎). */
    public boolean isOneDrive() { return raw.has("oneNote"); }
}
