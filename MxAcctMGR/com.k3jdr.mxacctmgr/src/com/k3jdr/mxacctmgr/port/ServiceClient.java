package com.k3jdr.mxacctmgr.port;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k3jdr.mx.core.http.Http;
import com.k3jdr.mx.core.json.Json;

/**
 * The same optimizations through the Layer 3 services: the account's inputs go inline, so portfolio-java's
 * /optimize/active and portfolio-cpp's /optimize/tracking work on exactly what the built-in engine sees; the analytics
 * service's /attribution reads the account from the security master by code, and /inputs builds the inputs.
 * Synchronous: call off the UI thread.
 */
public final class ServiceClient {
    private ServiceClient() {}

    public static final String PORTFOLIO_JAVA = "http://127.0.0.1:8766";
    public static final String PORTFOLIO_CPP = "http://127.0.0.1:8767";
    public static final String ANALYTICS = "http://127.0.0.1:8772";
    public static final String PORTFOLIO_JAVA_SERVICE = "portfolio-java (Java, port 8766)";
    public static final String PORTFOLIO_CPP_SERVICE = "portfolio-cpp (C++, port 8767)";
    public static final String ANALYTICS_SERVICE = "analytics (Python, port 8772)";

    /** Inputs from the analytics service and what they cover ("40 securities, 250 days of prices"). */
    public record InputsAnswer(OptimizationInputs inputs, String note) {}

    /** POST body as JSON (60 s); the answer object, or a ServiceException with the text to show. */
    public static JsonObject post(String base, String path, JsonObject body, String service) throws ServiceException {
        String text;
        try {
            text = Json.COMPACT.toJson(body);
        } catch (RuntimeException e) {
            throw ServiceException.rejected(service + ": the request could not be written (" + e.getMessage() + ").");
        }
        Http.Response r;
        try {
            r = Http.send("POST", base + path, text, Duration.ofSeconds(60), Map.of());
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw ServiceException.from(e, service);
        }
        JsonObject json = null;
        try {
            json = JsonRead.obj(JsonParser.parseString(r.body()));
        } catch (RuntimeException ignored) {
            // not JSON
        }
        if (json == null) throw ServiceException.rejected(service + " answered with something that isn't JSON.");
        String error = JsonRead.str(json, "error");
        if (error != null && !json.has("weights") && !json.has("portfolioReturn")) {
            throw ServiceException.rejected(service + ": " + error);
        }
        return json;
    }

    private static JsonObject inputsBody(OptimizationInputs inputs) {
        int n = inputs.n();
        JsonObject body = new JsonObject();
        body.add("assets", strings(inputs.assets));
        body.add("sectors", strings(inputs.sectors));
        body.add("benchmarkWeights", numbers(inputs.benchmark, 0, inputs.benchmark.length));
        JsonObject current = new JsonObject();
        for (int i = 0; i < n; i++) if (inputs.current[i] > 0) current.addProperty(inputs.assets.get(i), inputs.current[i]);
        body.add("currentWeights", current);
        body.add("expectedReturns", numbers(inputs.alpha, 0, inputs.alpha.length));
        JsonArray cov = new JsonArray();
        for (int i = 0; i < n; i++) cov.add(numbers(inputs.cov, i * n, i * n + n));
        body.add("covariance", cov);
        return body;
    }

    /** portfolio-java's POST /optimize/active on these inputs. */
    public static OptimizationResult active(OptimizationInputs inputs, ActiveSettings s) throws ServiceException {
        JsonObject body = inputsBody(inputs);
        body.addProperty("maxWeight", s.maxWeight);
        body.addProperty("maxActiveWeight", s.maxActiveWeight);
        body.addProperty("riskAversion", s.riskAversion);
        if (s.sectorBand != null) body.addProperty("maxSectorActive", s.sectorBand);
        if (s.trackingErrorTarget != null) body.addProperty("trackingErrorTarget", s.trackingErrorTarget);
        if (s.maxTurnover != null) body.addProperty("maxTurnover", s.maxTurnover);
        JsonObject json = post(PORTFOLIO_JAVA, "/optimize/active", body, PORTFOLIO_JAVA_SERVICE);
        return result(json, inputs, "portfolio-java service");
    }

