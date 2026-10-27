package com.k3jdr.mxfilemgr.cloud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxfilemgr.model.FileOps;

/**
 * One cloud storage service as the {@link RemoteBrowser} window drives it: the pickers above the list (profile and
 * bucket; subscription, account and container; project and bucket; none for SFTP), listing a folder, and the
 * transfers and changes — each through the service's command-line tool. The methods that call a tool run on a
 * background thread; {@code chosen} is the picked value of each picker, in order.
 */
public abstract class RemoteBackend {

    /** A value of a picker: its id (what the CLI takes) and the label shown. */
    public record Choice(String id, String label, boolean isDefault) {
        public Choice(String id) { this(id, id, false); }
    }

    /** A folder's listing; {@code prefix} is where it really is (SFTP resolves the home folder). */
    public record Listing(List<RemoteEntry> entries, String prefix, boolean truncated) {}

    /** A part of the breadcrumb: its label and the prefix it goes to. */
    public record Crumb(String label, String prefix) {}

    /** Thrown when the service wants a sign-in before anything else. */
    public static final class SignInNeeded extends IOException {
        public SignInNeeded(String message) { super(message); }
    }

    public abstract String title();
    /** "S3", "Azure", "Cloud Storage", the account's name — used in the list's Quick Help and job titles. */
    public abstract String shortName();
    public abstract QuickHelpItem screenHelp();

    /** The CLI's path, or null when it isn't installed. */
    public abstract String cliPath();
    /** What to do when {@link #cliPath()} is null. */
    public abstract String installHint();

    public List<String> pickerTitles() { return List.of(); }
    public List<Choice> choices(int level, List<String> chosen) throws IOException { return List.of(); }
    public String savedChoice(int level) { return null; }
    public void saveChoice(int level, String id) { }

    /** Where the browser starts: "" (the bucket's top, or the SFTP account's home). */
    public String initialPrefix() { return ""; }
    public abstract Listing list(List<String> chosen, String prefix) throws IOException;

    public List<Crumb> crumbs(List<String> chosen, String prefix) {
        List<Crumb> out = new ArrayList<>();
        String root = chosen.isEmpty() ? "" : chosen.get(chosen.size() - 1);
        out.add(new Crumb(root, ""));
        StringBuilder p = new StringBuilder();
        for (String part : prefix.split("/")) {
            if (part.isEmpty()) continue;
            p.append(part).append('/');
            out.add(new Crumb(part, p.toString()));
        }
        return out;
    }

    /** The enclosing folder's prefix, or null at the top. */
    public String parent(String prefix) {
        if (prefix.isEmpty()) return null;
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        int slash = p.lastIndexOf('/');
        return slash < 0 ? "" : p.substring(0, slash + 1);
    }

    public boolean needsLogin(String message) { return false; }
    /** The CLI arguments of Sign In (opens the browser), or null when the service has none. */
    public List<String> signInArgs(List<String> chosen) { return null; }
    public Map<String, String> signInEnvironment() { return environment(); }
    public String signInText(List<String> chosen) { return "The sign-in has expired or hasn't started. Signing in opens your browser to approve it."; }

    public abstract Map<String, String> environment();

    /** The column showing {@code storageClass}: "Storage", "Tier", "Permissions"; null hides it. */
    public String classColumn() { return null; }

    public abstract String uri(List<String> chosen, String key);
    public String copyUriTitle() { return "Copy URI"; }
    /** The second copy command ("Copy Presigned Link (7 days)"), or null. */
    public String copyLinkTitle() { return null; }
    public List<String> links(List<String> chosen, List<RemoteEntry> items) throws IOException { return List.of(); }

    /** Starts a transfer-panel job that downloads {@code items} into {@code folder}. */
    public abstract void download(List<String> chosen, List<RemoteEntry> items, Path folder);
    /** Starts a job that uploads local items into {@code prefix}; {@code after} runs (on the job's thread) when it ends. */
    public abstract void upload(List<String> chosen, String prefix, List<Path> paths, Runnable after);

    /** True when folders exist only once something is in them (Azure, Cloud Storage): New Folder just opens one. */
    public boolean virtualFolders() { return false; }
    public String virtualFolderNote() { return "The folder opens now and is kept once you upload into it."; }
    public void createFolder(List<String> chosen, String prefix, String name) throws IOException { }

    public boolean canRename(RemoteEntry entry) { return true; }
    /** Renames and returns the new entry's key. */
    public abstract String rename(List<String> chosen, String prefix, RemoteEntry entry, String newName) throws IOException;

    public abstract String deleteInfo(boolean folders);
    public abstract void delete(List<String> chosen, List<RemoteEntry> items, Runnable after);

    /** Opening a symbolic link (SFTP): returns true when it is a folder to go into. */
    public boolean isFolderLink(List<String> chosen, RemoteEntry entry) { return false; }

    // MARK: Helpers

    /** "stem.ext" in {@code folder}, or "stem (2).ext" when taken. */
    protected static Path localTarget(Path folder, RemoteEntry item) {
        if (item.isFolder()) return FileOps.available(folder, item.name(), "");
        String n = item.name();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? FileOps.available(folder, n.substring(0, dot), n.substring(dot + 1)) : FileOps.available(folder, n, "");
    }

    protected static List<RemoteEntry> sorted(List<RemoteEntry> list) {
        List<RemoteEntry> out = new ArrayList<>(list);
        out.sort((a, b) -> a.isFolder() != b.isFolder() ? (a.isFolder() ? -1 : 1)
                : com.k3jdr.mxfilemgr.model.SortOrder.NATURAL.compare(a.name(), b.name()));
        return out;
    }

    protected static String what(List<RemoteEntry> items) {
        return items.size() == 1 ? "“" + items.get(0).name() + "”" : com.k3jdr.mxfilemgr.model.Fmt.count(items.size(), "item");
    }
}
