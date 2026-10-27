package com.k3jdr.mxdatahub;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.k3jdr.mx.core.db.Pg;
import com.k3jdr.mx.core.db.PgSettings;
import com.k3jdr.mxdatahub.Rows.*;

/**
 * The security master's queries (SRMStore.swift), one connection, statements one at a time. Every method runs on
 * the calling thread: call them off the UI thread. Every change is one statement, committed on its own (the
 * connection is in autocommit).
 */
final class Db {
    static final int SECURITY_PAGE_SIZE = 1000;

    /** The results' columns that sort and filter: column title → v_security column. */
    static final Map<String, String> SECURITY_COLUMNS = new LinkedHashMap<>();
    static {
        SECURITY_COLUMNS.put("Ticker", "v.ticker");
        SECURITY_COLUMNS.put("Name", "v.name");
        SECURITY_COLUMNS.put("Type", "v.security_type");
        SECURITY_COLUMNS.put("ISIN", "v.isin");
        SECURITY_COLUMNS.put("CUSIP", "v.cusip");
        SECURITY_COLUMNS.put("Composite FIGI", "v.composite_figi");
        SECURITY_COLUMNS.put("Sector", "v.sector");
        SECURITY_COLUMNS.put("MIC", "v.mic");
    }

    static final List<String> IDENTIFIER_TYPES = List.of("Auto", "ISIN", "CUSIP", "SEDOL", "FIGI", "COMPOSITE_FIGI",
            "SHARE_CLASS_FIGI", "TICKER", "FMP_SYMBOL", "CIK", "LEI");

    private Connection connection;

    // MARK: Connection

    /** The result of connecting: the server, its databases, and whether the database has the security master. */
    record Connected(String serverVersion, List<String> databases, boolean available, List<String> securityTypes) {}

    synchronized Connected connect(PgSettings s, String password) throws SQLException {
        close();
        Connection c = Pg.connect(s, password, LifeCycle.APP);
        c.setAutoCommit(true);
        connection = c;
        String version = scalar("SELECT current_setting('server_version')");
        List<String> dbs;
        try {
            dbs = new ArrayList<>(Pg.databases(c));
        } catch (SQLException e) {
            dbs = new ArrayList<>();
        }
        if (!dbs.contains(s.database())) {
            dbs.add(s.database());
            dbs.sort(String::compareTo);
        }
        boolean available = "true".equals(scalar("SELECT (to_regclass('srm.security') IS NOT NULL)::text"));
        List<String> types = available ? column("SELECT security_type FROM srm.security_type ORDER BY 1") : List.of();
        return new Connected(version == null ? "?" : version, dbs, available, types);
    }

    synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closing a broken connection
            }
        }
        connection = null;
    }

    synchronized boolean isOpen() { return connection != null; }

    synchronized List<String[]> rows(String sql, String... binds) throws SQLException {
        return rows(sql, Arrays.asList(binds));
    }

    synchronized List<String[]> rows(String sql, List<String> binds) throws SQLException {
        if (connection == null) throw new SQLException("Not connected.");
        return Sql.rows(connection, sql, binds);
    }

    String scalar(String sql, String... binds) throws SQLException {
        List<String[]> r = rows(sql, binds);
        return r.isEmpty() || r.get(0).length == 0 ? null : r.get(0)[0];
    }

    List<String> column(String sql, String... binds) throws SQLException {
        List<String> out = new ArrayList<>();
        for (String[] r : rows(sql, binds)) if (r.length > 0 && r[0] != null) out.add(r[0]);
        return out;
    }

    // MARK: Securities

    /** The search fields: the search box, Type, In (benchmark) and Active only, and the column sort and filters. */
    record SecuritySearch(String query, String type, String benchmark, boolean activeOnly, GridOptions options, int offset) {}

    record SecurityPage(List<SecurityRow> rows, int total, int offset) {}

    private static final String RESOLVED_MATCHES =
            "WITH m AS (SELECT security_id, min(rank) AS rank FROM srm.resolve_identifier($1) GROUP BY 1)";

    private static Sql.Binds searchBinds(SecuritySearch q) {
        return new Sql.Binds(q.query().trim(), q.type(), q.benchmark(), q.activeOnly() ? "true" : "false");
    }

    /** FROM and WHERE of the search: the search fields and the column filters, except {@code exceptColumn}'s. */
    private static String securitySource(SecuritySearch q, String exceptColumn, Sql.Binds binds) {
        String filters = GridOptions.conditions(q.options(), SECURITY_COLUMNS, exceptColumn, binds);
        return """
                  FROM srm.v_security v
                  LEFT JOIN m ON m.security_id = v.security_id
                 WHERE ($1 = '' OR m.security_id IS NOT NULL OR v.name ILIKE '%' || $1 || '%'
                        OR v.issuer_name ILIKE '%' || $1 || '%')
                   AND ($2 = 'All' OR v.security_type = $2)
                   AND ($3 = '' OR v.security_id IN (
                        SELECT w.security_id FROM srm.benchmark b, srm.benchmark_weights(b.benchmark_id) w WHERE b.code = $3))
                   AND (NOT $4::bool OR v.is_active)
                """ + (filters.isEmpty() ? "" : "\n   AND " + filters);
    }

    /** One page of the results: the column sort first, then the best identifier matches, an exact ticker, the name. */
    SecurityPage searchSecurities(SecuritySearch q) throws SQLException {
        Sql.Binds binds = searchBinds(q);
        String source = securitySource(q, null, binds);
        List<String> order = new ArrayList<>();
        String sort = GridOptions.orderTerm(q.options(), SECURITY_COLUMNS);
        if (sort != null) order.add(sort);
        order.addAll(List.of("m.rank NULLS LAST", "(v.ticker = upper($1)) DESC NULLS LAST", "v.name", "v.security_id"));
        List<String[]> r = rows(RESOLVED_MATCHES + """

                SELECT v.security_id::text, coalesce(v.ticker, ''), v.name, v.security_type, coalesce(v.isin, ''),
                       coalesce(v.cusip, ''), coalesce(v.composite_figi, ''), coalesce(v.sector, ''),
                       coalesce(v.mic::text, ''), v.is_active::text, count(*) OVER ()::text
                """ + source + "\n ORDER BY " + String.join(", ", order)
                + "\n LIMIT " + SECURITY_PAGE_SIZE + " OFFSET " + Math.max(0, q.offset()), binds.values);
        int total = r.isEmpty() ? 0 : Rows.n(r.get(0), 10);
        return new SecurityPage(r.stream().map(SecurityRow::of).toList(), total, q.offset());
    }

    record DistinctValue(String value, int count) {}

    /** A column's distinct values with their counts, for its filter panel (the other columns' filters applied). */
    List<DistinctValue> securityValues(SecuritySearch q, String column) throws SQLException {
        String expr = SECURITY_COLUMNS.get(column);
        if (expr == null) return List.of();
        Sql.Binds binds = searchBinds(q);
        String source = securitySource(q, column, binds);
        List<String[]> r = rows(RESOLVED_MATCHES + "\nSELECT " + expr + "::text, count(*)::text\n" + source
                + "\n GROUP BY 1 ORDER BY 1 NULLS FIRST LIMIT 1001", binds.values);
        return r.stream().limit(1000).map(x -> new DistinctValue(x[0], Rows.n(x, 1))).toList();
    }

    SecurityDetail securityDetail(long id) throws SQLException {
        String sid = Long.toString(id);
        List<String[]> head = rows("""
                SELECT v.security_id::text, coalesce(v.ticker, ''), v.name, v.security_type, coalesce(v.isin, ''),
                       coalesce(v.cusip, ''), coalesce(v.composite_figi, ''), coalesce(v.sector, ''),
                       coalesce(v.mic::text, ''), v.is_active::text
                  FROM srm.v_security v WHERE v.security_id = $1::text::bigint
                """, sid);
        if (head.isEmpty()) return null;
        SecurityRow security = SecurityRow.of(head.get(0));
        List<NameValue> facts = rows("""
                SELECT k, v FROM srm.security s
                LEFT JOIN srm.issuer i USING (issuer_id)
                LEFT JOIN srm.exchange e ON e.mic = s.primary_mic
                CROSS JOIN LATERAL (VALUES
                    (1, 'Issuer', i.name), (2, 'LEI', i.lei), (3, 'CIK', i.cik),
                    (4, 'Entity status', i.entity_status), (5, 'Legal form (ELF)', i.legal_form),
                    (6, 'Country', coalesce(s.country_code, i.country_code)), (7, 'Incorporation', i.incorporation),
                    (8, 'SIC', i.sic_code || ' ' || coalesce(i.sic_description, '')), (9, 'EIN', i.ein),
                    (10, 'Primary exchange', s.primary_mic || ' ' || coalesce(e.name, '')),
                    (11, 'Currency', s.currency_code), (12, 'Website', i.website),
                    (13, 'Last price', (SELECT to_char(lp.close, 'FM999,999,990.00') || ' on ' || lp.price_date
                                          FROM srm.last_price(s.security_id) lp)),
                    (14, 'Market cap', (SELECT to_char(md.market_cap, 'FM999,999,999,999,990') || ' (' || md.as_of_date || ')'
                                          FROM srm.security_market_data md WHERE md.security_id = s.security_id
                                         ORDER BY md.as_of_date DESC LIMIT 1)),
                    (15, 'Status', CASE WHEN s.is_active THEN 'Active' ELSE 'Inactive since ' || coalesce(s.inactive_date::text, '?') END),
                    (16, 'Updated', to_char(s.updated_at, 'YYYY-MM-DD HH24:MI'))) AS f(n, k, v)
                WHERE s.security_id = $1::text::bigint AND v IS NOT NULL ORDER BY n
                """, sid).stream().map(r -> new NameValue(Rows.s(r, 0), Rows.s(r, 1))).toList();
        List<IdentifierRow> identifiers = rows("""
                SELECT si.id_type, si.id_value, coalesce(si.mic::text, ''), si.valid_from::text,
                       CASE WHEN si.valid_to = 'infinity' THEN '' ELSE si.valid_to::text END, ds.code,
                       to_char(si.last_seen, 'YYYY-MM-DD'),
                       (current_date >= si.valid_from AND current_date < si.valid_to)::text
                  FROM srm.security_identifier si JOIN srm.data_source ds USING (source_id)
                  JOIN srm.identifier_type it USING (id_type)
                 WHERE si.security_id = $1::text::bigint
                 ORDER BY it.sort_order, si.valid_to DESC, si.mic
                """, sid).stream().map(r -> new IdentifierRow(Rows.s(r, 0), Rows.s(r, 1), Rows.s(r, 2), Rows.s(r, 3),
                Rows.s(r, 4), Rows.s(r, 5), Rows.s(r, 6), Rows.b(r, 7))).toList();
        List<ListingRow> listings = rows("""
                SELECT l.mic::text, coalesce(e.name, ''), l.ticker, l.is_primary::text, l.valid_from::text,
                       CASE WHEN l.valid_to = 'infinity' THEN '' ELSE l.valid_to::text END
                  FROM srm.listing l LEFT JOIN srm.exchange e USING (mic)
                 WHERE l.security_id = $1::text::bigint ORDER BY l.valid_to DESC, l.is_primary DESC
                """, sid).stream().map(r -> new ListingRow(Rows.s(r, 0), Rows.s(r, 1), Rows.s(r, 2), Rows.b(r, 3),
                Rows.s(r, 4), Rows.s(r, 5))).toList();
        List<NameValue> classifications = rows("""
                SELECT sc.scheme_code || ' ' || n.level_name, n.name
                       || CASE WHEN sc.valid_to <> 'infinity' THEN ' (until ' || sc.valid_to || ')' ELSE '' END
                  FROM srm.security_classification sc JOIN srm.classification_node n USING (node_id)
                 WHERE sc.security_id = $1::text::bigint ORDER BY sc.scheme_code, sc.level, sc.valid_to DESC
                """, sid).stream().map(r -> new NameValue(Rows.s(r, 0), Rows.s(r, 1))).toList();
        List<NameValue> memberships = rows("""
                SELECT b.code || ' — ' || b.name, to_char(w.weight * 100, 'FM990.0000') || '% on ' || w.as_of_date
                  FROM srm.benchmark b CROSS JOIN LATERAL srm.benchmark_weights(b.benchmark_id) w
                 WHERE w.security_id = $1::text::bigint ORDER BY w.weight DESC
                """, sid).stream().map(r -> new NameValue(Rows.s(r, 0), Rows.s(r, 1))).toList();
        List<ActionRow> actions = rows("""
                SELECT ca.action_type, ca.ex_date::text,
                       CASE ca.action_type
                            WHEN 'CASH_DIVIDEND' THEN coalesce(ca.amount::text, '?') || coalesce(' ' || ca.currency_code, '')
                                 || coalesce(', paid ' || ca.pay_date, '')
                            WHEN 'SPLIT' THEN ca.ratio_new::float8 || ' for ' || ca.ratio_old::float8
                            WHEN 'SYMBOL_CHANGE' THEN ca.old_value || ' → ' || ca.new_value
                            ELSE coalesce(ca.new_value, '') END,
                       ds.code
                  FROM srm.corporate_action ca JOIN srm.data_source ds USING (source_id)
                 WHERE ca.security_id = $1::text::bigint ORDER BY ca.ex_date DESC LIMIT 40
                """, sid).stream().map(r -> new ActionRow(Rows.s(r, 0), Rows.s(r, 1), Rows.s(r, 2), Rows.s(r, 3))).toList();
        List<PricePoint> prices = rows("""
                SELECT p.price_date::text, p.close::float8::text
                  FROM srm.listing l JOIN srm.price p USING (listing_id)
                 WHERE l.security_id = $1::text::bigint AND p.price_date > current_date - 400
                 ORDER BY p.price_date
                """, sid).stream().map(PricePoint::of).filter(Objects::nonNull).toList();
        return new SecurityDetail(security, facts, identifiers, listings, classifications, memberships, actions, prices);
    }

    // MARK: Portfolios

    List<PortfolioRow> portfolios() throws SQLException {
        return rows("""
                SELECT p.portfolio_id::text, p.code, p.name, p.base_currency, p.benchmark_id::text, coalesce(b.code, ''),
                       coalesce(p.description, ''),
                       (SELECT count(DISTINCT as_of_date) FROM srm.portfolio_holding h WHERE h.portfolio_id = p.portfolio_id)::text,
                       coalesce((SELECT max(as_of_date)::text FROM srm.portfolio_holding h WHERE h.portfolio_id = p.portfolio_id), ''),
                       coalesce(to_jsonb(p) ->> 'account_type', ''), coalesce(to_jsonb(p) ->> 'approach', ''),
                       coalesce(to_jsonb(p) ->> 'managed_by', ''), coalesce(to_jsonb(p) ->> 'cash_balance', '0')
                  FROM srm.portfolio p LEFT JOIN srm.benchmark b USING (benchmark_id) ORDER BY p.code
                """).stream().map(r -> new PortfolioRow(Rows.n(r, 0), Rows.s(r, 1), Rows.s(r, 2), Rows.s(r, 3),
                Rows.nOrNull(r, 4), Rows.s(r, 5), Rows.s(r, 6), Rows.n(r, 7), Rows.s(r, 8), Rows.s(r, 9),
                Rows.s(r, 10), Rows.s(r, 11), Rows.d0(r, 12))).toList();
    }

    /** A personal account's open tax lots (V011's srm.v_portfolio_tax_lot). */
    List<TaxLotRow> taxLots(int portfolioId) throws SQLException {
        return rows("""
                SELECT lot_id::text, ticker, coalesce(name, ''), acquired_date::text, quantity::float8::text, cost_basis::float8::text,
                       price::float8::text, market_value::float8::text, unrealized_gain::float8::text, long_term::text
                  FROM srm.v_portfolio_tax_lot WHERE portfolio_id = $1::text::int ORDER BY ticker, acquired_date
                """, Integer.toString(portfolioId)).stream().map(r -> new TaxLotRow(Rows.l(r, 0), Rows.s(r, 1),
                Rows.s(r, 2), Rows.s(r, 3), Rows.d0(r, 4), Rows.d0(r, 5), Rows.d(r, 6), Rows.d(r, 7), Rows.d(r, 8),
                Rows.b(r, 9))).toList();
    }

    /** Creates or updates a portfolio; answers its id. */
    int savePortfolio(Integer id, String code, String name, String currency, Integer benchmarkId, String description)
            throws SQLException {
        String bench = benchmarkId == null ? null : benchmarkId.toString();
        String desc = description == null || description.isEmpty() ? null : description;
        if (id != null) {
            rows("""
                    UPDATE srm.portfolio SET code = $1, name = $2, base_currency = $3, benchmark_id = $4::text::int,
                           description = $5 WHERE portfolio_id = $6::text::int
                    """, code, name, currency, bench, desc, id.toString());
            return id;
        }
        String newId = scalar("""
                INSERT INTO srm.portfolio (code, name, base_currency, benchmark_id, description, inception_date)
                VALUES ($1, $2, $3, $4::text::int, $5, current_date) RETURNING portfolio_id::text
                """, code, name, currency, bench, desc);
        return newId == null ? -1 : Integer.parseInt(newId);
    }

    void deletePortfolio(int id) throws SQLException {
        rows("DELETE FROM srm.portfolio WHERE portfolio_id = $1::text::int", Integer.toString(id));
    }

    List<String> snapshotDates(int portfolioId) throws SQLException {
        return column("""
                SELECT DISTINCT as_of_date::text FROM srm.portfolio_holding WHERE portfolio_id = $1::text::int ORDER BY 1 DESC
                """, Integer.toString(portfolioId));
    }

    /** A snapshot's holdings valued, its checks, and its active weights against {@code benchmarkId} (when given). */
    record Snapshot(List<HoldingRow> holdings, List<ValidationIssue> issues, List<ActiveWeightRow> activeWeights) {}

    Snapshot snapshot(int portfolioId, String date, Integer benchmarkId) throws SQLException {
        String pid = Integer.toString(portfolioId);
        List<HoldingRow> holdings = rows("""
                SELECT v.security_id::text, coalesce(s.ticker, ''), coalesce(s.name, ''), coalesce(h.input_identifier, ''),
                       coalesce(h.input_type, ''), v.quantity::float8::text, v.target_weight::float8::text,
                       v.price::float8::text, coalesce(v.price_date::text, ''), v.market_value::float8::text,
                       v.weight::float8::text, coalesce(s.security_type, '')
                  FROM srm.portfolio_valuation($1::text::int, $2::text::date) v
                  LEFT JOIN srm.v_security s USING (security_id)
                  LEFT JOIN srm.portfolio_holding h ON h.portfolio_id = $1::text::int AND h.as_of_date = $2::text::date
                       AND h.security_id = v.security_id
                 ORDER BY v.weight DESC NULLS LAST, s.name
                """, pid, date).stream().map(r -> new HoldingRow(Rows.l(r, 0), Rows.s(r, 1), Rows.s(r, 2), Rows.s(r, 3),
                Rows.s(r, 4), Rows.d(r, 5), Rows.d(r, 6), Rows.d(r, 7), Rows.s(r, 8), Rows.d(r, 9), Rows.d(r, 10),
                Rows.s(r, 11))).toList();
        List<ValidationIssue> issues = rows(
                "SELECT severity, message FROM srm.portfolio_validate($1::text::int, $2::text::date) ORDER BY 1, 2", pid, date)
                .stream().map(r -> new ValidationIssue(Rows.s(r, 0), Rows.s(r, 1))).toList();
        List<ActiveWeightRow> active = List.of();
        if (benchmarkId != null) {
            active = rows("""
                    SELECT a.security_id::text, coalesce(s.ticker, ''), coalesce(s.name, ''), a.portfolio_weight::float8::text,
                           a.benchmark_weight::float8::text, a.active_weight::float8::text
                      FROM srm.portfolio_active_weights($1::text::int, $3::text::int, $2::text::date) a
                      LEFT JOIN srm.v_security s USING (security_id)
                     ORDER BY abs(a.active_weight) DESC LIMIT 200
                    """, pid, date, benchmarkId.toString()).stream().map(r -> new ActiveWeightRow(Rows.l(r, 0),
                    Rows.s(r, 1), Rows.s(r, 2), Rows.d0(r, 3), Rows.d0(r, 4), Rows.d0(r, 5))).toList();
        }
        return new Snapshot(holdings, issues, active);
    }

    /** Securities an identifier could mean (for the Add Holding preview); none on a failure. */
    List<ResolveMatch> resolve(String text, String type) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() || !isOpen()) return List.of();
        try {
            return rows("""
                    SELECT r.security_id::text, r.matched_type, r.matched_value, r.name, coalesce(v.ticker, ''),
                           coalesce(v.isin, ''), coalesce(v.security_type, '')
                      FROM srm.resolve_identifier($1, nullif($2, 'Auto')) r LEFT JOIN srm.v_security v USING (security_id)
                     ORDER BY r.rank, r.name LIMIT 20
                    """, value, type).stream().map(r -> new ResolveMatch(Rows.l(r, 0), Rows.s(r, 1), Rows.s(r, 2),
                    Rows.s(r, 3), Rows.s(r, 4), Rows.s(r, 5), Rows.s(r, 6))).toList();
        } catch (SQLException e) {
            return List.of();
        }
    }

    /** A holding to add: an identifier, its type or "Auto", and shares or a weight in percent. */
    record HoldingEntry(String identifier, String type, Double shares, Double weightPercent) {}

    /** Adds or replaces holdings in a snapshot, one statement each; answers the lines that failed. */
    List<String> addHoldings(int portfolioId, String date, List<HoldingEntry> entries) {
        List<String> failures = new ArrayList<>();
        for (HoldingEntry e : entries) {
            try {
                rows("""
                        SELECT srm.add_holding($1::text::int, $2::text::date, $3, $4::text::numeric,
                                               $5::text::numeric / 100, nullif($6, 'Auto'))
                        """, Integer.toString(portfolioId), date, e.identifier(),
                        e.shares() == null ? null : plainNumber(e.shares()),
                        e.weightPercent() == null ? null : plainNumber(e.weightPercent()), e.type());
            } catch (SQLException ex) {
                String msg = Pg.describe(ex);
                String first = msg == null ? "" : msg.split("\n")[0].replaceFirst("^ERROR:\\s+", "");
                failures.add(e.identifier() + ": " + first);
            }
        }
        return failures;
    }

    private static String plainNumber(double v) {
        return new java.math.BigDecimal(Double.toString(v)).toPlainString();
    }

    void removeHoldings(int portfolioId, String date, List<Long> ids) throws SQLException {
        if (ids.isEmpty()) return;
        rows("""
                DELETE FROM srm.portfolio_holding WHERE portfolio_id = $1::text::int AND as_of_date = $2::text::date
                   AND security_id = ANY(string_to_array($3, ',')::bigint[])
                """, Integer.toString(portfolioId), date, String.join(",", ids.stream().map(String::valueOf).toList()));
    }

    /** Scales the snapshot's target weights to add up to 100%. */
    void normalizeWeights(int portfolioId, String date) throws SQLException {
        rows("""
                UPDATE srm.portfolio_holding h SET weight = h.weight / t.total, updated_at = now()
                  FROM (SELECT sum(weight) AS total FROM srm.portfolio_holding
                         WHERE portfolio_id = $1::text::int AND as_of_date = $2::text::date) t
                 WHERE h.portfolio_id = $1::text::int AND h.as_of_date = $2::text::date AND h.weight IS NOT NULL AND t.total > 0
                """, Integer.toString(portfolioId), date);
    }

    /** Starts a snapshot on {@code day}, copying the latest earlier one (or empty). */
    void newSnapshot(int portfolioId, String day) throws SQLException {
        rows("""
                INSERT INTO srm.portfolio_holding (portfolio_id, as_of_date, security_id, quantity, weight, cost_price,
                                                   input_identifier, input_type, note)
                SELECT portfolio_id, $2::text::date, security_id, quantity, weight, cost_price, input_identifier, input_type, note
                  FROM srm.portfolio_holding
                 WHERE portfolio_id = $1::text::int
                   AND as_of_date = (SELECT max(as_of_date) FROM srm.portfolio_holding
                                      WHERE portfolio_id = $1::text::int AND as_of_date < $2::text::date)
                ON CONFLICT DO NOTHING
                """, Integer.toString(portfolioId), day);
    }

    /** Fills the snapshot from a benchmark's weights (a starting point for an index-tracking portfolio). */
    void copyBenchmark(int portfolioId, String date, int benchmarkId) throws SQLException {
        rows("""
                INSERT INTO srm.portfolio_holding (portfolio_id, as_of_date, security_id, weight, input_identifier, input_type)
                SELECT $1::text::int, $2::text::date, w.security_id, w.weight, b.code, 'BENCHMARK'
                  FROM srm.benchmark b, srm.benchmark_weights(b.benchmark_id, $2::text::date) w
                 WHERE b.benchmark_id = $3::text::int
                ON CONFLICT (portfolio_id, as_of_date, security_id) DO UPDATE SET weight = excluded.weight, updated_at = now()
                """, Integer.toString(portfolioId), date, Integer.toString(benchmarkId));
    }

    // MARK: Benchmarks

    record Benchmarks(List<BenchmarkRow> benchmarks, List<String> sectors) {}

    Benchmarks benchmarks() throws SQLException {
        List<BenchmarkRow> list = rows("""
                SELECT benchmark_id::text, code, name, coalesce(provider, ''), weighting_method, is_proxy::text,
                       coalesce(constituent_source, ''), coalesce(proxy_note, ''), coalesce(as_of_date::text, ''),
                       members::text, coalesce(weight_sum, 0)::float8::text, snapshots::text,
                       coalesce(last_level_date::text, ''), coalesce(parent_code, '')
                  FROM srm.v_benchmark_summary ORDER BY parent_code IS NOT NULL, code
                """).stream().map(r -> new BenchmarkRow(Rows.n(r, 0), Rows.s(r, 1), Rows.s(r, 2), Rows.s(r, 3),
                Rows.s(r, 4), Rows.b(r, 5), Rows.s(r, 6), Rows.s(r, 7), Rows.s(r, 8), Rows.n(r, 9), Rows.d0(r, 10),
                Rows.n(r, 11), Rows.s(r, 12), Rows.s(r, 13))).toList();
        List<String> sectors = column("""
                SELECT DISTINCT name FROM srm.classification_node WHERE scheme_code = 'FMP' AND level = 1 ORDER BY 1
                """);
        return new Benchmarks(list, sectors);
    }

    record BenchmarkDetail(List<ConstituentRow> constituents, List<SectorWeight> sectors, List<PricePoint> levels) {}

    BenchmarkDetail benchmark(int id) throws SQLException {
        String bid = Integer.toString(id);
        List<ConstituentRow> constituents = rows("""
                SELECT w.security_id::text, coalesce(v.ticker, ''), v.name,
                       coalesce(v.sector, (SELECT n.name FROM srm.security_classification sc
                                            JOIN srm.classification_node n USING (node_id)
                                           WHERE sc.security_id = w.security_id AND sc.scheme_code = 'SSGA'
                                             AND current_date < sc.valid_to LIMIT 1), ''),
                       w.weight::float8::text, w.shares::float8::text
                  FROM srm.benchmark_weights($1::text::int) w JOIN srm.v_security v USING (security_id)
                 ORDER BY w.weight DESC
                """, bid).stream().map(r -> new ConstituentRow(Rows.l(r, 0), Rows.s(r, 1), Rows.s(r, 2), Rows.s(r, 3),
                Rows.d0(r, 4), Rows.d(r, 5))).toList();
        Map<String, double[]> bySector = new HashMap<>();
        for (ConstituentRow c : constituents) {
            String key = c.sector().isEmpty() ? "Unclassified" : c.sector();
            double[] acc = bySector.computeIfAbsent(key, k -> new double[2]);
            acc[0] += c.weight();
            acc[1] += 1;
        }
        List<SectorWeight> sectors = bySector.entrySet().stream()
                .map(e -> new SectorWeight(e.getKey(), e.getValue()[0], (int) e.getValue()[1]))
                .sorted((a, b) -> Double.compare(b.weight(), a.weight())).toList();
        List<PricePoint> levels = rows("""
                SELECT level_date::text, close::float8::text FROM srm.benchmark_level
                 WHERE benchmark_id = $1::text::int AND level_date > current_date - 800 ORDER BY level_date
                """, bid).stream().map(PricePoint::of).filter(Objects::nonNull).toList();
        return new BenchmarkDetail(constituents, sectors, levels);
    }

    /** Builds a custom benchmark with srm.build_benchmark; answers its number of constituents. */
    String buildBenchmark(String code, String name, String parent, String method, String sector) throws SQLException {
        String count = scalar("SELECT srm.build_benchmark($1, $2, $3, $4, $5)::text", code, name, parent, method, sector);
        return count == null ? "0" : count;
    }

    void deleteBenchmark(int id) throws SQLException {
        rows("DELETE FROM srm.benchmark WHERE benchmark_id = $1::text::int AND provider = 'MxDataHUB'", Integer.toString(id));
    }

    // MARK: Loads

    record Audit(List<LoadRow> loads, List<CoverageRow> coverage, List<SourceRow> sources, List<SnapshotCoverageRow> snapshots,
                 boolean hasSnapshotView) {}

    Audit audit() throws SQLException {
        List<LoadRow> loads = rows("""
                SELECT load_id::text, source, loader, to_char(started_at, 'YYYY-MM-DD HH24:MI:SS'),
                       coalesce(to_char(duration, 'HH24:MI:SS'), 'running'), status, rows_read::text, rows_inserted::text,
                       rows_updated::text, rows_rejected::text, api_calls::text, coalesce(message, '')
                  FROM srm.v_load_history ORDER BY load_id DESC LIMIT 300
                """).stream().map(r -> new LoadRow(Rows.l(r, 0), Rows.s(r, 1), Rows.s(r, 2), Rows.s(r, 3), Rows.s(r, 4),
                Rows.s(r, 5), Rows.n(r, 6), Rows.n(r, 7), Rows.n(r, 8), Rows.n(r, 9), Rows.n(r, 10), Rows.s(r, 11))).toList();
        List<CoverageRow> coverage = rows("""
                SELECT id_type, name, securities::text, total_securities::text, coalesce(pct, 0)::text, coalesce(sources, '')
                  FROM srm.v_identifier_coverage ORDER BY sort_order
                """).stream().map(r -> new CoverageRow(Rows.s(r, 0), Rows.s(r, 1), Rows.n(r, 2), Rows.n(r, 3),
                Rows.d0(r, 4), Rows.s(r, 5))).toList();
        List<SourceRow> sources = rows("""
                SELECT ds.code, ds.name, coalesce(ds.url, ''), concat_ws(' ', ds.licence_note, ds.plan_note),
                       coalesce((SELECT to_char(max(started_at), 'YYYY-MM-DD HH24:MI') FROM srm.load_run r
                                  WHERE r.source_id = ds.source_id), '')
                  FROM srm.data_source ds ORDER BY ds.code
                """).stream().map(r -> new SourceRow(Rows.s(r, 0), Rows.s(r, 1), Rows.s(r, 2), Rows.s(r, 3), Rows.s(r, 4))).toList();
        // srm.v_snapshot_coverage arrived with migration V010.
        boolean hasView = "true".equals(scalar("SELECT (to_regclass('srm.v_snapshot_coverage') IS NOT NULL)::text"));
        List<SnapshotCoverageRow> snapshots = !hasView ? List.of() : rows("""
                SELECT kind, code, snapshots::text, coalesce(first_date::text, ''), coalesce(last_date::text, ''),
                       months::text, drifted_snapshots::text, max_gap_days::text
                  FROM srm.v_snapshot_coverage ORDER BY kind, code
                """).stream().map(r -> new SnapshotCoverageRow(Rows.s(r, 0), Rows.s(r, 1), Rows.n(r, 2), Rows.s(r, 3),
                Rows.s(r, 4), Rows.n(r, 5), Rows.n(r, 6), Rows.nOrNull(r, 7))).toList();
        return new Audit(loads, coverage, sources, snapshots, hasView);
    }

    // MARK: Links on tickers and names (srm.security_link, V014)

    boolean hasLinks() {
        try {
            return "true".equals(scalar("SELECT (to_regclass('srm.security_link') IS NOT NULL)::text"));
        } catch (SQLException e) {
            return false;
        }
    }

    /** The links of these securities: security id → field ("ticker" / "name") → link. */
    Map<Long, Map<String, SecurityLink>> links(List<Long> ids) throws SQLException {
        Map<Long, Map<String, SecurityLink>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        String array = "{" + String.join(",", ids.stream().distinct().map(String::valueOf).toList()) + "}";
        for (String[] r : rows("""
                SELECT security_id::text, field, address, display_text, hover_text, link_title
                  FROM srm.security_link WHERE security_id = ANY($1::text::bigint[])
                """, array)) {
            if (r[2] == null) continue;
            out.computeIfAbsent(Rows.l(r, 0), k -> new HashMap<>()).put(Rows.s(r, 1), new SecurityLink(r[2], r[3], r[4], r[5]));
        }
        return out;
    }

    void saveLink(long securityId, String field, SecurityLink link) throws SQLException {
        rows("""
                INSERT INTO srm.security_link (security_id, field, address, display_text, hover_text, hover_rtf, link_title, updated_at, updated_by)
                VALUES ($1::text::bigint, $2, $3, $4, $5, NULL, $6, now(), 'MxDataHUB')
                ON CONFLICT (security_id, field) DO UPDATE SET address = excluded.address, display_text = excluded.display_text,
                    hover_text = excluded.hover_text, hover_rtf = excluded.hover_rtf, link_title = excluded.link_title,
                    updated_at = now(), updated_by = excluded.updated_by
                RETURNING security_id::text
                """, Long.toString(securityId), field, link.address(), blankToNull(link.text()), blankToNull(link.hover()),
                blankToNull(link.title()));
    }

    void deleteLink(long securityId, String field) throws SQLException {
        rows("DELETE FROM srm.security_link WHERE security_id = $1::text::bigint AND field = $2 RETURNING field",
                Long.toString(securityId), field);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
