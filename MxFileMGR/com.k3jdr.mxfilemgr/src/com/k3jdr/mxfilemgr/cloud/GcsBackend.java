package com.k3jdr.mxfilemgr.cloud;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.Settings;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.ops.CLITool;
import com.k3jdr.mxfilemgr.ops.ProgressReporter;
import com.k3jdr.mxfilemgr.ops.TransferManager;

/**
 * Google Cloud Storage through the Google Cloud CLI ({@code gcloud storage}) after {@code gcloud auth login}
 * (GCSView.swift's GCloudCLI and GCSModel); the app never stores keys.
 */
public final class GcsBackend extends RemoteBackend {

    public static String cli() {
        String local = System.getenv().getOrDefault("LOCALAPPDATA", "");
        return CLITool.find("gcloud", local + "\\Google\\Cloud SDK\\google-cloud-sdk\\bin\\gcloud.cmd",
                "C:\\Program Files (x86)\\Google\\Cloud SDK\\google-cloud-sdk\\bin\\gcloud.cmd",
                "C:\\Program Files\\Google\\Cloud SDK\\google-cloud-sdk\\bin\\gcloud.cmd");
    }

    @Override public String title() { return "Google Cloud Storage"; }
    @Override public String shortName() { return "Cloud Storage"; }
    @Override public QuickHelpItem screenHelp() { return FileHelp.GCS_SCREEN; }
    @Override public String cliPath() { return cli(); }

    @Override
    public String installHint() {
        return "The Google Cloud CLI isn't installed. Install it with: winget install Google.CloudSDK\nThen sign in here with Sign In (gcloud auth login).";
    }

    @Override public List<String> pickerTitles() { return List.of("Project", "Bucket"); }

    @Override
    public Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        env.put("CLOUDSDK_CORE_DISABLE_COLOR", "1");
        env.put("CLOUDSDK_CORE_DISABLE_PROMPTS", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        return env;
    }

    @Override
    public Map<String, String> signInEnvironment() {
        Map<String, String> env = environment();
        env.remove("CLOUDSDK_CORE_DISABLE_PROMPTS");
        return env;
    }

    private String run(List<String> args) throws IOException {
        String cli = cli();
        if (cli == null) throw new IOException("The Google Cloud CLI isn't installed.");
        return new String(CLITool.run(cli, args, environment()), StandardCharsets.UTF_8);
    }

    private boolean runJob(List<String> args, ProgressReporter reporter) {
        String cli = cli();
        if (cli == null) {
            reporter.error("The Google Cloud CLI isn't installed.");
            return false;
        }
        return CLITool.runJob(cli, args, environment(), reporter, null);
    }

    @Override
    public List<Choice> choices(int level, List<String> chosen) throws IOException {
        List<Choice> out = new ArrayList<>();
        if (level == 0) {
            String text = run(List.of("projects", "list", "--format=json(projectId,name)")).trim();
            String configured = "";
            try {
                configured = run(List.of("config", "get-value", "project")).trim();
            } catch (IOException ignored) {
                // no default project
            }
            if (!text.isEmpty()) {
                for (JsonElement e : JsonParser.parseString(text).getAsJsonArray()) {
                    JsonObject p = e.getAsJsonObject();
                    if (!p.has("projectId")) continue;
                    String id = p.get("projectId").getAsString();
                    out.add(new Choice(id, p.has("name") ? p.get("name").getAsString() : id, id.equals(configured)));
                }
            }
            out.sort((a, b) -> com.k3jdr.mxfilemgr.model.SortOrder.NATURAL.compare(a.label(), b.label()));
            if (out.isEmpty()) throw new IOException("No projects are visible to the signed-in account.");
            return out;
        }
        List<String> names = new ArrayList<>();
        for (String line : run(List.of("storage", "buckets", "list", "--project", chosen.get(0), "--format=value(name)")).split("\\R")) {
            if (!line.isBlank()) names.add(line.trim());
        }
        names.sort(com.k3jdr.mxfilemgr.model.SortOrder.NATURAL);
        for (String n : names) out.add(new Choice(n));
        return out;
    }

    @Override public String savedChoice(int level) { return Settings.PREFS.get(level == 0 ? "gcsProject" : "gcsBucket", null); }
    @Override public void saveChoice(int level, String id) { Settings.PREFS.put(level == 0 ? "gcsProject" : "gcsBucket", id); }

