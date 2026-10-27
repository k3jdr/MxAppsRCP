package com.k3jdr.mxjobsmgr.engine;

import java.net.URI;
import java.util.List;

import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.Specs.AuthStyle;

/** How to get and add a key for each data provider (KeyHelp.swift): steps, how it is sent, limits, notes. */
public record ProviderGuide(String id, String name, List<String> hosts, String signupURL, String docsURL, boolean needsKey,
                            AuthStyle auth, String keyName, List<String> steps, String limits, List<String> notes,
                            String baseURL) {

    public static final List<ProviderGuide> ALL = List.of(
            new ProviderGuide("alphavantage", "Alpha Vantage", List.of("alphavantage.co"),
                    "https://www.alphavantage.co/support/#api-key", "https://www.alphavantage.co/documentation/",
                    true, AuthStyle.QUERY_PARAMETER, "apikey",
                    List.of("Open the Alpha Vantage key page (Get a Key…).",
                            "Fill in the short form (what you use it for, organisation, email) and press GET FREE API KEY.",
                            "The key appears on the page right away — a 16-character code of letters and digits. Copy it.",
                            "In MxAcctMGR ▸ Secure Accounts, put it in the account's Service key (New in MxAcctMGR… under API Key makes one); then Test Key here."),
                    "Free tier: 25 requests a day. Premium plans raise the limit.",
                    List.of("MxJobsMGR sends the key as the apikey query parameter (masked in Test Call).",
                            "Alpha Vantage answers HTTP 200 even for a bad key or a reached limit: the message is in the JSON "
                                    + "(Error Message, Information or Note). Test Key and Test Call show it.",
                            "The free tier also allows about 1 request a second: a quick burst of calls gets an Information "
                                    + "notice instead of data (Test Key reports it as a rate limit).",
                            "Alpha Vantage does not reject unknown keys, so Test Key confirms that calls work rather than "
                                    + "that the key is yours — make sure you paste your own key."),
                    null),
            new ProviderGuide("finnhub", "Finnhub", List.of("finnhub.io"), "https://finnhub.io/register", "https://finnhub.io/docs/api",
                    true, AuthStyle.HEADER, "X-Finnhub-Token",
                    List.of("Create a free account (Get a Key…) and confirm your email.",
                            "Your API key is shown on the Finnhub dashboard. Copy it.",
                            "In MxAcctMGR ▸ Secure Accounts, put it in the account's Service key (New in MxAcctMGR… under API Key makes one); then Test Key here."),
                    "Free tier: 60 calls a minute; some endpoints need a paid plan.",
                    List.of("MxJobsMGR sends the key in the X-Finnhub-Token header."), null),
            new ProviderGuide("fmp", "Financial Modeling Prep", List.of("financialmodelingprep.com"),
                    "https://site.financialmodelingprep.com/developer/docs/dashboard",
                    "https://site.financialmodelingprep.com/developer/docs/stable",
                    true, AuthStyle.QUERY_PARAMETER, "apikey",
                    List.of("Sign in at Financial Modeling Prep; your API key is on the dashboard (Get a Key…). Copy it.",
                            "Set Base URL to https://financialmodelingprep.com/stable — only the address every call starts with. "
                                    + "Each API Data Read step adds its own path and parameters, e.g. /search-name with query=apple, "
                                    + "/historical-price-eod/full with symbol=AAPL and from=2026-01-01, or /quote with symbol=AAPL.",
                            "Put the key in the service key of its secure account in MxAcctMGR ▸ Secure Accounts, then Test Key here (it reads Apple's profile).",
                            "For the security master's price loads (SRM Daily Refresh ▸ End-of-day prices, Index levels, Profiles, "
                                    + "Corporate actions) the key must also be where srm.py reads it (SRM's own key store). Those steps call the srm-loader "
                                    + "service on this PC, which runs srm.py — they don't use this subscription's key."),
                    "Depends on your plan (calls a minute and which endpoints); endpoints outside it answer HTTP 402.",
                    List.of("MxJobsMGR sends the key as the apikey query parameter (masked in Test Call).",
                            "Use the stable API (/stable/…); the older /api/v3/… paths answer only for legacy plans."),
                    "https://financialmodelingprep.com/stable"),
            new ProviderGuide("edgar", "SEC EDGAR", List.of("sec.gov"), null,
                    "https://www.sec.gov/search-filings/edgar-application-programming-interfaces",
                    false, AuthStyle.NONE, "",
                    List.of("No key is needed.",
                            "Under Headers Sent With Every Call, set User-Agent to your name and email "
                                    + "(e.g. “Jane Doe jane@example.com”) — the SEC asks every client to identify itself."),
                    "At most 10 requests a second.", List.of(), null),
            new ProviderGuide("frankfurter", "Frankfurter (ECB rates)", List.of("frankfurter.dev", "frankfurter.app"), null,
                    "https://frankfurter.dev", false, AuthStyle.NONE, "", List.of("No key is needed."),
                    "Free and open; rates update once a working day.", List.of(), null),
            new ProviderGuide("local", "Local service on this PC", List.of("127.0.0.1", "localhost"), null, null,
                    false, AuthStyle.LOCAL_SERVICE, "",
                    List.of("No key is needed unless you start the service with a token (--token or MXJOBS_SERVICE_TOKEN).",
                            "If you do, make it the service key of a secure account in MxAcctMGR ▸ Secure Accounts and choose that account here; it is sent as “Authorization: Bearer …”."),
                    "Whatever the service allows.",
                    List.of("Services (MxAppsIOS/Services, start them with python Services/mxservices.py start): market-sample (8765), portfolio-java (8766), portfolio-cpp (8767), srm-loader (8770), analytics (8772), accounts (8774)."),
                    null));

    /** Steps for any other provider. */
    public static final ProviderGuide GENERIC = new ProviderGuide("generic", "Other providers", List.of(), null, null,
            true, AuthStyle.QUERY_PARAMETER, "",
            List.of("Sign up with the provider and find the API key in its dashboard or account settings.",
                    "Check its documentation for how the key is sent, and set Authentication to match: "
                            + "a query parameter (name it, e.g. apikey or token), a header (e.g. X-API-Key), or a Bearer token.",
                    "Put the key in its secure account's Service key in MxAcctMGR ▸ Secure Accounts, choose that account here, then Test Key — or Test Call on one of its API Data Read steps."),
            "See the provider's plan.", List.of(), null);

    /** Whether two base URLs are the same apart from a trailing slash and letter case. */
    public static boolean sameBase(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s.trim().toLowerCase().replaceAll("/+$", "");
    }

    public static String host(String url) {
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? null : h.toLowerCase();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The guide matching a subscription's base URL. */
    public static ProviderGuide matching(ApiSubscription sub) {
        String host = host(sub.baseURL);
        if (host == null) host = "";
        for (ProviderGuide g : ALL) {
            for (String h : g.hosts) if (host.equals(h) || host.endsWith("." + h)) return g;
        }
        return GENERIC;
    }
}
