package com.k3jdr.mxacctmgr.port;

/**
 * A security as the accounts service returns it. kind: stock, fund or bond (a bond or other fixed-income fund);
 * expenseRatio: a fund's yearly expense ratio as a fraction.
 */
public record SRMSecurity(
        int securityId,
        String ticker,
        String mic,
        String name,
        String securityType,
        String assetClass,
        String sector,
        String industry,
        String isin,
        String cusip,
        String kind,
        Double price,
        String priceDate,
        Double beta,
        Double marketCap,
        Double weight,
        Double expenseRatio) {

    /** A placeholder for a ticker the analytics service named but the registry hasn't seen. */
    public SRMSecurity(int securityId, String ticker, String name, String sector, String kind) {
        this(securityId, ticker, null, name, null, null, sector, null, null, null, kind, null, null, null, null, null, null);
    }

    public int id() { return securityId; }

    // Non-optional sort keys for the table's sortable columns (the service does the sorting).
    public String typeKey() { return securityType != null ? securityType : ""; }

    public String sectorKey() { return sector != null ? sector : ""; }

    public String micKey() { return mic != null ? mic : ""; }

    public double priceKey() { return price != null ? price : -1; }

    public String asOfKey() { return priceDate != null ? priceDate : ""; }

    public double marketCapKey() { return marketCap != null ? marketCap : -1; }

    public String kindTitle() {
        return switch (kind == null ? "" : kind) {
            case "bond" -> "Bond fund";
            case "fund" -> "Fund";
            default -> securityType != null ? securityType : "Stock";
        };
    }
}
