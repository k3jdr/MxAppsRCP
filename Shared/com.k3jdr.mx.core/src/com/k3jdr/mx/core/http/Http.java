package com.k3jdr.mx.core.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.google.gson.JsonElement;
import com.k3jdr.mx.core.json.Json;

/** Small HTTP helpers for the Layer 3 services (127.0.0.1) and public APIs. Call them off the UI thread. */
public final class Http {
    private Http() {}

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** A response: status, body and how long it took. */
    public record Response(int status, String body, long millis, Map<String, java.util.List<String>> headers) {
        public boolean ok() { return status >= 200 && status < 300; }
        public JsonElement json() { return Json.parse(body); }
    }

    public static Response get(String url, Duration timeout, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        headers.forEach(b::header);
        return send(b.build());
    }

    public static Response get(String url) throws IOException, InterruptedException {
        return get(url, Duration.ofSeconds(30), Map.of());
    }

    public static Response send(String method, String url, String body, Duration timeout, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        boolean hasBody = body != null && !body.isEmpty();
        b.method(method, hasBody ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
        if (hasBody && headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("Content-Type")))
            b.header("Content-Type", "application/json");
        headers.forEach(b::header);
        return send(b.build());
    }

    public static Response postJson(String url, Object body) throws IOException, InterruptedException {
        String text = body instanceof String s ? s : Json.COMPACT.toJson(body);
        return send("POST", url, text, Duration.ofMinutes(10), Map.of());
    }

    private static Response send(HttpRequest request) throws IOException, InterruptedException {
        long start = System.nanoTime();
        HttpResponse<String> r = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body(), (System.nanoTime() - start) / 1_000_000, r.headers().map());
    }

    /** Whether something answers 200 on {@code baseUrl}/health within 1.5 s. */
    public static boolean isUp(String baseUrl) {
        try {
            return get(baseUrl + "/health", Duration.ofMillis(1500), Map.of()).status() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
