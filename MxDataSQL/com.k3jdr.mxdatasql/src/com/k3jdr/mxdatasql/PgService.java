package com.k3jdr.mxdatasql;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.postgresql.PGResultSetMetaData;

import com.k3jdr.mx.core.db.Pg;
import com.k3jdr.mxdatasql.Catalog.ColumnInfo;
import com.k3jdr.mxdatasql.Catalog.RoutineSummary;
import com.k3jdr.mxdatasql.Catalog.TableDescription;
import com.k3jdr.mxdatasql.Catalog.TableSummary;
import com.k3jdr.mxdatasql.GridFilters.DistinctValue;
import com.k3jdr.mxdatasql.GridFilters.GridQueryOptions;
import com.k3jdr.mxdatasql.GridFilters.SqlBinds;

/**
 * One PostgreSQL connection per workspace or table window (PostgresService.swift). Calls are serialized (the Swift
 * actor), so work started from several places never interleaves on the connection. Catalog queries select text, so
 * every row comes back as {@code String[]}.
 */
public final class PgService {
    public static final String APP = "MxDataSQL";
    static final String QUERY_PREFIX = "WITH mx_q AS (\n";

    private final Connection connection;
    private int preflightCounter;

    /** The statement tried to change data inside a READ ONLY transaction (SQLSTATE 25006). */
    public static final class WriteNotAllowed extends Exception {
        private static final long serialVersionUID = 1L;
    }

    /** A result column: its name, the table column it comes from (base names; empty when computed) and its type. */
    public record QueryColumn(String name, String baseSchema, String baseTable, String baseColumn, String type) {}

    public record QueryResult(List<QueryColumn> columns, List<String[]> rows, boolean hasMore) {}

    public record UpdateOutcome(int applied, List<String> failures) {}

    public record Distinct(List<DistinctValue> values, boolean truncated) {}

    private PgService(Connection connection) {
        this.connection = connection;
    }

