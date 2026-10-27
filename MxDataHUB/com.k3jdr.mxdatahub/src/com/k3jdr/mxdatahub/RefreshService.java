package com.k3jdr.mxdatahub;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.http.Http;
import com.k3jdr.mx.core.json.Json;
import com.k3jdr.mx.core.services.ServiceCatalog;

/**
 * The SRM loader service (Services/srm-loader, 127.0.0.1:8770) as Data Refresh uses it (DataRefreshView.swift's
 * RefreshStore): the categories with their freshness, refreshing a category, and the activity log. Call it off the
 * UI thread.
 */
final class RefreshService {
    record Loader(String loader, String status, String lastRun, String message) {}

    record Category(String id, String name, String description, String cadence, boolean optional, List<Loader> loaders,
                    String lastRun, String dataAsOf, Integer ageDays, String state) {}

    record ResultLoader(String loader, String status, Double seconds, String command) {}

    record Result(String category, String name, String status, Double seconds, boolean dryRun, Integer run, List<ResultLoader> loaders) {}

    /** One line of the activity log: a line a loader printed, or a refresh / loader starting or ending. */
    record Entry(int seq, String time, String level, String loader, Integer run, String event, String text, String name,
                 String status, Double seconds) {
        /** HH:mm:ss of the ISO time. */
        String clock() { return time.length() >= 19 ? time.substring(11, 19) : time; }
    }

    /** The refresh or load the service is running now. */
    record Running(int run, String category, String name, List<String> loaders, String loader, int index, boolean dryRun, double started) {}

    record Page(List<Entry> entries, int last, Running running) {}

    /** The service answered, but not with what was asked for: its body (an {"error": …} usually). */
    static final class ServiceError extends Exception {
        ServiceError(String body) { super(body); }
    }

    final String base;

    /** {@code port} null or 0: the catalogue's port for srm-loader (8770). */
    RefreshService(Integer port) {
        int p = port != null && port > 0 ? port : ServiceCatalog.service("srm-loader").map(ServiceCatalog.Service::port).orElse(8770);
        base = "http://127.0.0.1:" + p;
    }

    /** GET /categories. IOException: the service isn't running; ServiceError: it answered with an error. */
    List<Category> categories() throws IOException, InterruptedException, ServiceError {
        Http.Response r = Http.get(base + "/categories", Duration.ofSeconds(10), Map.of());
        if (r.status() != 200) throw new ServiceError(r.body());
        List<Category> out = new ArrayList<>();
        for (JsonElement e : arr(Json.parse(r.body()).getAsJsonObject(), "categories")) {
            JsonObject o = e.getAsJsonObject();
            List<Loader> loaders = new ArrayList<>();
            for (JsonElement l : arr(o, "loaders")) {
                JsonObject lo = l.getAsJsonObject();
                loaders.add(new Loader(str(lo, "loader"), str(lo, "status"), str(lo, "lastRun"), str(lo, "message")));
            }
            Double age = num(o, "ageDays");
            out.add(new Category(str(o, "id"), str(o, "name"), nz(str(o, "description")), nz(str(o, "cadence")),
                    Boolean.TRUE.equals(bool(o, "optional")), loaders, str(o, "lastRun"), str(o, "dataAsOf"),
                    age == null ? null : age.intValue(), nz(str(o, "state"))));
        }
        return out;
    }

    /** POST /refresh/{category}: runs the category's loaders in order; with {@code dryRun} only shows the commands. */
    Result refresh(String id, int verbosity, boolean dryRun) throws IOException, InterruptedException, ServiceError {
        Map<String, String> headers = new LinkedHashMap<>();
        if (dryRun) headers.put("X-SRM-Dry-Run", "1");
        String url = base + "/refresh/" + id + (verbosity > 0 ? "?verbosity=" + verbosity : "");
        // The long loads take tens of minutes.
        Http.Response r = Http.send("POST", url, null, Duration.ofHours(3), headers);
        if (r.status() != 200) throw new ServiceError(r.body());
        JsonObject o = Json.parse(r.body()).getAsJsonObject();
        List<ResultLoader> loaders = new ArrayList<>();
        for (JsonElement l : arr(o, "loaders")) {
            JsonObject lo = l.getAsJsonObject();
            loaders.add(new ResultLoader(str(lo, "loader"), str(lo, "status"), num(lo, "seconds"), str(lo, "command")));
        }
        Double run = num(o, "run");
        return new Result(str(o, "category"), str(o, "name"), str(o, "status"), num(o, "seconds"),
                Boolean.TRUE.equals(bool(o, "dryRun")), run == null ? null : run.intValue(), loaders);
    }

    /** GET /log?after=: what the service logged since {@code after}, and what it runs now. */
    Page log(int after) throws IOException, InterruptedException, ServiceError {
        Http.Response r = Http.get(base + "/log?after=" + after, Duration.ofSeconds(5), Map.of());
        if (r.status() != 200) throw new ServiceError(r.body());
        JsonObject o = Json.parse(r.body()).getAsJsonObject();
        List<Entry> entries = new ArrayList<>();
        for (JsonElement e : arr(o, "entries")) {
            JsonObject x = e.getAsJsonObject();
            Double seq = num(x, "seq"), run = num(x, "run");
            entries.add(new Entry(seq == null ? 0 : seq.intValue(), nz(str(x, "time")), nz(str(x, "level")), str(x, "loader"),
                    run == null ? null : run.intValue(), str(x, "event"), nz(str(x, "text")), str(x, "name"), str(x, "status"),
                    num(x, "seconds")));
        }
        Running running = null;
        if (o.has("running") && o.get("running").isJsonObject()) {
            JsonObject x = o.getAsJsonObject("running");
            List<String> loaders = new ArrayList<>();
            for (JsonElement l : arr(x, "loaders")) loaders.add(l.getAsString());
            Double run = num(x, "run"), index = num(x, "index"), started = num(x, "started");
            running = new Running(run == null ? 0 : run.intValue(), str(x, "category"), nz(str(x, "name")), loaders,
                    str(x, "loader"), index == null ? 0 : index.intValue(), Boolean.TRUE.equals(bool(x, "dryRun")),
                    started == null ? System.currentTimeMillis() / 1000.0 : started);
        }
        Double last = num(o, "last");
        return new Page(entries, last == null ? 0 : last.intValue(), running);
    }

    // MARK: JSON

    private static String nz(String s) { return s == null ? "" : s; }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    static Double num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        try {
            return e.getAsDouble();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsBoolean();
    }

    static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
