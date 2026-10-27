package com.k3jdr.mxfilemgr.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.Settings;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.model.Places;
import com.k3jdr.mxfilemgr.ops.CLITool;
import com.k3jdr.mxfilemgr.ops.ProgressReporter;
import com.k3jdr.mxfilemgr.ops.TransferManager;

/**
 * Amazon S3 through the AWS CLI (S3View.swift's AWSCLI and S3Model) with the profiles in .aws\config and
 * .aws\credentials, IAM Identity Center (SSO) included; the app never reads or stores access keys.
 */
public final class S3Backend extends RemoteBackend {

    /** The AWS CLI: on the PATH, or where its installer puts it. */
    public static String cli() {
        return CLITool.find("aws", "C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe", "C:\\Program Files (x86)\\Amazon\\AWSCLIV2\\aws.exe");
    }

    /** Profile names from .aws\config and .aws\credentials (section headers only). */
    public static List<String> profiles() {
        List<String> names = new ArrayList<>();
        Path aws = Places.home().resolve(".aws");
        List<Path> files = new ArrayList<>();
        String config = System.getenv("AWS_CONFIG_FILE"), creds = System.getenv("AWS_SHARED_CREDENTIALS_FILE");
        files.add(config != null && !config.isBlank() ? Path.of(config) : aws.resolve("config"));
        files.add(creds != null && !creds.isBlank() ? Path.of(creds) : aws.resolve("credentials"));
        for (Path f : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            for (String line : lines) {
                String t = line.trim();
                if (!t.startsWith("[") || !t.endsWith("]")) continue;
                String name = t.substring(1, t.length() - 1).trim();
                if (name.startsWith("sso-session") || name.startsWith("services")) continue;
                if (name.startsWith("profile ")) name = name.substring("profile ".length()).trim();
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        }
        return names;
    }

    @Override public String title() { return "Amazon S3"; }
    @Override public String shortName() { return "S3"; }
    @Override public QuickHelpItem screenHelp() { return FileHelp.S3_SCREEN; }
    @Override public String cliPath() { return cli(); }

    @Override
    public String installHint() {
        return "The AWS CLI isn't installed. Install it with: winget install Amazon.AWSCLI\n"
                + "Then set up a profile with aws configure or aws configure sso.";
    }

    @Override public List<String> pickerTitles() { return List.of("Profile", "Bucket"); }
    @Override public String classColumn() { return "Storage"; }

    @Override
    public Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        env.put("AWS_PAGER", "");
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        return env;
    }

    private byte[] run(String profile, List<String> args) throws IOException {
        String cli = cli();
        if (cli == null) throw new IOException("The AWS CLI isn't installed.");
        List<String> all = new ArrayList<>(args);
        all.addAll(List.of("--profile", profile, "--no-cli-pager"));
        return CLITool.run(cli, all, environment());
    }

