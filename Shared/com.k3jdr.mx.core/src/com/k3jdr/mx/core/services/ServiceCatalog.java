package com.k3jdr.mx.core.services;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.k3jdr.mx.core.Mx;
import com.k3jdr.mx.core.http.Http;

/**
 * The Layer 3 services MxDataHUB, MxJobsMGR and MxAcctMGR rely on (MxAppsIOS/Services): what each is for, why it is
 * written in its language, which use cases and samples it serves, and whether it is running now. The entries mirror
 * each service's GET /describe, so the apps can explain a service and how to start it while it is stopped.
 */
public final class ServiceCatalog {
    private ServiceCatalog() {}

    public record Endpoint(String method, String path, String summary, String contract) {
        public Endpoint(String method, String path, String summary) { this(method, path, summary, null); }
    }

    public enum UseCase {
        DATA_REFRESH("data-refresh", "Data refresh", "Bring a category of market data up to date — reference data, universe, constituents, identifiers, prices, corporate actions."),
        DATA_QUALITY("data-quality", "Data quality", "Check freshness and completeness before data feeds optimization or reporting."),
        ACTIVE("active", "Active management", "Beat a benchmark: turn alpha forecasts into overweights and underweights within a tracking-error budget and sector bands."),
        PASSIVE("passive", "Passive / index", "Match a benchmark: hold it in full or sample it with fewer names at the smallest tracking error, and rebalance when it drifts."),
        ATTRIBUTION("attribution", "Attribution", "Explain past performance against the benchmark: allocation, selection and interaction by sector, linked over months."),
        RISK("risk", "Risk", "Measure risk now: volatility, VaR and expected shortfall, tracking error and beta, and which positions and sectors drive active risk."),
        ABSOLUTE("absolute", "Absolute optimization", "Allocate without a benchmark: max-Sharpe or mean-variance over a small set of assets."),
        TEMPLATES("templates", "Templates", "Start a new Layer 3 service for your own data source."),
        TESTING("testing", "Testing", "Build and test jobspaces without data subscriptions.");

        public final String id, title, whenToUse;

        UseCase(String id, String title, String whenToUse) {
            this.id = id;
            this.title = title;
            this.whenToUse = whenToUse;
        }

        public static Optional<UseCase> of(String id) {
            for (UseCase u : values()) if (u.id.equals(id)) return Optional.of(u);
            return Optional.empty();
        }
    }

    public record Service(String id, String name, String language, int port, String purpose, String whyThisLanguage,
                          List<String> useCases, List<Endpoint> endpoints, List<String> samples, String folder,
                          String developIn) {
        public String baseUrl() { return "http://127.0.0.1:" + port; }
        public Path projectPath() { return Mx.iosRepository().resolve("Services").resolve(folder); }
        public List<UseCase> cases() { return useCases.stream().flatMap(u -> UseCase.of(u).stream()).toList(); }
    }

    public record Sample(String id, String title, String area, String summary, List<String> services, boolean takesPortfolio) {}

    public static String startCommand() { return "python \"" + Mx.iosRepository().resolve("Services\\mxservices.py") + "\" start"; }
    public static String statusCommand() { return "python \"" + Mx.iosRepository().resolve("Services\\mxservices.py") + "\" status"; }

    /** How to choose a language for a Layer 3 service. */
    public static final List<String[]> LANGUAGE_GUIDE = List.of(
            new String[] { "Python", "Data work: loading and cleaning sources, database queries, statistics (numpy, pandas), reports. Quickest to write and change; slower in tight numeric loops." },
            new String[] { "Java", "Long-running business services with many rules: portfolio construction constraints, compliance, order-management integration. Typed, unit-tested, stable for months." },
            new String[] { "C++", "Numerically heavy, latency-sensitive loops: large covariance matrices, tracking and risk recomputed for every scenario or rebalance." });

