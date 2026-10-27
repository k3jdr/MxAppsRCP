package com.k3jdr.mxacctmgr.port;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.k3jdr.mx.core.http.Http;
import com.k3jdr.mx.core.json.Json;

/**
 * The accounts service (Services/accounts, 127.0.0.1:8774): search securities, read benchmark weights, load and save
 * accounts with their tax lots. Synchronous: call off the UI thread.
 */
public final class AccountsClient {
    private AccountsClient() {}

    /** The service; -Dmxacctmgr.accountsPort=18774 points the app at a test instance. */
    public static final String BASE = "http://127.0.0.1:" + System.getProperty("mxacctmgr.accountsPort", "8774");
    public static final String SERVICE = "accounts (Python, port 8774)";

    /** A filterable column's value and how many securities have it. */
    public record Facet(String value, int count) {}

    /** One page of matching securities, how many match in all, and (when asked) each filterable column's values. */
    public record SearchPage(List<SRMSecurity> securities, int total, Map<String, List<Facet>> facets) {}

    /** Each ticker's security with its latest price, and the tickers the security master lacks. */
    public record Resolved(Map<String, SRMSecurity> found, List<String> unknown) {}

    /** A month-end close. */
    public record MonthClose(String date, double close) {}

    /** What POST /accounts/{code}/load-prices did, and the account after it. */
    public record PriceLoad(List<String> loaded, String status, String message, SRMAccount account) {
        public PriceLoad {
            loaded = loaded == null ? List.of() : List.copyOf(loaded);
        }
    }

    /** A search request: text, kind, column filters (the service's parameter names), sort column and order. */
    public static final class Search {
        public String text = "";
        public String kind = "all";
        public Map<String, String> filters = new LinkedHashMap<>();
        public String sort = "relevance";
        public boolean descending;

        public Search copy() {
            Search s = new Search();
            s.text = text;
            s.kind = kind;
            s.filters = new LinkedHashMap<>(filters);
            s.sort = sort;
            s.descending = descending;
            return s;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Search s && Objects.equals(text, s.text) && Objects.equals(kind, s.kind)
                    && Objects.equals(filters, s.filters) && Objects.equals(sort, s.sort) && descending == s.descending;
        }

        @Override
        public int hashCode() { return Objects.hash(text, kind, filters, sort, descending); }
    }

    // Requests

    /** A request; the answer's JSON, or a ServiceException (409 with an error: conflict). */
    public static JsonElement request(String method, String path, JsonObject body, Duration timeout) throws ServiceException {
        Http.Response r;
        try {
            r = Http.send(method, BASE + path, body == null ? null : Json.COMPACT.toJson(body), timeout, Map.of());
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw ServiceException.from(e, SERVICE);
        }
        JsonElement json = null;
        try {
            json = JsonParser.parseString(r.body());
        } catch (RuntimeException ignored) {
            // decoded below
        }
        String error = JsonRead.str(JsonRead.obj(json), "error");
        if (error != null) {
            if (r.status() == 409) throw ServiceException.conflict(error);
            throw ServiceException.rejected("Security master: " + error);
        }
        if (json == null || json.isJsonNull()) throw unexpected("no JSON in the answer");
        return json;
    }

    private static JsonElement get(String path) throws ServiceException {
        return request("GET", path, null, Duration.ofSeconds(60));
    }

    private static <T> T decode(JsonElement json, Type type) throws ServiceException {
        try {
            T value = Json.COMPACT.fromJson(json, type);
            if (value == null) throw unexpected("no data");
            return value;
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unexpected(e.getMessage());
        }
    }

    private static <T> T decode(JsonElement json, Class<T> type) throws ServiceException {
        return decode(json, (Type) type);
    }

