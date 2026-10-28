package com.k3jdr.mxfilemgr.model;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k3jdr.mxfilemgr.win.ShellInfo;
import com.k3jdr.mxfilemgr.win.Win32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.platform.win32.WinReg;

/**
 * Where things are on this PC: the known folders (Desktop, Documents, Downloads — wherever OneDrive moved them),
 * the drives with their space, and the cloud storage folders the sync apps keep (AppSettings.swift's cloud
 * locations and VolumeMonitor, the Windows way): OneDrive personal and work or school, the SharePoint libraries
 * OneDrive syncs, Teams chat files, Google Drive, Dropbox, Box and iCloud Drive for Windows.
 */
public final class Places {
    private Places() {}

    public record Volume(Path root, String label, int type, long total, long free) {
        public String letter() { return root.toString().replace("\\", ""); }

        public String kind() {
            return switch (type) {
                case Win32.DRIVE_REMOVABLE -> "Removable Disk";
                case Win32.DRIVE_REMOTE -> "Network Drive";
                case Win32.DRIVE_CDROM -> "CD Drive";
                case Win32.DRIVE_RAMDISK -> "RAM Disk";
                default -> "Local Disk";
            };
        }

        /** "Local Disk (C:)", "USB Stick (E:)". */
        public String title() {
            return (label == null || label.isBlank() ? kind() : label) + " (" + letter() + ")";
        }

        public boolean isEjectable() { return type == Win32.DRIVE_REMOVABLE || type == Win32.DRIVE_CDROM || type == Win32.DRIVE_REMOTE; }
        public boolean isNetwork() { return type == Win32.DRIVE_REMOTE; }

        public String capacityText() {
            return total <= 0 ? kind() : Fmt.size(free) + " free of " + Fmt.size(total);
        }
    }

    public record Cloud(String name, String icon, Path path) {}

    // MARK: Known folders

    public static Path home() { return Path.of(System.getProperty("user.home")); }

    public static Path desktop() { return known(KnownFolders.FOLDERID_Desktop, home().resolve("Desktop")); }
    public static Path documents() { return known(KnownFolders.FOLDERID_Documents, home().resolve("Documents")); }
    public static Path downloads() { return known(KnownFolders.FOLDERID_Downloads, home().resolve("Downloads")); }
    public static Path pictures() { return known(KnownFolders.FOLDERID_Pictures, home().resolve("Pictures")); }

    public static Path programFiles() {
        String pf = System.getenv("ProgramFiles");
        return Path.of(pf != null ? pf : "C:\\Program Files");
    }

    public static Path systemDrive() {
        String sd = System.getenv("SystemDrive");
        return Path.of((sd != null ? sd : "C:") + "\\");
    }

    private static Path known(Guid.GUID id, Path fallback) {
        try {
            String p = Shell32Util.getKnownFolderPath(id);
            if (p != null && !p.isBlank()) return Path.of(p);
        } catch (Throwable t) {
            // fall back to the usual place
        }
        return fallback;
    }

    // MARK: Drives

    /** The drives (A: to Z:), with label and space. Network drives that don't answer may take a moment: call off the UI thread. */
    public static List<Volume> volumes() {
        List<Volume> out = new ArrayList<>();
        for (File root : File.listRoots()) {
            Path r = root.toPath();
            int type = ShellInfo.driveType(r);
            String label = "";
            long total = 0, free = 0;
            if (type != Win32.DRIVE_CDROM || root.exists()) {
                try {
                    char[] name = new char[261];
                    if (Kernel32.INSTANCE.GetVolumeInformation(r.toString(), name, name.length, null, null, null, null, 0)) {
                        label = com.sun.jna.Native.toString(name);
                    }
                } catch (Throwable t) {
                    // no label
                }
                total = root.getTotalSpace();
                free = root.getUsableSpace();
            }
            out.add(new Volume(r, label, type, total, free));
        }
        return out;
    }

