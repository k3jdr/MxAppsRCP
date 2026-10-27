package com.k3jdr.mxnoteone.store;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mxnoteone.model.PageElement;
import com.k3jdr.mxnoteone.model.PageMeta.TagItem;
import com.k3jdr.mxnoteone.model.SwiftJson;
import com.k3jdr.mxnoteone.text.Paragraphs;
import com.k3jdr.mxnoteone.text.RichCodec;
import com.k3jdr.mxnoteone.text.RtfWriter;

/** The search index of a page — its plain text and tagged paragraphs — as the Mac's PageIndexer makes it. */
public final class PageIndexer {
    private PageIndexer() {}

    public record Index(String text, List<TagItem> tags) {}

    /** The texts of a page's containers (for containers not shown on a canvas). */
    public static List<String> texts(List<PageElement> elements) {
        List<String> texts = new ArrayList<>();
        for (PageElement e : elements) {
            String t = text(e);
            if (t != null) texts.add(t);
        }
        return texts;
    }

    public static String text(PageElement e) {
        switch (e.kind) {
            case PageElement.TEXT -> {
                if (e.rtfd == null) return null;
                var rt = RichCodec.decode(e.rtfd);
                StringBuilder sb = new StringBuilder(rt.text);
                String last = null;
                for (var s : rt.spans) {
                    if (s.style().tip() != null && !s.style().tip().equals(last)) sb.append('\n').append(RtfWriter.tipText(s.style().tip()));
                    last = s.style().tip();
                }
                return sb.toString();
            }
            case PageElement.TABLE -> {
                if (e.table == null) return "";
                StringBuilder sb = new StringBuilder();
                for (List<String> row : e.table.rows) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(String.join(" ", row));
                }
                return sb.toString();
            }
            case PageElement.FILE, PageElement.AUDIO, PageElement.VIDEO, PageElement.IMAGE -> {
                StringBuilder sb = new StringBuilder();
                if (e.fileName != null) sb.append(e.fileName);
                JsonArray transcript = null;
                try {
                    JsonObject o = e.toJson();
                    transcript = SwiftJson.arr(o, "transcript");
                } catch (RuntimeException ex) {
                    transcript = null;
                }
                if (transcript != null) {
                    List<String> parts = new ArrayList<>();
                    for (JsonElement t : transcript) parts.add(SwiftJson.str(t.getAsJsonObject(), "text", ""));
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(String.join(" ", parts));
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            case PageElement.DIAGRAM -> {
                List<String> parts = new ArrayList<>();
                if (e.fileName != null) parts.add(e.fileName);
                String labels = e.rawString("diagramText");
                if (labels != null) parts.add(labels);
                return String.join(" ", parts);
            }
            case PageElement.CHART -> {
                return SwiftJson.str(e.rawMember("chart"), "title", null);
            }
            case PageElement.SCORE -> {
                return SwiftJson.str(e.rawMember("score"), "title", null);
            }
            case PageElement.MODEL -> {
                return SwiftJson.str(e.rawMember("cad"), "name", null);
            }
            case PageElement.MAP -> {
                return SwiftJson.str(e.rawMember("map"), "title", null);
            }
            default -> {
                return null;
            }
        }
    }

    public static Index index(List<String> texts) {
        List<TagItem> tags = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String text : texts) {
            for (String line : text.split("[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]", -1)) {
                Paragraphs.Info info = Paragraphs.parse(line);
                if (info.tag == null) continue;
                tags.add(new TagItem(info.tag, line.substring(Math.min(line.length(), info.contentStart())).strip(), info.tagDone));
            }
            String t = text.strip();
            if (!t.isEmpty()) kept.add(t);
        }
        return new Index(String.join("\n", kept), tags);
    }

    public static Index index(Iterable<PageElement> elements) {
        List<PageElement> list = new ArrayList<>();
        elements.forEach(list::add);
        return index(texts(list));
    }
}
