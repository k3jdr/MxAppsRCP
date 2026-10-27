package com.k3jdr.mxacctmgr.port;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The securities and benchmarks the app works with, all from the security master (through SRMRegistry). */
public final class Universe {
    private Universe() {}

    /** The sectors the security master classifies stocks into (FMP's), for pickers and sector shocks. */
    public static final List<String> SECTORS = List.of("Technology", "Healthcare", "Financial Services",
            "Consumer Cyclical", "Consumer Defensive", "Industrials", "Energy", "Utilities", "Real Estate",
            "Basic Materials", "Communication Services");
    public static final String FIXED_INCOME = "Fixed Income", CASH_SECTOR = "Cash", FUND_SECTOR = "Fund";

    /** An assumption, not data: the extra return expected from stocks over Treasury bills, a year. */
    public static final double EQUITY_PREMIUM = 0.05;

    /** The latest 3-month Treasury yield, from the analytics service's inputs (srm market series DGS3MO). */
    public static double riskFree() {
        Double r = SRMRegistry.shared().riskFree();
        return r != null ? r : 0;
    }

    /** The market proxy's (VTI's) annual volatility from the analytics service's inputs. */
    public static double marketVol() {
        Double v = SRMRegistry.shared().marketVol();
        return v != null ? v : 0;
    }

    /** A security by key (or plain ticker); null when not registered. */
    public static Security security(String ticker) { return SRMRegistry.shared().security(ticker); }

    /** The security-master benchmarks read so far. */
    public static List<Benchmark> benchmarks() {
        List<Benchmark> out = new ArrayList<>();
        for (String code : SRMRegistry.shared().benchmarkCodes()) {
            Benchmark b = SRMRegistry.shared().benchmark(code);
            if (b != null) out.add(b);
        }
        return out;
    }

    /** A benchmark by code; an empty one (no members yet) until the security master's is read. */
    public static Benchmark benchmark(String code) {
        Benchmark b = SRMRegistry.shared().benchmark(code);
        return b != null ? b : new Benchmark(code, code, code + ": not read from the security master yet.", Map.of());
    }

    /** Expected annual return, CAPM-style: the risk-free rate plus beta times the (assumed) equity premium. */
    public static double expectedReturn(Security s) {
        return s.assetClass == AssetClass.CASH ? riskFree() : riskFree() + s.beta * EQUITY_PREMIUM;
    }
}
