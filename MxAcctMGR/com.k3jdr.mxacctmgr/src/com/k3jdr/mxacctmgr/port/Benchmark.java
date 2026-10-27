package com.k3jdr.mxacctmgr.port;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A benchmark: weights over securities, adding up to 1. */
public record Benchmark(String code, String name, String summary, Map<String, Double> weights) {
    public Benchmark {
        weights = weights == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(weights));
    }

    public String id() { return code; }
}
