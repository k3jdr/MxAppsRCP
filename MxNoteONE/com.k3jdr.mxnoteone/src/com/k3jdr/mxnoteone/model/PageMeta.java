package com.k3jdr.mxnoteone.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mxnoteone.model.Kinds.TagKind;

/** A page in a section's page list, with the search index for the page (Models.swift PageMeta). */
public final class PageMeta {
    public String id = Kinds.newId();
    public String title = "";
    /** 0 = page, 1 = subpage, 2 = sub-subpage. */
    public int level;
    public Instant created = SwiftJson.now();
    public Instant modified = SwiftJson.now();
    /** Subpages hidden in the page list. */
    public boolean collapsed;
    public String plainText = "";
    public List<TagItem> tags = new ArrayList<>();
    private JsonObject raw = new JsonObject();

    /** A tagged paragraph: its tag, text and whether it's checked. */
    public record TagItem(TagKind kind, String text, boolean done) {
        static TagItem fromJson(JsonObject o) {
            TagKind k = TagKind.of(SwiftJson.str(o, "kind", ""));
            return k == null ? null : new TagItem(k, SwiftJson.str(o, "text", ""), SwiftJson.bool(o, "done", false));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind.key);
            o.addProperty("text", text);
            o.addProperty("done", done);
            return o;
        }
    }

    public String displayTitle() {
        return title == null || title.isBlank() ? "Untitled Page" : title;
    }

    public static PageMeta fromJson(JsonObject o) {
        PageMeta m = new PageMeta();
        m.raw = o.deepCopy();
        m.id = SwiftJson.str(o, "id", Kinds.newId());
        m.title = SwiftJson.str(o, "title", "");
        m.level = (int) SwiftJson.num(o, "level", 0);
        m.created = SwiftJson.parseDate(SwiftJson.str(o, "created", null));
        m.modified = SwiftJson.parseDate(SwiftJson.str(o, "modified", null));
        m.collapsed = SwiftJson.bool(o, "collapsed", false);
        m.plainText = SwiftJson.str(o, "plainText", "");
        JsonArray tags = SwiftJson.arr(o, "tags");
        if (tags != null) {
            for (JsonElement e : tags) {
                if (!e.isJsonObject()) continue;
                TagItem t = TagItem.fromJson(e.getAsJsonObject());
                if (t != null) m.tags.add(t);
            }
        }
        return m;
    }

    public JsonObject toJson() {
        JsonObject o = raw.deepCopy();
        o.addProperty("id", id);
        o.addProperty("title", title);
        o.addProperty("level", level);
        o.addProperty("created", SwiftJson.date(created));
        o.addProperty("modified", SwiftJson.date(modified));
        o.addProperty("collapsed", collapsed);
        o.addProperty("plainText", plainText);
        JsonArray a = new JsonArray();
        for (TagItem t : tags) a.add(t.toJson());
        o.add("tags", a);
        return o;
    }

    public PageMeta copy() {
        return fromJson(toJson());
    }

    public boolean hasOpenTodo() {
        for (TagItem t : tags) if (t.kind() == TagKind.TODO && !t.done()) return true;
        return false;
    }
}
