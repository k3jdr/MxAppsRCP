package com.k3jdr.mxdatahub;

import java.time.LocalDate;
import java.util.List;

/** The row types of the security master's screens (SRMStore.swift), read from text columns. */
final class Rows {
    private Rows() {}

    static String s(String[] r, int i) { return i < r.length && r[i] != null ? r[i] : ""; }

    static Double d(String[] r, int i) {
        if (i >= r.length || r[i] == null || r[i].isEmpty()) return null;
        try {
            return Double.valueOf(r[i]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double d0(String[] r, int i) {
        Double v = d(r, i);
        return v == null ? 0 : v;
    }

    static long l(String[] r, int i) {
        try {
            return i < r.length && r[i] != null ? Long.parseLong(r[i].trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int n(String[] r, int i) { return (int) l(r, i); }

    static Integer nOrNull(String[] r, int i) {
        return i < r.length && r[i] != null && !r[i].isEmpty() ? (int) l(r, i) : null;
    }

    static boolean b(String[] r, int i) { return "true".equals(s(r, i)) || "t".equals(s(r, i)); }

    record SecurityRow(long id, String ticker, String name, String type, String isin, String cusip, String figi,
                       String sector, String mic, boolean active) {
        static SecurityRow of(String[] r) {
            return new SecurityRow(l(r, 0), s(r, 1), s(r, 2), s(r, 3), s(r, 4), s(r, 5), s(r, 6), s(r, 7), s(r, 8), b(r, 9));
        }
    }

    record IdentifierRow(String type, String value, String mic, String from, String to, String source, String lastSeen,
                         boolean current) {}

    record ListingRow(String mic, String exchange, String ticker, boolean primary, String from, String to) {}

    record NameValue(String name, String value) {}

    record ActionRow(String type, String exDate, String detail, String source) {}

    record PricePoint(LocalDate date, double close) {
        static PricePoint of(String[] r) {
            try {
                return new PricePoint(LocalDate.parse(s(r, 0)), Double.parseDouble(s(r, 1)));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    record SecurityDetail(SecurityRow security, List<NameValue> facts, List<IdentifierRow> identifiers,
                          List<ListingRow> listings, List<NameValue> classifications, List<NameValue> memberships,
                          List<ActionRow> actions, List<PricePoint> prices) {}

    record PortfolioRow(int id, String code, String name, String currency, Integer benchmarkId, String benchmarkCode,
                        String description, int snapshots, String lastSnapshot, String accountType, String approach,
                        String managedBy, double cash) {
        boolean personal() { return !accountType.isEmpty(); }
    }

    record TaxLotRow(long id, String ticker, String name, String acquired, double quantity, double costBasis,
                     Double price, Double marketValue, Double gain, boolean longTerm) {}

    record HoldingRow(long securityId, String ticker, String name, String input, String inputType, Double quantity,
                      Double targetWeight, Double price, String priceDate, Double marketValue, Double weight, String type) {}

    record ValidationIssue(String severity, String message) {}

    record ActiveWeightRow(long id, String ticker, String name, double portfolio, double benchmark, double active) {}

    record ResolveMatch(long id, String matchedType, String matchedValue, String name, String ticker, String isin, String type) {}

    record BenchmarkRow(int id, String code, String name, String provider, String method, boolean proxy, String source,
                        String proxyNote, String asOf, int members, double weightSum, int snapshots, String lastLevel,
                        String parent) {
        boolean custom() { return "MxDataHUB".equals(provider); }
    }

    record ConstituentRow(long id, String ticker, String name, String sector, double weight, Double shares) {}

    record SectorWeight(String sector, double weight, int count) {}

    record LoadRow(long id, String source, String loader, String started, String duration, String status, int read,
                   int inserted, int updated, int rejected, int apiCalls, String message) {}

    record CoverageRow(String type, String name, int securities, int total, double percent, String sources) {}

    record SnapshotCoverageRow(String kind, String code, int snapshots, String first, String last, int months,
                               int drifted, Integer maxGapDays) {}

    record SourceRow(String code, String name, String url, String note, String lastLoad) {}

    /** A link on a security's ticker or name (srm.security_link, V014). */
    record SecurityLink(String address, String text, String hover, String title) {}
}
