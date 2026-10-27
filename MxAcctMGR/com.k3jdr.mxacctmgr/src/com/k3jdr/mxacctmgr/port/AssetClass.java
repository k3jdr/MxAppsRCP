package com.k3jdr.mxacctmgr.port;

/** Stocks, bonds or cash. */
public enum AssetClass {
    EQUITY("Stocks"), BOND("Bonds"), CASH("Cash");

    /** The Swift raw value, shown on screen. */
    public final String title;

    AssetClass(String title) { this.title = title; }

    public String rawValue() { return title; }

    public static AssetClass fromRawValue(String raw) {
        for (AssetClass c : values()) if (c.title.equals(raw)) return c;
        return null;
    }

    @Override
    public String toString() { return title; }
}
