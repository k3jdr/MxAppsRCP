package com.k3jdr.mxfilemgr.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.swt.widgets.Shell;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.k3jdr.mxfilemgr.Settings;
import com.k3jdr.mxfilemgr.dialogs.Dialogs;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * The saved SFTP accounts shown in Favorites (SFTP.swift's SFTPStore), kept as JSON in the settings under
 * "sftpAccounts". Listeners (the sidebar) hear about changes; all calls are made on the UI thread.
 */
public final class SftpStore {
    private static final String KEY = "sftpAccounts";
    private static final SftpStore INSTANCE = new SftpStore();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<SftpAccount> accounts = new ArrayList<>();

    public static SftpStore get() { return INSTANCE; }

    private SftpStore() {
        String raw = Settings.PREFS.get(KEY, null);
        if (raw == null || raw.isBlank()) return;
        try {
            for (JsonElement e : JsonParser.parseString(raw).getAsJsonArray()) {
                SftpAccount a = SftpAccount.fromJson(e.getAsJsonObject());
                if (!a.id().isEmpty() && !a.host().isEmpty()) accounts.add(a);
            }
        } catch (RuntimeException e) {
            // unreadable accounts start over
        }
    }

    private void save() {
        JsonArray a = new JsonArray();
        for (SftpAccount acc : accounts) a.add(acc.toJson());
        Settings.PREFS.put(KEY, a.toString());
        listeners.forEach(Runnable::run);
    }

    public List<SftpAccount> accounts() { return List.copyOf(accounts); }

    public void addListener(Runnable r) { listeners.add(r); }

    public SftpAccount account(String id) {
        for (SftpAccount a : accounts) if (a.id().equals(id)) return a;
        return null;
    }

    /** Adds the account, or replaces the saved one with the same id. */
    public void upsert(SftpAccount account) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id().equals(account.id())) {
                accounts.set(i, account);
                save();
                return;
            }
        }
        accounts.add(account);
        save();
    }

    /** Opens the account's browser window (or brings it to the front). */
    public void open(Workspace ws, SftpAccount account) {
        RemoteBrowser.open(ws, windowId(account), () -> new SftpBackend(account));
    }

    static String windowId(SftpAccount account) { return "sftp:" + account.id(); }

    /** Add SFTP Account… */
    public void add(Workspace ws) { SftpAccountDialog.open(ws, null); }

    public void edit(Workspace ws, SftpAccount account) { SftpAccountDialog.open(ws, account); }

    /** Removes the account after asking; files on the server aren't touched. */
    public void remove(Shell parent, SftpAccount account) {
        if (!Dialogs.confirm(parent, "Remove the SFTP account “" + account.name() + "”?", "Files on the server aren't touched.", "Remove", false)) return;
        accounts.removeIf(a -> a.id().equals(account.id()));
        RemoteBrowser.close(windowId(account));
        save();
    }

    /** Moves the account up (-1) or down (+1) in Favorites. */
    public void move(SftpAccount account, int delta) {
        int i = -1;
        for (int k = 0; k < accounts.size(); k++) if (accounts.get(k).id().equals(account.id())) i = k;
        int j = i + delta;
        if (i < 0 || j < 0 || j >= accounts.size()) return;
        SftpAccount a = accounts.remove(i);
        accounts.add(j, a);
        save();
    }
}