    public static final List<Service> ALL = List.of(
            new Service("srm-loader", "Security master loaders", "Python", 8770,
                    "Refreshes the security master (MxDataHUB/SRM) by category — reference data, the listed universe and issuers, benchmark constituents, identifiers, prices and index levels, corporate actions, market series (VIX, Treasury yields) and company fundamentals — by running srm.py's loaders one at a time, and reports data freshness and quality.",
                    "Loading is I/O: downloading files and API pages, parsing CSV / XML / JSON, upserting into PostgreSQL. Python does that with the least code, and the loaders are the same scripts you run from the command line.",
                    List.of("data-refresh", "data-quality"),
                    List.of(new Endpoint("GET", "/categories", "Data categories with their loaders, freshness and last load"),
                            new Endpoint("POST", "/refresh/{category}", "Run every loader of a category in order"),
                            new Endpoint("POST", "/load/{loader}", "Run one srm.py loader (X-SRM-Dry-Run: 1 to preview)"),
                            new Endpoint("GET", "/status", "Coverage, benchmarks, price freshness, recent loads, quality checks")),
                    List.of("srm-daily-refresh"), "srm-loader", "VS Code or PyCharm"),
            new Service("analytics", "Portfolio analytics", "Python", 8772,
                    "Data and statistics for portfolio work: builds optimization inputs from the security master (universe, sectors, benchmark and current weights, Ledoit-Wolf covariance, momentum alpha) and runs performance attribution (Brinson-Fachler by sector, monthly, Carino-linked).",
                    "Python with numpy is the quickest way to query the database, align price histories, estimate statistics and shape reports. The heavy optimization loops go to Java and C++.",
                    List.of("active", "passive", "attribution", "risk"),
                    List.of(new Endpoint("POST", "/inputs", "Optimization inputs for a portfolio / benchmark; returns inputsUrl", "mxjobs.inputs/1"),
                            new Endpoint("POST", "/attribution", "Sector attribution of a portfolio against its benchmark", "mxjobs.attribution/1"),
                            new Endpoint("GET", "/portfolios", "Portfolios with their benchmark and latest snapshot")),
                    List.of("active-optimization", "passive-tracking", "attribution"), "analytics", "VS Code or PyCharm"),
            new Service("accounts", "Personal accounts", "Python", 8774,
                    "Keeps MxAcctMGR's My Portfolio in the security master: searches securities to add, serves benchmark weights, and stores each personal account — type, approach, benchmark, cash and open tax lots — writing the day's holdings snapshot from the lots, so MxDataHUB ▸ Portfolios and the analytics service see it like any other portfolio.",
                    "Python and psycopg2 are the shortest path from an HTTP request to a PostgreSQL transaction, with srm.py's own connection settings — so MxAcctMGR needs no database credentials.",
                    List.of("data-quality", "active", "passive"),
                    List.of(new Endpoint("GET", "/securities?q=&kind=", "Search securities (ticker, name, ISIN, CUSIP) with the latest price"),
                            new Endpoint("GET", "/benchmarks/{code}/weights", "A benchmark's latest weights with sectors"),
                            new Endpoint("GET", "/accounts/{code}", "A personal account with its open tax lots, valued"),
                            new Endpoint("PUT", "/accounts/{code}", "Create or replace an account and its lots; rewrites today's holdings"),
                            new Endpoint("POST", "/accounts/{code}/load-prices", "Load prices for unpriced holdings (srm-loader: nasdaq-prices, no key needed)")),
                    List.of(), "accounts", "VS Code or PyCharm"),
            new Service("portfolio-java", "Active portfolio construction", "Java", 8766,
                    "Builds active (benchmark-relative) portfolios: maximise alpha for a tracking-error budget, long-only, with per-name, active-weight and sector-band limits, and a trade list against the current holdings. Also absolute max-Sharpe / mean-variance optimization.",
                    "Java suits rule-heavy, long-running services: constraints and business rules stay readable and unit-tested (JUnit), it runs for months without restarts, and it connects easily to order-management and compliance systems.",
                    List.of("active", "absolute"),
                    List.of(new Endpoint("POST", "/optimize/active", "Active mean-variance against a benchmark (inputsUrl from analytics)", "mxjobs.active-optimization/1"),
                            new Endpoint("POST", "/optimize", "Absolute max-Sharpe / mean-variance / min-variance", "mxjobs.optimization/1")),
                    List.of("active-optimization", "market-refresh"), "portfolio-java", "IntelliJ IDEA or Eclipse"),
            new Service("portfolio-cpp", "Index tracking & risk", "C++", 8767,
                    "Passive index tracking — the long-only portfolio of at most N names with the smallest ex-ante tracking error against a benchmark (full replication or sampling by largest weights or sector strata) — and portfolio risk: VaR, expected shortfall, risk contributions, and tracking error, beta and active-risk contributions against a benchmark.",
                    "C++ is for the numerically heavy loops: thousands of iterations over a 500 × 500 covariance finish in milliseconds, so tracking portfolios and risk can be recomputed for every rebalance, scenario or intraday check.",
                    List.of("passive", "risk", "absolute"),
                    List.of(new Endpoint("POST", "/optimize/tracking", "Minimum tracking error with at most maxNames securities", "mxjobs.tracking/1"),
                            new Endpoint("POST", "/risk", "VaR / ES / risk contributions; tracking error and active risk vs a benchmark", "mxjobs.risk/1"),
                            new Endpoint("POST", "/optimize", "Absolute max-Sharpe / mean-variance / min-variance", "mxjobs.optimization/1")),
                    List.of("passive-tracking", "attribution", "market-refresh"), "portfolio-cpp", "CLion, Visual Studio or VS Code"),
            new Service("market-sample", "Market data sample", "Python", 8765,
                    "Synthetic quotes, price history and indicators (SMA, EMA, RSI) — repeatable per symbol — for building and testing jobspaces without a data subscription. Replace history() with a real source to go live.",
                    "A standard-library Python service is the quickest way to stand up a JSON endpoint for a new data source; it is the template for wrapping your own feeds.",
                    List.of("templates", "testing"),
                    List.of(new Endpoint("GET", "/quote?symbol=", "Latest quote"),
                            new Endpoint("GET", "/indicators?symbol=&window=", "SMA, EMA, RSI")),
                    List.of("market-refresh"), "market-sample", "VS Code or PyCharm"));

