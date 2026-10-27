package com.k3jdr.mxdatahub;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.db.Pg;
import com.k3jdr.mx.core.db.PgSettings;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;

/**
 * DB Connection (ConnectionView.swift): PostgreSQL host, port, user, database, password and SSL mode — the same
 * screen as MxDataSQL's. The fields are remembered by the app; the password in Windows secure storage (in place of
 * the Keychain) when Save password is on.
 */
final class ConnectionScreen extends Composite implements Screen {
    private final Hub hub;
    private final MainPart main;
    private final Text host, port, user, database, password;
    private final Combo ssl;
    private final Button save, connect, disconnect;
    private final Text status;
    private final Label noSchema;
    private String lastLabel;

    ConnectionScreen(Composite parent, Hub hub, MainPart main) {
        super(parent, SWT.NONE);
        this.hub = hub;
        this.main = main;
        setLayout(Ui.grid(1, 20, 10));

        Composite column = new Composite(this, SWT.NONE);
        GridData cg = new GridData(SWT.CENTER, SWT.TOP, true, true);
        cg.widthHint = 560;
        column.setLayoutData(cg);
        column.setLayout(Ui.grid(1, 0, 10));

        Group form = new Group(column, SWT.NONE);
        form.setText("PostgreSQL Connection");
        form.setLayout(Ui.grid(2, 12, 8));
        form.setLayoutData(Ui.fillH());
        host = field(form, "Host", hub.host, SWT.NONE, "Host", "The server's name or address: localhost, a name on your network, or an IP address.");
        port = field(form, "Port", Integer.toString(hub.port), SWT.NONE, "Port", "The server's port; PostgreSQL's is 5432.");
        user = field(form, "User", hub.user, SWT.NONE, "User", "The PostgreSQL role to connect as.");
        database = field(form, "Database", hub.database, SWT.NONE, "Database",
                "The database that holds the security master (schema srm). Once connected, the Database drop-down in the toolbar switches to another one on the same server.");
        password = field(form, "Password", hub.password, SWT.PASSWORD, "Password",
                "The role's password. With Save password it is kept in Windows secure storage, encrypted for your Windows account.");
        Ui.label(form, "SSL");
        ssl = new Combo(form, SWT.READ_ONLY);
        ssl.setItems(PgSettings.SSL_MODES);
        ssl.setText(hub.sslMode);
        QuickHelp.help(ssl, "SSL Mode", "disable: a plain connection. prefer: encrypted when the server offers TLS, plain otherwise. "
                + "require: encrypted or no connection. The server's certificate isn't verified in any mode, as with libpq's prefer and require.");
        new Label(form, SWT.NONE);
        save = Ui.check(form, "Save password", hub.savePassword, on -> {
            hub.savePassword = on;
            hub.prefs.putBoolean("savePassword", on);
            if (!on) Pg.forgetPassword(LifeCycle.APP, currentSettings());
        });
        QuickHelp.help(save, "Save Password", "Keeps the password in Windows secure storage (in place of the macOS Keychain) so MxDataHUB "
                + "can connect without asking. Turning it off removes the saved one.");

        Composite buttons = new Composite(column, SWT.NONE);
        buttons.setLayout(Ui.grid(3, 0, 8));
        buttons.setLayoutData(Ui.fillH());
        connect = Ui.button(buttons, "Connect", main::connect);
        disconnect = Ui.button(buttons, "Disconnect", hub::disconnect);
        QuickHelp.help(disconnect, "Disconnect", "Closes the connection. Data Refresh and Services & Workflows still work: they go through the Layer 3 services.");
        status = new Text(buttons, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
        GridData sg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sg.widthHint = 300;
        status.setLayoutData(sg);
        QuickHelp.help(status, "Connection", "Whether MxDataHUB is connected, and to which PostgreSQL version — or why connecting failed. Select the text to copy it.");

        Label note = Ui.wrap(column, "The connection fields are remembered by MxDataHUB; the password only in Windows secure storage.");
        note.setForeground(Ui.SECONDARY);
        note.setFont(Ui.small());
        noSchema = Ui.wrap(column, "");
        noSchema.setForeground(Ui.ORANGE);
        noSchema.setFont(Ui.small());

        for (Text t : new Text[] { host, port, user, database }) t.addListener(SWT.Modify, e -> fieldsChanged());
        for (Text t : new Text[] { host, port, user, database, password }) t.addListener(SWT.DefaultSelection, e -> main.connect());
        ssl.addListener(SWT.Selection, e -> fieldsChanged());
        password.addListener(SWT.Modify, e -> hub.password = password.getText());
        lastLabel = currentSettings().label();
        hubChanged();
    }

    private Text field(Composite parent, String label, String value, int style, String helpTitle, String help) {
        Ui.label(parent, label);
        Text t = new Text(parent, SWT.BORDER | SWT.SINGLE | style);
        t.setText(value == null ? "" : value);
        t.setLayoutData(Ui.fillH());
        QuickHelp.help(t, helpTitle, help);
        return t;
    }

    private PgSettings currentSettings() {
        int p;
        try {
            p = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            p = 5432;
        }
        return new PgSettings(host.getText(), p, user.getText(), database.getText(), ssl.getText());
    }

    /** Copies the fields to the hub (Connect reads them). */
    void readFields() {
        PgSettings s = currentSettings();
        hub.host = host.getText().trim();
        hub.port = s.port();
        hub.user = user.getText().trim();
        hub.database = database.getText().trim();
        hub.sslMode = s.sslMode();
        hub.password = password.getText();
    }

    /** Other fields may have their own saved password. */
    private void fieldsChanged() {
        readFields();
        String label = currentSettings().label();
        if (!label.equals(lastLabel)) {
            lastLabel = label;
            if (hub.savePassword) {
                Pg.savedPassword(LifeCycle.APP, currentSettings()).ifPresent(saved -> {
                    if (!saved.equals(password.getText())) password.setText(saved);
                });
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        connect.setText(hub.isConnected() ? "Reconnect" : "Connect");
        connect.setEnabled(hub.status != Hub.Status.CONNECTING && !host.getText().isBlank() && !user.getText().isBlank());
        disconnect.setEnabled(hub.isConnected());
        QuickHelp.help(connect, hub.isConnected() ? "Reconnect" : "Connect",
                "Connects with these fields and checks the database for the security master; then Securities opens.", "Ctrl+Shift+K");
        connect.getParent().layout(true);
    }

    @Override
    public void hubChanged() {
        if (isDisposed()) return;
        // The Database drop-down may have switched databases.
        if (hub.isConnected() && hub.connected != null && !database.getText().trim().equals(hub.database)) {
            database.setText(hub.database);
        }
        String text = switch (hub.status) {
            case DISCONNECTED -> "";
            case CONNECTING -> "Connecting…";
            case CONNECTED -> "✓ Connected — PostgreSQL " + hub.serverVersion;
            case FAILED -> "⚠ " + hub.failure;
        };
        status.setText(text);
        status.setForeground(hub.status == Hub.Status.CONNECTED ? Ui.GREEN : hub.status == Hub.Status.FAILED ? Ui.RED : Ui.SECONDARY);
        status.setBackground(getBackground());
        boolean missing = hub.isConnected() && Boolean.FALSE.equals(hub.available);
        noSchema.setText(missing ? Ui.escape("Database " + (hub.connected == null ? "" : hub.connected.database())
                + " has no srm schema. Pick the database that has it in the Database drop-down, or create it with SRM/srm.py migrate and load it with SRM/srm.py load all.") : "");
        Ui.setVisible(noSchema, missing);
        updateButtons();
        layout(true, true);
    }

    @Override
    public void shown() {
        updateButtons();
        host.setFocus();
    }

    @Override
    public Control control() { return this; }
}
