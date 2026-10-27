package com.k3jdr.mx.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import com.k3jdr.mx.core.secure.Secrets;

/**
 * PostgreSQL through pgjdbc, in place of the macOS apps' PostgresNIO. The driver is called directly (not through
 * DriverManager), which is what works under OSGi.
 */
public final class Pg {
    private Pg() {}

    /** Opens a connection; {@code appName} shows in pg_stat_activity. */
    public static Connection connect(PgSettings s, String password, String appName) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", s.user());
        if (password != null && !password.isEmpty()) p.setProperty("password", password);
        p.setProperty("ApplicationName", appName);
        p.setProperty("connectTimeout", "10");
        p.setProperty("tcpKeepAlive", "true");
        p.setProperty("sslmode", s.sslMode());
        if (!"disable".equals(s.sslMode())) p.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        Connection c = new org.postgresql.Driver().connect(s.url(), p);
        if (c == null) throw new SQLException("The PostgreSQL driver did not accept " + s.url());
        return c;
    }

    /** The databases on the server this user may connect to. */
    public static List<String> databases(Connection c) throws SQLException {
        return column(c, """
                SELECT datname FROM pg_database
                WHERE datallowconn AND NOT datistemplate AND has_database_privilege(datname, 'CONNECT')
                ORDER BY datname""");
    }

    public static boolean schemaExists(Connection c, String schema) throws SQLException {
        return !column(c, "SELECT nspname FROM pg_namespace WHERE nspname = ?", schema).isEmpty();
    }

    /** "name" with embedded quotes doubled. */
    public static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** 'text' with embedded quotes doubled. */
    public static String literal(String text) {
        return text == null ? "NULL" : "'" + text.replace("'", "''") + "'";
    }

    /** The first column of every row, as text. */
    public static List<String> column(Connection c, String sql, Object... params) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** Every row as a map of column label to value (as JDBC returns it). */
    public static List<Map<String, Object>> rows(Connection c, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                out.add(row);
            }
        }
        return out;
    }

    /** Every row as text, column by column (NULL as null). */
    public static List<String[]> textRows(Connection c, String sql, Object... params) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                String[] row = new String[n];
                for (int i = 0; i < n; i++) row[i] = rs.getString(i + 1);
                out.add(row);
            }
        }
        return out;
    }

    /** Runs one statement that changes data, committed on its own; returns the update count. */
    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    public static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        return ps;
    }

    /** The password saved for these settings by {@code app}, if any. */
    public static Optional<String> savedPassword(String app, PgSettings s) {
        return Secrets.get(Secrets.path(app, s.label()), "password");
    }

    public static boolean savePassword(String app, PgSettings s, String password) {
        return Secrets.put(Secrets.path(app, s.label()), "password", password);
    }

    public static void forgetPassword(String app, PgSettings s) {
        Secrets.remove(Secrets.path(app, s.label()), "password");
    }

    /** A message like psql's: the server's text, its detail and hint when there are any. */
    public static String describe(SQLException e) {
        if (e instanceof org.postgresql.util.PSQLException pe && pe.getServerErrorMessage() != null) {
            var m = pe.getServerErrorMessage();
            StringBuilder sb = new StringBuilder("ERROR:  ").append(m.getMessage());
            if (m.getDetail() != null) sb.append("\nDETAIL:  ").append(m.getDetail());
            if (m.getHint() != null) sb.append("\nHINT:  ").append(m.getHint());
            return sb.toString();
        }
        return e.getMessage();
    }
}
