package com.k3jdr.mxacctmgr.port;

/** Which lots a sale takes first. */
public enum LotOrder {
    FIFO("First in, first out"), HIFO("Highest cost first");

    /** The Swift raw value, shown on screen and saved in the settings. */
    public final String title;

    LotOrder(String title) { this.title = title; }

    public String rawValue() { return title; }

    public static LotOrder fromRawValue(String raw) {
        for (LotOrder o : values()) if (o.title.equals(raw)) return o;
        return null;
    }

    @Override
    public String toString() { return title; }
}
