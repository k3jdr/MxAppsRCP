package com.k3jdr.mxjobsmgr.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k3jdr.mxjobsmgr.model.Specs.RunSettings;

/**
 * A jobspace (*.jobspace, JSON): the API subscriptions, the nodes of the three-layer diagram and the links between
 * them, and the run settings. Read with the Mac app's defaults for missing keys; written as the Mac app writes it.
 */
public final class Jobspace implements J.Writable {
    public String format = "mxjobspace";
    public int formatVersion = 1;
    public String name = "Untitled Jobspace";
    public String description = "";
    public List<ApiSubscription> subscriptions = new ArrayList<>();
    public List<JobNode> nodes = new ArrayList<>();
    public List<JobLink> links = new ArrayList<>();
    public RunSettings run = new RunSettings();

    // MARK: File format

    public static Jobspace read(JsonObject o) {
        Jobspace s = new Jobspace();
        s.format = J.str(o, "format", "mxjobspace");
        s.formatVersion = J.integer(o, "formatVersion", 1);
        s.name = J.str(o, "name", "Untitled Jobspace");
        s.description = J.str(o, "description", "");
        s.subscriptions = J.list(o, "subscriptions", ApiSubscription::read);
        s.nodes = J.list(o, "nodes", JobNode::read);
        s.links = J.list(o, "links", JobLink::read);
        s.run = RunSettings.read(J.obj(o, "run"));
        Set<String> ids = new HashSet<>();
        for (JobNode n : s.nodes) ids.add(n.id);
        s.links.removeIf(l -> !ids.contains(l.from) || !ids.contains(l.to));
        return s;
    }

    /** Parses a jobspace file's text; throws when it is not a JSON object. */
    public static Jobspace parse(String text) {
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        var e = JsonParser.parseString(text);
        if (!e.isJsonObject()) throw new IllegalArgumentException("The file is not a jobspace (no JSON object).");
        return read(e.getAsJsonObject());
    }

    @Override
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", format);
        o.addProperty("formatVersion", formatVersion);
        o.addProperty("name", name);
        o.addProperty("description", description);
        o.add("subscriptions", J.array(subscriptions));
        o.add("nodes", J.array(nodes));
        o.add("links", J.array(links));
        o.add("run", run.toJson());
        return o;
    }

    /** The file's text: pretty-printed with sorted keys, as the Mac app saves it. */
    public String toText() {
        return AppleJson.write(toJson());
    }

    public Jobspace copy() {
        return read(toJson());
    }

    // MARK: Lookups

    public JobNode node(String id) {
        if (id == null) return null;
        for (JobNode n : nodes) if (n.id.equals(id)) return n;
        return null;
    }

    public int indexOf(String id) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id.equals(id)) return i;
        return -1;
    }

    public JobLink link(String id) {
        if (id == null) return null;
        for (JobLink l : links) if (l.id.equals(id)) return l;
        return null;
    }

    public ApiSubscription subscription(String id) {
        if (id == null) return null;
        for (ApiSubscription s : subscriptions) if (s.id.equals(id)) return s;
        return null;
    }

    // MARK: Editing

    /** Adds a link unless it would join a node to itself or repeat one; returns its id or null. */
    public String addLink(String from, String to) {
        if (from.equals(to)) return null;
        for (JobLink l : links) if (l.from.equals(from) && l.to.equals(to)) return null;
        JobLink link = new JobLink(from, to, "");
        links.add(link);
        return link.id;
    }

    public void delete(Collection<String> nodeIds, Collection<String> linkIds) {
        nodes.removeIf(n -> nodeIds.contains(n.id));
        links.removeIf(l -> nodeIds.contains(l.from) || nodeIds.contains(l.to) || linkIds.contains(l.id));
    }

    /** Copies the nodes (and the links among them) 30 points down-right; returns the new ids. */
    public Set<String> duplicate(Collection<String> ids) {
        Map<String, String> map = new HashMap<>();
        List<JobNode> copies = new ArrayList<>();
        for (JobNode node : nodes) {
            if (!ids.contains(node.id)) continue;
            JobNode copy = node.copy();
            copy.id = J.newId();
            copy.x += 30;
            copy.y += 30;
            copy.title += " copy";
            map.put(node.id, copy.id);
            copies.add(copy);
        }
        nodes.addAll(copies);
        List<JobLink> newLinks = new ArrayList<>();
        for (JobLink link : links) {
            String from = map.get(link.from), to = map.get(link.to);
            if (from != null && to != null) newLinks.add(new JobLink(from, to, link.label));
        }
        links.addAll(newLinks);
        return new LinkedHashSet<>(map.values());
    }

    /** Every node upstream of {@code id} (following links backwards). */
    public Set<String> upstream(String id) {
        Set<String> seen = new HashSet<>();
        List<String> stack = new ArrayList<>(List.of(id));
        while (!stack.isEmpty()) {
            String current = stack.remove(stack.size() - 1);
            for (JobLink l : links) {
                if (l.to.equals(current) && seen.add(l.from)) stack.add(l.from);
            }
        }
        return seen;
    }

    /** All outputNames published by nodes linked into {@code id} (directly). */
    public List<String> inputNames(String id) {
        List<String> out = new ArrayList<>();
        for (JobLink l : links) {
            if (!l.to.equals(id)) continue;
            JobNode from = node(l.from);
            if (from != null && !from.outcome.outputName.isEmpty()) out.add(from.outcome.outputName);
        }
        return out;
    }

    /** Gives every node, link and subscription a new id (a sample opened as a new document), keeping references. */
    public void renewIds() {
        Map<String, String> map = new HashMap<>();
        for (ApiSubscription s : subscriptions) map.put(s.id, s.id = J.newId());
        for (JobNode n : nodes) {
            map.put(n.id, n.id = J.newId());
            if (n.api != null && n.api.subscriptionID != null) n.api.subscriptionID = map.getOrDefault(n.api.subscriptionID, n.api.subscriptionID);
            if (n.script != null && n.script.subscriptionID != null) n.script.subscriptionID = map.getOrDefault(n.script.subscriptionID, n.script.subscriptionID);
        }
        for (JobLink l : links) {
            l.id = J.newId();
            l.from = map.getOrDefault(l.from, l.from);
            l.to = map.getOrDefault(l.to, l.to);
        }
    }
}