    @Override
    public List<Choice> choices(int level, List<String> chosen) throws IOException {
        if (level == 0) {
            List<String> names = profiles();
            if (names.isEmpty()) throw new IOException("No AWS profile is set up. Run aws configure or aws configure sso, then select Refresh.");
            List<Choice> out = new ArrayList<>();
            for (String n : names) out.add(new Choice(n, n, n.equals("default")));
            return out;
        }
        JsonObject json = JsonParser.parseString(new String(run(chosen.get(0), List.of("s3api", "list-buckets", "--output", "json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> names = new ArrayList<>();
        if (json.has("Buckets")) for (JsonElement b : json.getAsJsonArray("Buckets")) names.add(b.getAsJsonObject().get("Name").getAsString());
        names.sort(com.k3jdr.mxfilemgr.model.SortOrder.NATURAL);
        List<Choice> out = new ArrayList<>();
        for (String n : names) out.add(new Choice(n));
        return out;
    }

    @Override public String savedChoice(int level) { return Settings.PREFS.get(level == 0 ? "s3Profile" : "s3Bucket", null); }
    @Override public void saveChoice(int level, String id) { Settings.PREFS.put(level == 0 ? "s3Profile" : "s3Bucket", id); }

    @Override
    public Listing list(List<String> chosen, String prefix) throws IOException {
        List<String> args = new ArrayList<>(List.of("s3api", "list-objects-v2", "--bucket", chosen.get(1), "--delimiter", "/",
                "--max-items", String.valueOf(RemoteBrowser.MAX_ITEMS), "--output", "json"));
        if (!prefix.isEmpty()) args.addAll(List.of("--prefix", prefix));
        String text = new String(run(chosen.get(0), args), StandardCharsets.UTF_8).trim();
        List<RemoteEntry> result = new ArrayList<>();
        boolean truncated = false;
        if (!text.isEmpty()) {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            if (json.has("CommonPrefixes")) {
                for (JsonElement e : json.getAsJsonArray("CommonPrefixes")) {
                    String key = e.getAsJsonObject().get("Prefix").getAsString();
                    String name = key.substring(prefix.length(), key.length() - 1);
                    result.add(new RemoteEntry(key, name, true, 0, 0, ""));
                }
            }
            if (json.has("Contents")) {
                for (JsonElement e : json.getAsJsonArray("Contents")) {
                    JsonObject o = e.getAsJsonObject();
                    String key = o.get("Key").getAsString();
                    if (key.equals(prefix)) continue;      // the folder marker itself
                    result.add(new RemoteEntry(key, key.substring(prefix.length()), false,
                            o.has("Size") ? o.get("Size").getAsLong() : 0,
                            CLITool.parseISODate(o.has("LastModified") ? o.get("LastModified").getAsString() : null),
                            o.has("StorageClass") ? o.get("StorageClass").getAsString() : ""));
                }
            }
            truncated = json.has("NextToken");
        }
        return new Listing(sorted(result), prefix, truncated);
    }

    @Override
    public boolean needsLogin(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return (m.contains("sso") && (m.contains("expired") || m.contains("error loading") || m.contains("token"))) || m.contains("aws sso login");
    }

    @Override public List<String> signInArgs(List<String> chosen) { return chosen.isEmpty() || chosen.get(0) == null ? null : List.of("sso", "login", "--profile", chosen.get(0)); }

    @Override
    public String signInText(List<String> chosen) {
        String p = chosen.isEmpty() || chosen.get(0) == null ? "" : " “" + chosen.get(0) + "”";
        return "The sign-in for profile" + p + " has expired or hasn't started. Signing in opens your browser to approve it.";
    }

    @Override public String uri(List<String> chosen, String key) { return "s3://" + (chosen.size() > 1 && chosen.get(1) != null ? chosen.get(1) : "") + "/" + key; }
    @Override public String copyUriTitle() { return "Copy S3 URI"; }
    @Override public String copyLinkTitle() { return "Copy Presigned Link (7 days)"; }

    @Override
    public List<String> links(List<String> chosen, List<RemoteEntry> items) throws IOException {
        List<String> out = new ArrayList<>();
        for (RemoteEntry e : items) {
            if (e.isFolder()) continue;
            out.add(new String(run(chosen.get(0), List.of("s3", "presign", uri(chosen, e.key()), "--expires-in", "604800")), StandardCharsets.UTF_8).trim());
        }
        return out;
    }

    private static final Pattern PROGRESS = Pattern.compile("Completed ([\\d.]+) (\\w+)/~?([\\d.]+) (\\w+)");

    /** "Completed 1.2 MiB/~10.0 MiB (3.1 MiB/s) with 2 file(s) remaining" → {done, total} bytes. */
    static long[] parseProgress(String line) {
        Matcher m = PROGRESS.matcher(line);
        if (!m.find()) return null;
        return new long[] { bytes(m.group(1), m.group(2)), bytes(m.group(3), m.group(4)) };
    }

    private static long bytes(String value, String unit) {
        double v;
        try {
            v = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
        double f = switch (unit) {
            case "KiB" -> 1024.0;
            case "MiB" -> 1_048_576.0;
            case "GiB" -> 1_073_741_824.0;
            case "TiB" -> 1_099_511_627_776.0;
            default -> 1.0;
        };
        return (long) (v * f);
    }

    private boolean transfer(String profile, List<String> args, ProgressReporter reporter) {
        String cli = cli();
        if (cli == null) {
            reporter.error("The AWS CLI isn't installed.");
            return false;
        }
        List<String> all = new ArrayList<>(args);
        all.addAll(List.of("--profile", profile, "--no-cli-pager"));
        return CLITool.runJob(cli, all, environment(), reporter, line -> {
            long[] p = parseProgress(line);
            if (p != null) reporter.progress(p[0], p[1], line);
        });
    }

    @Override
    public void download(List<String> chosen, List<RemoteEntry> items, Path folder) {
        String profile = chosen.get(0);
        List<Object[]> jobs = new ArrayList<>();
        for (RemoteEntry i : items) jobs.add(new Object[] { uri(chosen, i.key()), localTarget(folder, i), i.isFolder() });
        TransferManager.get().start("Downloading " + Fmt.count(items.size(), "item") + " from S3", reporter -> {
            for (Object[] j : jobs) {
                if (reporter.isCancelled()) break;
                Path dest = (Path) j[1];
                reporter.begin(FileOps.name(dest));
                List<String> args = new ArrayList<>(List.of("s3", "cp", (String) j[0], dest.toString()));
                if ((Boolean) j[2]) args.add("--recursive");
                transfer(profile, args, reporter);
            }
        });
    }

    @Override
    public void upload(List<String> chosen, String prefix, List<Path> paths, Runnable after) {
        String profile = chosen.get(0);
        TransferManager.get().start("Uploading " + Fmt.count(paths.size(), "item") + " to S3", reporter -> {
            for (Path p : paths) {
                if (reporter.isCancelled()) break;
                boolean dir = FileOps.isRealDirectory(p);
                reporter.begin(FileOps.name(p));
                List<String> args = new ArrayList<>(List.of("s3", "cp", p.toString(), uri(chosen, prefix + FileOps.name(p) + (dir ? "/" : ""))));
                if (dir) args.add("--recursive");
                transfer(profile, args, reporter);
            }
            after.run();
        });
    }

    @Override
    public void createFolder(List<String> chosen, String prefix, String name) throws IOException {
        run(chosen.get(0), List.of("s3api", "put-object", "--bucket", chosen.get(1), "--key", prefix + name + "/"));
    }

    @Override
    public String rename(List<String> chosen, String prefix, RemoteEntry entry, String newName) throws IOException {
        String target = prefix + newName + (entry.isFolder() ? "/" : "");
        List<String> args = new ArrayList<>(List.of("s3", "mv", uri(chosen, entry.key()), uri(chosen, target), "--only-show-errors"));
        if (entry.isFolder()) args.add("--recursive");
        run(chosen.get(0), args);
        return target;
    }

    @Override
    public String deleteInfo(boolean folders) {
        return (folders ? "Folders are deleted with everything in them. " : "") + "This can't be undone unless the bucket has versioning turned on.";
    }

    @Override
    public void delete(List<String> chosen, List<RemoteEntry> items, Runnable after) {
        String profile = chosen.get(0);
        TransferManager.get().start("Deleting " + what(items) + " from S3", reporter -> {
            for (int i = 0; i < items.size() && !reporter.isCancelled(); i++) {
                RemoteEntry e = items.get(i);
                String target = uri(chosen, e.key());
                reporter.step(i, items.size(), target);
                List<String> args = new ArrayList<>(List.of("s3", "rm", target, "--only-show-errors"));
                if (e.isFolder()) args.add("--recursive");
                transfer(profile, args, reporter);
            }
            after.run();
        });
    }

    static JsonArray array(String text) {
        return JsonParser.parseString(text).getAsJsonArray();
    }
}
