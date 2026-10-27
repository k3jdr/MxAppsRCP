package com.k3jdr.mxdatahub;

/** The window's seven screens, in the sidebar's and the Hub menu's order (Ctrl+1 … Ctrl+7). */
enum Section {
    CONNECTION("DB Connection", "connection"),
    SECURITIES("Securities", "securities"),
    PORTFOLIOS("Portfolios", "portfolios"),
    BENCHMARKS("Benchmarks", "benchmarks"),
    REFRESH("Data Refresh", "refresh"),
    LOADS("Load History", "loads"),
    SERVICES("Services & Workflows", "services");

    final String title;
    final String id;

    Section(String title, String id) {
        this.title = title;
        this.id = id;
    }

    /** Screens that work through the Layer 3 services, without the app's own database connection. */
    boolean needsConnection() {
        return !(this == CONNECTION || this == REFRESH || this == SERVICES);
    }

    /** "Ctrl+3" for the third screen. */
    String shortcut() { return "Ctrl+" + (ordinal() + 1); }

    static Section byTitle(String title) {
        if (title == null) return null;
        for (Section s : values()) {
            if (s.title.equalsIgnoreCase(title.trim()) || s.id.equalsIgnoreCase(title.trim()) || s.name().equalsIgnoreCase(title.trim())) return s;
        }
        return null;
    }

    static Section byId(String id) {
        for (Section s : values()) if (s.id.equals(id)) return s;
        return null;
    }
}
