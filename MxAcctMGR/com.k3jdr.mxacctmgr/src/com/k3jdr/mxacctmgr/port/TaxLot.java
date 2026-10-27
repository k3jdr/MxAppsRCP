package com.k3jdr.mxacctmgr.port;

import java.util.Objects;

/** A tax lot of a holding. */
public final class TaxLot {
    /** yyyy-MM-dd */
    public String acquired;
    /** Share of the holding's value in this lot (the lots add up to 1). */
    public double share;
    /** What this lot cost, in dollars. */
    public double costBasis;
    /** Where the lot came from, as the security master keeps it (e.g. "Vanguard plan: contributions"); kept on save. */
    public String note;

    public TaxLot(String acquired, double share, double costBasis, String note) {
        this.acquired = acquired;
        this.share = share;
        this.costBasis = costBasis;
        this.note = note;
    }

    public TaxLot(String acquired, double share, double costBasis) { this(acquired, share, costBasis, null); }

    public String id() { return acquired + costBasis; }

    public TaxLot copy() { return new TaxLot(acquired, share, costBasis, note); }

    @Override
    public boolean equals(Object o) {
        return o instanceof TaxLot l && Objects.equals(acquired, l.acquired) && share == l.share
                && costBasis == l.costBasis && Objects.equals(note, l.note);
    }

    @Override
    public int hashCode() { return Objects.hash(acquired, share, costBasis, note); }
}
