package com.k3jdr.mxjobsmgr.model;

/** The three layers of a jobspace, top to bottom. */
public enum Layer {
    OPERATIONS(1, "Business Operations", "Refresh, assemble instructions, approve, execute, report", 0x5856D6, 0xF1F1FC),
    AGENTS(2, "Agentic Operations", "Agents calling the data services and preparing instructions", 0xFF9500, 0xFFF6EA),
    DATA(3, "Data Reads", "API calls against subscriptions, with keys when needed", 0x30B0C7, 0xEDF8FA);

    public final int number;
    public final String title;
    public final String subtitle;
    /** The lane colour (indigo, orange, teal) and its light tint, as 0xRRGGBB. */
    public final int rgb;
    public final int tint;

    Layer(int number, String title, String subtitle, int rgb, int tint) {
        this.number = number;
        this.title = title;
        this.subtitle = subtitle;
        this.rgb = rgb;
        this.tint = tint;
    }

    public static Layer of(int number) {
        return switch (Math.max(1, Math.min(3, number))) {
            case 1 -> OPERATIONS;
            case 2 -> AGENTS;
            default -> DATA;
        };
    }

    public String hex() {
        return String.format("#%06X", rgb);
    }

    public String tintHex() {
        return String.format("#%06X", tint);
    }
}
