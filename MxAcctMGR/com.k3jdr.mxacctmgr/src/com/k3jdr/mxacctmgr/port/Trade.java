package com.k3jdr.mxacctmgr.port;

/** A trade from today's weight to the target weight. */
public record Trade(String asset, double from, double to) {
    public String id() { return asset; }

    public double change() { return to - from; }

    public String side() { return change() > 0 ? "Buy" : "Sell"; }
}
