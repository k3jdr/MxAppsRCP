package com.k3jdr.mxacctmgr.port;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** A personal account's holdings and what can be read from them (Portfolio.swift). */
public final class Portfolio {
    public String id;
    public String name;
    public AccountType account;
    public String benchmark;
    public PortfolioStyle style;
    public List<Holding> holdings;
    /** The edge case the sample teaches, in a sentence or two. */
    public String lesson;
    /** What to look for in the results. */
    public List<String> lookFor;
    /** How the holdings changed through the attribution year: "buyAndHold" drifts with returns, "trader" changes them every month. */
    public String trading = "buyAndHold";
    /** Base securities with no returns from the given month on (0-based), for the missing-data case. */
    public Map<String, Integer> missingFrom = new LinkedHashMap<>();
    public String symbol = "briefcase";
    /** "srm" when the account lives in the security master, with its code. */
    public String source;
    public String srmCode;

    public Portfolio(String id, String name, AccountType account, String benchmark, PortfolioStyle style,
                     List<Holding> holdings, String lesson, List<String> lookFor) {
        this.id = id;
        this.name = name;
        this.account = account;
        this.benchmark = benchmark;
        this.style = style;
        this.holdings = holdings == null ? new ArrayList<>() : new ArrayList<>(holdings);
        this.lesson = lesson;
        this.lookFor = lookFor == null ? new ArrayList<>() : new ArrayList<>(lookFor);
    }

    public boolean isSRM() { return "srm".equals(source); }

    public double total() {
        double s = 0;
        for (Holding h : holdings) s += h.value;
        return s;
    }

    /** Weight of each holding as entered. */
    public double weight(Holding h) {
        double t = total();
        return t > 0 ? h.value / t : 0;
    }

    /** Weights over the base securities: funds are replaced by what they hold. Unknown tickers are left out. */
    public Map<String, Double> lookThrough() {
        Map<String, Double> out = new LinkedHashMap<>();
        double t = total();
        if (!(t > 0)) return out;
        for (Holding h : holdings) {
            Security s = h.security();
            if (s == null) continue;
            double w = h.value / t;
            if (s.lookThrough != null) {
                for (Map.Entry<String, Double> e : s.lookThrough.entrySet()) out.merge(e.getKey(), w * e.getValue(), Double::sum);
            } else {
                out.merge(s.ticker, w, Double::sum);
            }
        }
        return out;
    }

    public Map<String, Double> sectorWeights() { return sectors(lookThrough()); }

    public Map<AssetClass, Double> classWeights() { return classes(lookThrough()); }

