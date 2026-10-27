package com.k3jdr.mxdatahub;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.db.Pg;
import com.k3jdr.mx.core.db.PgSettings;
import com.k3jdr.mx.core.json.Json;
import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mxdatahub.Rows.BenchmarkRow;
import com.k3jdr.mxdatahub.Rows.PortfolioRow;

/**
 * The window's state on the UI thread (SRMStore.swift's connection part): the DB Connection fields, the
 * connection's status, the lists several screens share (benchmarks, portfolios, sectors, security types), and the
 * busy count and last message the status bar shows. Queries run off the UI thread through {@link #run}.
 */
final class Hub {
    enum Status { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    final Db db = new Db();
    /** Links on tickers and names (srm.security_link), shared by Securities and Portfolios. */
    final Links links = new Links(this);
    final Prefs prefs = Prefs.of("com.k3jdr.mxdatahub");
    Shell shell;

    // The DB Connection fields (remembered; the password in secure storage when Save password is on).
    String host, user, database, sslMode;
    int port;
    boolean savePassword;
    String password = "";

    Status status = Status.DISCONNECTED;
    String serverVersion = "";
    String failure = "";
    /** Settings of the live connection (the fields may be edited afterwards). */
    PgSettings connected;
    /** null until checked; false when the database has no srm schema. */
    Boolean available;
    List<String> databases = List.of();
    List<String> securityTypes = List.of();
    List<String> sectors = List.of();
    List<BenchmarkRow> benchmarks = List.of();
    List<PortfolioRow> portfolios = List.of();
    /** The portfolio selected in Portfolios (Services & Workflows opens its workflows for it). */
    Integer selectedPortfolioId;

    String infoMessage;
    private int busy;
    private final List<Runnable> listeners = new ArrayList<>();

    Hub() {
        host = prefs.get("host", "localhost");
        port = prefs.getInt("port", 5432);
        user = prefs.get("user", "postgres");
        database = prefs.get("database", "srm");
        sslMode = prefs.get("sslMode", "disable");
        savePassword = prefs.getBoolean("savePassword", true);
    }

    PgSettings settings() { return new PgSettings(host, port, user, database, sslMode); }

    void saveSettings(PgSettings s) {
        prefs.put("host", s.host());
        prefs.putInt("port", s.port());
        prefs.put("user", s.user());
        prefs.put("database", s.database());
        prefs.put("sslMode", s.sslMode());
        prefs.putBoolean("savePassword", savePassword);
    }

    /** The password saved for the fields, when Save password is on and there is one. */
    void loadSavedPassword() {
        if (password.isEmpty() && savePassword) password = Pg.savedPassword(LifeCycle.APP, settings()).orElse("");
    }

    boolean isConnected() { return status == Status.CONNECTED; }
    boolean isBusy() { return busy > 0 || status == Status.CONNECTING; }

    void addListener(Runnable r) { listeners.add(r); }

    /** Tells the window something changed: the status bar, the menus, the screens. */
    void changed() {
        for (Runnable r : List.copyOf(listeners)) r.run();
    }

    void info(String message) {
        infoMessage = message;
        changed();
    }

    // MARK: Connection

    /** Connects with the DB Connection fields, then checks for the srm schema and loads the lists. */
    void connect(Consumer<Boolean> done) {
        connect(settings(), done);
    }

    private void connect(PgSettings target, Consumer<Boolean> done) {
        status = Status.CONNECTING;
        available = null;
        failure = "";
        changed();
        String pw = password;
        Background.run(shell, () -> db.connect(target, pw), c -> {
            status = Status.CONNECTED;
            links.reset();
            serverVersion = c.serverVersion();
            connected = target;
            saveSettings(target);
            if (savePassword && !pw.isEmpty()) Pg.savePassword(LifeCycle.APP, target, pw);
            databases = c.databases();
            available = c.available();
            securityTypes = c.securityTypes();
            benchmarks = List.of();
            portfolios = List.of();
            changed();
            if (c.available()) {
                refreshBenchmarks(() -> refreshPortfolios(() -> {
                    if (done != null) done.accept(true);
                }));
            } else if (done != null) {
                done.accept(true);
            }
        }, t -> {
            db.close();
            status = Status.FAILED;
            failure = ErrorDialog.describe(t);
            connected = null;
            changed();
            if (done != null) done.accept(false);
        });
    }

    void disconnect() {
        Background.execute(db::close);
        status = Status.DISCONNECTED;
        connected = null;
        available = null;
        benchmarks = List.of();
        portfolios = List.of();
        changed();
    }

    /**
     * Reconnects to another database on the same server with the same user and password. On failure the previous
     * database is reconnected and the error shown.
     */
    void switchDatabase(String name) {
        PgSettings current = connected;
        if (current == null || name == null || name.equals(current.database())) return;
        String previousField = database;
        connect(current.withDatabase(name), ok -> {
            if (ok) {
                database = name;
                changed();
            } else {
                String reason = failure;
                database = previousField;
                connect(current, again -> error("Could not connect to database " + name + ".\n\n" + reason));
            }
        });
    }

    // MARK: Shared lists

    void refreshBenchmarks(Runnable after) {
        if (!isConnected()) return;
        run(shell, db::benchmarks, b -> {
            benchmarks = b.benchmarks();
            sectors = b.sectors();
            changed();
            if (after != null) after.run();
        });
    }

    void refreshPortfolios(Runnable after) {
        if (!isConnected()) return;
        run(shell, db::portfolios, p -> {
            portfolios = p;
            if (selectedPortfolioId == null || p.stream().noneMatch(x -> x.id() == selectedPortfolioId)) {
                selectedPortfolioId = p.isEmpty() ? null : p.get(0).id();
            }
            changed();
            if (after != null) after.run();
        });
    }

    PortfolioRow selectedPortfolio() {
        if (selectedPortfolioId == null) return null;
        return portfolios.stream().filter(p -> p.id() == selectedPortfolioId).findFirst().orElse(null);
    }

    BenchmarkRow benchmark(Integer id) {
        if (id == null) return null;
        return benchmarks.stream().filter(b -> b.id() == id).findFirst().orElse(null);
    }

    // MARK: Work off the UI thread

    /** Runs {@code work} off the UI thread with the busy indicator on; a failure shows in the error dialog. */
    <T> void run(Control owner, Callable<T> work, Consumer<T> done) {
        run(owner, work, done, null);
    }

    <T> void run(Control owner, Callable<T> work, Consumer<T> done, Consumer<Throwable> failed) {
        busy++;
        changed();
        Control o = owner == null || owner.isDisposed() ? shell : owner;
        Background.run(o, work, r -> {
            busy = Math.max(0, busy - 1);
            changed();
            if (done != null) done.accept(r);
        }, t -> {
            busy = Math.max(0, busy - 1);
            changed();
            if (failed != null) failed.accept(t);
            else error(ErrorDialog.describe(t));
        });
    }

    /** Shows an error in the dialog whose text can be selected and copied (ErrorDialog.swift). */
    void error(String message) {
        error("MxDataHUB", message);
    }

    void error(String title, String message) {
        if (shell == null || shell.isDisposed()) return;
        ErrorDialog.show(shell, title, readable(message));
    }

    /** A service's answer {"error": "…"} as its message; anything else as it is. */
    static String readable(String text) {
        if (text == null) return "";
        try {
            JsonElement e = Json.parse(text);
            if (e != null && e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("error") && o.get("error").isJsonPrimitive()) return o.get("error").getAsString().trim();
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return text;
    }
}