    /** portfolio-cpp's POST /optimize/tracking on these inputs. */
    public static OptimizationResult track(OptimizationInputs inputs, TrackingSettings s) throws ServiceException {
        JsonObject body = inputsBody(inputs);
        body.addProperty("maxNames", s.maxNames);
        body.addProperty("selection", s.selection.rawValue);
        body.addProperty("maxWeight", s.maxWeight);
        body.addProperty("trackingErrorBudget", s.trackingErrorBudget);
        if (s.maxTurnover != null) body.addProperty("maxTurnover", s.maxTurnover);
        JsonObject json = post(PORTFOLIO_CPP, "/optimize/tracking", body, PORTFOLIO_CPP_SERVICE);
        return result(json, inputs, "portfolio-cpp service");
    }

    private static OptimizationResult result(JsonObject json, OptimizationInputs inputs, String engine) {
        Map<String, Double> weights = JsonRead.numberMap(json.get("weights"));
        int n = inputs.n();
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = weights.getOrDefault(inputs.assets.get(i), 0.0);
        JsonObject dc = JsonRead.objOrEmpty(json, "decisionContext");
        Map<String, Double> sectorActive = JsonRead.numberMap(json.get("sectorActiveWeights"));
        JsonObject diagnostics = JsonRead.objOrEmpty(json, "diagnostics");
        Double activeShare = JsonRead.num(json, "activeShare");
        if (activeShare == null) {
            double a = 0;
            for (int i = 0; i < n; i++) a += Math.abs(w[i] - inputs.benchmark[i]) / 2;
            activeShare = a;
        }
        String method = JsonRead.str(json, "method");
        if (method == null) method = JsonRead.str(json, "objective");
        Double ms = JsonRead.num(diagnostics, "ms");
        return new OptimizationResult(engine, JsonRead.str(json, "solverStatus", "unknown"), weights,
                JsonRead.num0(json, "trackingError"), JsonRead.num(json, "currentTrackingError"),
                JsonRead.num(json, "activeAlpha"), JsonRead.num(json, "informationRatio"), activeShare,
                JsonRead.num0(json, "turnover"), weights.size(), JsonRead.stringsOrEmpty(json, "bindingConstraints"),
                sectorActive, Engine.trades(inputs, w),
                new Decision(JsonRead.str(dc, "recommendation", "escalate"), JsonRead.str(dc, "reason", "")),
                JsonRead.num(json, "riskAversion"), method, JsonRead.num(json, "benchmarkWeightCovered"),
                ms != null ? ms : 0);
    }

    /** Attribution of a security-master portfolio (MxDataHUB ▸ Portfolios) by the analytics service. */
    public static AttributionResult attribution(String code, AttributionEngine.Method method) throws ServiceException {
        JsonObject body = new JsonObject();
        body.addProperty("portfolio", code);
        body.addProperty("method", method.rawValue);
        JsonObject json = post(ANALYTICS, "/attribution", body, ANALYTICS_SERVICE);
        return attributionResult(json, method, "analytics service · " + code + " vs " + JsonRead.str(json, "benchmark", ""));
    }

