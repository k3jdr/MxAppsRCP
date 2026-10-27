package com.k3jdr.mxnoteone.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.SecretKey;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mxnoteone.model.NotebookManifest;
import com.k3jdr.mxnoteone.model.PageMeta;
import com.k3jdr.mxnoteone.model.SwiftJson;

/**
 * An open notebook: its folder (Name.mxnotebook), notebook.json, the page lists of its sections (locked sections
 * aren't loaded) and the keys of unlocked password-protected sections.
 */
public final class Notebook {
    public Path folder;
    public NotebookManifest manifest;
    /** Loaded page lists, by section ID. */
    public final Map<String, SectionContent> sections = new HashMap<>();
    public final Set<String> unlocked = new HashSet<>();
    public final Map<String, SecretKey> keys = new HashMap<>();

    public Notebook(Path folder, NotebookManifest manifest) {
        this.folder = folder;
        this.manifest = manifest;
    }

    public String id() { return manifest.id; }
    public String name() { return manifest.name; }

    public Path manifestFile() { return folder.resolve("notebook.json"); }
    public Path sectionFile(String id) { return folder.resolve("sections").resolve(id + ".json"); }
    public Path pageFile(String id) { return folder.resolve("pages").resolve(id + ".json"); }
    public Path assetFile(String name) { return folder.resolve("assets").resolve(name); }

    /** sections/&lt;id&gt;.json: a section's page list with its search index. */
    public static final class SectionContent {
        public List<PageMeta> pages = new ArrayList<>();
        private JsonObject raw = new JsonObject();

        public static SectionContent fromJson(JsonObject o) {
            SectionContent s = new SectionContent();
            s.raw = o.deepCopy();
            JsonArray a = SwiftJson.arr(o, "pages");
            if (a != null) for (JsonElement e : a) if (e.isJsonObject()) s.pages.add(PageMeta.fromJson(e.getAsJsonObject()));
            return s;
        }

        public JsonObject toJson() {
            JsonObject o = raw.deepCopy();
            JsonArray a = new JsonArray();
            for (PageMeta m : pages) a.add(m.toJson());
            o.add("pages", a);
            return o;
        }

        public PageMeta find(String pageId) {
            for (PageMeta m : pages) if (m.id.equals(pageId)) return m;
            return null;
        }

        public int indexOf(String pageId) {
            for (int i = 0; i < pages.size(); i++) if (pages.get(i).id.equals(pageId)) return i;
            return -1;
        }
    }
}
