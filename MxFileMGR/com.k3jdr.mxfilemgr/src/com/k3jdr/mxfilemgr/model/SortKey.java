package com.k3jdr.mxfilemgr.model;

/** The sortable columns of a file list, in their default order. */
public enum SortKey {
    NAME("Name"), EXT("Ext"), SIZE("Size"), MODIFIED("Date Modified"), KIND("Kind");

    public final String title;

    SortKey(String title) { this.title = title; }

    public static SortKey parse(String s) {
        for (SortKey k : values()) if (k.name().equalsIgnoreCase(s)) return k;
        return NAME;
    }
}