    private static AttributionResult attributionResult(JsonObject json, AttributionEngine.Method method, String engine) {
        JsonObject effects = JsonRead.objOrEmpty(json, "effects");
        List<AttributionResult.Group> groups = new ArrayList<>();
        for (JsonObject g : JsonRead.objects(json, "byGroup")) {
            groups.add(new AttributionResult.Group(JsonRead.str(g, "group", ""), JsonRead.num0(g, "portfolioWeight"),
                    JsonRead.num0(g, "benchmarkWeight"), JsonRead.num0(g, "activeWeight"), JsonRead.num0(g, "allocation"),
                    JsonRead.num0(g, "selection"), JsonRead.num0(g, "interaction")));
        }
        List<AttributionResult.Period> periods = new ArrayList<>();
        for (JsonObject p : JsonRead.objects(json, "periods")) {
            String month = JsonRead.str(p, "period");
            if (month == null) {
                String end = JsonRead.str(p, "end", "");
                month = end.substring(0, Math.min(7, end.length()));
            }
            periods.add(new AttributionResult.Period(month, JsonRead.num0(p, "portfolioReturn"),
                    JsonRead.num0(p, "benchmarkReturn"), JsonRead.num0(p, "allocation"), JsonRead.num0(p, "selection"),
                    JsonRead.num0(p, "interaction")));
        }
        JsonObject dc = JsonRead.objOrEmpty(json, "decisionContext");
        JsonObject gaps = JsonRead.objOrEmpty(json, "dataGaps");
        return new AttributionResult(engine, method.title(),
                JsonRead.num0(json, "portfolioReturn"), JsonRead.num0(json, "benchmarkReturn"),
                JsonRead.num0(effects, "allocation"), JsonRead.num0(effects, "selection"),
                JsonRead.num0(effects, "interaction"), JsonRead.num0(effects, "residual"), groups, periods,
                contributors(json, "topContributors"), contributors(json, "bottomContributors"),
                Math.max(JsonRead.num0(gaps, "portfolio"), JsonRead.num0(gaps, "benchmark")),
                JsonRead.str(dc, "recommendation", "review"), JsonRead.stringsOrEmpty(dc, "reasons"),
                JsonRead.str(dc, "highlight", ""), JsonRead.stringsOrEmpty(json, "assumptions"));
    }

    private static List<AttributionResult.Contributor> contributors(JsonObject json, String key) {
        List<AttributionResult.Contributor> out = new ArrayList<>();
        for (JsonObject c : JsonRead.objects(json, key)) {
            out.add(new AttributionResult.Contributor(JsonRead.str(c, "ticker", ""), JsonRead.str(c, "sector", ""),
                    JsonRead.num0(c, "portfolioWeight"), JsonRead.num0(c, "benchmarkWeight"),
                    JsonRead.num0(c, "activeContribution")));
        }
        return out;
    }

    // Inputs from the analytics service (SRMData.swift's extension)

    /**
     * Optimization inputs for a security-master account from the analytics service: POST /inputs, then the full
     * matrices from its inputsUrl, with each asset mapped to the app's key for it. mode: active, tracking, risk, absolute.
     */
    public static InputsAnswer srmInputs(String code, String benchmark, String mode) throws ServiceException {
        JsonObject body = new JsonObject();
        body.addProperty("portfolio", code);
        body.addProperty("mode", mode);
        if (benchmark != null) body.addProperty("benchmark", benchmark);
        return riskInputs(body);
    }

    /** Risk inputs for securities by ticker (Learn, stress tests, Add Securities): covariance, betas, rate betas. */
    public static InputsAnswer riskInputs(List<String> tickers, String benchmark) throws ServiceException {
        JsonObject body = new JsonObject();
        body.add("securities", strings(tickers));
        body.addProperty("mode", benchmark == null ? "risk" : "absolute");
        body.addProperty("alpha", "none");
        if (benchmark != null) body.addProperty("benchmark", benchmark);
        return riskInputs(body);
    }

