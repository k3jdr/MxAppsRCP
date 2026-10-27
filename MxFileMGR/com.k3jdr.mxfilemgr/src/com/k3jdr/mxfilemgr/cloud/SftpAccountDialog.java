package com.k3jdr.mxfilemgr.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import com.k3jdr.mxfilemgr.ops.TransferManager;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * Add / Edit SFTP Account (SFTP.swift's SFTPSheet): a name for the sidebar, a host or an alias picked from
 * .ssh\config, optional user, port and starting folder, and Test Connection. Sign-in uses the SSH keys and agent.
 */
final class SftpAccountDialog extends Dialog {
    private static final int TEST = IDialogConstants.CLIENT_ID + 1, SAVE = IDialogConstants.CLIENT_ID + 2;

    private final Workspace ws;
    private final SftpAccount original;
    private Text name, host, user, port, folder;
    private Label result;
    private Button testButton;
    private boolean testing;

    private SftpAccountDialog(Workspace ws, SftpAccount original) {
        super(ws.shell());
        this.ws = ws;
        this.original = original;
    }

    static void open(Workspace ws, SftpAccount account) {
        new SftpAccountDialog(ws, account).open();
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(original == null ? "Add SFTP Account" : "Edit SFTP Account");
        shell.addListener(SWT.Activate, e -> QuickHelp.get().setScreen(() -> FileHelp.SFTP_SHEET));
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(2, 14, 8));
        List<String> hosts = SftpCli.configHosts();
        if (!hosts.isEmpty()) {
            Ui.label(area, "From .ssh\\config:");
            Combo pick = new Combo(area, SWT.READ_ONLY | SWT.DROP_DOWN);
            pick.add("Choose a host…");
            hosts.forEach(pick::add);
            pick.select(0);
            pick.addListener(SWT.Selection, e -> {
                int i = pick.getSelectionIndex();
                if (i <= 0) return;
                host.setText(hosts.get(i - 1));
                if (name.getText().isBlank()) name.setText(hosts.get(i - 1));
                user.setText("");
                port.setText("");
                validate();
            });
            QuickHelp.help(pick, "Hosts in .ssh\\config", "Fills in a host alias from your .ssh\\config; ssh then uses its HostName, User, Port and keys.");
        }
        name = field(area, "Name:", "e.g. Web server", original == null ? "" : original.name(), "Name", "The name shown under Favorites.");
        host = field(area, "Host:", "server.example.com or a .ssh\\config alias", original == null ? "" : original.host(), "Host",
                "The server's name or address, or a Host alias from .ssh\\config.");
        Ui.label(area, "User:");
        Composite row = new Composite(area, SWT.NONE);
        row.setLayout(Ui.grid(3, 0, 8));
        row.setLayoutData(Ui.fillH());
        user = new Text(row, SWT.BORDER);
        user.setMessage("Optional");
        user.setLayoutData(Ui.fillH());
        user.setText(original == null ? "" : original.user());
        QuickHelp.help(user, "User", "The user to sign in as; empty uses ssh's default (your Windows user name, or the config's User).");
        Ui.label(row, "Port:");
        port = new Text(row, SWT.BORDER);
        port.setMessage("22");
        GridData pg = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        pg.widthHint = 60;
        port.setLayoutData(pg);
        port.setText(original == null ? "" : original.port());
        QuickHelp.help(port, "Port", "The SSH port; empty uses 22, or the config's Port.");
        folder = field(area, "Folder:", "Optional — the home folder if empty", original == null ? "" : original.path(), "Folder",
                "The remote folder the window starts in; empty starts in the account's home folder.");
        Label note = new Label(area, SWT.WRAP);
        note.setText("Uses your SSH keys, the ssh-agent service and .ssh\\config (aliases, ports, ProxyJump) exactly as ssh in a terminal does. "
                + "Accounts that need a typed password aren't supported: add your key to the server's authorized_keys.");
        note.setForeground(Ui.SECONDARY);
        GridData ng = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        ng.widthHint = 480;
        note.setLayoutData(ng);
        result = new Label(area, SWT.WRAP);
        GridData rg = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        rg.widthHint = 480;
        result.setLayoutData(rg);
        QuickHelp.help(result, "Test Result", "What Test Connection found: the folder it reached, or ssh's error.");
        for (Text t : new Text[] { name, host, user, port, folder }) t.addListener(SWT.Modify, e -> validate());
        return area;
    }

    private Text field(Composite area, String title, String hint, String value, String helpTitle, String help) {
        Ui.label(area, title);
        Text t = new Text(area, SWT.BORDER);
        t.setMessage(hint);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 380;
        t.setLayoutData(gd);
        t.setText(value == null ? "" : value);
        QuickHelp.help(t, helpTitle, help);
        return t;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        testButton = createButton(parent, TEST, "Test Connection", false);
        QuickHelp.help(testButton, "Test Connection", "Connects with these settings and shows the folder reached, or the error.");
        Button save = createButton(parent, SAVE, "Save", false);
        QuickHelp.help(save, "Save", "Saves the account under Favorites.");
        Button open = createButton(parent, IDialogConstants.OK_ID, "Save and Open", true);
        QuickHelp.help(open, "Save and Open", "Saves the account and opens its window.");
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        validate();
    }

    private boolean isValid() {
        if (host == null || host.getText().isBlank()) return false;
        String p = port.getText().trim();
        if (p.isEmpty()) return true;
        try {
            int n = Integer.parseInt(p);
            return n > 0 && n < 65536;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void validate() {
        boolean ok = isValid();
        for (int id : new int[] { IDialogConstants.OK_ID, SAVE }) {
            Button b = getButton(id);
            if (b != null) b.setEnabled(ok);
        }
        if (testButton != null) testButton.setEnabled(ok && !testing);
    }

    private SftpAccount account() {
        String h = host.getText().trim();
        String n = name.getText().trim();
        return new SftpAccount(original == null ? UUID.randomUUID().toString() : original.id(), n.isEmpty() ? h : n, h,
                user.getText().trim(), port.getText().trim(), folder.getText().trim());
    }

    @Override
    protected void buttonPressed(int id) {
        if (id == TEST) {
            test();
        } else if (id == SAVE) {
            save();
            close();
        } else if (id == IDialogConstants.OK_ID) {
            SftpAccount a = save();
            close();
            SftpStore.get().open(ws, a);
        } else {
            super.buttonPressed(id);
        }
    }

    private SftpAccount save() {
        SftpAccount a = account();
        if (original != null && !a.equals(original)) RemoteBrowser.close(SftpStore.windowId(original));
        SftpStore.get().upsert(a);
        return a;
    }

    private void test() {
        if (!isValid()) return;
        SftpAccount a = account();
        testing = true;
        validate();
        result.setForeground(Ui.SECONDARY);
        result.setText("Connecting…");
        List<String> commands = new ArrayList<>();
        if (!a.path().isEmpty()) commands.add("cd " + SftpCli.quote(a.path()));
        commands.add("pwd");
        Background.run(result, () -> SftpCli.run(a, commands), output -> {
            testing = false;
            String pwd = SftpCli.parsePWD(output);
            result.setForeground(Ui.GREEN);
            result.setText("Connected. Folder: " + (pwd == null ? "?" : pwd));
            validate();
            getShell().layout(true, true);
        }, err -> {
            testing = false;
            result.setForeground(Ui.RED);
            result.setText(TransferManager.message(err));
            validate();
            getShell().layout(true, true);
        });
    }

    @Override
    protected Point getInitialSize() {
        Point p = super.getInitialSize();
        return new Point(Math.max(p.x, 560), Math.max(p.y, 360));
    }
}
