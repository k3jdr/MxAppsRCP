package com.k3jdr.mxacctmgr.port;

import java.util.List;

/** A benchmark's members with their weights (each SRMSecurity's weight). */
public record SRMBenchmarkWeights(String code, String name, String asOf, int members, List<SRMSecurity> weights) {
    public SRMBenchmarkWeights {
        weights = weights == null ? List.of() : List.copyOf(weights);
    }
}
