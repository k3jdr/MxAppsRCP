package com.k3jdr.mxacctmgr.port;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A holding: a security or fund with its market value and, in a taxable account, its tax lots. */
public final class Holding {
    public String ticker;
    public double value;
    public List<TaxLot> lots = new ArrayList<>();
    /** A price is missing for this holding (stale or delisted): it can't be valued reliably. */
    public boolean unpriced;
    /** Shares (or units) held, when known — always for a security-master account. */
    public Double quantity;

    public Holding(String ticker, double value) {
        this.ticker = ticker;
        this.value = value;
    }

    public Holding(String ticker, double value, List<TaxLot> lots, boolean unpriced, Double quantity) {
        this.ticker = ticker;
        this.value = value;
        this.lots = lots == null ? new ArrayList<>() : new ArrayList<>(lots);
        this.unpriced = unpriced;
        this.quantity = quantity;
    }

    public String id() { return ticker; }

    /** The registered security; null when the ticker is unknown. */
    public Security security() { return Universe.security(ticker); }

    public double costBasis() {
        if (lots.isEmpty()) return value;
        double s = 0;
        for (TaxLot l : lots) s += l.costBasis;
        return s;
    }

    public double unrealizedGain() { return value - costBasis(); }

    /** A deep copy (Holding is a value type in Swift). */
    public Holding copy() {
        List<TaxLot> l = new ArrayList<>();
        for (TaxLot t : lots) l.add(t.copy());
        return new Holding(ticker, value, l, unpriced, quantity);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Holding h && Objects.equals(ticker, h.ticker) && value == h.value
                && Objects.equals(lots, h.lots) && unpriced == h.unpriced && Objects.equals(quantity, h.quantity);
    }

    @Override
    public int hashCode() { return Objects.hash(ticker, value, lots, unpriced, quantity); }
}
