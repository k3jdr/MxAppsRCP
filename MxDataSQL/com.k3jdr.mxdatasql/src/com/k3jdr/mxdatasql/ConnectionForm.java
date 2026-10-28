package com.k3jdr.mxdatasql;

import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.db.Pg;
import com.k3jdr.mx.core.db.PgSettings;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;

/** Form 1 — DB Connection (ConnectionForm.swift): host, port, user, database, password, SSL mode. */
public final class ConnectionForm {
    private final Session session;
    private final Supplier<Workspace.Connection> settings;
    private final Runnable edited;
    private final Composite root;
    private final Text host, port, user, database, password;
    private final Combo ssl;
    private final Button savePassword, connect, disconnect;
    private final Text status;
    private boolean filling;

    public ConnectionForm(Composite parent, Session session, Supplier<Workspace.Connection> settings, Runnable edited,
                          Runnable onConnect) {
        this.session = session;
        this.settings = settings;
        this.edited = edited;
        root = new Composite(parent, SWT.NONE);
        root.setLayout(Ui.grid(1, 24, 10));
        Group group = new Group(root, SWT.NONE);
        group.setText("PostgreSQL Connection");
        group.setLayout(Ui.grid(2, 12, 8));
        GridData gd = new GridData(SWT.LEFT, SWT.TOP, false, false);
        gd.widthHint = 540;
        group.setLayoutData(gd);

        host = field(group, "Host", "Host", "The server's name or address, e.g. localhost or db.example.com.");
        port = field(group, "Port", "Port", "The server's port; PostgreSQL's is 5432.");
        user = field(group, "User", "User", "The PostgreSQL role to connect as.");
        database = field(group, "Database", "Database", "The database to connect to; once connected, the Database drop-down above switches to another on the same server.");
        Ui.label(group, "Password");
        password = new Text(group, SWT.BORDER | SWT.PASSWORD);
        password.setLayoutData(Ui.fillH());
        password.addListener(SWT.Modify, e -> {
            if (!filling) session.password = password.getText();
        });
        QuickHelp.help(password, "Password", "The role's password. It is never written to the workspace file; Save password keeps it in secure storage.");
        Ui.label(group, "SSL");
        ssl = new Combo(group, SWT.READ_ONLY);
        ssl.setItems(PgSettings.SSL_MODES);
        ssl.addListener(SWT.Selection, e -> {
            settings.get().sslMode = ssl.getText();
            edited.run();
        });
        QuickHelp.help(ssl, "SSL Mode", "disable: a plain connection. prefer: encrypted when the server offers TLS, plain otherwise. require: encrypted or no connection. The server's certificate isn't verified in any mode, as with libpq's prefer and require.");
        new Label(group, SWT.NONE);
        savePassword = Ui.check(group, "Save password", true, v -> {
            Workspace.Connection s = settings.get();
            s.savePasswordInKeychain = v;
            if (!v) Pg.forgetPassword(PgService.APP, s.pg());
            edited.run();
        });
        QuickHelp.help(savePassword, "Save Password", "Keeps the password in Windows secure storage (never in the workspace file) so the workspace can connect without asking. Turning it off removes it.");

        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(Ui.grid(3, 0, 8));
        GridData bd = new GridData(SWT.LEFT, SWT.TOP, false, false);
        bd.widthHint = 540;
        buttons.setLayoutData(bd);
        connect = Ui.button(buttons, "Connect", onConnect);
        connect.setLayoutData(new GridData(100, SWT.DEFAULT));
        QuickHelp.help(connect, "Connect", "Connects with these fields, then lists the database's tables in DB Table Selection.", "Enter");
        disconnect = Ui.button(buttons, "Disconnect", session::disconnect);
        QuickHelp.help(disconnect, "Disconnect", "Closes this workspace's connection. Table windows opened from it keep their own.");
        status = new Text(buttons, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
        GridData sd = Ui.fillH();
        sd.widthHint = 300;
        status.setLayoutData(sd);
        QuickHelp.help(status, "Connection Status", "Whether the workspace is connected, and why the last connection failed. Select text to copy it.");
        Label footer = Ui.label(root, "The password is not stored in the workspace file.", Ui.small());
        footer.setForeground(Ui.SECONDARY);
        refresh();
    }

    private Text field(Composite parent, String label, String title, String help) {
        Ui.label(parent, label);
        Text t = new Text(parent, SWT.BORDER);
        t.setLayoutData(Ui.fillH());
        t.addListener(SWT.Modify, e -> {
            if (filling) return;
            Workspace.Connection s = settings.get();
            String v = t.getText();
            if (t == host) s.host = v;
            else if (t == port) {
                try {
                    s.port = Integer.parseInt(v.trim());
                } catch (NumberFormatException ex) {
                    return;
                }
            } else if (t == user) s.user = v;
            else if (t == database) s.database = v;
            edited.run();
            updateButtons();
        });
        QuickHelp.help(t, title, help);
        return t;
    }

    public Composite control() { return root; }
    public Button defaultButton() { return connect; }

    private static void set(Text t, String v) {
        if (!t.getText().equals(v)) t.setText(v);
    }

    /** Fills the fields from the workspace (after opening a file) and shows the connection's state. */
    public void refresh() {
        Workspace.Connection s = settings.get();
        filling = true;
        try {
            set(host, s.host);
            if (!port.getText().trim().equals(Integer.toString(s.port))) port.setText(Integer.toString(s.port));
            set(user, s.user);
            set(database, s.database);
            set(password, session.password);
            ssl.setText(s.sslMode);
            savePassword.setSelection(s.savePasswordInKeychain);
        } finally {
            filling = false;
        }
        updateButtons();
        switch (session.status) {
            case DISCONNECTED -> {
                status.setText("");
                status.setForeground(Ui.SECONDARY);
            }
            case CONNECTING -> {
                status.setText("Connecting…");
                status.setForeground(Ui.SECONDARY);
            }
            case CONNECTED -> {
                status.setText("✔ Connected — PostgreSQL " + session.serverVersion);
                status.setForeground(Ui.GREEN);
            }
            case FAILED -> {
                status.setText("⚠ " + session.failure);
                status.setForeground(Ui.RED);
            }
        }
        root.layout(true, true);
    }

    private void updateButtons() {
        Workspace.Connection s = settings.get();
        connect.setText(session.isConnected() ? "Reconnect" : "Connect");
        QuickHelp.help(connect, session.isConnected() ? "Reconnect" : "Connect",
                "Connects with these fields, then lists the database's tables in DB Table Selection.", "Enter");
        connect.setEnabled(session.status != Session.Status.CONNECTING && !s.host.isBlank() && !s.user.isBlank());
        disconnect.setEnabled(session.isConnected());
    }
}
