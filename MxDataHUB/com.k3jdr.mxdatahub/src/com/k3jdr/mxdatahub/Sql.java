package com.k3jdr.mxdatahub;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * The macOS app's queries as they are written there: PostgreSQL's {@code $1 … $n} placeholders, each possibly used
 * more than once, every value sent as text and every column read as text (PostgresService.swift). pgjdbc only knows
 * {@code ?}, so the statement is rewritten with one {@code ?} per use and the binds repeated to match.
 */
final class Sql {
    private Sql() {}

    /** A statement rewritten for JDBC: the SQL with {@code ?} and, for each, the index of its bind. */
    record Prepared(String sql, int[] binds) {}

    /** Replaces {@code $n} outside quotes, identifiers and comments with {@code ?}. */
    static Prepared rewrite(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<Integer> order = new ArrayList<>();
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == c) {
                        if (j + 1 < n && sql.charAt(j + 1) == c) { j += 2; continue; }
                        break;
                    }
                    j++;
                }
                out.append(sql, i, Math.min(n, j + 1));
                i = j;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int j = sql.indexOf('\n', i);
                if (j < 0) j = n;
                out.append(sql, i, j);
                i = j - 1;
            } else if (c == '$' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))
                    && (i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1)) && sql.charAt(i - 1) != '_')) {
                int j = i + 1;
                while (j < n && Character.isDigit(sql.charAt(j))) j++;
                order.add(Integer.parseInt(sql.substring(i + 1, j)) - 1);
                out.append('?');
                i = j - 1;
            } else {
                out.append(c);
            }
        }
        return new Prepared(out.toString(), order.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Every row as text values (NULL as null); the values are bound as text. */
    static List<String[]> rows(Connection c, String sql, List<String> binds) throws SQLException {
        Prepared p = rewrite(sql);
        try (PreparedStatement ps = c.prepareStatement(p.sql())) {
            for (int i = 0; i < p.binds().length; i++) {
                int b = p.binds()[i];
                if (b < 0 || b >= binds.size()) throw new SQLException("No value for $" + (b + 1));
                String v = binds.get(b);
                if (v == null) ps.setNull(i + 1, Types.VARCHAR);
                else ps.setString(i + 1, v);
            }
            List<String[]> out = new ArrayList<>();
            boolean hasResult = ps.execute();
            while (true) {
                if (hasResult) {
                    try (ResultSet rs = ps.getResultSet()) {
                        int cols = rs.getMetaData().getColumnCount();
                        while (rs.next()) {
                            String[] row = new String[cols];
                            for (int k = 0; k < cols; k++) row[k] = rs.getString(k + 1);
                            out.add(row);
                        }
                    }
                } else if (ps.getUpdateCount() == -1) {
                    break;
                }
                hasResult = ps.getMoreResults();
            }
            return out;
        }
    }

    /** A growing list of binds for statements built from parts (the search's column filters). */
    static final class Binds {
        final List<String> values = new ArrayList<>();

        Binds(String... initial) {
            for (String s : initial) values.add(s);
        }

        /** Adds a value and answers its placeholder, e.g. {@code $5}. */
        String add(String value) {
            values.add(value);
            return "$" + values.size();
        }
    }
}