    public static PgService connect(Workspace.Connection settings, String password) throws DatabaseError {
        try {
            return new PgService(Pg.connect(settings.pg(), password, APP));
        } catch (SQLException e) {
            throw DatabaseError.from(e);
        }
    }

    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // closing a broken connection
        }
    }

    // MARK: Plain queries

    /** Runs {@code sql} with text parameters ($1 …) and returns every row as text values. */
    public synchronized List<String[]> rows(String sql, String... binds) throws DatabaseError {
        try (PreparedStatement ps = prepare(sql, Arrays.asList(binds)); ResultSet rs = ps.executeQuery()) {
            return readAll(rs);
        } catch (SQLException e) {
            throw DatabaseError.from(e);
        }
    }

    private PreparedStatement prepare(String sql, List<String> binds) throws SQLException {
        Sql.Jdbc j = Sql.toJdbc(sql);
        PreparedStatement ps = connection.prepareStatement(j.sql());
        for (int k = 0; k < j.params().length; k++) {
            int n = j.params()[k];
            String v = n >= 1 && n <= binds.size() ? binds.get(n - 1) : null;
            if (v == null) ps.setNull(k + 1, Types.VARCHAR); else ps.setString(k + 1, v);
        }
        return ps;
    }

    private static List<String[]> readAll(ResultSet rs) throws SQLException {
        int n = rs.getMetaData().getColumnCount();
        List<String[]> out = new ArrayList<>();
        while (rs.next()) {
            String[] row = new String[n];
            for (int i = 0; i < n; i++) row[i] = rs.getString(i + 1);
            out.add(row);
        }
        return out;
    }

    private static String first(List<String[]> rows) {
        return rows.isEmpty() || rows.get(0).length == 0 ? null : rows.get(0)[0];
    }

    /** Runs {@code work} in a transaction (READ ONLY when asked), committed if it succeeds, else rolled back. */
    private <T> T inTransaction(boolean readOnly, SqlWork<T> work) throws Exception {
        connection.setAutoCommit(false);
        try {
            if (readOnly) {
                try (Statement s = connection.createStatement()) {
                    s.execute("SET TRANSACTION READ ONLY");
                }
            }
            T result = work.run();
            connection.commit();
            return result;
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // the connection is gone; the error says why
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // as above
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws Exception;
    }

    // MARK: Server

    public synchronized List<String> databases() throws DatabaseError {
        List<String> out = new ArrayList<>();
        for (String[] r : rows("""
                SELECT datname::text FROM pg_database
                WHERE datallowconn AND NOT datistemplate AND has_database_privilege(datname, 'CONNECT')
                ORDER BY 1""")) out.add(r[0]);
        return out;
    }

    public synchronized String serverVersion() throws DatabaseError {
        String v = first(rows("SELECT current_setting('server_version')"));
        return v == null ? "?" : v;
    }

    // MARK: DB Table Selection

    /** Tables, partitioned tables, views and materialized views; a plain view has no estimate (0) and no size. */
    public synchronized List<TableSummary> listTables() throws DatabaseError {
        List<TableSummary> out = new ArrayList<>();
        for (String[] r : rows("""
                SELECT c.oid::text, n.nspname::text, c.relname::text,
                       CASE c.relkind WHEN 'p' THEN 'partitioned table' WHEN 'v' THEN 'view'
                                      WHEN 'm' THEN 'materialized view' ELSE 'table' END,
                       (CASE WHEN c.relkind = 'v' THEN 0
                             WHEN c.reltuples >= 0 THEN c.reltuples::bigint
                             ELSE coalesce(s.n_live_tup, 0) END)::text,
                       CASE WHEN c.relkind = 'v' THEN '' ELSE pg_size_pretty(pg_total_relation_size(c.oid)) END
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid
                WHERE c.relkind IN ('r', 'p', 'v', 'm')
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND n.nspname NOT LIKE 'pg_toast%'
                ORDER BY 2, 3""")) {
            out.add(new TableSummary(r[0], new TableRef(r[1], r[2]), r[3], parseLong(r[4]), r[5] == null ? "" : r[5]));
        }
        return out;
    }

    /** Functions and procedures like psql's \df, without extensions' functions. Needs PostgreSQL 11 (prokind). */
    public synchronized List<RoutineSummary> listRoutines() throws DatabaseError {
        List<RoutineSummary> out = new ArrayList<>();
        for (String[] r : rows("""
                SELECT p.oid::text, n.nspname::text, p.proname::text,
                       CASE p.prokind WHEN 'p' THEN 'procedure' ELSE 'function' END,
                       pg_get_function_identity_arguments(p.oid),
                       CASE WHEN p.prokind = 'f' THEN pg_get_function_result(p.oid) ELSE '' END,
                       l.lanname::text
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                JOIN pg_language l ON l.oid = p.prolang
                WHERE p.prokind IN ('f', 'p')
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND n.nspname NOT LIKE 'pg_toast%'
                  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                                  WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
                ORDER BY 4, 2, 3, 5""")) {
            out.add(new RoutineSummary(r[0], r[1], r[2], r[3], nz(r[4]), nz(r[5]), nz(r[6])));
        }
        return out;
    }

    /** The CREATE OR REPLACE statement psql's \sf shows; null when it no longer exists. */
    public synchronized String routineCode(RoutineSummary routine) throws DatabaseError {
        return first(rows("SELECT pg_get_functiondef($1::text::oid)", routine.oid()));
    }

    public synchronized long exactRowCount(TableRef ref) throws DatabaseError {
        return parseLong(first(rows("SELECT count(*)::text FROM " + ref.qualifiedSQL())));
    }

    /** Catalog queries equivalent to psql's \d table; for a view also its definition (\d+ view). */
    public synchronized TableDescription describe(TableSummary summary) throws DatabaseError {
        String oid = summary.oid;
        List<ColumnInfo> columns = new ArrayList<>();
        for (String[] r : rows("""
                SELECT a.attname::text, format_type(a.atttypid, a.atttypmod),
                       coalesce(co.collname::text, ''), a.attnotnull::text,
                       CASE WHEN a.attidentity = 'a' THEN 'generated always as identity'
                            WHEN a.attidentity = 'd' THEN 'generated by default as identity'
                            WHEN a.attgenerated = 's' THEN 'generated always as (' || pg_get_expr(d.adbin, d.adrelid, true) || ') stored'
                            ELSE coalesce(pg_get_expr(d.adbin, d.adrelid, true), '') END,
                       a.attnum::text
                FROM pg_attribute a
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                LEFT JOIN pg_type t ON t.oid = a.atttypid
                LEFT JOIN pg_collation co ON co.oid = a.attcollation AND a.attcollation <> t.typcollation
                WHERE a.attrelid = $1::text::oid AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum""", oid)) {
            columns.add(new ColumnInfo(r[0], r[1], nz(r[2]), "true".equals(r[3]), nz(r[4]), (int) parseLong(r[5])));
        }
        List<String> primaryKey = column(rows("""
                SELECT a.attname::text
                FROM pg_index i
                CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                WHERE i.indrelid = $1::text::oid AND i.indisprimary
                ORDER BY k.ord""", oid));
        List<String> indexes = column(rows("""
                SELECT '"' || c2.relname || '"'
                       || CASE WHEN i.indisprimary THEN ' PRIMARY KEY,'
                               WHEN con.contype = 'u' THEN ' UNIQUE CONSTRAINT,'
                               WHEN i.indisunique THEN ' UNIQUE,' ELSE '' END
                       || ' ' || substring(pg_get_indexdef(i.indexrelid, 0, true) FROM ' USING (.*)$')
                FROM pg_index i
                JOIN pg_class c2 ON c2.oid = i.indexrelid
                LEFT JOIN pg_constraint con ON con.conindid = i.indexrelid AND con.conrelid = i.indrelid AND con.contype IN ('p', 'u', 'x')
                WHERE i.indrelid = $1::text::oid
                ORDER BY i.indisprimary DESC, c2.relname""", oid));
        List<String> checks = column(rows("""
                SELECT '"' || conname || '" ' || pg_get_constraintdef(oid, true)
                FROM pg_constraint WHERE conrelid = $1::text::oid AND contype = 'c' ORDER BY conname""", oid));
        List<String> foreignKeys = column(rows("""
                SELECT '"' || conname || '" ' || pg_get_constraintdef(oid, true)
                FROM pg_constraint WHERE conrelid = $1::text::oid AND contype = 'f' ORDER BY conname""", oid));
        List<String> referencedBy = column(rows("""
                SELECT 'TABLE "' || conrelid::regclass::text || '" CONSTRAINT "' || conname || '" ' || pg_get_constraintdef(oid, true)
                FROM pg_constraint WHERE confrelid = $1::text::oid AND contype = 'f' ORDER BY 1""", oid));
        // quote_ident on the server knows which names are reserved words.
        List<String> names = new ArrayList<>(List.of(summary.schema(), summary.name()));
        names.addAll(primaryKey);
        StringBuilder q = new StringBuilder("SELECT ");
        for (int i = 0; i < names.size(); i++) q.append(i > 0 ? ", " : "").append("quote_ident($").append(i + 1).append(')');
        String[] quoted = rows(q.toString(), names.toArray(String[]::new)).get(0);
        StringBuilder defaultSQL = new StringBuilder("SELECT * FROM " + quoted[0] + "." + quoted[1]);
        if (quoted.length > 2) defaultSQL.append(" ORDER BY ").append(String.join(", ", Arrays.copyOfRange(quoted, 2, quoted.length)));
        String viewDefinition = summary.isView() ? first(rows("SELECT pg_get_viewdef($1::text::oid, true)", oid)) : null;
        return new TableDescription(summary, columns, primaryKey, indexes, checks, foreignKeys, referencedBy,
                defaultSQL.toString(), viewDefinition);
    }

    private static List<String> column(List<String[]> rows) {
        List<String> out = new ArrayList<>();
        for (String[] r : rows) if (r[0] != null) out.add(r[0]);
        return out;
    }

    // MARK: DB Table

    /**
     * One page of the table in primary-key (or ctid) order, sorted and filtered by {@code options}; totalRows is then
     * the filtered count and unfilteredTotal the table's. A view has no row IDs, so its grid is read-only.
     */
    public synchronized GridResult fetchGrid(TableDescription description, int limit, int offset, GridQueryOptions options)
            throws DatabaseError {
        TableRef ref = description.summary.ref;
        boolean withRowIDs = !description.summary.isView();
        List<String> select = new ArrayList<>();
        if (withRowIDs) select.add("t.ctid::text");
        for (ColumnInfo c : description.columns) select.add("t." + Sql.ident(c.name()) + "::text");
        UnaryOperator<String> expr = name -> "t." + Sql.ident(name);
        SqlBinds binds = new SqlBinds();
        String whereSQL = GridFilters.whereClause(options, description.columns, null, expr, binds);
        List<String> order = new ArrayList<>();
        String term = GridFilters.orderTerm(options, expr);
        if (term != null) order.add(term);
        if (!description.primaryKey.isEmpty()) {
            for (String k : description.primaryKey) order.add(expr.apply(k));
        } else if (!description.summary.isPlainView()) {
            order.add("t.ctid");
        }
        String from = ref.qualifiedSQL() + " AS t" + whereSQL;
        String orderSQL = order.isEmpty() ? "" : " ORDER BY " + String.join(", ", order);
        String[] params = binds.values.toArray(String[]::new);
        List<String[]> result = rows("SELECT " + String.join(", ", select) + " FROM " + from + orderSQL
                + " LIMIT " + Math.max(1, limit) + " OFFSET " + Math.max(0, offset), params);
        long unfiltered = exactRowCount(ref);
        long total = whereSQL.isEmpty() ? unfiltered : parseLong(first(rows("SELECT count(*)::text FROM " + from, params)));
        GridResult g = new GridResult();
        g.description = description;
        g.title = ref.displayName();
        g.columns = description.columns;
        g.rows = new ArrayList<>();
        for (String[] r : result) {
            if (withRowIDs) {
                g.rowIDs.add(r[0] == null ? "" : r[0]);
                g.rows.add(Arrays.copyOfRange(r, 1, r.length));
            } else {
                g.rows.add(r);
            }
        }
        g.totalRows = total;
        g.offset = Math.max(0, offset);
        g.options = options;
        g.unfilteredTotal = whereSQL.isEmpty() ? null : unfiltered;
        return g;
    }

    /**
     * Distinct values of {@code column} with row counts for the filter checklist, honouring the other columns'
     * filters. At most {@code limit} values; {@code truncated} when there are more.
     */
    public synchronized Distinct distinctValues(GridResult grid, String column, GridQueryOptions options, int limit)
            throws DatabaseError {
        boolean table = grid.customSQL == null;
        UnaryOperator<String> alias = name -> (table ? "t." : "mx_q.") + Sql.ident(name);
        SqlBinds binds = new SqlBinds();
        String whereSQL = GridFilters.whereClause(options, grid.columns, column, alias, binds);
        String c = alias.apply(column);
        String prefix = table ? "" : QUERY_PREFIX + grid.customSQL + "\n) ";
        String from = table ? grid.ref().qualifiedSQL() + " AS t" : "mx_q";
        String typed = prefix + "SELECT " + c + "::text, count(*)::text FROM " + from + whereSQL + " GROUP BY " + c
                + " ORDER BY " + c + " NULLS FIRST LIMIT " + (limit + 1);
        String asText = prefix + "SELECT " + c + "::text, count(*)::text FROM " + from + whereSQL
                + " GROUP BY 1 ORDER BY 1 NULLS FIRST LIMIT " + (limit + 1);
        List<String[]> result = null;
        DatabaseError last = null;
        for (String sql : new String[] { typed, asText }) {
            try {
                result = inTransaction(true, () -> {
                    try (PreparedStatement ps = prepare(sql, binds.values); ResultSet rs = ps.executeQuery()) {
                        return readAll(rs);
                    }
                });
                break;
            } catch (Exception e) {
                last = DatabaseError.from(e);
            }
        }
        if (result == null) throw last;
        List<DistinctValue> values = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, result.size()); i++) {
            values.add(new DistinctValue(result.get(i)[0], parseLong(result.get(i)[1])));
        }
        return new Distinct(values, result.size() > limit);
    }

    /**
     * Runs the planned UPDATEs. Each must touch exactly one row; otherwise its transaction is rolled back (the whole
     * batch with {@code singleTransaction}).
     */
    public synchronized UpdateOutcome apply(List<TableUpdate.Planned> updates, boolean singleTransaction) throws DatabaseError {
        if (singleTransaction) {
            try {
                inTransaction(false, () -> {
                    for (TableUpdate.Planned u : updates) {
                        int count = countRows(u);
                        if (count != 1) throw new DatabaseError(mismatch(u, count) + "\nNo changes were applied (rolled back).");
                    }
                    return null;
                });
                return new UpdateOutcome(updates.size(), List.of());
            } catch (Exception e) {
                throw DatabaseError.from(e);
            }
        }
        int applied = 0;
        List<String> failures = new ArrayList<>();
        for (TableUpdate.Planned u : updates) {
            try {
                int[] count = new int[1];
                inTransaction(false, () -> {
                    count[0] = countRows(u);
                    if (count[0] != 1) throw new MismatchException();
                    return null;
                });
                applied++;
            } catch (MismatchException m) {
                failures.add(mismatch(u, lastCount));
            } catch (Exception e) {
                failures.add("Row " + u.rowLabel() + ": " + DatabaseError.message(e));
            }
        }
        return new UpdateOutcome(applied, failures);
    }

    private int lastCount;

    private static final class MismatchException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private int countRows(TableUpdate.Planned u) throws SQLException {
        try (PreparedStatement ps = prepare(u.sql(), u.binds()); ResultSet rs = ps.executeQuery()) {
            int n = 0;
            while (rs.next()) n++;
            lastCount = n;
            return n;
        }
    }

    private static String mismatch(TableUpdate.Planned u, int count) {
        return count == 0 ? "Row " + u.rowLabel() + ": not found, or changed since it was loaded."
                : "Row " + u.rowLabel() + ": matches " + count + " rows; the row key is not unique.";
    }

    // MARK: Run SQL

    /** Result types of a statement parsed and analysed on the server without running it (PREPARE / DEALLOCATE). */
    public synchronized List<String> preflight(String sql) throws DatabaseError {
        preflightCounter++;
        String name = "mx_preflight_" + preflightCounter;
        String prefix = "PREPARE " + name + " AS ";
        try (Statement s = connection.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(prefix + sql);
        } catch (SQLException e) {
            throw DatabaseError.from(e).shifted(prefix.length());
        }
        List<String[]> info;
        try {
            info = rows("""
                    SELECT coalesce(array_length(parameter_types, 1), 0)::text, u.t::text
                    FROM pg_prepared_statements
                    LEFT JOIN LATERAL unnest(result_types) WITH ORDINALITY AS u(t, o) ON true
                    WHERE name = $1
                    ORDER BY u.o""", name);
        } finally {
            try (Statement s = connection.createStatement()) {
                s.execute("DEALLOCATE " + name);
            } catch (SQLException ignored) {
                // gone with the session
            }
        }
        if (!info.isEmpty() && info.get(0)[0] != null && !info.get(0)[0].equals("0")) {
            throw new DatabaseError("The statement uses parameters ($1 …). Replace them with values.");
        }
        List<String> types = new ArrayList<>();
        for (String[] r : info) if (r[1] != null) types.add(r[1]);
        return types;
    }

    /**
     * Runs a statement that returns rows and fetches at most {@code limit} of them. Without {@code allowWrites} it
     * runs in a READ ONLY transaction and throws WriteNotAllowed if the statement tries to change data.
     */
    public synchronized QueryResult runQuery(String sql, int limit, int offset, boolean allowWrites, GridQueryOptions options,
                                             List<ColumnInfo> filterColumns) throws DatabaseError, WriteNotAllowed {
        int max = Math.max(1, limit);
        List<QueryColumn> columns = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        streamQuery(sql, (long) max + 1, offset, allowWrites, options, filterColumns, columns::addAll, rows::add);
        boolean more = rows.size() > max;
        return new QueryResult(columns, more ? new ArrayList<>(rows.subList(0, max)) : rows, more);
    }

    /** Streams every row of the read-only query into a CSV file (header first); returns the data rows written. */
    public synchronized long exportCSV(String sql, Path file, GridQueryOptions options, List<ColumnInfo> filterColumns)
            throws DatabaseError, WriteNotAllowed, IOException {
        long[] count = new long[1];
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            IOException[] failure = new IOException[1];
            streamQuery(sql, null, 0, false, options, filterColumns, cols -> {
                List<String> names = new ArrayList<>();
                for (QueryColumn c : cols) names.add(c.name());
                write(w, TextFormats.csvLine(names), failure);
            }, row -> {
                write(w, TextFormats.csvLine(Arrays.asList(row)), failure);
                count[0]++;
            });
            if (failure[0] != null) throw failure[0];
        } catch (DatabaseError | WriteNotAllowed | IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return count[0];
    }

    private static void write(BufferedWriter w, String text, IOException[] failure) {
        if (failure[0] != null) return;
        try {
            w.write(text);
        } catch (IOException e) {
            failure[0] = e;
        }
    }

    /**
     * Runs {@code sql} wrapped in a CTE and hands over the column metadata, then each row as text values. Each row
     * comes back as its composite text form (mx_q::text), so every column is read as text whatever its type.
     */
    private void streamQuery(String sql, Long limit, int offset, boolean allowWrites, GridQueryOptions options,
                             List<ColumnInfo> filterColumns, Consumer<List<QueryColumn>> onColumns,
                             Consumer<String[]> onRow) throws DatabaseError, WriteNotAllowed {
        UnaryOperator<String> expr = name -> "mx_q." + Sql.ident(name);
        SqlBinds binds = new SqlBinds();
        String whereSQL = GridFilters.whereClause(options, filterColumns, null, expr, binds);
        String term = GridFilters.orderTerm(options, expr);
        String wrapped = QUERY_PREFIX + sql + "\n) SELECT mx_q::text, mx_q.* FROM mx_q" + whereSQL
                + (term == null ? "" : " ORDER BY " + term)
                + (limit == null ? "" : " LIMIT " + limit)
                + (offset > 0 ? " OFFSET " + offset : "");
        try {
            inTransaction(!allowWrites, () -> {
                try (PreparedStatement ps = prepare(wrapped, binds.values)) {
                    ps.setFetchSize(500);
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount() - 1;
                        PGResultSetMetaData pg = md.isWrapperFor(PGResultSetMetaData.class) ? md.unwrap(PGResultSetMetaData.class) : null;
                        List<QueryColumn> cols = new ArrayList<>();
                        List<Integer> boolColumns = new ArrayList<>();
                        for (int i = 2; i <= n + 1; i++) {
                            String type = md.getColumnTypeName(i);
                            if ("bool".equals(type)) boolColumns.add(i - 2);
                            cols.add(new QueryColumn(md.getColumnLabel(i),
                                    pg == null ? "" : nz(pg.getBaseSchemaName(i)), pg == null ? "" : nz(pg.getBaseTableName(i)),
                                    pg == null ? "" : nz(pg.getBaseColumnName(i)), type == null ? "" : type));
                        }
                        onColumns.accept(cols);
                        while (rs.next()) {
                            String[] values = TextFormats.parseRecord(rs.getString(1), n);
                            // Record output writes booleans as t/f; bool::text gives true/false.
                            for (int index : boolColumns) {
                                if ("t".equals(values[index])) values[index] = "true";
                                else if ("f".equals(values[index])) values[index] = "false";
                            }
                            onRow.accept(values);
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
            DatabaseError error = DatabaseError.from(e);
            if ("25006".equals(error.sqlState())) throw new WriteNotAllowed();
            throw error.shifted(QUERY_PREFIX.length());
        }
    }

    /** Runs a statement that returns no rows (INSERT / UPDATE / DELETE / MERGE) in its own transaction. */
    public synchronized void execute(String sql) throws DatabaseError {
        try {
            inTransaction(false, () -> {
                try (Statement s = connection.createStatement()) {
                    s.setEscapeProcessing(false);
                    s.execute(sql);
                }
                return null;
            });
        } catch (Exception e) {
            throw DatabaseError.from(e);
        }
    }

    /**
     * Runs a CALL, CREATE, ALTER or DROP as sent, outside an explicit transaction block (autocommit): a procedure may
     * COMMIT or ROLLBACK itself, and statements PostgreSQL refuses inside BEGIN … COMMIT work too.
     */
    public synchronized void call(String sql) throws DatabaseError {
        try (Statement s = connection.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(sql);
        } catch (SQLException e) {
            throw DatabaseError.from(e);
        }
    }

    static long parseLong(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
