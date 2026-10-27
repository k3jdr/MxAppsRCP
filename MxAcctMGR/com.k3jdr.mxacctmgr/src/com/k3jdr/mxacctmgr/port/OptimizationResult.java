package com.k3jdr.mxacctmgr.port;

import java.util.List;
import java.util.Map;

/** An optimization's answer, from the built-in engine or a Layer 3 service (null: not reported). */
public record OptimizationResult(
        String engine,
        String status,
        Map<String, Double> weights,
        double trackingError,
        Double currentTrackingError,
        Double activeAlpha,
        Double informationRatio,
        double activeShare,
        double turnover,
        int names,
        List<String> bindingConstraints,
        Map<String, Double> sectorActive,
        List<Trade> trades,
        Decision decision,
        Double riskAversion,
        String method,
        Double benchmarkCovered,
        double milliseconds) {
}
