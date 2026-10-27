package com.k3jdr.mxfilemgr.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.Settings;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.ops.CLITool;
import com.k3jdr.mxfilemgr.ops.CopyEngine;
import com.k3jdr.mxfilemgr.ops.ProgressReporter;
import com.k3jdr.mxfilemgr.ops.TransferManager;

/**
 * Azure Blob Storage through the Azure CLI after {@code az login} (AzureView.swift's AzureCLI and AzureModel); the
 * app never stores account keys. Data access uses the Microsoft Entra sign-in (--auth-mode login); when an account
 * refuses it (no "Storage Blob Data" role), that account is retried with its key (--auth-mode key).
 */
public final class AzureBackend extends RemoteBackend {
    private static final String[] PREFS = { "azureSubscription", "azureAccount", "azureContainer" };
    private final Set<String> keyModeAccounts = Collections.synchronizedSet(new HashSet<>());

    public static String cli() {
        return CLITool.find("az", "C:\\Program Files\\Microsoft SDKs\\Azure\\CLI2\\wbin\\az.cmd",
                "C:\\Program Files (x86)\\Microsoft SDKs\\Azure\\CLI2\\wbin\\az.cmd");
    }

    @Override public String title() { return "Azure Blob Storage"; }
    @Override public String shortName() { return "Azure"; }
    @Override public QuickHelpItem screenHelp() { return FileHelp.AZURE_SCREEN; }
    @Override public String cliPath() { return cli(); }

    @Override
    public String installHint() {
        return "The Azure CLI isn't installed. Install it with: winget install Microsoft.AzureCLI\nThen sign in here with Sign In (az login).";
    }

    @Override public List<String> pickerTitles() { return List.of("Subscription", "Account", "Container"); }
    @Override public String classColumn() { return "Tier"; }

