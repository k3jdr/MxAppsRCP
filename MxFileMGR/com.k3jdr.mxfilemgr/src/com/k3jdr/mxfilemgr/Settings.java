package com.k3jdr.mxfilemgr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mxfilemgr.model.Places;

/**
 * MxFileMGR's preferences (AppSettings.swift): hidden files, folders first, the function key bar, asking before
 * the Recycle Bin, the editor (F4) and terminal, favorites and recent servers. Listeners hear about changes that
 * affect the listings or the window.
 */
public final class Settings {
    public static final Prefs PREFS = Prefs.of("com.k3jdr.mxfilemgr");
    private static final Settings INSTANCE = new Settings();

    /** Something that changes the listings (hidden files, folders first): every tab re-lists. */
    public static final String LISTING = "listing";
    /** Favorites or recent servers changed: the sidebar rebuilds. */
    public static final String SIDEBAR = "sidebar";
    /** The function key bar was shown or hidden. */
    public static final String LAYOUT = "layout";

    public interface Listener {
        void changed(String what);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public static Settings get() { return INSTANCE; }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public void fire(String what) { listeners.forEach(l -> l.changed(what)); }

    public boolean showHidden() { return PREFS.getBoolean("showHidden", false); }
    public void setShowHidden(boolean v) { PREFS.putBoolean("showHidden", v); fire(LISTING); }

    public boolean foldersFirst() { return PREFS.getBoolean("foldersFirst", true); }
    public void setFoldersFirst(boolean v) { PREFS.putBoolean("foldersFirst", v); fire(LISTING); }

    public boolean confirmTrash() { return PREFS.getBoolean("confirmTrash", false); }
    public void setConfirmTrash(boolean v) { PREFS.putBoolean("confirmTrash", v); }

    public boolean showFunctionKeyBar() { return PREFS.getBoolean("showFunctionKeyBar", true); }
    public void setShowFunctionKeyBar(boolean v) { PREFS.putBoolean("showFunctionKeyBar", v); fire(LAYOUT); }

    /** The program Edit (F4) opens files with: Notepad unless chosen otherwise. */
    public String editorPath() { return PREFS.get("editorPath", "notepad.exe"); }
    public void setEditorPath(String v) { PREFS.put("editorPath", v == null || v.isBlank() ? null : v.trim()); }

    /** What Open Terminal Here starts: "wt" (Windows Terminal), "powershell", "pwsh", "cmd", or a program's path. */
    public String terminal() { return PREFS.get("terminal", defaultTerminal()); }
    public void setTerminal(String v) { PREFS.put("terminal", v == null || v.isBlank() ? null : v.trim()); }

    public static String defaultTerminal() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && Files.exists(Path.of(local, "Microsoft", "WindowsApps", "wt.exe"))) return "wt";
        return "cmd";
    }

    public List<String> favorites() {
        if (!PREFS.has("favorites")) {
            List<String> defaults = new ArrayList<>();
            for (Path p : List.of(Places.home(), Places.desktop(), Places.documents(), Places.downloads())) {
                if (Files.isDirectory(p) && !defaults.contains(p.toString())) defaults.add(p.toString());
            }
            return defaults;
        }
        return PREFS.getList("favorites");
    }

    public void setFavorites(List<String> list) {
        PREFS.putList("favorites", list);
        fire(SIDEBAR);
    }

    public void addFavorite(Path p) {
        List<String> list = favorites();
        if (!list.contains(p.toString())) {
            list.add(p.toString());
            setFavorites(list);
        }
    }

    public void removeFavorite(String path) {
        List<String> list = favorites();
        list.remove(path);
        setFavorites(list);
    }

    public void moveFavorite(String path, int delta) {
        List<String> list = favorites();
        int i = list.indexOf(path);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= list.size()) return;
        list.remove(i);
        list.add(j, path);
        setFavorites(list);
    }

    public List<String> recentServers() { return PREFS.getList("recentServers"); }

    public void addRecentServer(String address) {
        PREFS.pushRecent("recentServers", address, 10);
        fire(SIDEBAR);
    }

    public void removeRecentServer(String address) {
        List<String> list = recentServers();
        list.remove(address);
        PREFS.putList("recentServers", list);
        fire(SIDEBAR);
    }
}
