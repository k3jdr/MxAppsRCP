package com.k3jdr.mxacctmgr.port;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A security the app works with, as SRMRegistry registers it (Universe.swift). */
public final class Security {
    public final String ticker;
    public final String name;
    public final String sector;
    public final AssetClass assetClass;
    public final double price;
    /** Market beta (against VTI), from the analytics service once its inputs cover the security. */
    public double beta;
    /** Return for a 1.00 rise in the 10-year Treasury yield (≈ −duration for a bond fund), from the analytics service. */
    public double rateBeta;
    /** Annual volatility from the analytics service's covariance; null until its inputs cover the security. */
    public Double volatility;
    /** Market value in $bn, for cap weights. */
    public final double marketCap;
    /** The analytics service's momentum alpha (12-1 month), when its inputs include one. */
    public double alpha;
    /** A fund's holdings (weights adding up to 1): the security master has no fund holdings, so always null. */
    public Map<String, Double> lookThrough;
    public double fee;
    /** A bond's coupon, maturity, rating, issuer. */
    public Double coupon;
    public String maturity;
    public String rating;
    public String issuer;
    /** The security master's security_id, plain ticker, latest price date and type. */
    public Integer srmID;
    public String srmTicker;
    public String priceDate;
    public String securityType;
    /** The risk numbers above are the analytics service's (not placeholders). */
    public boolean hasRisk;

    public Security(String ticker, String name, String sector, AssetClass assetClass, double price, double beta,
                    double rateBeta, Double volatility, double marketCap, double alpha, Integer srmID, String srmTicker,
                    String priceDate, String securityType) {
        this.ticker = ticker;
        this.name = name;
        this.sector = sector;
        this.assetClass = assetClass;
        this.price = price;
        this.beta = beta;
        this.rateBeta = rateBeta;
        this.volatility = volatility;
        this.marketCap = marketCap;
        this.alpha = alpha;
        this.srmID = srmID;
        this.srmTicker = srmTicker;
        this.priceDate = priceDate;
        this.securityType = securityType;
    }

    public String id() { return ticker; }

    public boolean isFund() {
        return securityType != null && List.of("ETF", "ETN", "Mutual Fund", "Closed-End Fund").contains(securityType);
    }

    /** The ticker to show: the security master's own (the key may carry a :MIC suffix). */
    public String displayTicker() { return srmTicker != null ? srmTicker : ticker; }

    /** ≈ duration for a bond (the price change for a 1-point move in yields, with the sign flipped). */
    public double duration() { return -rateBeta; }

    /** Own (diversifiable) volatility: total volatility less the market part. */
    public double idioVol() {
        if (volatility == null) return 0;
        double v = volatility, m = Universe.marketVol();
        return Math.sqrt(Math.max(v * v - beta * beta * m * m, 0));
    }

    /** A copy (Security is a value type in Swift). */
    public Security copy() {
        Security s = new Security(ticker, name, sector, assetClass, price, beta, rateBeta, volatility, marketCap, alpha,
                srmID, srmTicker, priceDate, securityType);
        s.lookThrough = lookThrough;
        s.fee = fee;
        s.coupon = coupon;
        s.maturity = maturity;
        s.rating = rating;
        s.issuer = issuer;
        s.hasRisk = hasRisk;
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Security s)) return false;
        return ticker.equals(s.ticker) && Objects.equals(name, s.name) && Objects.equals(sector, s.sector)
                && assetClass == s.assetClass && price == s.price && beta == s.beta && rateBeta == s.rateBeta
                && Objects.equals(volatility, s.volatility) && marketCap == s.marketCap && alpha == s.alpha
                && Objects.equals(lookThrough, s.lookThrough) && fee == s.fee && Objects.equals(srmID, s.srmID)
                && Objects.equals(srmTicker, s.srmTicker) && Objects.equals(priceDate, s.priceDate)
                && Objects.equals(securityType, s.securityType) && hasRisk == s.hasRisk;
    }

    @Override
    public int hashCode() { return Objects.hash(ticker, name, price, beta, srmID); }

    @Override
    public String toString() { return ticker + " (" + name + ")"; }
}