    /** A key of the answer's object, decoded. */
    private static <T> T field(JsonElement json, String key, Type type) throws ServiceException {
        JsonObject o = JsonRead.obj(json);
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) throw unexpected("no " + key);
        return decode(e, type);
    }

    private static ServiceException unexpected(String detail) {
        return ServiceException.rejected("The accounts service answered in an unexpected form (" + detail + ").");
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static Type listOf(Class<?> c) { return TypeToken.getParameterized(List.class, c).getType(); }

    // Securities and benchmarks

    /** One page of matching securities, how many match in all, and (with facets) each filterable column's values. */
    public static SearchPage search(Search s, int offset, int limit, boolean facets) throws ServiceException {
        StringBuilder q = new StringBuilder();
        q.append("q=").append(enc(s.text)).append("&kind=").append(enc(s.kind)).append("&sort=").append(enc(s.sort))
                .append("&dir=").append(s.descending ? "desc" : "asc").append("&offset=").append(offset)
                .append("&limit=").append(limit);
        for (Map.Entry<String, String> f : s.filters.entrySet()) {
            if (f.getValue() != null && !f.getValue().isEmpty()) q.append('&').append(enc(f.getKey())).append('=').append(enc(f.getValue()));
        }
        if (facets) q.append("&facets=1");
        JsonElement json = get("/securities?" + q);
        List<SRMSecurity> securities = field(json, "securities", listOf(SRMSecurity.class));
        Integer total = JsonRead.integer(JsonRead.obj(json), "total");
        Map<String, List<Facet>> facetMap = null;
        JsonElement fe = json.getAsJsonObject().get("facets");
        if (fe != null && !fe.isJsonNull()) {
            facetMap = decode(fe, TypeToken.getParameterized(Map.class, String.class, listOf(Facet.class)).getType());
        }
        return new SearchPage(securities, total != null ? total : securities.size(), facetMap);
    }

    public static SearchPage search(Search s) throws ServiceException { return search(s, 0, 200, false); }

    public static List<SRMBenchmarkInfo> benchmarks() throws ServiceException {
        return field(get("/benchmarks"), "benchmarks", listOf(SRMBenchmarkInfo.class));
    }

    public static SRMBenchmarkWeights benchmarkWeights(String code) throws ServiceException {
        return decode(get("/benchmarks/" + enc(code) + "/weights"), SRMBenchmarkWeights.class);
    }

    // Accounts

    /** Every personal account's code. */
    public static List<String> accounts() throws ServiceException {
        List<String> out = new ArrayList<>();
        for (SRMAccountSummary a : accountList()) out.add(a.code());
        return out;
    }

    /** Every personal account with its value, version and who saved it last (File ▸ Open Portfolio…). */
    public static List<SRMAccountSummary> accountList() throws ServiceException {
        return field(get("/accounts"), "accounts", listOf(SRMAccountSummary.class));
    }

    /** A portfolio's decisions, newest first. */
    public static List<SRMDecision> decisions(String code) throws ServiceException {
        return field(get("/accounts/" + enc(code) + "/decisions"), "decisions", listOf(SRMDecision.class));
    }

    /** Records a decision; returns them all. */
    public static List<SRMDecision> addDecision(String code, String title, String rationale, String category,
                                                String decidedOn) throws ServiceException {
        JsonObject body = new JsonObject();
        body.addProperty("title", title);
        body.addProperty("rationale", rationale);
        body.addProperty("category", category);
        body.addProperty("decidedOn", decidedOn);
        body.addProperty("by", "MxAcctMGR");
        return field(request("POST", "/accounts/" + enc(code) + "/decisions", body, Duration.ofSeconds(60)),
                "decisions", listOf(SRMDecision.class));
    }

    /** A new portfolio from the saved version of code. */
    public static SRMAccount duplicate(String code, String newCode, String name) throws ServiceException {
        JsonObject body = new JsonObject();
        body.addProperty("code", newCode);
        body.addProperty("name", name);
        body.addProperty("by", "MxAcctMGR");
        return decode(request("POST", "/accounts/" + enc(code) + "/duplicate", body, Duration.ofSeconds(60)), SRMAccount.class);
    }

    public static List<SRMPolicyBenchmark> policyBenchmarks() throws ServiceException {
        return field(get("/benchmarks/policy"), "benchmarks", listOf(SRMPolicyBenchmark.class));
    }

    /** Defines a policy benchmark's mixes; the service then has the srm-loader write its snapshots (up to 5 min). */
    public static SRMPolicyBenchmark savePolicy(SRMPolicyBenchmark p) throws ServiceException {
        JsonObject body = new JsonObject();
        body.addProperty("name", p.name());
        body.addProperty("by", "MxAcctMGR");
        JsonArray mixes = new JsonArray();
        for (SRMPolicyBenchmark.Mix m : p.mixes()) {
            JsonObject mix = new JsonObject();
            mix.addProperty("effectiveFrom", m.effectiveFrom());
            JsonArray components = new JsonArray();
            for (SRMPolicyBenchmark.Component c : m.components()) {
                JsonObject comp = new JsonObject();
                comp.addProperty("ticker", c.ticker());
                comp.addProperty("weight", c.weight());
                components.add(comp);
            }
            mix.add("components", components);
            mixes.add(mix);
        }
        body.add("mixes", mixes);
        return decode(request("PUT", "/benchmarks/policy/" + enc(p.code()), body, Duration.ofSeconds(300)),
                SRMPolicyBenchmark.class);
    }

    /** Each ticker's security with its latest price (Import Holdings), and the tickers the security master lacks. */
    public static Resolved resolve(List<String> tickers) throws ServiceException {
        JsonObject body = new JsonObject();
        body.add("tickers", ServiceClient.strings(tickers));
        JsonElement json = request("POST", "/securities/resolve", body, Duration.ofSeconds(60));
        Map<String, SRMSecurity> found = field(json, "securities",
                TypeToken.getParameterized(Map.class, String.class, SRMSecurity.class).getType());
        List<String> unknown = field(json, "unknown", listOf(String.class));
        return new Resolved(found, unknown);
    }

    public static SRMAccount account(String code) throws ServiceException {
        return decode(get("/accounts/" + enc(code)), SRMAccount.class);
    }

    /** Saves the account (PUT); a ServiceException of kind CONFLICT when it changed since the version the body names. */
    public static SRMAccount save(String code, JsonObject body) throws ServiceException {
        return decode(request("PUT", "/accounts/" + enc(code), body, Duration.ofSeconds(60)), SRMAccount.class);
    }

    /** Month-end closes over the last months, by the security master's ticker. */
    public static Map<String, List<MonthClose>> monthlyPrices(List<String> tickers, int months) throws ServiceException {
        JsonObject body = new JsonObject();
        body.add("tickers", ServiceClient.strings(tickers));
        body.addProperty("months", months);
        return field(request("POST", "/prices/monthly", body, Duration.ofSeconds(60)), "prices",
                TypeToken.getParameterized(Map.class, String.class, listOf(MonthClose.class)).getType());
    }

    public static Map<String, List<MonthClose>> monthlyPrices(List<String> tickers) throws ServiceException {
        return monthlyPrices(tickers, 12);
    }

    /** Asks the security master to load prices for the account's unpriced holdings (up to 10 min). */
    public static PriceLoad loadPrices(String code) throws ServiceException {
        return decode(request("POST", "/accounts/" + enc(code) + "/load-prices", null, Duration.ofSeconds(600)), PriceLoad.class);
    }

    // Saving My Portfolio (PortStore.saveSRMAccount)

    /**
     * The PUT body for My Portfolio: its lots (shares from each holding's quantity, or value / price), cash, and the
     * version it was opened at. settings: PortfolioSettings JSON (settingsJson); note: the loose ends joined by "; ".
     */
    public static JsonObject saveBody(Portfolio mine, PortfolioStyle approach, int version, JsonObject settings, String note) {
        String today = LocalDate.now().toString();
        JsonArray lots = new JsonArray();
        for (Holding h : mine.holdings) {
            Security s = h.security();
            if (s == null || s.srmID == null || s.srmID <= 0) continue;
            double quantity = h.quantity != null ? h.quantity : (s.price > 0 ? h.value / s.price : 0);
            if (!(quantity > 0)) continue;
            List<TaxLot> parts = h.lots.isEmpty() ? List.of(new TaxLot(today, 1, h.value)) : h.lots;
            for (TaxLot l : parts) {
                if (!(l.share > 0)) continue;
                JsonObject lot = new JsonObject();
                lot.addProperty("securityId", s.srmID);
                lot.addProperty("acquired", l.acquired != null && l.acquired.length() == 10 ? l.acquired : today);
                lot.addProperty("quantity", quantity * l.share);
                lot.addProperty("costBasis", l.costBasis);
                if (l.note != null && !l.note.isEmpty()) lot.addProperty("note", l.note);
                lots.add(lot);
            }
        }
        double cash = 0;
        for (Holding h : mine.holdings) if ("CASH".equals(h.ticker)) cash += h.value;
        JsonObject body = new JsonObject();
        body.addProperty("name", mine.name);
        body.addProperty("accountType", mine.account.code);
        body.addProperty("approach", approach == PortfolioStyle.ACTIVE ? "active" : "passive");
        body.addProperty("benchmark", mine.benchmark);
        body.addProperty("cash", cash);
        body.add("lots", lots);
        body.addProperty("version", version);
        body.add("settings", settings != null ? settings : new JsonObject());
        body.addProperty("by", "MxAcctMGR");
        body.addProperty("note", note != null ? note : "");
        return body;
    }

    /** PortfolioSettings JSON as MxAcctMGR saves it with the account (schema 1). */
    public static JsonObject settingsJson(ActiveSettings active, TrackingSettings tracking, LotOrder lotOrder,
                                          double longTermRate, double shortTermRate, double bandWidth,
                                          AttributionEngine.Method attributionMethod) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", 1);
        if (active != null) o.add("active", active.toJson());
        if (tracking != null) o.add("tracking", tracking.toJson());
        if (lotOrder != null) o.addProperty("lotOrder", lotOrder.title);
        o.addProperty("longTermRate", longTermRate);
        o.addProperty("shortTermRate", shortTermRate);
        o.addProperty("bandWidth", bandWidth);
        if (attributionMethod != null) o.addProperty("attributionMethod", attributionMethod.rawValue);
        return o;
    }
}
