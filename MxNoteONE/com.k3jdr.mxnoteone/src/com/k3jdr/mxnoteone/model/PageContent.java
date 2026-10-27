package com.k3jdr.mxnoteone.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mxnoteone.model.Kinds.PageColor;
import com.k3jdr.mxnoteone.model.Kinds.RuleLines;

/**
 * pages/&lt;id&gt;.json: a page's title, style and containers (Models.swift PageContent). Containers of kinds this
 * port can't edit (scores, diagrams, maps, 3D models, DBML, videos, charts) and the ink strokes are kept exactly as
 * they were read, so saving a page on Windows never loses them.
 */
public final class PageContent {
    public String id;
    public String title = "";
    public Instant created = SwiftJson.now();
    public PageColor color = PageColor.NONE;
    public RuleLines rules = RuleLines.NONE;
    public List<PageElement> elements = new ArrayList<>();
    private JsonArray strokes = new JsonArray();
    private JsonObject styleRaw = new JsonObject();
    private JsonObject raw = new JsonObject();

    public PageContent(String id) {
        this.id = id;
    }

    public boolean hasInk() { return !strokes.isEmpty(); }

    public static PageContent fromJson(JsonObject o) {
        PageContent p = new PageContent(SwiftJson.str(o, "id", Kinds.newId()));
        p.raw = o.deepCopy();
        p.title = SwiftJson.str(o, "title", "");
        p.created = SwiftJson.parseDate(SwiftJson.str(o, "created", null));
        JsonObject style = SwiftJson.obj(o, "style");
        if (style != null) {
            p.styleRaw = style.deepCopy();
            p.color = PageColor.of(SwiftJson.str(style, "color", "none"));
            p.rules = RuleLines.of(SwiftJson.str(style, "rules", "none"));
        }
        JsonArray els = SwiftJson.arr(o, "elements");
        if (els != null) for (JsonElement e : els) if (e.isJsonObject()) p.elements.add(PageElement.fromJson(e.getAsJsonObject()));
        JsonArray ink = SwiftJson.arr(o, "strokes");
        if (ink != null) p.strokes = ink.deepCopy();
        return p;
    }

    public JsonObject toJson() {
        JsonObject o = raw.deepCopy();
        o.addProperty("id", id);
        o.addProperty("title", title);
        o.addProperty("created", SwiftJson.date(created));
        JsonObject style = styleRaw.deepCopy();
        style.addProperty("color", color.key());
        style.addProperty("rules", rules.key);
        o.add("style", style);
        JsonArray a = new JsonArray();
        for (PageElement e : elements) a.add(e.toJson());
        o.add("elements", a);
        o.add("strokes", strokes.deepCopy());
        return o;
    }

    public PageContent copy() {
        return fromJson(toJson());
    }

    /** The ink strokes' bounds (minX, minY, maxX, maxY), or null when there is no ink. */
    public double[] inkBounds() {
        double[] b = null;
        for (JsonElement s : strokes) {
            if (!s.isJsonObject()) continue;
            JsonArray pts = SwiftJson.arr(s.getAsJsonObject(), "points");
            if (pts == null) continue;
            for (JsonElement p : pts) {
                if (!p.isJsonArray() || p.getAsJsonArray().size() < 2) continue;
                double x = p.getAsJsonArray().get(0).getAsDouble(), y = p.getAsJsonArray().get(1).getAsDouble();
                if (b == null) b = new double[] { x, y, x, y };
                b[0] = Math.min(b[0], x);
                b[1] = Math.min(b[1], y);
                b[2] = Math.max(b[2], x);
                b[3] = Math.max(b[3], y);
            }
        }
        return b;
    }

    /** The ink strokes as stored (for drawing them). */
    public JsonArray strokes() { return strokes; }
}
