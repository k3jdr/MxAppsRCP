package com.k3jdr.mxacctmgr.port;

import java.util.List;

/** A policy benchmark: dated mixes of securities (srm.benchmark_policy, V023). */
public record SRMPolicyBenchmark(String code, String name, String description, List<Mix> mixes) {
    public SRMPolicyBenchmark {
        mixes = mixes == null ? List.of() : List.copyOf(mixes);
    }

    public String id() { return code; }

    /** The mix in effect from a date. */
    public record Mix(String effectiveFrom, List<Component> components) {
        public Mix {
            components = components == null ? List.of() : List.copyOf(components);
        }
    }

    /** A security's weight in a mix. */
    public record Component(Integer securityId, String ticker, String name, double weight) {}
}
