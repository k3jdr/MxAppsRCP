package com.k3jdr.mx.core.db;

/**
 * A PostgreSQL connection as the apps' DB Connection screens enter it. SSL mode is {@code disable},
 * {@code prefer} or {@code require}, with no certificate verification, like libpq.
 */
public record PgSettings(String host, int port, String user, String database, String sslMode) {

    public static final String[] SSL_MODES = { "disable", "prefer", "require" };

    public PgSettings {
        host = host == null || host.isBlank() ? "localhost" : host.trim();
        port = port <= 0 ? 5432 : port;
        user = user == null ? "" : user.trim();
        database = database == null || database.isBlank() ? "postgres" : database.trim();
        sslMode = sslMode == null || sslMode.isBlank() ? "prefer" : sslMode.trim();
    }

    public static PgSettings defaults() {
        return new PgSettings("localhost", 5432, System.getProperty("user.name", "postgres"), "postgres", "prefer");
    }

    public PgSettings withDatabase(String db) { return new PgSettings(host, port, user, db, sslMode); }

    public String url() { return "jdbc:postgresql://" + host + ":" + port + "/" + database; }

    /** user@host:port/database — the name a saved password goes under. */
    public String label() { return user + "@" + host + ":" + port + "/" + database; }
}
