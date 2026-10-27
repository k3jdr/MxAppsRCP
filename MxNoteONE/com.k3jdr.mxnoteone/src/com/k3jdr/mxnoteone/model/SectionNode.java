package com.k3jdr.mxnoteone.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mxnoteone.model.Kinds.NoteColor;

/** A section or a section group in a notebook's section tree (Models.swift SectionNode). */
public final class SectionNode {
    public String id = Kinds.newId();
    public boolean group;
    public String name;
    public NoteColor color = NoteColor.BLUE;
    /** Members of a section group (null for sections). */
    public List<SectionNode> children;
    /** Password protection {salt, verifier, hint} as stored, or null. */
    public JsonObject protection;
    private JsonObject raw = new JsonObject();

    public SectionNode() {}

    public static SectionNode section(String name, NoteColor color) {
        SectionNode n = new SectionNode();
        n.name = name;
        n.color = color;
        return n;
    }

    public static SectionNode group(String name) {
        SectionNode n = new SectionNode();
        n.group = true;
        n.name = name;
        n.color = NoteColor.GRAY;
        n.children = new ArrayList<>();
        return n;
    }

    public boolean isProtected() { return protection != null; }

    public byte[] salt() { return SwiftJson.unbase64(SwiftJson.str(protection, "salt", "")); }
    public byte[] verifier() { return SwiftJson.unbase64(SwiftJson.str(protection, "verifier", "")); }
    public String hint() { return SwiftJson.str(protection, "hint", ""); }

    public static SectionNode fromJson(JsonObject o) {
        SectionNode n = new SectionNode();
        n.raw = o.deepCopy();
        n.id = SwiftJson.str(o, "id", Kinds.newId());
        n.group = "group".equals(SwiftJson.str(o, "kind", "section"));
        n.name = SwiftJson.str(o, "name", "");
        n.color = NoteColor.of(SwiftJson.str(o, "color", "blue"));
        JsonArray kids = SwiftJson.arr(o, "children");
        if (kids != null) {
            n.children = new ArrayList<>();
            for (JsonElement e : kids) if (e.isJsonObject()) n.children.add(fromJson(e.getAsJsonObject()));
        } else if (n.group) {
            n.children = new ArrayList<>();
        }
        n.protection = SwiftJson.obj(o, "protection");
        if (n.protection != null) n.protection = n.protection.deepCopy();
        return n;
    }

    public JsonObject toJson() {
        JsonObject o = raw.deepCopy();
        o.addProperty("id", id);
        o.addProperty("kind", group ? "group" : "section");
        o.addProperty("name", name);
        o.addProperty("color", color.key());
        if (children != null) {
            JsonArray a = new JsonArray();
            for (SectionNode c : children) a.add(c.toJson());
            o.add("children", a);
        } else {
            o.remove("children");
        }
        SwiftJson.put(o, "protection", protection);
        return o;
    }

    public SectionNode copy() {
        return fromJson(toJson());
    }

    /** This node and everything under it, depth first. */
    public void forEach(Consumer<SectionNode> body) {
        body.accept(this);
        if (children != null) for (SectionNode c : children) c.forEach(body);
    }

    // MARK: Tree helpers (Models.swift: extension Array where Element == SectionNode)

    public static SectionNode find(List<SectionNode> nodes, String id) {
        for (SectionNode n : nodes) {
            if (n.id.equals(id)) return n;
            if (n.children != null) {
                SectionNode f = find(n.children, id);
                if (f != null) return f;
            }
        }
        return null;
    }

    public static SectionNode remove(List<SectionNode> nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id.equals(id)) return nodes.remove(i);
        for (SectionNode n : nodes) {
            if (n.children != null) {
                SectionNode r = remove(n.children, id);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Sections (not groups), in display order. */
    public static List<SectionNode> sections(List<SectionNode> nodes) {
        List<SectionNode> out = new ArrayList<>();
        for (SectionNode n : nodes) {
            if (n.group) out.addAll(sections(n.children == null ? List.of() : n.children));
            else out.add(n);
        }
        return out;
    }

    /** Section groups, in display order. */
    public static List<SectionNode> groups(List<SectionNode> nodes) {
        List<SectionNode> out = new ArrayList<>();
        for (SectionNode n : nodes) {
            if (n.group) {
                out.add(n);
                out.addAll(groups(n.children == null ? List.of() : n.children));
            }
        }
        return out;
    }

    /** The list a node is in (the notebook's top level or a group's children). */
    public static List<SectionNode> siblings(List<SectionNode> nodes, String id) {
        for (SectionNode n : nodes) if (n.id.equals(id)) return nodes;
        for (SectionNode n : nodes) {
            if (n.children != null) {
                List<SectionNode> s = siblings(n.children, id);
                if (s != null) return s;
            }
        }
        return null;
    }

    public static boolean moveSibling(List<SectionNode> nodes, String id, int delta) {
        List<SectionNode> list = siblings(nodes, id);
        if (list == null) return false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                int j = i + delta;
                if (j >= 0 && j < list.size()) {
                    SectionNode t = list.get(i);
                    list.set(i, list.get(j));
                    list.set(j, t);
                }
                return true;
            }
        }
        return false;
    }

    /** Puts a node just before or after another (in whatever group that one is in). */
    public static boolean insertBeside(List<SectionNode> nodes, SectionNode node, String target, boolean after) {
        List<SectionNode> list = siblings(nodes, target);
        if (list == null) return false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(target)) {
                list.add(after ? i + 1 : i, node);
                return true;
            }
        }
        return false;
    }

    public static void append(List<SectionNode> nodes, SectionNode node, String groupId) {
        if (groupId != null) {
            SectionNode g = find(nodes, groupId);
            if (g != null && g.group) {
                if (g.children == null) g.children = new ArrayList<>();
                g.children.add(node);
                return;
            }
        }
        nodes.add(node);
    }

    /** Names of the groups leading to a node; null if it isn't in the tree. */
    public static List<String> path(List<SectionNode> nodes, String id) {
        for (SectionNode n : nodes) {
            if (n.id.equals(id)) return new ArrayList<>();
            if (n.children != null) {
                List<String> sub = path(n.children, id);
                if (sub != null) {
                    sub.add(0, n.name);
                    return sub;
                }
            }
        }
        return null;
    }

    /** The group whose children contain the node (null at top level). */
    public static SectionNode parentGroup(List<SectionNode> nodes, String id) {
        for (SectionNode n : nodes) {
            if (!n.group || n.children == null) continue;
            for (SectionNode c : n.children) if (c.id.equals(id)) return n;
            SectionNode f = parentGroup(n.children, id);
            if (f != null) return f;
        }
        return null;
    }
}
