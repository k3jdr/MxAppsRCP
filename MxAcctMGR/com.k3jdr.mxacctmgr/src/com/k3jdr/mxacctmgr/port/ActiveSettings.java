package com.k3jdr.mxacctmgr.port;

import java.util.Objects;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The active optimization's limits (null: not used). */
public final class ActiveSettings {
    public double maxWeight = 0.10;
    public double maxActiveWeight = 0.03;
    public Double sectorBand = 0.05;
    public Double trackingErrorTarget = 0.03;
    public double riskAversion = 10.0;
    public Double maxTurnover = 0.25;

    public ActiveSettings copy() {
        ActiveSettings s = new ActiveSettings();
        s.maxWeight = maxWeight;
        s.maxActiveWeight = maxActiveWeight;
        s.sectorBand = sectorBand;
        s.trackingErrorTarget = trackingErrorTarget;
        s.riskAversion = riskAversion;
        s.maxTurnover = maxTurnover;
        return s;
    }

    /** As Swift's Codable writes it (nulls left out). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("maxWeight", maxWeight);
        o.addProperty("maxActiveWeight", maxActiveWeight);
        if (sectorBand != null) o.addProperty("sectorBand", sectorBand);
        if (trackingErrorTarget != null) o.addProperty("trackingErrorTarget", trackingErrorTarget);
        o.addProperty("riskAversion", riskAversion);
        if (maxTurnover != null) o.addProperty("maxTurnover", maxTurnover);
        return o;
    }

    /** From saved settings; a missing required key keeps its default, a missing optional one is null. */
    public static ActiveSettings fromJson(JsonObject o) {
        ActiveSettings s = new ActiveSettings();
        if (o == null) return s;
        s.maxWeight = num(o, "maxWeight", s.maxWeight);
        s.maxActiveWeight = num(o, "maxActiveWeight", s.maxActiveWeight);
        s.sectorBand = opt(o, "sectorBand");
        s.trackingErrorTarget = opt(o, "trackingErrorTarget");
        s.riskAversion = num(o, "riskAversion", s.riskAversion);
        s.maxTurnover = opt(o, "maxTurnover");
        return s;
    }

    static double num(JsonObject o, String key, double fallback) {
        Double d = opt(o, key);
        return d != null ? d : fallback;
    }

    static Double opt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ActiveSettings s && maxWeight == s.maxWeight && maxActiveWeight == s.maxActiveWeight
                && Objects.equals(sectorBand, s.sectorBand) && Objects.equals(trackingErrorTarget, s.trackingErrorTarget)
                && riskAversion == s.riskAversion && Objects.equals(maxTurnover, s.maxTurnover);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxWeight, maxActiveWeight, sectorBand, trackingErrorTarget, riskAversion, maxTurnover);
    }
}
