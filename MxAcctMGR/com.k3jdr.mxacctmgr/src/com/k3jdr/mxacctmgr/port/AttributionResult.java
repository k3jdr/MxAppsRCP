package com.k3jdr.mxacctmgr.port;

import java.util.List;

/**
 * Performance attribution as the analytics service computes it (POST /attribution): monthly sector Brinson effects,
 * Carino-linked over the year. The app shows the result; it has no attribution engine of its own.
 */
public record AttributionResult(
        String engine,
        String method,
        double portfolioReturn,
        double benchmarkReturn,
        double allocation,
        double selection,
        double interaction,
        double residual,
        List<Group> groups,
        List<Period> periods,
        List<Contributor> top,
        List<Contributor> bottom,
        double dataGap,
        String recommendation,
        List<String> reasons,
        String highlight,
        List<String> assumptions) {

    public double activeReturn() { return portfolioReturn - benchmarkReturn; }

    /** A sector's weights and effects. */
    public record Group(String group, double portfolioWeight, double benchmarkWeight, double activeWeight,
                        double allocation, double selection, double interaction) {
        public String id() { return group; }

        public double total() { return allocation + selection + interaction; }
    }

    /** A month's returns and effects. */
    public record Period(String month, double portfolioReturn, double benchmarkReturn, double allocation,
                         double selection, double interaction) {
        public String id() { return month; }

        public double activeReturn() { return portfolioReturn - benchmarkReturn; }
    }

    /** A security's contribution to the active return. */
    public record Contributor(String ticker, String sector, double portfolioWeight, double benchmarkWeight,
                              double contribution) {
        public String id() { return ticker; }
    }
}
