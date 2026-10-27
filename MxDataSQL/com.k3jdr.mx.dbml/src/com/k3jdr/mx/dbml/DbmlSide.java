package com.k3jdr.mx.dbml;

/** The side of a table a relationship leaves or meets. */
public enum DbmlSide {
    LEFT("left"), RIGHT("right");

    private final String raw;

    DbmlSide(String raw) {
        this.raw = raw;
    }

    /** The name in the layout comment ("left", "right"). */
    public String raw() { return raw; }

    public static DbmlSide fromRaw(String s) {
        for (DbmlSide v : values()) if (v.raw.equals(s)) return v;
        return null;
    }
}
