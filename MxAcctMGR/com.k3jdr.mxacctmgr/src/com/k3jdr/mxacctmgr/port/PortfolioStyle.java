package com.k3jdr.mxacctmgr.port;

/** How an account is run: active, passive, or either. */
public enum PortfolioStyle {
    ACTIVE("Active"), PASSIVE("Passive"), EITHER("Either");

    /** The Swift raw value, shown on screen. */
    public final String title;

    PortfolioStyle(String title) { this.title = title; }

    public String rawValue() { return title; }

    public static PortfolioStyle fromRawValue(String raw) {
        for (PortfolioStyle s : values()) if (s.title.equals(raw)) return s;
        return null;
    }

    /** The security master's approach ("active" / "passive"); null for anything else. */
    public static PortfolioStyle fromApproach(String approach) {
        return "active".equals(approach) ? ACTIVE : "passive".equals(approach) ? PASSIVE : null;
    }

    @Override
    public String toString() { return title; }
}
