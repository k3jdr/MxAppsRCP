package com.k3jdr.mxjobsmgr.model;

import com.google.gson.JsonObject;

/** A link between two steps: down the layers a call, up the layers (dashed) a result coming back. */
public final class JobLink implements J.Writable {
    public String id = J.newId();
    public String from;
    public String to;
    public String label = "";

    public JobLink(String from, String to, String label) {
        this.from = from;
        this.to = to;
        this.label = label == null ? "" : label;
    }

    /** Reads a link; null when its ends are missing. */
    public static JobLink read(JsonObject o) {
        String from = J.uuid(o, "from", null), to = J.uuid(o, "to", null);
        if (from == null || to == null) return null;
        JobLink l = new JobLink(from, to, J.str(o, "label", ""));
        l.id = J.uuid(o, "id", l.id);
        return l;
    }

    @Override
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("from", from);
        o.addProperty("to", to);
        o.addProperty("label", label);
        return o;
    }
}