    @Override
    public Listing list(List<String> chosen, String prefix) throws IOException {
        String text;
        try {
            text = run(List.of("storage", "ls", "--long", uri(chosen, prefix)));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("One or more URLs matched no objects")) {
                return new Listing(List.of(), prefix, false);       // an empty (or new, virtual) folder
            }
            throw e;
        }
        return new Listing(sorted(parseListing(text, chosen.get(1), prefix)), prefix, false);
    }

    /**
     * Parses {@code gcloud storage ls --long gs://bucket/prefix/}: "      1234  2024-05-01T12:34:56Z  gs://bucket/prefix/file"
     * for objects, "                                 gs://bucket/prefix/sub/" for folders, and a closing "TOTAL:" line.
     */
    static List<RemoteEntry> parseListing(String text, String bucket, String prefix) {
        String base = "gs://" + bucket + "/";
        List<RemoteEntry> result = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            int start = line.indexOf(base);
            if (start < 0) continue;
            String key = line.substring(start + base.length());
            if (!key.startsWith(prefix) || key.equals(prefix)) continue;
            String name = key.substring(prefix.length());
            if (key.endsWith("/")) {
                result.add(new RemoteEntry(key, name.substring(0, name.length() - 1), true, 0, 0, ""));
            } else {
                String[] fields = line.substring(0, start).trim().split("\\s+");
                long size = 0;
                try {
                    size = fields.length > 0 && !fields[0].isEmpty() ? Long.parseLong(fields[0]) : 0;
                } catch (NumberFormatException ignored) {
                    // no size
                }
                result.add(new RemoteEntry(key, name, false, size, CLITool.parseISODate(fields.length > 1 ? fields[1] : null), ""));
            }
        }
        return result;
    }

    @Override
    public boolean needsLogin(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("gcloud auth login") || m.contains("no active account") || m.contains("do not currently have an active account")
                || m.contains("reauthentication") || m.contains("invalid_grant");
    }

    @Override public List<String> signInArgs(List<String> chosen) { return List.of("auth", "login", "--brief"); }

    @Override
    public String signInText(List<String> chosen) {
        return "Sign in to Google Cloud to browse your buckets. Signing in (gcloud auth login) opens your browser.";
    }

    @Override public String uri(List<String> chosen, String key) { return "gs://" + (chosen.size() > 1 && chosen.get(1) != null ? chosen.get(1) : "") + "/" + key; }
    @Override public String copyUriTitle() { return "Copy gs:// URI"; }
    @Override public String copyLinkTitle() { return "Copy Browser Link"; }

    /** Links that open the object in a browser for anyone signed in with access (storage.cloud.google.com). */
    @Override
    public List<String> links(List<String> chosen, List<RemoteEntry> items) {
        List<String> out = new ArrayList<>();
        for (RemoteEntry e : items) {
            if (e.isFolder()) continue;
            StringBuilder path = new StringBuilder();
            for (String part : e.key().split("/", -1)) {
                if (path.length() > 0) path.append('/');
                path.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
            }
            out.add("https://storage.cloud.google.com/" + chosen.get(1) + "/" + path);
        }
        return out;
    }

    /** gcloud's Python reads a trailing backslash before a quote as an escaped quote: give it forward slashes. */
    private static String local(Path p) {
        return p.toString().replace('\\', '/');
    }

    @Override
    public void download(List<String> chosen, List<RemoteEntry> items, Path folder) {
        List<Object[]> jobs = new ArrayList<>();
        for (RemoteEntry i : items) jobs.add(new Object[] { uri(chosen, i.key()), localTarget(folder, i), i.isFolder() });
        TransferManager.get().start("Downloading " + Fmt.count(items.size(), "item") + " from Google Cloud", reporter -> {
            for (Object[] j : jobs) {
                if (reporter.isCancelled()) break;
                Path dest = (Path) j[1];
                reporter.begin(FileOps.name(dest));
                if ((Boolean) j[2]) {
                    try {
                        Files.createDirectories(dest);
                    } catch (IOException e) {
                        reporter.error(FileOps.name(dest) + ": " + TransferManager.message(e));
                        continue;
                    }
                    runJob(List.of("storage", "cp", "-r", j[0] + "*", local(dest) + "/"), reporter);
                } else {
                    runJob(List.of("storage", "cp", (String) j[0], local(dest)), reporter);
                }
            }
        });
    }

    @Override
    public void upload(List<String> chosen, String prefix, List<Path> paths, Runnable after) {
        String target = uri(chosen, prefix);
        TransferManager.get().start("Uploading " + Fmt.count(paths.size(), "item") + " to Google Cloud", reporter -> {
            for (Path p : paths) {
                if (reporter.isCancelled()) break;
                reporter.begin(FileOps.name(p));
                if (FileOps.isRealDirectory(p)) runJob(List.of("storage", "cp", "-r", local(p), target), reporter);
                else runJob(List.of("storage", "cp", local(p), target + FileOps.name(p)), reporter);
            }
            after.run();
        });
    }

    @Override public boolean virtualFolders() { return true; }
    @Override public String virtualFolderNote() { return "Cloud Storage keeps a folder once something is in it. The folder opens now; upload into it to keep it."; }

    @Override
    public String rename(List<String> chosen, String prefix, RemoteEntry entry, String newName) throws IOException {
        String source = entry.isFolder() ? uri(chosen, entry.path()) : uri(chosen, entry.key());
        run(List.of("storage", "mv", source, uri(chosen, prefix + newName)));
        return prefix + newName + (entry.isFolder() ? "/" : "");
    }

    @Override
    public String deleteInfo(boolean folders) {
        return (folders ? "Folders are deleted with everything in them. " : "") + "This can't be undone unless the bucket has soft delete or versioning turned on.";
    }

    @Override
    public void delete(List<String> chosen, List<RemoteEntry> items, Runnable after) {
        TransferManager.get().start("Deleting " + what(items) + " from Google Cloud", reporter -> {
            for (int i = 0; i < items.size() && !reporter.isCancelled(); i++) {
                RemoteEntry e = items.get(i);
                String target = uri(chosen, e.key());
                reporter.step(i, items.size(), target);
                List<String> args = new ArrayList<>(List.of("storage", "rm"));
                if (e.isFolder()) args.add("-r");
                args.add(target);
                runJob(args, reporter);
            }
            after.run();
        });
    }
}
