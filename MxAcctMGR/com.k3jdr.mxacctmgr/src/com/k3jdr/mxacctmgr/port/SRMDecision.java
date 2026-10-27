package com.k3jdr.mxacctmgr.port;

/** A decision recorded for a portfolio (srm.portfolio_decision, V023). */
public record SRMDecision(
        int id,
        String decidedOn,
        String category,
        String title,
        String rationale,
        Integer portfolioVersion,
        String createdAt,
        String createdBy) {
}
