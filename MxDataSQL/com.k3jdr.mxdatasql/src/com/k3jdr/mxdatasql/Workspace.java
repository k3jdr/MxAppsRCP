package com.k3jdr.mxdatasql;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.k3jdr.mx.core.db.PgSettings;
import com.k3jdr.mx.core.json.AppleJson;

/**
 * A workspace, MxDataSQL's document (*.mxworkspace): the connection parameters, the last opened table and the grid
 * view settings, as the macOS app's WorkspaceDocument.swift writes them (JSONEncoder, pretty-printed, sorted keys).
 * The password is never written to the file.
 */
public final class Workspace {
    public static final String EXTENSION = "mxworkspace";

    /** The connection as the DB Connection form enters it. */
    public static final class Connection {
        public String host = "localhost";
        public int port = 5432;
        public String user = System.getProperty("user.name", "postgres");
        public String database = "postgres";
        /** disable, prefer or require (no certificate verification, like libpq). */
        public String sslMode = "prefer";
        /** "Save password" (in secure storage; the key keeps the macOS name). */
        public boolean savePasswordInKeychain = true;

        public Connection copy() {
            Connection c = new Connection();
            c.host = host; c.port = port; c.user = user; c.database = database; c.sslMode = sslMode;
            c.savePasswordInKeychain = savePasswordInKeychain;
            return c;
        }

        public PgSettings pg() { return new PgSettings(host, port, user, database, sslMode); }

        /** user@host:port/database: where the saved password is kept. */
        public String account() { return user + "@" + host + ":" + port + "/" + database; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Connection c && c.port == port && c.savePasswordInKeychain == savePasswordInKeychain
                    && Objects.equals(c.host, host) && Objects.equals(c.user, user)
                    && Objects.equals(c.database, database) && Objects.equals(c.sslMode, sslMode);
        }

        @Override
        public int hashCode() { return Objects.hash(host, port, user, database, sslMode); }
    }

    /** Rows and columns the grid shows, the rows fetched per page and the column widths the user set. */
    public static final class ViewSettings {
        public int visibleRows = 10;
        public int visibleColumns = 3;
        public int fetchLimit = 1000;
        /** Table ("schema.name", or "(query)") → column → width. */
        public Map<String, Map<String, Double>> columnWidths = new TreeMap<>();

        public ViewSettings copy() {
            ViewSettings v = new ViewSettings();
            v.visibleRows = visibleRows; v.visibleColumns = visibleColumns; v.fetchLimit = fetchLimit;
            columnWidths.forEach((k, m) -> v.columnWidths.put(k, new TreeMap<>(m)));
            return v;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ViewSettings v && v.visibleRows == visibleRows && v.visibleColumns == visibleColumns
                    && v.fetchLimit == fetchLimit && v.columnWidths.equals(columnWidths);
        }

        @Override
        public int hashCode() { return Objects.hash(visibleRows, visibleColumns, fetchLimit); }
    }

    public int formatVersion = 1;
    public Connection connection = new Connection();
    public TableRef selectedTable;
    public ViewSettings view = new ViewSettings();

    public Workspace copy() {
        Workspace w = new Workspace();
        w.formatVersion = formatVersion;
        w.connection = connection.copy();
        w.selectedTable = selectedTable;
        w.view = view.copy();
        return w;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Workspace w && w.formatVersion == formatVersion && w.connection.equals(connection)
                && Objects.equals(w.selectedTable, selectedTable) && w.view.equals(view);
    }

    @Override
    public int hashCode() { return Objects.hash(connection, selectedTable); }

    // MARK: Reading

    /** Reads a workspace; keys missing from files of earlier versions take their defaults. */
    public static Workspace read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Workspace w = new Workspace();
        w.formatVersion = intOr(root, "formatVersion", 1);
        if (root.get("connection") instanceof JsonObject c) {
            Connection d = w.connection;
            d.host = stringOr(c, "host", d.host);
            d.port = intOr(c, "port", d.port);
            d.user = stringOr(c, "user", d.user);
            d.database = stringOr(c, "database", d.database);
            d.sslMode = stringOr(c, "sslMode", d.sslMode);
            if (c.get("savePasswordInKeychain") instanceof JsonPrimitive p && p.isBoolean()) d.savePasswordInKeychain = p.getAsBoolean();
        }
        if (root.get("selectedTable") instanceof JsonObject t && t.has("schema") && t.has("name")) {
            w.selectedTable = new TableRef(t.get("schema").getAsString(), t.get("name").getAsString());
        }
        if (root.get("view") instanceof JsonObject v) {
            w.view.visibleRows = intOr(v, "visibleRows", 10);
            w.view.visibleColumns = intOr(v, "visibleColumns", 3);
            w.view.fetchLimit = intOr(v, "fetchLimit", 1000);
            if (v.get("columnWidths") instanceof JsonObject widths) {
                for (var table : widths.entrySet()) {
                    if (!(table.getValue() instanceof JsonObject cols)) continue;
                    Map<String, Double> m = new TreeMap<>();
                    for (var col : cols.entrySet()) {
                        if (col.getValue() instanceof JsonPrimitive p && p.isNumber()) m.put(col.getKey(), p.getAsDouble());
                    }
                    w.view.columnWidths.put(table.getKey(), m);
                }
            }
        }
        return w;
    }

    private static int intOr(JsonObject o, String key, int def) {
        return o.get(key) instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : def;
    }

    private static String stringOr(JsonObject o, String key, String def) {
        return o.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : def;
    }

    // MARK: Writing

    /** The file's text, as the macOS app writes it. */
    public String write() {
        JsonObject root = new JsonObject();
        JsonObject c = new JsonObject();
        c.addProperty("host", connection.host);
        c.addProperty("port", connection.port);
        c.addProperty("user", connection.user);
        c.addProperty("database", connection.database);
        c.addProperty("sslMode", connection.sslMode);
        c.addProperty("savePasswordInKeychain", connection.savePasswordInKeychain);
        root.add("connection", c);
        root.addProperty("formatVersion", formatVersion);
        if (selectedTable != null) {
            JsonObject t = new JsonObject();
            t.addProperty("schema", selectedTable.schema());
            t.addProperty("name", selectedTable.name());
            root.add("selectedTable", t);
        }
        JsonObject v = new JsonObject();
        v.addProperty("visibleRows", view.visibleRows);
        v.addProperty("visibleColumns", view.visibleColumns);
        v.addProperty("fetchLimit", view.fetchLimit);
        JsonObject widths = new JsonObject();
        view.columnWidths.forEach((table, cols) -> {
            JsonObject o = new JsonObject();
            cols.forEach(o::addProperty);
            widths.add(table, o);
        });
        v.add("columnWidths", widths);
        root.add("view", v);
        // JSONEncoder's .prettyPrinted + .sortedKeys layout, slashes escaped, as the Mac app writes a workspace.
        return AppleJson.write(root, AppleJson.Format.PRETTY_SORTED_ESCAPED);
    }
}
