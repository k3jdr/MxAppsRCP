package com.k3jdr.mxacctmgr.port;

import com.google.gson.annotations.SerializedName;

/** A personal account as the Open Portfolio list shows it (srm.v_personal_account; snake_case keys). */
public record SRMAccountSummary(
        String code,
        String name,
        @SerializedName("account_type") String accountType,
        String approach,
        @SerializedName("managed_by") String managedBy,
        String benchmark,
        Integer lots,
        @SerializedName("invested_value") Double investedValue,
        @SerializedName("cost_basis") Double costBasis,
        @SerializedName("cash_balance") Double cashBalance,
        @SerializedName("updated_at") String updatedAt,
        Integer version,
        @SerializedName("updated_by") String updatedBy,
        @SerializedName("closed_date") String closedDate) {

    public String id() { return code; }

    /** (Still "MxPortMGR samples" until migration V034 renames the value.) */
    public boolean isSample() { return "MxAcctMGR samples".equals(managedBy) || "MxPortMGR samples".equals(managedBy); }
}