    /** Free space on the volume holding {@code p}, or -1. */
    public static long freeSpace(Path p) {
        if (p == null) return -1;
        try {
            return Files.getFileStore(p).getUsableSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    // MARK: Cloud storage

    private static List<Cloud> cache;
    private static long cacheTime;

    private static boolean rescanning;

    /**
     * The cloud folders present on this PC. The first call scans; later calls answer from the cache and, when it
     * is a few seconds old, re-scan in the background (the path bar asks on every repaint).
     */
    public static List<Cloud> cloudLocations() {
        synchronized (Places.class) {
            if (cache != null) {
                if (System.currentTimeMillis() - cacheTime > 5000 && !rescanning) {
                    rescanning = true;
                    Thread t = new Thread(() -> {
                        try {
                            rescanClouds();
                        } finally {
                            rescanning = false;
                        }
                    }, "MxFileMGR cloud scan");
                    t.setDaemon(true);
                    t.start();
                }
                return cache;
            }
        }
        return rescanClouds();
    }

    /** Scans for the cloud folders now (off the UI thread when possible). */
    public static List<Cloud> rescanClouds() {
        List<Cloud> list = new ArrayList<>();
        try {
            Path icloud = home().resolve("iCloudDrive");
            if (FileOps.isRealDirectory(icloud)) list.add(new Cloud("iCloud Drive", "icloud", icloud));
            List<Path> oneDrives = oneDriveFolders();
            for (Path p : oneDrives) list.add(new Cloud(oneDriveName(p), "onedrive", p));
            for (Path p : sharePointFolders()) list.add(new Cloud(sharePointName(p), "sharepoint", p));
            List<Path> chats = teamsChatFolders(oneDrives);
            for (Path p : chats) list.add(new Cloud(teamsChatName(p, chats.size()), "teams", p));
            List<Path> google = googleDriveFolders();
            for (Path p : google) list.add(new Cloud(google.size() > 1 ? "Google Drive – " + googleAccount(p) : "Google Drive", "gdrive", p));
            for (Path p : dropboxFolders()) list.add(new Cloud(dropboxName(p), "dropbox", p));
            Path box = home().resolve("Box");
            if (FileOps.isRealDirectory(box) || Files.isDirectory(box)) list.add(new Cloud("Box", "box", box));
        } catch (Throwable t) {
            // whatever was found is shown
        }
        synchronized (Places.class) {
            cache = List.copyOf(list);
            cacheTime = System.currentTimeMillis();
            return cache;
        }
    }

    public static Cloud cloudAt(Path p) {
        if (p == null) return null;
        for (Cloud c : cloudLocations()) if (c.path().toString().equalsIgnoreCase(p.toString())) return c;
        return null;
    }

    private static final String ONEDRIVE_KEY = "Software\\Microsoft\\OneDrive\\Accounts";

    /** OneDrive folders: personal ("OneDrive") and work or school ("OneDrive - Contoso"), from OneDrive's registry and the environment. */
    public static List<Path> oneDriveFolders() {
        Set<String> seen = new LinkedHashSet<>();
        List<Path> out = new ArrayList<>();
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, ONEDRIVE_KEY)) {
                for (String account : Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, ONEDRIVE_KEY)) {
                    String key = ONEDRIVE_KEY + "\\" + account;
                    Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER, key);
                    if (values.get("UserFolder") instanceof String folder && !folder.isBlank()) add(out, seen, Path.of(folder));
                }
            }
        } catch (Throwable t) {
            // no OneDrive registry
        }
        for (String env : new String[] { "OneDriveConsumer", "OneDriveCommercial", "OneDrive" }) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) add(out, seen, Path.of(v));
        }
        return out;
    }

    private static void add(List<Path> out, Set<String> seen, Path p) {
        if (Files.isDirectory(p) && seen.add(p.toString().toLowerCase(Locale.ROOT))) out.add(p);
    }

    /** "OneDrive", or "OneDrive – Contoso" for "OneDrive - Contoso". */
    public static String oneDriveName(Path p) {
        String n = FileOps.name(p);
        String org = n.startsWith("OneDrive") ? n.substring("OneDrive".length()).replaceAll("^[\\s\\-–]+", "") : "";
        return org.isEmpty() || org.equalsIgnoreCase("Personal") ? "OneDrive" : "OneDrive – " + org;
    }

    /**
     * SharePoint document libraries the OneDrive app syncs: one folder per organisation (C:\Users\me\Contoso)
     * holding a "Site - Library" folder for each library, listed in OneDrive's Tenants registry key.
     */
    public static List<Path> sharePointFolders() {
        Set<String> seen = new LinkedHashSet<>();
        List<Path> out = new ArrayList<>();
        Set<String> oneDrives = new LinkedHashSet<>();
        for (Path p : oneDriveFolders()) oneDrives.add(p.toString().toLowerCase(Locale.ROOT));
        try {
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, ONEDRIVE_KEY)) return out;
            for (String account : Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, ONEDRIVE_KEY)) {
                String tenants = ONEDRIVE_KEY + "\\" + account + "\\Tenants";
                if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, tenants)) continue;
                for (String tenant : Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, tenants)) {
                    for (String folder : Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER, tenants + "\\" + tenant).keySet()) {
                        if (folder.isBlank() || oneDrives.contains(folder.toLowerCase(Locale.ROOT))) continue;
                        Path p = Path.of(folder);
                        if (!Files.isDirectory(p)) continue;
                        // A library folder ("Site - Documents") lives in the organisation's folder.
                        Path org = FileOps.name(p).contains(" - ") && p.getParent() != null && !p.getParent().equals(home()) ? p.getParent() : p;
                        if (oneDrives.contains(org.toString().toLowerCase(Locale.ROOT))) continue;
                        add(out, seen, org);
                    }
                }
            }
        } catch (Throwable t) {
            // nothing synced
        }
        return out;
    }

    public static String sharePointName(Path p) {
        return "SharePoint – " + FileOps.name(p);
    }

    /** Files shared in Teams chats: "Microsoft Teams Chat Files" in each OneDrive. */
    public static List<Path> teamsChatFolders(List<Path> oneDrives) {
        List<Path> out = new ArrayList<>();
        for (Path od : oneDrives) {
            Path chats = od.resolve("Microsoft Teams Chat Files");
            if (Files.isDirectory(chats)) out.add(chats);
        }
        return out;
    }

    public static String teamsChatName(Path p, int accounts) {
        if (accounts <= 1) return "Teams Chat Files";
        String account = oneDriveName(p.getParent());
        return "Teams Chat Files – " + account.replace("OneDrive – ", "");
    }

    /** Google Drive for desktop: its virtual drive ("Google Drive", My Drive inside) or the mirrored and older folders. */
    public static List<Path> googleDriveFolders() {
        List<Path> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (File root : File.listRoots()) {
            try {
                char[] name = new char[261];
                if (ShellInfo.driveType(root.toPath()) == Win32.DRIVE_FIXED
                        && Kernel32.INSTANCE.GetVolumeInformation(root.toString(), name, name.length, null, null, null, null, 0)
                        && com.sun.jna.Native.toString(name).startsWith("Google Drive")) {
                    Path my = root.toPath().resolve("My Drive");
                    add(out, seen, Files.isDirectory(my) ? my : root.toPath());
                }
            } catch (Throwable t) {
                // skip this drive
            }
        }
        add(out, seen, home().resolve("My Drive"));
        add(out, seen, home().resolve("Google Drive"));
        return out;
    }

    private static String googleAccount(Path p) {
        Path root = p.getRoot();
        return root != null && p.getNameCount() <= 1 ? root.toString().replace("\\", "") : FileOps.name(p);
    }

    /** Dropbox folders: what Dropbox records in info.json (personal and business), and ~\Dropbox, ~\Dropbox (Team). */
    public static List<Path> dropboxFolders() {
        List<Path> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String env : new String[] { "LOCALAPPDATA", "APPDATA" }) {
            String base = System.getenv(env);
            if (base == null) continue;
            Path info = Path.of(base, "Dropbox", "info.json");
            if (!Files.isRegularFile(info)) continue;
            try {
                JsonObject json = JsonParser.parseString(Files.readString(info)).getAsJsonObject();
                for (String key : new String[] { "personal", "business" }) {
                    if (json.has(key) && json.getAsJsonObject(key).has("path")) {
                        add(out, seen, Path.of(json.getAsJsonObject(key).get("path").getAsString()));
                    }
                }
            } catch (Exception e) {
                // unreadable info.json
            }
        }
        try (var ds = Files.newDirectoryStream(home(), p -> {
            String n = FileOps.name(p);
            return n.equals("Dropbox") || n.startsWith("Dropbox (");
        })) {
            for (Path p : ds) add(out, seen, p);
        } catch (Exception e) {
            // no home listing
        }
        return out;
    }

    /** "Dropbox" for a personal folder, "Dropbox – Acme" for "Dropbox (Acme)". */
    public static String dropboxName(Path p) {
        String account = FileOps.name(p).substring("Dropbox".length()).replaceAll("[-() ]", " ").trim();
        if (account.equalsIgnoreCase("Personal")) account = "";
        return account.isEmpty() ? "Dropbox" : "Dropbox – " + account;
    }
}
