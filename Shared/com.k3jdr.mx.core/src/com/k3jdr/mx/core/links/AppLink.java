package com.k3jdr.mx.core.links;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.k3jdr.mx.core.Mx;

/**
 * How the apps hand work to each other (MxAppsIOS/Shared/AppLinks.swift):
 *
 * <pre>
 *   mxjobsmgr://sample/&lt;sample id&gt;?portfolio=CODE&amp;benchmark=CODE   new jobspace from a sample, for a portfolio
 *   mxdatahub://portfolio/&lt;CODE&gt;    mxdatahub://benchmark/&lt;CODE&gt;    mxdatahub://data-refresh    mxdatahub://services
 *   mxacctmgr://my-portfolio/&lt;CODE&gt;   My Portfolio, opened from the security-master account CODE
 *   mxacctmgr://secure-accounts/&lt;id&gt;   Banking ▸ Secure Accounts, with that account open
 *   mxacctmgr://secure-accounts/new?name=…&amp;api=…&amp;url=…   New Secure Account, filled in
 * </pre>
 *
 * The schemes from before the renames (mxjobswms://, mxportmgr://) still open their apps.
 */
public sealed interface AppLink {
    String JOBS_SCHEME = "mxjobsmgr";
    String HUB_SCHEME = "mxdatahub";
    String ACCT_SCHEME = "mxacctmgr";
    String LEGACY_JOBS_SCHEME = "mxjobswms";
    String LEGACY_ACCT_SCHEME = "mxportmgr";

    record Sample(String id, String portfolio, String benchmark) implements AppLink {}
    record Portfolio(String code) implements AppLink {}
    record Benchmark(String code) implements AppLink {}
    record DataRefresh() implements AppLink {}
    record Services() implements AppLink {}
    record MyPortfolio(String code) implements AppLink {}
    record SecureAccount(Integer id) implements AppLink {}
    record NewSecureAccount(String name, String api, String url) implements AppLink {}

    /** The app this link opens. */
    default String appName() {
        return switch (this) {
            case Sample s -> Mx.JOBSMGR;
            case MyPortfolio m -> Mx.ACCTMGR;
            case SecureAccount s -> Mx.ACCTMGR;
            case NewSecureAccount n -> Mx.ACCTMGR;
            default -> Mx.DATAHUB;
        };
    }

    default String scheme() {
        return switch (appName()) {
            case Mx.JOBSMGR -> JOBS_SCHEME;
            case Mx.ACCTMGR -> ACCT_SCHEME;
            default -> HUB_SCHEME;
        };
    }

    /** The link as a URI string. */
    default String url() {
        return switch (this) {
            case Sample s -> JOBS_SCHEME + "://sample/" + enc(s.id()) + query(Map.of(
                    "portfolio", nz(s.portfolio()), "benchmark", nz(s.benchmark())));
            case Portfolio p -> HUB_SCHEME + "://portfolio/" + enc(p.code());
            case Benchmark b -> HUB_SCHEME + "://benchmark/" + enc(b.code());
            case DataRefresh d -> HUB_SCHEME + "://data-refresh";
            case Services s -> HUB_SCHEME + "://services";
            case MyPortfolio m -> ACCT_SCHEME + "://my-portfolio" + (m.code() == null || m.code().isEmpty() ? "" : "/" + enc(m.code()));
            case SecureAccount s -> ACCT_SCHEME + "://secure-accounts" + (s.id() == null ? "" : "/" + s.id());
            case NewSecureAccount n -> {
                Map<String, String> q = new LinkedHashMap<>();
                q.put("name", nz(n.name()));
                q.put("api", nz(n.api()));
                q.put("url", nz(n.url()));
                yield ACCT_SCHEME + "://secure-accounts/new" + query(q);
            }
        };
    }

    /** Reads a link; empty when it is not one of the apps' links. */
    static Optional<AppLink> parse(String text) {
        if (text == null) return Optional.empty();
        URI uri;
        try {
            uri = URI.create(text.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? uri.getAuthority() : uri.getHost();
        if (host == null) return Optional.empty();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String value = path.isEmpty() ? "" : dec(path.replaceFirst("^/", "").split("/")[0]);
        Map<String, String> q = params(uri.getRawQuery());
        AppLink link = switch (scheme + "|" + host) {
            case JOBS_SCHEME + "|sample", LEGACY_JOBS_SCHEME + "|sample" ->
                    value.isEmpty() ? null : new Sample(value, q.get("portfolio"), q.get("benchmark"));
            case HUB_SCHEME + "|portfolio" -> value.isEmpty() ? null : new Portfolio(value);
            case HUB_SCHEME + "|benchmark" -> value.isEmpty() ? null : new Benchmark(value);
            case HUB_SCHEME + "|data-refresh" -> new DataRefresh();
            case HUB_SCHEME + "|services" -> new Services();
            case ACCT_SCHEME + "|my-portfolio", LEGACY_ACCT_SCHEME + "|my-portfolio" -> new MyPortfolio(value.isEmpty() ? null : value);
            case ACCT_SCHEME + "|secure-accounts", LEGACY_ACCT_SCHEME + "|secure-accounts" -> {
                if (value.equals("new")) yield new NewSecureAccount(q.getOrDefault("name", ""), q.get("api"), q.get("url"));
                Integer id = null;
                try {
                    id = value.isEmpty() ? null : Integer.valueOf(value);
                } catch (NumberFormatException e) {
                    // an unknown id opens the list
                }
                yield new SecureAccount(id);
            }
            default -> null;
        };
        return Optional.ofNullable(link);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }

    private static String query(Map<String, String> items) {
        StringBuilder sb = new StringBuilder();
        items.forEach((k, v) -> {
            if (v == null || v.isEmpty()) return;
            sb.append(sb.isEmpty() ? '?' : '&').append(k).append('=').append(enc(v));
        });
        return sb.toString();
    }

    private static Map<String, String> params(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(dec(pair), "");
            else out.put(dec(pair.substring(0, eq)), dec(pair.substring(eq + 1)));
        }
        return out;
    }
}