    public static final List<Sample> SAMPLES = List.of(
            new Sample("srm-daily-refresh", "SRM Daily Refresh", "Data",
                    "Refresh every data category of the security master in dependency order, check data quality, and ask the data steward to approve the report and any backfills.",
                    List.of("srm-loader"), false),
            new Sample("active-optimization", "Active Portfolio Optimization", "Active",
                    "Momentum alpha (Python) → benchmark-relative optimization within a tracking-error budget and sector bands (Java) → risk check (C++) → decision, approval and the trade list.",
                    List.of("analytics", "portfolio-java", "portfolio-cpp"), true),
            new Sample("passive-tracking", "Passive Index Tracking", "Passive",
                    "Covariance from the security master (Python) → a sampled index portfolio with the smallest tracking error (C++) → rebalance decision against the drift budget, approval and trades.",
                    List.of("analytics", "portfolio-cpp"), true),
            new Sample("attribution", "Performance & Risk Attribution", "Analytics",
                    "Brinson-Fachler sector attribution, monthly and Carino-linked (Python), with ex-ante active risk by sector (C++) → commentary, review and publication.",
                    List.of("analytics", "portfolio-cpp"), true),
            new Sample("market-refresh", "Daily Market Data Refresh", "Templates",
                    "The original sample: public market data APIs, a Python script step, and the Java and C++ absolute optimizers.",
                    List.of("market-sample", "portfolio-java", "portfolio-cpp"), false));

    public static Optional<Service> service(String id) {
        return ALL.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static Optional<Sample> sample(String id) {
        return SAMPLES.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** Whether a service answers on its port now (call off the UI thread). */
    public static boolean isRunning(Service s) {
        return Http.isUp(s.baseUrl());
    }

    /** The running/stopped state of every service, probed in parallel (call off the UI thread). */
    public static Map<String, Boolean> probeAll() {
        Map<String, CompletableFuture<Boolean>> futures = ALL.stream().collect(Collectors.toMap(
                Service::id, s -> CompletableFuture.supplyAsync(() -> isRunning(s))));
        return futures.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().join()));
    }
}
