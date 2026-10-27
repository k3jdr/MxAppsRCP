package com.k3jdr.mxacctmgr.port;

/** A security-master benchmark: code, name and member count. */
public record SRMBenchmarkInfo(String code, String name, Integer members) {
    public String id() { return code; }
}
