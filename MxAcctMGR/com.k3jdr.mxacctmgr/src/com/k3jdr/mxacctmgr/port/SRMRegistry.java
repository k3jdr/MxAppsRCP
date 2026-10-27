package com.k3jdr.mxacctmgr.port;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Security-master securities and benchmarks, by the keys the app uses (thread-safe). A ticker two securities share (or
 * the reserved CASH) is registered as TICKER:MIC. The Securities it hands out are never changed afterwards.
 */
public final class SRMRegistry {
    private static final SRMRegistry SHARED = new SRMRegistry();

    public static SRMRegistry shared() { return SHARED; }

    private final Object lock = new Object();
    private final Map<String, Security> securities = new HashMap<>();
    private final Map<Integer, String> keyByID = new HashMap<>();
    private final Map<String, String> keyByTicker = new HashMap<>();
    private final Map<String, Benchmark> benchmarks = new HashMap<>();
    private Double riskFree;
    private Double marketVol;

    private SRMRegistry() {}

    private static String upper(String s) { return s.toUpperCase(Locale.ROOT); }

    /** The latest 3-month Treasury yield, from the last analytics inputs read; null before any. */
    public Double riskFree() {
        synchronized (lock) { return riskFree; }
    }

    /** The market proxy's volatility, from the last analytics inputs read; null before any. */
    public Double marketVol() {
        synchronized (lock) { return marketVol; }
    }

    /** The analytics service's numbers for these securities (by key): market beta, rate beta, volatility, alpha. */
    public void applyRisk(List<String> keys, double[] betas, double[] rateBetas, double[] vols, double[] alphas,
                          Double riskFree, Double marketVol) {
        synchronized (lock) {
            if (riskFree != null) this.riskFree = riskFree;
            if (marketVol != null) this.marketVol = marketVol;
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                Security old = securities.get(key);
                if (old == null) continue;
                Security sec = old.copy();
                if (betas != null && i < betas.length) sec.beta = betas[i];
                if (rateBetas != null && i < rateBetas.length) sec.rateBeta = rateBetas[i];
                if (vols != null && i < vols.length) sec.volatility = vols[i];
                if (alphas != null && i < alphas.length) sec.alpha = alphas[i];
                sec.hasRisk = true;
                securities.put(key, sec);
            }
        }
    }

    /** A security by key, or by plain ticker; null when unknown. */
    public Security security(String key) {
        if (key == null) return null;
        synchronized (lock) {
            String k = upper(key);
            Security s = securities.get(k);
            if (s != null) return s;
            String byTicker = keyByTicker.get(k);
            return byTicker != null ? securities.get(byTicker) : null;
        }
    }

    /** The key of a security_id; null when not registered. */
    public String key(int id) {
        synchronized (lock) { return keyByID.get(id); }
    }

    /** The key of a plain ticker; null when not registered. */
    public String key(String ticker) {
        synchronized (lock) { return keyByTicker.get(upper(ticker)); }
    }

    /** Drops a benchmark's weights (a policy benchmark just redefined): the next use reads them again. */
    public void forgetBenchmark(String code) {
        synchronized (lock) { benchmarks.remove(upper(code)); }
    }

    public Benchmark benchmark(String code) {
        if (code == null) return null;
        synchronized (lock) { return benchmarks.get(upper(code)); }
    }

    /** The benchmark codes read so far, sorted. */
    public List<String> benchmarkCodes() {
        synchronized (lock) {
            List<String> out = new ArrayList<>(benchmarks.keySet());
            out.sort(null);
            return out;
        }
    }

    /** Registers a security and returns its key: the ticker, or TICKER:MIC when another security already has it. */
    public String register(SRMSecurity s) {
        synchronized (lock) {
            String known = keyByID.get(s.securityId());
            if (known != null) {
                securities.put(known, make(s, known, securities.get(known)));
                return known;
            }
            String plain = upper(s.ticker());
            String key = plain;
            Security existing = securities.get(plain);
            if (plain.equals("CASH") || (existing != null && !Objects.equals(existing.srmID, s.securityId()))) {
                key = plain + ":" + (s.mic() != null ? s.mic() : "SRM" + s.securityId());
            }
            securities.put(key, make(s, key, null));
            keyByID.put(s.securityId(), key);
            keyByTicker.putIfAbsent(plain, key);
            return key;
        }
    }

    /** Registers a benchmark's members and the benchmark itself. */
    public Benchmark register(SRMBenchmarkWeights w) {
        Map<String, Double> weights = new HashMap<>();
        for (SRMSecurity s : w.weights()) weights.merge(register(s), s.weight() != null ? s.weight() : 0, Double::sum);
        Benchmark b = new Benchmark(w.code(), w.name(), w.name() + ": " + w.members() + " members on "
                + (w.asOf() != null ? w.asOf() : "—") + ", from the security master (srm).", weights);
        synchronized (lock) { benchmarks.put(upper(w.code()), b); }
        return b;
    }

    /**
     * A Security for the app's tools: what the security master says about it, and — once the analytics service's inputs
     * have covered it — its estimated betas, volatility and alpha (kept when it is registered again).
     */
    private static Security make(SRMSecurity s, String key, Security old) {
        boolean bond = "bond".equals(s.kind()) || "Bond".equals(s.securityType()) || "Listed Note".equals(s.securityType());
        boolean fund = "fund".equals(s.kind());
        String sector = bond ? Universe.FIXED_INCOME
                : s.sector() != null ? s.sector() : (fund ? "Diversified Fund" : "Unclassified");
        double price = s.price() != null ? s.price() : old != null ? old.price : 0;
        double marketCap = s.marketCap() != null ? s.marketCap() / 1e9 : old != null ? old.marketCap : 0;
        String priceDate = s.priceDate() != null ? s.priceDate() : old != null ? old.priceDate : null;
        Security sec = new Security(key, s.name(), sector, bond ? AssetClass.BOND : AssetClass.EQUITY, price,
                s.beta() != null ? s.beta() : 0, 0, null, marketCap, 0, s.securityId(), s.ticker(), priceDate,
                s.securityType());
        sec.fee = s.expenseRatio() != null ? s.expenseRatio() : old != null ? old.fee : 0;
        if (old != null && old.hasRisk) {
            sec.beta = old.beta;
            sec.rateBeta = old.rateBeta;
            sec.volatility = old.volatility;
            sec.alpha = old.alpha;
            sec.hasRisk = true;
        }
        return sec;
    }
}