    public static Map<String, Double> sectors(Map<String, Double> weights) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            Security s = Universe.security(e.getKey());
            out.merge(s != null ? s.sector : "Unknown", e.getValue(), Double::sum);
        }
        return out;
    }

    public static Map<AssetClass, Double> classes(Map<String, Double> weights) {
        Map<AssetClass, Double> out = new EnumMap<>(AssetClass.class);
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            Security s = Universe.security(e.getKey());
            if (s != null) out.merge(s.assetClass, e.getValue(), Double::sum);
        }
        return out;
    }

    public Benchmark benchmarkInfo() { return Universe.benchmark(benchmark); }

    /** Fees a year, weighted. */
    public double weightedFee() {
        double s = 0;
        for (Holding h : holdings) {
            Security sec = h.security();
            s += weight(h) * (sec != null ? sec.fee : 0);
        }
        return s;
    }

    // Checks

    /** Something to fix or understand before optimizing. */
    public record Issue(Level level, String title, String detail) {
        public enum Level {
            ERROR("error"), WARNING("warning"), NOTE("note");

            public final String rawValue;

            Level(String rawValue) { this.rawValue = rawValue; }
        }

        public String id() { return title; }
    }

    /** What to fix or understand before optimizing. */
    public List<Issue> issues() {
        List<Issue> out = new ArrayList<>();
        for (Holding h : holdings) {
            if (h.security() == null && !"CASH".equals(h.ticker)) {
                out.add(new Issue(Issue.Level.ERROR, "Unknown security " + h.ticker,
                        h.ticker + " isn't in the security master, so it has no sector, risk or returns. It is left out of every calculation — "
                                + Fmt.pct(weight(h)) + " of the account is invisible to the optimizer."));
            }
        }
        for (Holding h : holdings) {
            if (h.unpriced) {
                out.add(new Issue(Issue.Level.ERROR, "No current price for " + h.ticker,
                        "The last price is stale (a halted or delisted security). Its value — and so every weight — may be wrong. Attribution marks the months without a return as a data gap."));
            }
        }
        for (Holding h : holdings) {
            Security s = h.security();
            if (s != null && s.lookThrough == null && weight(h) > 0.20 && s.assetClass == AssetClass.EQUITY && !s.isFund()) {
                out.add(new Issue(Issue.Level.WARNING, h.ticker + " is " + Fmt.pct(weight(h)) + " of the account",
                        "A single company over 20%: one earnings report, lawsuit or product failure can move the whole account. Its own risk is not diversified away."));
            }
        }
        Benchmark info = benchmarkInfo();
        Map<String, Double> b = sectors(info.weights());
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(sectorWeights().entrySet());
        sorted.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        for (Map.Entry<String, Double> e : sorted) {
            String sector = e.getKey();
            double w = e.getValue();
            if (sector.equals(Universe.FIXED_INCOME) || sector.equals(Universe.CASH_SECTOR)) continue;
            double bw = b.getOrDefault(sector, 0.0);
            if (w > 0.15 && w > 2 * bw) {
                out.add(new Issue(Issue.Level.WARNING, sector + " is " + Fmt.pct(w) + " vs " + Fmt.pct(bw) + " in " + benchmark,
                        "More than twice the benchmark's weight in one sector: the account's results will follow that sector's news far more than the market's."));
            }
        }
        Map<AssetClass, Double> classes = classWeights(), benchClasses = classes(info.weights());
        List<AssetClass> missingClasses = new ArrayList<>();
        double outsideBenchmark = 0;
        for (AssetClass c : AssetClass.values()) {
            if (benchClasses.getOrDefault(c, 0.0) == 0) {
                missingClasses.add(c);
                outsideBenchmark += classes.getOrDefault(c, 0.0);
            }
        }
        if (outsideBenchmark > 0.05) {
            out.add(new Issue(Issue.Level.WARNING, Fmt.pct(outsideBenchmark) + " is in asset classes " + benchmark + " doesn't hold",
                    "The benchmark has no " + missingClasses.stream().map(c -> c.title.toLowerCase(java.util.Locale.ROOT)).collect(Collectors.joining(" or "))
                            + ". Measured against it, those holdings are pure active risk and an optimizer will sell them. If they are part of your plan, choose a benchmark that includes them (BAL6040 or BAL3070)."));
        }
        double cash = classes.getOrDefault(AssetClass.CASH, 0.0);
        if (cash > 0.10) {
            out.add(new Issue(Issue.Level.NOTE, Fmt.pct(cash) + " in cash",
                    "Cash is safe from market falls but earns less than stocks or bonds over long periods; waiting for a 'better time' to invest is market timing."));
        }
        List<String> missing = new ArrayList<>(new TreeMap<>(missingFrom).keySet());
        if (!missing.isEmpty()) {
            out.add(new Issue(Issue.Level.WARNING, "Returns missing for " + String.join(", ", missing),
                    "Part of the year has no returns for these securities, so attribution can't explain all of the active return: its result is marked 'review'."));
        }
        return out;
    }

    // Security master

    /** Holdings and lots from the service's account (PortStore.apply); returns My Portfolio's approach to use, else null. */
    public PortfolioStyle apply(SRMAccount a) {
        srmCode = a.code();
        account = AccountType.fromCode(a.accountType());
        if (a.benchmark() != null) benchmark = a.benchmark();
        PortfolioStyle approach = null;
        if ("mine".equals(id)) {
            name = a.name();
            approach = PortfolioStyle.fromApproach(a.approach());
        }
        holdings = holdingsFrom(a);
        return approach;
    }

    /** One holding per security (registered in SRMRegistry) with its lots, then CASH when the account has any. */
    public static List<Holding> holdingsFrom(SRMAccount a) {
        Map<String, List<SRMLot>> byKey = new LinkedHashMap<>();
        for (SRMLot lot : a.lots()) {
            String key = SRMRegistry.shared().register(lot.security());
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(lot);
        }
        List<Holding> out = new ArrayList<>();
        for (Map.Entry<String, List<SRMLot>> e : byKey.entrySet()) {
            List<SRMLot> lots = e.getValue();
            double quantity = 0, cost = 0;
            for (SRMLot l : lots) {
                quantity += l.quantity();
                cost += l.costBasis();
            }
            Double price = lots.get(0).price();
            double value = price != null ? quantity * price : cost;
            List<TaxLot> taxLots = new ArrayList<>();
            for (SRMLot l : lots) taxLots.add(new TaxLot(l.acquired(), l.quantity() / Math.max(quantity, 1e-9), l.costBasis(), l.note()));
            out.add(new Holding(e.getKey(), value, taxLots, price == null, quantity));
        }
        if (a.cash() > 0) out.add(new Holding("CASH", a.cash()));
        return out;
    }

    /** A deep copy (Portfolio is a value type in Swift). */
    public Portfolio copy() {
        List<Holding> h = new ArrayList<>();
        for (Holding x : holdings) h.add(x.copy());
        Portfolio p = new Portfolio(id, name, account, benchmark, style, h, lesson, lookFor);
        p.trading = trading;
        p.missingFrom = new LinkedHashMap<>(missingFrom);
        p.symbol = symbol;
        p.source = source;
        p.srmCode = srmCode;
        return p;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Portfolio p && Objects.equals(id, p.id) && Objects.equals(name, p.name) && account == p.account
                && Objects.equals(benchmark, p.benchmark) && style == p.style && Objects.equals(holdings, p.holdings)
                && Objects.equals(lesson, p.lesson) && Objects.equals(lookFor, p.lookFor) && Objects.equals(trading, p.trading)
                && Objects.equals(missingFrom, p.missingFrom) && Objects.equals(symbol, p.symbol)
                && Objects.equals(source, p.source) && Objects.equals(srmCode, p.srmCode);
    }

    @Override
    public int hashCode() { return Objects.hash(id, name, benchmark, holdings); }
}