    @Override
    public Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        env.put("AZURE_CORE_NO_COLOR", "true");
        env.put("AZURE_CORE_ONLY_SHOW_ERRORS", "true");
        env.put("AZURE_CORE_SURVEY_MESSAGE", "false");
        env.put("PYTHONIOENCODING", "utf-8");
        return env;
    }

    private String run(List<String> args) throws IOException {
        String cli = cli();
        if (cli == null) throw new IOException("The Azure CLI isn't installed.");
        List<String> all = new ArrayList<>(args);
        all.add("--only-show-errors");
        return new String(CLITool.run(cli, all, environment()), StandardCharsets.UTF_8);
    }

    private boolean runJob(List<String> args, ProgressReporter reporter) {
        String cli = cli();
        if (cli == null) {
            reporter.error("The Azure CLI isn't installed.");
            return false;
        }
        List<String> all = new ArrayList<>(args);
        all.add("--only-show-errors");
        return CLITool.runJob(cli, all, environment(), reporter, null);
    }

    private static boolean isPermissionMismatch(String message) {
        return message.contains("AuthorizationPermissionMismatch") || message.contains("This request is not authorized");
    }

    private String authMode(String account) { return keyModeAccounts.contains(account) ? "key" : "login"; }

    /** Arguments that address the chosen account (and subscription). */
    private List<String> accountArgs(List<String> chosen) {
        List<String> args = new ArrayList<>(List.of("--account-name", chosen.get(1), "--auth-mode", authMode(chosen.get(1))));
        if (chosen.get(0) != null && !chosen.get(0).isEmpty()) args.addAll(List.of("--subscription", chosen.get(0)));
        return args;
    }

    /** A data-plane command, switching the account to key auth once if Entra access is refused. */
    private String runData(List<String> chosen, List<String> args) throws IOException {
        List<String> all = new ArrayList<>(args);
        all.addAll(accountArgs(chosen));
        try {
            return run(all);
        } catch (IOException e) {
            if (e.getMessage() != null && isPermissionMismatch(e.getMessage()) && authMode(chosen.get(1)).equals("login")) {
                keyModeAccounts.add(chosen.get(1));
                List<String> again = new ArrayList<>(args);
                again.addAll(accountArgs(chosen));
                return run(again);
            }
            throw e;
        }
    }

    @Override
    public List<Choice> choices(int level, List<String> chosen) throws IOException {
        switch (level) {
            case 0 -> {
                List<Choice> out = new ArrayList<>();
                for (JsonElement e : JsonParser.parseString(run(List.of("account", "list", "-o", "json"))).getAsJsonArray()) {
                    JsonObject s = e.getAsJsonObject();
                    if (!s.has("id")) continue;
                    String id = s.get("id").getAsString();
                    out.add(new Choice(id, s.has("name") ? s.get("name").getAsString() : id, s.has("isDefault") && s.get("isDefault").getAsBoolean()));
                }
                if (out.isEmpty()) throw new SignInNeeded("You aren't signed in to Azure. Signing in (az login) opens your browser.");
                return out;
            }
            case 1 -> {
                List<String> names = strings(run(List.of("storage", "account", "list", "--subscription", chosen.get(0), "--query", "[].name", "-o", "json")));
                List<Choice> out = new ArrayList<>();
                for (String n : names) out.add(new Choice(n));
                return out;
            }
            default -> {
                List<String> names = strings(runData(chosen.subList(0, 2), List.of("storage", "container", "list", "--num-results", "*", "--query", "[].name", "-o", "json")));
                List<Choice> out = new ArrayList<>();
                for (String n : names) out.add(new Choice(n));
                return out;
            }
        }
    }

    private static List<String> strings(String json) {
        List<String> out = new ArrayList<>();
        String t = json.trim();
        if (t.isEmpty()) return out;
        for (JsonElement e : JsonParser.parseString(t).getAsJsonArray()) out.add(e.getAsString());
        out.sort(com.k3jdr.mxfilemgr.model.SortOrder.NATURAL);
        return out;
    }

    @Override public String savedChoice(int level) { return Settings.PREFS.get(PREFS[level], null); }
    @Override public void saveChoice(int level, String id) { Settings.PREFS.put(PREFS[level], id); }

    @Override
    public Listing list(List<String> chosen, String prefix) throws IOException {
        List<String> args = new ArrayList<>(List.of("storage", "blob", "list", "--container-name", chosen.get(2), "--delimiter", "/",
                "--num-results", String.valueOf(RemoteBrowser.MAX_ITEMS), "-o", "json"));
        if (!prefix.isEmpty()) args.addAll(List.of("--prefix", prefix));
        String text = runData(chosen, args).trim();
        List<RemoteEntry> result = new ArrayList<>();
        if (!text.isEmpty()) {
            for (JsonElement e : JsonParser.parseString(text).getAsJsonArray()) {
                JsonObject blob = e.getAsJsonObject();
                if (!blob.has("name")) continue;
                String name = blob.get("name").getAsString();
                if (name.equals(prefix)) continue;
                JsonObject props = blob.has("properties") && blob.get("properties").isJsonObject() ? blob.getAsJsonObject("properties") : null;
                Long size = props != null && props.has("contentLength") && !props.get("contentLength").isJsonNull() ? props.get("contentLength").getAsLong() : null;
                if (name.endsWith("/") && (size == null || size == 0)) {
                    result.add(new RemoteEntry(name, name.substring(prefix.length(), name.length() - 1), true, 0, 0, ""));
                } else {
                    String modified = props != null && props.has("lastModified") && !props.get("lastModified").isJsonNull() ? props.get("lastModified").getAsString() : null;
                    String tier = props != null && props.has("blobTier") && !props.get("blobTier").isJsonNull() ? props.get("blobTier").getAsString() : "";
                    result.add(new RemoteEntry(name, name.substring(prefix.length()), false, size == null ? 0 : size, CLITool.parseISODate(modified), tier));
                }
            }
        }
        return new Listing(sorted(result), prefix, result.size() >= RemoteBrowser.MAX_ITEMS);
    }

    @Override
    public boolean needsLogin(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("az login") || m.contains("please run 'az login'") || m.contains("aadsts")
                || (m.contains("refresh token") && m.contains("expired"));
    }

    @Override public List<String> signInArgs(List<String> chosen) { return List.of("login", "-o", "none"); }

    @Override
    public String signInText(List<String> chosen) {
        return "Sign in to Azure to browse your storage accounts. Signing in (az login) opens your browser.";
    }

    @Override
    public String uri(List<String> chosen, String key) {
        String account = chosen.size() > 1 && chosen.get(1) != null ? chosen.get(1) : "";
        String container = chosen.size() > 2 && chosen.get(2) != null ? chosen.get(2) : "";
        return "https://" + account + ".blob.core.windows.net/" + container + "/" + key;
    }

    @Override public String copyUriTitle() { return "Copy URL"; }
    @Override public String copyLinkTitle() { return "Copy SAS Link (7 days)"; }

    /** Read-only SAS links valid for 7 days (a user delegation SAS when signed in with Entra). */
    @Override
    public List<String> links(List<String> chosen, List<RemoteEntry> items) throws IOException {
        String expiry = ZonedDateTime.now(ZoneOffset.UTC).plusDays(7).minusMinutes(5).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'"));
        List<String> out = new ArrayList<>();
        for (RemoteEntry e : items) {
            if (e.isFolder()) continue;
            List<String> args = new ArrayList<>(List.of("storage", "blob", "generate-sas", "--container-name", chosen.get(2), "--name", e.key(),
                    "--permissions", "r", "--expiry", expiry, "--full-uri", "-o", "tsv"));
            if (authMode(chosen.get(1)).equals("login")) args.add("--as-user");
            out.add(runData(chosen, args).trim());
        }
        return out;
    }

    @Override
    public void download(List<String> chosen, List<RemoteEntry> items, Path folder) {
        List<String> base = accountArgs(chosen);
        String container = chosen.get(2);
        List<Object[]> jobs = new ArrayList<>();
        for (RemoteEntry i : items) jobs.add(new Object[] { i, localTarget(folder, i) });
        TransferManager.get().start("Downloading " + Fmt.count(items.size(), "item") + " from Azure", reporter -> {
            for (Object[] j : jobs) {
                if (reporter.isCancelled()) break;
                RemoteEntry item = (RemoteEntry) j[0];
                Path dest = (Path) j[1];
                reporter.begin(FileOps.name(dest));
                if (item.isFolder()) {
                    // download-batch keeps the full blob path: download into a temporary folder and move the folder out.
                    Path temp = folder.resolve(".mxazure-" + UUID.randomUUID());
                    try {
                        Files.createDirectories(temp);
                        List<String> args = new ArrayList<>(List.of("storage", "blob", "download-batch", "--source", container,
                                "--destination", temp.toString(), "--pattern", item.key() + "*"));
                        args.addAll(base);
                        if (runJob(args, reporter)) Files.move(temp.resolve(item.path().replace('/', '\\')), dest);
                    } catch (IOException e) {
                        reporter.error(item.name() + ": " + TransferManager.message(e));
                    } finally {
                        try {
                            if (Files.exists(temp)) CopyEngine.deleteRecursively(temp, null);
                        } catch (IOException ignored) {
                            // the temporary folder stays
                        }
                    }
                } else {
                    List<String> args = new ArrayList<>(List.of("storage", "blob", "download", "--container-name", container, "--name", item.key(),
                            "--file", dest.toString()));
                    args.addAll(base);
                    runJob(args, reporter);
                }
            }
        });
    }

    @Override
    public void upload(List<String> chosen, String prefix, List<Path> paths, Runnable after) {
        List<String> base = accountArgs(chosen);
        String container = chosen.get(2);
        TransferManager.get().start("Uploading " + Fmt.count(paths.size(), "item") + " to Azure", reporter -> {
            for (Path p : paths) {
                if (reporter.isCancelled()) break;
                reporter.begin(FileOps.name(p));
                List<String> args;
                if (FileOps.isRealDirectory(p)) {
                    args = new ArrayList<>(List.of("storage", "blob", "upload-batch", "--destination", container,
                            "--destination-path", prefix + FileOps.name(p), "--source", p.toString(), "--overwrite"));
                } else {
                    args = new ArrayList<>(List.of("storage", "blob", "upload", "--container-name", container,
                            "--name", prefix + FileOps.name(p), "--file", p.toString(), "--overwrite"));
                }
                args.addAll(base);
                runJob(args, reporter);
            }
            after.run();
        });
    }

    @Override public boolean virtualFolders() { return true; }
    @Override public String virtualFolderNote() { return "Blob Storage has no empty folders. The folder opens now and is kept once you upload into it."; }
    @Override public boolean canRename(RemoteEntry entry) { return !entry.isFolder(); }

    /** Copies the blob to the new name, waits for the copy, then deletes the original. */
    @Override
    public String rename(List<String> chosen, String prefix, RemoteEntry entry, String newName) throws IOException {
        String container = chosen.get(2);
        String target = prefix + newName;
        runData(chosen, List.of("storage", "blob", "copy", "start", "--destination-container", container, "--destination-blob", target,
                "--source-container", container, "--source-blob", entry.key(), "-o", "none"));
        for (int i = 0; i < 120; i++) {
            String status = runData(chosen, List.of("storage", "blob", "show", "--container-name", container, "--name", target,
                    "--query", "properties.copy.status", "-o", "json")).trim().replace("\"", "");
            if (status.isEmpty() || status.equals("null") || status.equals("success")) break;
            if (status.equals("failed") || status.equals("aborted")) throw new IOException("The copy to “" + newName + "” " + status + ".");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
            }
        }
        runData(chosen, List.of("storage", "blob", "delete", "--container-name", container, "--name", entry.key()));
        return target;
    }

    @Override
    public String deleteInfo(boolean folders) {
        return (folders ? "Folders are deleted with everything in them. " : "") + "This can't be undone unless soft delete is turned on for the storage account.";
    }

    @Override
    public void delete(List<String> chosen, List<RemoteEntry> items, Runnable after) {
        List<String> base = accountArgs(chosen);
        String container = chosen.get(2);
        TransferManager.get().start("Deleting " + what(items) + " from Azure", reporter -> {
            for (int i = 0; i < items.size() && !reporter.isCancelled(); i++) {
                RemoteEntry e = items.get(i);
                reporter.step(i, items.size(), e.name());
                List<String> args = e.isFolder()
                        ? new ArrayList<>(List.of("storage", "blob", "delete-batch", "--source", container, "--pattern", e.key() + "*"))
                        : new ArrayList<>(List.of("storage", "blob", "delete", "--container-name", container, "--name", e.key()));
                args.addAll(base);
                runJob(args, reporter);
            }
            after.run();
        });
    }
}
