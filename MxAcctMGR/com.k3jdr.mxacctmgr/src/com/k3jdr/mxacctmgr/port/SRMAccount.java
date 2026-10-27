package com.k3jdr.mxacctmgr.port;

import java.util.List;

import com.google.gson.JsonObject;

/**
 * An account in the security master. version: saves so far (a save names the version it was made from); settings:
 * the portfolio's preferences as MxAcctMGR saved them (PortfolioSettings JSON; null or empty before V022).
 */
public record SRMAccount(
        String code,
        String name,
        String accountType,
        String approach,
        String managedBy,
        String benchmark,
        double cash,
        List<SRMLot> lots,
        double investedValue,
        double totalValue,
        List<String> unpriced,
        Integer version,
        JsonObject settings,
        String updatedBy,
        String closedDate) {

    public SRMAccount {
        lots = lots == null ? List.of() : List.copyOf(lots);
        unpriced = unpriced == null ? List.of() : List.copyOf(unpriced);
    }
}
