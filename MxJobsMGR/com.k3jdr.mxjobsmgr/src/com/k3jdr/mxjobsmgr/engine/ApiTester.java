package com.k3jdr.mxjobsmgr.engine;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.http.Http;
import com.k3jdr.mxjobsmgr.model.AppleJson;
import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Specs.ApiCallSpec;
import com.k3jdr.mxjobsmgr.model.Specs.Argument;
import com.k3jdr.mxjobsmgr.model.Specs.ArgumentType;
import com.k3jdr.mxjobsmgr.model.Specs.AssertionOp;
import com.k3jdr.mxjobsmgr.model.Specs.AuthStyle;
import com.k3jdr.mxjobsmgr.model.Specs.HttpMethod;
import com.k3jdr.mxjobsmgr.model.Specs.KeyValue;
import com.k3jdr.mxjobsmgr.model.Specs.ValueAssertion;

/**
 * Test Call for a Layer 3 API read, and the live calls of a run (ApiTester.swift): builds the request from the
 * subscription and the step ({{argument}}, {{today}}, {{in:…}} and {{json:…}} placeholders and the secure account's
 * key), sends it once, and checks the expected outcome (status, required fields, value checks). Call off the UI thread.
 */
public final class ApiTester {
    private ApiTester() {}

    public record Check(String label, boolean passed) {}

    /** What a call or a script run gave. */
    public record Result(String url, Integer status, double elapsed, String body, List<Check> checks, String error, JsonElement json) {
        public boolean passed() {
            return error == null && checks.stream().allMatch(Check::passed);
        }

        static Result failure(String url, String error) {
            return new Result(url, null, 0, "", List.of(), error, null);
        }
    }

    /** A request ready to send. */
    public record Request(String method, URI uri, Map<String, String> headers, String body, Duration timeout, String shownUrl) {}

    // MARK: Placeholders

    /**
     * Replaces {{name}} with argument values and the built-ins {{today}} / {{yesterday}} (yyyy-MM-dd), and the
     * placeholders that read the outputs of the steps linked into this one: {{in:path}} a value (text, number, or
     * compact JSON), {{json:path}} the value as JSON.
     */
    public static String substitute(String text, List<Argument> arguments, JsonObject inputs) {
        if (text == null) return "";
        String out = text;
        Map<String, String> values = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        values.put("today", today.toString());
        values.put("yesterday", today.minusDays(1).toString());
        for (Argument arg : arguments) {
            if (arg.name.isEmpty()) continue;
            // A symbol list tests with its first symbol.
            values.put(arg.name, arg.type == ArgumentType.SYMBOLS ? arg.value.split(",", -1)[0].trim() : arg.value);
        }
        for (var e : values.entrySet()) out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        return fillInputs(out, inputs == null ? new JsonObject() : inputs);
    }

    public static String substitute(String text, List<Argument> arguments) {
        return substitute(text, arguments, null);
    }

    /** {{in:path}} / {{json:path}} from the inputs (dotted paths; keys may contain dots). */
    public static String fillInputs(String text, JsonObject inputs) {
        if (!text.contains("{{in:") && !text.contains("{{json:")) return text;
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (true) {
            int open = text.indexOf("{{", pos);
            if (open < 0) break;
            int close = text.indexOf("}}", open + 2);
            if (close < 0) break;
            out.append(text, pos, open);
            String token = text.substring(open + 2, close);
            if (token.startsWith("in:") || token.startsWith("json:")) {
                boolean asJson = token.startsWith("json:");
                String path = token.substring(asJson ? 5 : 3).trim();
                JsonElement value = Values.lookup(path, inputs);
                if (asJson) out.append(value == null ? "null" : Values.jsonText(value));
                else if (Values.isString(value)) out.append(value.getAsString());
                else if (Values.isNumber(value)) out.append(AppleJson.number(value.getAsNumber()));
                else if (value != null) out.append(Values.jsonText(value));
            } else {
                out.append(text, open, close + 2);
            }
            pos = close + 2;
        }
        out.append(text.substring(pos));
        return out.toString();
    }

