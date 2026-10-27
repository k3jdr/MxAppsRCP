package com.k3jdr.mxfilemgr.cloud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.ops.ProgressReporter;
import com.k3jdr.mxfilemgr.ops.TransferManager;

/**
 * An SFTP account's folders through {@code sftp.exe} (SFTP.swift's SFTPModel): browse, download to and upload
 * from the active pane, new folder, rename, delete (folders with everything in them), copy sftp:// links.
 * Prefixes are absolute remote folders ending with "/"; "" starts in the account's folder or home.
 */
final class SftpBackend extends RemoteBackend {
    private final SftpAccount account;

    SftpBackend(SftpAccount account) { this.account = account; }

    @Override public String title() { return account.name(); }
    @Override public String shortName() { return account.name(); }
    @Override public QuickHelpItem screenHelp() { return FileHelp.SFTP_SCREEN; }
    @Override public String cliPath() { return SftpCli.path(); }

    @Override
    public String installHint() {
        return "Windows' OpenSSH client (sftp.exe) isn't installed. Add it in Settings ▸ System ▸ Optional features ▸ OpenSSH Client.";
    }

    @Override public Map<String, String> environment() { return Map.of(); }
    @Override public String classColumn() { return "Permissions"; }
    @Override public String initialPrefix() { return account.path() == null ? "" : account.path().trim(); }

    private static String strip(String folder) {
        return folder.length() > 1 && folder.endsWith("/") ? folder.substring(0, folder.length() - 1) : folder;
    }

    @Override
    public Listing list(List<String> chosen, String prefix) throws IOException {
        List<String> commands = new ArrayList<>();
        if (!prefix.isBlank()) commands.add("cd " + SftpCli.quote(strip(prefix)));
        commands.add("pwd");
        commands.add("ls -la");
        String output = SftpCli.run(account, commands);
        String folder = SftpCli.parsePWD(output);
        if (folder == null) folder = prefix.isBlank() ? "/" : strip(prefix);
        String withSlash = folder.endsWith("/") ? folder : folder + "/";
        return new Listing(sorted(SftpCli.parseListing(output, folder)), withSlash, false);
    }

    @Override
    public List<Crumb> crumbs(List<String> chosen, String prefix) {
        List<Crumb> out = new ArrayList<>();
        out.add(new Crumb("/", "/"));
        StringBuilder p = new StringBuilder("/");
        for (String part : prefix.split("/")) {
            if (part.isEmpty()) continue;
            p.append(part).append('/');
            out.add(new Crumb(part, p.toString()));
        }
        return out;
    }

    @Override
    public String parent(String prefix) {
        if (prefix.isBlank() || prefix.equals("/")) return null;
        String p = strip(prefix);
        int slash = p.lastIndexOf('/');
        return slash <= 0 ? "/" : p.substring(0, slash + 1);
    }

    @Override public String uri(List<String> chosen, String key) { return account.link(key == null || key.isEmpty() ? "" : strip(key)); }
    @Override public String copyUriTitle() { return "Copy sftp:// Link"; }

    @Override
    public void download(List<String> chosen, List<RemoteEntry> items, Path folder) {
        List<String> commands = new ArrayList<>();
        for (RemoteEntry i : items) {
            commands.add("get " + (i.isFolder() ? "-r " : "") + SftpCli.quote(i.path()) + " " + SftpCli.local(localTarget(folder, i)));
        }
        TransferManager.get().start("Downloading " + Fmt.count(items.size(), "item") + " from " + account.name(), reporter -> {
            reporter.begin(items.size() == 1 ? items.get(0).name() : items.size() + " items");
            SftpCli.runJob(account, commands, reporter);
        });
    }

    @Override
    public void upload(List<String> chosen, String prefix, List<Path> paths, Runnable after) {
        String base = prefix.endsWith("/") ? prefix : prefix + "/";
        List<String> commands = new ArrayList<>();
        for (Path p : paths) {
            commands.add("put " + (FileOps.isRealDirectory(p) ? "-r " : "") + SftpCli.local(p) + " " + SftpCli.quote(base + FileOps.name(p)));
        }
        TransferManager.get().start("Uploading " + Fmt.count(paths.size(), "item") + " to " + account.name(), reporter -> {
            reporter.begin(paths.size() == 1 ? FileOps.name(paths.get(0)) : paths.size() + " items");
            SftpCli.runJob(account, commands, reporter);
            after.run();
        });
    }

    @Override
    public void createFolder(List<String> chosen, String prefix, String name) throws IOException {
        SftpCli.run(account, List.of("mkdir " + SftpCli.quote(prefix + name)));
    }

    @Override
    public String rename(List<String> chosen, String prefix, RemoteEntry entry, String newName) throws IOException {
        String target = prefix + newName;
        SftpCli.run(account, List.of("rename " + SftpCli.quote(entry.path()) + " " + SftpCli.quote(target)));
        return target + (entry.isFolder() ? "/" : "");
    }

    @Override
    public String deleteInfo(boolean folders) {
        return (folders ? "Folders are deleted with everything in them. " : "") + "This can't be undone.";
    }

    /** Files, and folders with everything in them (sftp only removes empty folders, so their trees are listed first). */
    @Override
    public void delete(List<String> chosen, List<RemoteEntry> items, Runnable after) {
        TransferManager.get().start("Deleting " + what(items) + " from " + account.name(), reporter -> {
            List<String> commands = new ArrayList<>();
            for (RemoteEntry item : items) {
                if (reporter.isCancelled()) break;
                reporter.begin(item.name());
                if (item.isFolder()) {
                    List<String> files = new ArrayList<>(), dirs = new ArrayList<>();
                    collect(item.path(), files, dirs, reporter);
                    for (String f : files) commands.add("rm " + SftpCli.quote(f));
                    Collections.reverse(dirs);
                    for (String d : dirs) commands.add("rmdir " + SftpCli.quote(d));
                } else {
                    commands.add("rm " + SftpCli.quote(item.path()));
                }
            }
            if (!reporter.isCancelled() && !commands.isEmpty()) SftpCli.runJob(account, commands, reporter);
            after.run();
        });
    }

    /** Every file and folder under {@code dir} (folders parent-first, {@code dir} included). */
    private void collect(String dir, List<String> files, List<String> dirs, ProgressReporter reporter) {
        dirs.add(dir);
        if (reporter.isCancelled()) return;
        try {
            String output = SftpCli.run(account, List.of("cd " + SftpCli.quote(dir), "ls -la"));
            for (RemoteEntry e : SftpCli.parseListing(output, dir)) {
                if (e.isFolder()) collect(e.path(), files, dirs, reporter);
                else files.add(e.key());
            }
        } catch (IOException e) {
            reporter.error(dir + ": " + TransferManager.message(e));
        }
    }

    /** A symbolic link may point to a folder (sftp's listing can't tell): true when cd into it works. */
    @Override
    public boolean isFolderLink(List<String> chosen, RemoteEntry entry) {
        try {
            SftpCli.run(account, List.of("cd " + SftpCli.quote(entry.path())));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
