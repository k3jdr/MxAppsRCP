package com.k3jdr.mxjobsmgr.engine;

import java.net.http.HttpResponse;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.NodeKind;
import com.k3jdr.mxjobsmgr.model.Specs.KeyValue;

/** Test Key: one cheap call to the provider with the stored key, and what its answer says about the key. */
public final class KeyTester {
    private KeyTester() {}

    public enum Verdict { VALID, INVALID, LIMITED, UNREACHABLE, UNKNOWN }

    public record Outcome(Verdict verdict, String message) {}

    /** Calls the provider once with the stored key and says whether it was accepted (off the UI thread). */
    public static Outcome test(ApiSubscription original) throws InterruptedException {
        ApiSubscription sub = ApiSubscription.read(original.toJson());
        String key = SecureKeys.key(sub);
        if (sub.auth.requiresKey() && key == null) return new Outcome(Verdict.INVALID, Validator.missingKey(sub));
        JobNode node = new JobNode(NodeKind.API_READ, null, 0, 0);
        ProviderGuide guide = ProviderGuide.matching(sub);
        switch (guide.id()) {
            case "alphavantage" -> {
                node.api.path = "/query";
                // Not IBM: that is Alpha Vantage's open documentation example.
                node.api.query = new java.util.ArrayList<>(List.of(new KeyValue("function", "GLOBAL_QUOTE"), new KeyValue("symbol", "MSFT")));
            }
            case "finnhub" -> {
                node.api.path = "/quote";
                node.api.query = new java.util.ArrayList<>(List.of(new KeyValue("symbol", "AAPL")));
            }
            case "fmp" -> {
                if (guide.baseURL() != null) sub.baseURL = guide.baseURL();   // test the key, not a mistyped base URL
                node.api.path = "/profile";
                node.api.query = new java.util.ArrayList<>(List.of(new KeyValue("symbol", "AAPL")));
            }
            case "local" -> node.api.path = "/health";
            default -> node.api.path = "";
        }
        node.exceptions.timeoutSeconds = 15;
        ApiTester.Request request;
        try {
            request = ApiTester.buildRequest(node, sub, key, new JsonObject());
        } catch (IllegalArgumentException e) {
            return new Outcome(Verdict.UNKNOWN, e.getMessage());
        }
        try {
            HttpResponse<String> response = ApiTester.send(request);
            JsonElement json = Values.parse(response.body());
            Outcome result = interpret(response.statusCode(), json != null && json.isJsonObject() ? json.getAsJsonObject() : null);
            if (result.verdict() == Verdict.VALID && guide.id().equals("alphavantage")) {
                result = new Outcome(Verdict.VALID, "Alpha Vantage returned a quote, so calls with this key work. Alpha Vantage does not "
                        + "reject unknown keys, so this cannot prove the key is yours; your free key allows 25 requests a day.");
            }
            return result;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new Outcome(Verdict.UNREACHABLE, "Could not reach " + sub.baseURL + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    public static Outcome interpret(int status, JsonObject json) {
        if (status == 401 || status == 403)
            return new Outcome(Verdict.INVALID, "Rejected (HTTP " + status + ") — check the key and how it is sent.");
        if (status == 429)
            return new Outcome(Verdict.LIMITED, "Accepted, but the rate limit is reached (HTTP 429). Try later.");
        // Alpha Vantage style: HTTP 200 with the problem in the body.
        String message = providerMessage(json);
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("apikey") && (lower.contains("invalid") || lower.contains("missing"))) return new Outcome(Verdict.INVALID, message);
            if (lower.contains("rate limit") || lower.contains("requests per day") || lower.contains("call frequency") || lower.contains("premium"))
                return new Outcome(Verdict.LIMITED, message);
            return new Outcome(Verdict.UNKNOWN, message);
        }
        if (status >= 200 && status < 300) return new Outcome(Verdict.VALID, "Key accepted (HTTP " + status + ").");
        return new Outcome(Verdict.UNKNOWN, "HTTP " + status + " — the key may be fine; try Test Call on a step.");
    }

    /** The error or notice a provider put in a 200 response ("Error Message", "Information", "Note", "error"). */
    public static String providerMessage(JsonObject json) {
        if (json == null) return null;
        for (String key : new String[] { "Error Message", "Information", "Note", "error" }) {
            JsonElement e = json.get(key);
            if (Values.isString(e) && !e.getAsString().isEmpty()) {
                String s = e.getAsString();
                return s.length() > 300 ? s.substring(0, 300) + "…" : s;
            }
        }
        return null;
    }
}
