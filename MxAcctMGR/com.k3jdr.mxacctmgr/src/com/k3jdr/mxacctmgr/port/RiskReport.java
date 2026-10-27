package com.k3jdr.mxacctmgr.port;

import java.util.List;

/**
 * Risk of a set of weights. var95Month / es95Month: one-month loss not exceeded with 95% confidence, and the average
 * loss beyond it, as fractions of the account; rateSensitivity: return change for a 1-point rise in yields.
 */
public record RiskReport(
        double volatility,
        double benchmarkVolatility,
        double trackingError,
        double beta,
        double var95Month,
        double es95Month,
        double expectedReturn,
        double rateSensitivity,
        double diversificationRatio,
        double concentration,
        List<Contribution> contributions,
        List<Contribution> sectorContributions,
        List<Contribution> activeContributions) {

    /** A position's or sector's weight and share of the variance. */
    public record Contribution(String name, double weight, double share) {
        public String id() { return name; }
    }
}
