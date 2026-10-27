package com.k3jdr.mxnoteone.model;

import com.google.gson.JsonObject;

/**
 * A container placed anywhere on the page (Models.swift PageElement). Text keeps its rich text as the Mac stored
 * it (RTFD, or RTF when written here) until it is edited; members this port doesn't use are kept.
 */
public final class PageElement {
    public static final String TEXT = "text", IMAGE = "image", FILE = "file", AUDIO = "audio", VIDEO = "video", TABLE = "table",
            CHART = "chart", SCORE = "score", DIAGRAM = "diagram", MODEL = "model", MAP = "map", DBML = "dbml";

    public String id = Kinds.newId();
    public String kind;
    public double x, y, width, height;
    /** Rich text (RTFD or RTF) of a text container. */
    public byte[] rtfd;
    /** File name in the notebook's assets folder (image, file, audio, video). */
    public String asset;
    /** Original name of an attached file, recording or video. */
    public String fileName;
    /** A picture of a diagram, map or video (an asset). */
    public String preview;
    public TableData table;
    private JsonObject raw = new JsonObject();

    public PageElement(String kind, double x, double y, double width) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.width = width;
    }

    public static PageElement fromJson(JsonObject o) {
        PageElement e = new PageElement(SwiftJson.str(o, "kind", TEXT), SwiftJson.num(o, "x", 0), SwiftJson.num(o, "y", 0),
                SwiftJson.num(o, "width", 200));
        e.raw = o.deepCopy();
        e.id = SwiftJson.str(o, "id", Kinds.newId());
        e.height = SwiftJson.num(o, "height", 0);
        String r = SwiftJson.str(o, "rtfd", null);
        e.rtfd = r == null ? null : SwiftJson.unbase64(r);
        e.asset = SwiftJson.str(o, "asset", null);
        e.fileName = SwiftJson.str(o, "fileName", null);
        e.preview = SwiftJson.str(o, "preview", null);
        JsonObject t = SwiftJson.obj(o, "table");
        e.table = t == null ? null : TableData.fromJson(t);
        return e;
    }

    public JsonObject toJson() {
        JsonObject o = raw.deepCopy();
        o.addProperty("id", id);
        o.addProperty("kind", kind);
        o.add("x", SwiftJson.number(x));
        o.add("y", SwiftJson.number(y));
        o.add("width", SwiftJson.number(width));
        o.add("height", SwiftJson.number(height));
        SwiftJson.putStr(o, "rtfd", rtfd == null ? null : SwiftJson.base64(rtfd));
        SwiftJson.putStr(o, "asset", asset);
        SwiftJson.putStr(o, "fileName", fileName);
        SwiftJson.putStr(o, "preview", preview);
        SwiftJson.put(o, "table", table == null ? null : table.toJson());
        return o;
    }

    public PageElement copy() {
        return fromJson(toJson());
    }

    /** A copy with a new ID (Duplicate, Paste). */
    public PageElement duplicate() {
        PageElement e = copy();
        e.id = Kinds.newId();
        return e;
    }

    /** What a container of a kind this port shows but doesn't edit is called. */
    public String kindTitle() {
        return switch (kind) {
            case TEXT -> "Text Box";
            case IMAGE -> "Picture";
            case FILE -> "File Attachment";
            case AUDIO -> "Audio Recording";
            case VIDEO -> "Video";
            case TABLE -> "Table";
            case CHART -> "Chart";
            case SCORE -> "Music Score";
            case DIAGRAM -> "Diagram (draw.io)";
            case MODEL -> "3D Model";
            case MAP -> "Map";
            case DBML -> "DBML Diagram";
            default -> kind;
        };
    }

    /** A member of the stored JSON this port doesn't model (a chart's or score's title, say). */
    public JsonObject rawMember(String key) { return SwiftJson.obj(raw, key); }

    public String rawString(String key) { return SwiftJson.str(raw, key, null); }
}
