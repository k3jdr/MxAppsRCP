package com.k3jdr.mxacctmgr.port;

import java.util.Objects;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The index tracker's limits (null: not used). */
public final class TrackingSettings {
    /** Which names a ≤ N-name tracker holds. */
    public enum Selection {
        STRATIFIED("stratified"), LARGEST("largest");

        public final String rawValue;

        Selection(String rawValue) { this.rawValue = rawValue; }

        public static Selection fromRawValue(String raw) {
            for (Selection s : values()) if (s.rawValue.equals(raw)) return s;
            return null;
        }

        @Override
        public String toString() { return rawValue; }
    }

    public int maxNames = 20;
    public Selection selection = Selection.STRATIFIED;
    public double maxWeight = 1.0;
    public double trackingErrorBudget = 0.02;
    public Double maxTurnover = 0.30;

    public TrackingSettings copy() {
        TrackingSettings s = new TrackingSettings();
        s.maxNames = maxNames;
        s.selection = selection;
        s.maxWeight = maxWeight;
        s.trackingErrorBudget = trackingErrorBudget;
        s.maxTurnover = maxTurnover;
        return s;
    }

    /** As Swift's Codable writes it (nulls left out). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("maxNames", maxNames);
        o.addProperty("selection", selection.rawValue);
        o.addProperty("maxWeight", maxWeight);
        o.addProperty("trackingErrorBudget", trackingErrorBudget);
        if (maxTurnover != null) o.addProperty("maxTurnover", maxTurnover);
        return o;
    }

    /** From saved settings; a missing required key keeps its default, a missing optional one is null. */
    public static TrackingSettings fromJson(JsonObject o) {
        TrackingSettings s = new TrackingSettings();
        if (o == null) return s;
        Double names = ActiveSettings.opt(o, "maxNames");
        if (names != null) s.maxNames = (int) Math.round(names);
        JsonElement sel = o.get("selection");
        if (sel != null && sel.isJsonPrimitive() && sel.getAsJsonPrimitive().isString()) {
            Selection x = Selection.fromRawValue(sel.getAsString());
            if (x != null) s.selection = x;
        }
        s.maxWeight = ActiveSettings.num(o, "maxWeight", s.maxWeight);
        s.trackingErrorBudget = ActiveSettings.num(o, "trackingErrorBudget", s.trackingErrorBudget);
        s.maxTurnover = ActiveSettings.opt(o, "maxTurnover");
        return s;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TrackingSettings s && maxNames == s.maxNames && selection == s.selection
                && maxWeight == s.maxWeight && trackingErrorBudget == s.trackingErrorBudget
                && Objects.equals(maxTurnover, s.maxTurnover);
    }

    @Override
    public int hashCode() { return Objects.hash(maxNames, selection, maxWeight, trackingErrorBudget, maxTurnover); }
}