    /**
     * POST /inputs, then the full matrices; registers every asset and gives it the service's betas, rate betas,
     * volatility and alpha (Universe.security then carries them), and the risk-free rate.
     */
    public static InputsAnswer riskInputs(JsonObject body) throws ServiceException {
        JsonObject summary = post(ANALYTICS, "/inputs", body, ANALYTICS_SERVICE);
        String url = JsonRead.str(summary, "inputsUrl");
        if (url != null && url.startsWith("/")) url = ANALYTICS + url;
        if (url == null || url.isBlank()) throw ServiceException.rejected("analytics: no inputsUrl in the answer");
        Http.Response r;
        try {
            r = Http.get(url, Duration.ofSeconds(60), Map.of());
        } catch (IllegalArgumentException e) {
            throw ServiceException.rejected("analytics: no inputsUrl in the answer");
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw ServiceException.from(e, ANALYTICS_SERVICE);
        }
        JsonObject json = null;
        try {
            json = JsonRead.obj(JsonParser.parseString(r.body()));
        } catch (RuntimeException ignored) {
            // not JSON
        }
        List<String> assets = json == null ? null : JsonRead.strings(json.get("assets"));
        double[][] cov = json == null ? null : JsonRead.matrix(json.get("covariance"));
        if (assets == null || cov == null || cov.length < assets.size()) {
            throw ServiceException.rejected("analytics: the inputs could not be read");
        }
        int n = assets.size();
        List<String> names = JsonRead.strings(json.get("names"));
        if (names == null) names = assets;
        List<String> sectors = JsonRead.strings(json.get("sectors"));
        SRMRegistry registry = SRMRegistry.shared();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String t = assets.get(i);
            String key = registry.key(t);
            if (key == null) {
                String name = i < names.size() ? names.get(i) : t;
                String sector = sectors != null && i < sectors.size() ? sectors.get(i) : "Unclassified";
                key = registry.register(new SRMSecurity(-1 - i, t, name, sector, "stock"));
            }
            keys.add(key);
        }
        Map<String, Double> current = JsonRead.strictNumberMap(json.get("currentWeights"));
        if (current == null) current = Map.of();
        double[] vols = new double[cov.length];
        for (int i = 0; i < cov.length; i++) vols[i] = i < cov[i].length ? Math.sqrt(cov[i][i]) : Double.NaN;
        String proxy = JsonRead.str(json, "marketProxy");
        int marketIndex = proxy == null ? -1 : assets.indexOf(proxy);
        Double marketVol = JsonRead.num(json, "marketVol");
        if (marketVol == null && marketIndex >= 0) marketVol = vols[marketIndex];
        double[] expected = JsonRead.doubles(json.get("expectedReturns"));
        registry.applyRisk(keys, JsonRead.doubles(json.get("betas")), JsonRead.doubles(json.get("rateBetas")), vols,
                "active".equals(JsonRead.str(body, "mode")) ? expected : null, JsonRead.num(json, "riskFree"), marketVol);
        List<String> keySectors = new ArrayList<>();
        for (String k : keys) {
            Security s = Universe.security(k);
            keySectors.add(s != null ? s.sector : "Unclassified");
        }
        double[] benchmark = JsonRead.doubles(json.get("benchmarkWeights"));
        benchmark = benchmark == null ? new double[n] : java.util.Arrays.copyOf(benchmark, n);
        double[] cur = new double[n];
        for (int i = 0; i < n; i++) cur[i] = current.getOrDefault(assets.get(i), 0.0);
        expected = expected == null ? new double[n] : java.util.Arrays.copyOf(expected, n);
        double[] flat = new double[n * n];
        for (int i = 0; i < n; i++) {
            if (cov[i].length < n) throw ServiceException.rejected("analytics: the inputs could not be read");
            System.arraycopy(cov[i], 0, flat, i * n, n);
        }
        OptimizationInputs inputs = new OptimizationInputs(keys, keySectors, benchmark, cur, expected, flat);
        List<String> excluded = JsonRead.stringsOrEmpty(summary, "excluded");
        Integer observations = JsonRead.integer(JsonRead.obj(json.get("statistics")), "observations");
        String note = n + " securities, " + (observations != null ? observations : 0) + " days of prices"
                + (excluded.isEmpty() ? "" : "; left out for lack of price history: " + String.join(", ", excluded));
        return new InputsAnswer(inputs, note);
    }

    // JSON building

    static JsonArray strings(List<String> values) {
        JsonArray a = new JsonArray();
        for (String s : values) a.add(s);
        return a;
    }

    static JsonArray numbers(double[] values, int from, int to) {
        JsonArray a = new JsonArray();
        for (int i = from; i < to; i++) a.add(values[i]);
        return a;
    }
}