    // MARK: Requests

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    public static Request buildRequest(JobNode node, ApiSubscription sub, String key, JsonObject inputs) {
        ApiCallSpec api = node.api;
        if (api == null) throw new IllegalArgumentException("Not an API node.");
        String base = sub.baseURL.endsWith("/") ? sub.baseURL.substring(0, sub.baseURL.length() - 1) : sub.baseURL;
        String path = substitute(api.path, node.arguments, inputs);
        if (!path.isEmpty() && !path.startsWith("/")) path = "/" + path;
        String url = base + path;
        if (ProviderGuide.host(url) == null) throw new IllegalArgumentException("Invalid URL: " + url);
        List<String[]> items = new ArrayList<>();
        for (KeyValue q : api.query) {
            if (q.key.isEmpty()) continue;
            String value = substitute(q.value, node.arguments, inputs);
            // An optional argument left empty leaves its parameter out ("max={{maxCalls}}" with no maxCalls).
            if (value.isEmpty() && q.value.contains("{{")) continue;
            items.add(new String[] { q.key, value });
        }
        String keyParam = sub.keyName.isEmpty() ? "apikey" : sub.keyName;
        if (sub.auth == AuthStyle.QUERY_PARAMETER && key != null) items.add(new String[] { keyParam, key });
        StringBuilder full = new StringBuilder(url), shown = new StringBuilder(url);
        boolean hasQuery = url.contains("?");
        for (String[] item : items) {
            String sep = hasQuery ? "&" : "?";
            hasQuery = true;
            full.append(sep).append(enc(item[0])).append('=').append(enc(item[1]));
            boolean secret = sub.auth == AuthStyle.QUERY_PARAMETER && item[0].equals(keyParam) && key != null && item[1].equals(key);
            shown.append(sep).append(enc(item[0])).append('=').append(secret ? "•••" : enc(item[1]));
        }
        URI uri;
        try {
            uri = URI.create(full.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Could not build the URL: " + full);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        List<KeyValue> all = new ArrayList<>(sub.defaultHeaders);
        all.addAll(api.headers);
        for (KeyValue h : all) {
            if (h.key.isEmpty() || RESTRICTED.contains(h.key.toLowerCase(Locale.ROOT))) continue;
            headers.put(h.key, substitute(h.value, node.arguments, inputs));
        }
        switch (sub.auth) {
            case HEADER -> { if (key != null) headers.put(sub.keyName.isEmpty() ? "X-API-Key" : sub.keyName, key); }
            case BEARER, LOCAL_SERVICE -> { if (key != null) headers.put("Authorization", "Bearer " + key); }
            default -> { }
        }
        String body = null;
        if (api.method == HttpMethod.POST && !api.body.isEmpty()) {
            body = substitute(api.body, node.arguments, inputs);
            headers.put("Content-Type", "application/json");
        }
        return new Request(api.method.raw(), uri, headers, body, Duration.ofSeconds(Math.max(1, node.exceptions.timeoutSeconds)), shown.toString());
    }

    /** Sends a request; the response's status, body and time. */
    public static HttpResponse<String> send(Request r) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(r.uri()).timeout(r.timeout());
        b.method(r.method(), r.body() == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(r.body()));
        r.headers().forEach(b::header);
        return Http.CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends the step's request once; {@code inputs} are the outputs of the steps linked into it ({{in:…}}). */
    public static Result run(JobNode node, ApiSubscription sub, JsonObject inputs) throws InterruptedException {
        String key = sub.auth == AuthStyle.NONE ? null : SecureKeys.key(sub);
        Request request;
        try {
            if (sub.auth.requiresKey() && key == null) throw new IllegalArgumentException(Validator.missingKey(sub));
            request = buildRequest(node, sub, key, inputs);
        } catch (IllegalArgumentException e) {
            return Result.failure("", e.getMessage());
        }
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = send(request);
            double elapsed = (System.nanoTime() - started) / 1e9;
            int status = response.statusCode();
            JsonElement json = Values.parse(response.body());
            List<Check> checks = new ArrayList<>();
            checks.add(new Check("HTTP " + status + " in " + node.outcome.successStatus, statusMatches(status, node.outcome.successStatus)));
            checks.addAll(outcomeChecks(node, json));
            // Providers like Alpha Vantage report a bad key or a reached limit in a 200 response.
            String notice = null;
            if (!checks.stream().allMatch(Check::passed)) {
                String m = KeyTester.providerMessage(json != null && json.isJsonObject() ? json.getAsJsonObject() : null);
                if (m != null) notice = sub.name + ": " + m;
            }
            return new Result(request.shownUrl(), status, elapsed, pretty(response.body(), json), checks, notice, json);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (e instanceof java.net.ConnectException || e instanceof java.net.http.HttpConnectTimeoutException)
                msg = "Could not connect to " + request.uri().getHost() + (request.uri().getPort() > 0 ? ":" + request.uri().getPort() : "") + " (" + msg + ").";
            else if (e instanceof java.net.http.HttpTimeoutException) msg = "The request timed out after " + request.timeout().toSeconds() + " s.";
            return new Result(request.shownUrl(), null, (System.nanoTime() - started) / 1e9, "", List.of(), msg, null);
        }
    }

    // MARK: Checks

    /** Required fields and value checks of a step's expected outcome, against its JSON result. */
    public static List<Check> outcomeChecks(JobNode node, JsonElement json) {
        List<Check> checks = new ArrayList<>();
        for (String field : node.outcome.requiredFields) {
            if (field.isEmpty()) continue;
            checks.add(new Check("Field “" + field + "” present", json != null && Values.lookup(field, json) != null));
        }
        for (ValueAssertion a : node.outcome.assertions) {
            if (a.path.isEmpty()) continue;
            String expected = substitute(a.value, node.arguments);
            JsonElement actual = json == null ? null : Values.lookup(a.path, json);
            boolean passed = evaluate(actual, a.op, expected);
            String shown = a.op == AssertionOp.EXISTS ? a.path + " exists" : a.path + " " + a.op.raw() + " " + expected;
            checks.add(new Check(passed ? shown : shown + " (got " + Values.describe(actual) + ")", passed));
        }
        return checks;
    }

    private static Double number(String s) {
        try {
            String t = s.trim();
            if (t.isEmpty()) return null;
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Compares numerically when both sides are numbers, else as text. */
    public static boolean evaluate(JsonElement actual, AssertionOp op, String expected) {
        boolean present = Values.present(actual);
        if (op == AssertionOp.EXISTS) return present;
        if (!present) return false;
        Double a = Values.isNumber(actual) ? actual.getAsDouble() : null;
        Double e = number(expected);
        if (a != null && e != null) {
            double tol = 1e-9 * Math.max(1, Math.abs(e));
            switch (op) {
                case EQUALS: return Math.abs(a - e) <= tol;
                case NOT_EQUALS: return Math.abs(a - e) > tol;
                case LESS: return a < e;
                case LESS_OR_EQUAL: return a <= e;
                case GREATER: return a > e;
                case GREATER_OR_EQUAL: return a >= e;
                default: break;
            }
        }
        String text = Values.describe(actual);
        return switch (op) {
            case EQUALS -> text.equals(expected);
            case NOT_EQUALS -> !text.equals(expected);
            case CONTAINS -> {
                if (actual.isJsonArray()) {
                    for (JsonElement x : actual.getAsJsonArray()) if (Values.describe(x).equals(expected)) yield true;
                    yield false;
                }
                if (actual.isJsonObject()) yield actual.getAsJsonObject().has(expected);
                yield text.contains(expected);
            }
            default -> false; // ordering needs numbers
        };
    }

    /** "200-299", "200,304", "2xx". */
    public static boolean statusMatches(int status, String spec) {
        List<String> parts = new ArrayList<>();
        for (String p : spec.split(",")) if (!p.isBlank()) parts.add(p.trim().toLowerCase(Locale.ROOT));
        if (parts.isEmpty()) return status >= 200 && status < 300;
        for (String part : parts) {
            if (part.endsWith("xx") && part.length() >= 3 && Character.isDigit(part.charAt(0))) {
                if (status / 100 == part.charAt(0) - '0') return true;
                continue;
            }
            String[] bounds = part.split("-");
            try {
                if (bounds.length == 2) {
                    if (status >= Integer.parseInt(bounds[0].trim()) && status <= Integer.parseInt(bounds[1].trim())) return true;
                } else if (Integer.parseInt(part) == status) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // not a status
            }
        }
        return false;
    }

    static String pretty(String body, JsonElement json) {
        String text = json != null ? Values.pretty(json) : body;
        if (json != null && Values.isString(json)) text = AppleJson.quote(json.getAsString());
        if (text.length() > 20_000) text = text.substring(0, 20_000) + "\n… (truncated)";
        return text;
    }
}
