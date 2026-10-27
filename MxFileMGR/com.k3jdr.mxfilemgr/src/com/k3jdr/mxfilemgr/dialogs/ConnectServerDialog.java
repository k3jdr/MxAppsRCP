package com.k3jdr.mxfilemgr.dialogs;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.Settings;
import com.k3jdr.mxfilemgr.ops.CLITool;
import com.k3jdr.mxfilemgr.ops.TransferManager;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * Connect to Server (Ctrl+K, Sheets.swift's ConnectServerSheet), the Windows way: a share (\\server\share, or
 * smb://server/share) or a WebDAV folder (http:// or https://, through Windows' WebClient) is connected with
 * {@code net use} — optionally as a drive letter — and opened in the active pane. A share Windows can already reach
 * with your sign-in opens directly. The address is kept under Servers.
 */
public final class ConnectServerDialog extends Dialog {
    private static final String NO_LETTER = "(none)";

    private final Workspace ws;
    private final String prefill;
    private Combo address, letter;
    private Text user, password;
    private Label error, progress;
    private boolean connecting;

    private ConnectServerDialog(Workspace ws, String prefill) {
        super(ws.shell());
        this.ws = ws;
        this.prefill = prefill;
    }

    public static void open(Workspace ws, String prefill) {
        new ConnectServerDialog(ws, prefill == null ? "" : prefill).open();
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Connect to Server");
        shell.addListener(SWT.Activate, e -> QuickHelp.get().setScreen(() -> FileHelp.CONNECT));
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(2, 14, 8));
        Ui.label(area, "Server address:");
        address = new Combo(area, SWT.DROP_DOWN | SWT.BORDER);
        GridData ag = new GridData(SWT.FILL, SWT.CENTER, true, false);
        ag.widthHint = 420;
        address.setLayoutData(ag);
        for (String r : Settings.get().recentServers()) address.add(r);
        address.setText(prefill.isEmpty() ? "\\\\" : prefill);
        address.setSelection(new Point(address.getText().length(), address.getText().length()));
        address.addListener(SWT.Modify, e -> validate());
        QuickHelp.help(address, "Server Address", "A Windows share such as \\\\server\\share (smb://server/share works too), or a WebDAV folder's "
                + "http:// or https:// address. The list holds the addresses used recently.");
        new Label(area, SWT.NONE);
        Label hint = Ui.label(area, "\\\\server\\share, smb://server/share, or http(s)://… (WebDAV)");
        hint.setForeground(Ui.SECONDARY);

        Ui.label(area, "User:");
        Composite row = new Composite(area, SWT.NONE);
        row.setLayout(Ui.grid(3, 0, 8));
        row.setLayoutData(Ui.fillH());
        user = new Text(row, SWT.BORDER);
        user.setMessage("Optional (DOMAIN\\user)");
        user.setLayoutData(Ui.fillH());
        QuickHelp.help(user, "User", "The account to connect as; leave it empty to use your Windows sign-in.");
        Ui.label(row, "Password:");
        password = new Text(row, SWT.BORDER | SWT.PASSWORD);
        GridData pg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pg.widthHint = 140;
        password.setLayoutData(pg);
        QuickHelp.help(password, "Password", "The account's password. It is handed to net use only while connecting and isn't saved.");

        Ui.label(area, "Drive letter:");
        letter = new Combo(area, SWT.READ_ONLY | SWT.DROP_DOWN);
        letter.add(NO_LETTER);
        for (String l : freeLetters()) letter.add(l);
        letter.select(0);
        QuickHelp.help(letter, "Drive Letter", "Connects the share as this drive letter (it then shows under Locations), or as a plain network path with (none). "
                + "The connection isn't restored at the next sign-in.");

        error = new Label(area, SWT.WRAP);
        error.setForeground(Ui.RED);
        GridData eg = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        eg.widthHint = 440;
        error.setLayoutData(eg);
        QuickHelp.help(error, "Error", "Why the connection failed, as Windows reported it.");
        progress = Ui.label(area, "");
        progress.setForeground(Ui.SECONDARY);
        progress.setLayoutData(Ui.span(2));
        return area;
    }

    /** Drive letters D: … Z: that are free. */
    private static List<String> freeLetters() {
        Set<Character> used = new HashSet<>();
        for (File root : File.listRoots()) {
            String r = root.getPath();
            if (!r.isEmpty()) used.add(Character.toUpperCase(r.charAt(0)));
        }
        List<String> out = new ArrayList<>();
        for (char c = 'Z'; c >= 'D'; c--) if (!used.contains(c)) out.add(c + ":");
        return out;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button connect = createButton(parent, IDialogConstants.OK_ID, "Connect", true);
        QuickHelp.help(connect, FileHelp.CONNECT.title(), FileHelp.CONNECT.detail(), FileHelp.CONNECT.shortcut());
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        validate();
    }

    private void validate() {
        Button b = getButton(IDialogConstants.OK_ID);
        if (b != null) b.setEnabled(!connecting && unc(address.getText()) != null);
    }

    /**
     * The UNC path for an address: \\server\share as typed; smb://server/share → \\server\share; WebDAV
     * https://host[:port]/path → \\host@SSL[@port]\path and http://host/path → \\host\path (Windows' WebClient).
     * Null when it isn't one of those.
     */
    static String unc(String text) {
        String a = text == null ? "" : text.trim();
        if (a.startsWith("\\\\")) {
            String rest = a.substring(2).replace('/', '\\');
            return rest.isEmpty() || rest.startsWith("\\") ? null : "\\\\" + rest.replaceAll("\\\\+$", "");
        }
        String lower = a.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("smb://") && !lower.startsWith("http://") && !lower.startsWith("https://")) return null;
        try {
            URI u = URI.create(a.replace(" ", "%20"));
            String host = u.getHost();
            if (host == null || host.isBlank()) return null;
            String path = u.getPath() == null ? "" : u.getPath().replace('/', '\\').replaceAll("\\\\+$", "");
            if (lower.startsWith("smb://")) return path.isEmpty() ? null : "\\\\" + host + path;
            boolean ssl = lower.startsWith("https://");
            String server = host + (ssl ? "@SSL" : "") + (u.getPort() > 0 ? "@" + u.getPort() : "");
            return "\\\\" + server + (path.isEmpty() ? "\\DavWWWRoot" : path);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected void okPressed() {
        String typed = address.getText().trim();
        String target = unc(typed);
        if (target == null || connecting) return;
        String u = user.getText().trim(), p = password.getText();
        String drive = letter.getSelectionIndex() > 0 ? letter.getItem(letter.getSelectionIndex()) : null;
        boolean webdav = typed.toLowerCase(Locale.ROOT).startsWith("http");
        connecting = true;
        error.setText("");
        progress.setText("Connecting…");
        validate();
        Background.run(getShell(), () -> connect(target, u, p, drive, webdav), path -> {
            connecting = false;
            Settings.get().addRecentServer(typed);
            close();
            ws.go(path);
        }, err -> {
            connecting = false;
            progress.setText("");
            error.setText("Couldn't connect: " + TransferManager.message(err));
            validate();
            getShell().layout(true, true);
        });
    }

    /** Runs off the UI thread: the folder to open, or an exception with net use's message. */
    private static Path connect(String unc, String user, String password, String drive, boolean webdav) throws IOException {
        // A share Windows can already reach with your sign-in needs no net use.
        if (drive == null && user.isEmpty() && password.isEmpty() && !webdav) {
            try {
                if (Files.isDirectory(Path.of(unc))) return Path.of(unc);
            } catch (RuntimeException ignored) {
                // net use reports what's wrong
            }
        }
        List<String> args = new ArrayList<>();
        args.add("use");
        if (drive != null) args.add(drive);
        args.add(unc);
        // net use takes the password as an argument (it can't read one from a pipe); it is passed only for this
        // call, and like any command line it is visible to other processes of this user while net runs.
        if (!password.isEmpty()) args.add(password);
        if (!user.isEmpty()) args.add("/user:" + user);
        args.add("/persistent:no");
        CLITool.run("net.exe", args, null);
        Path opened = drive != null ? Path.of(drive + "\\") : Path.of(unc);
        if (!Files.isDirectory(opened)) throw new IOException("Connected, but “" + opened + "” can't be opened.");
        return opened;
    }

    @Override
    protected Point getInitialSize() {
        Point p = super.getInitialSize();
        return new Point(Math.max(p.x, 560), p.y);
    }
}
