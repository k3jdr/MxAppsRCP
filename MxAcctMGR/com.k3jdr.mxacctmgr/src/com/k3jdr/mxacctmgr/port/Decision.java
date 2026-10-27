package com.k3jdr.mxacctmgr.port;

/** recommendation: rebalance, rebalance_partially, hold, rerun_relaxed, escalate (the services' vocabulary). */
public record Decision(String recommendation, String reason) {
    public String title() {
        return switch (recommendation) {
            case "rebalance" -> "The plan suggests rebalancing";
            case "rebalance_partially" -> "The plan suggests rebalancing part of the way";
            case "hold" -> "The plan suggests holding";
            case "rerun_relaxed" -> "The limits can't all be met — relax one";
            default -> "Review before acting";
        };
    }
}
