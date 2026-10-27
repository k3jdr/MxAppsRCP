package com.k3jdr.mxacctmgr.port;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Realised gains and tax of a set of sales, lot by lot (PortStore.taxEstimate). */
public record TaxEstimate(List<Line> lines, double tax, List<String> viaFunds) {

    /** A sale from one lot. */
    public record Line(String ticker, String acquired, double sold, double gain, boolean shortTerm) {
        public String id() { return ticker + acquired; }
    }

    public double shortTermGain() {
        double s = 0;
        for (Line l : lines) if (l.shortTerm()) s += l.gain();
        return s;
    }

    public double longTermGain() {
        double s = 0;
        for (Line l : lines) if (!l.shortTerm()) s += l.gain();
        return s;
    }

    /**
     * Realised gains and tax of selling down to the target weights: sells come from directly held positions, lot by
     * lot in the chosen order. Names held only through a fund are listed but not estimated.
     */
    public static TaxEstimate of(Portfolio p, List<Trade> trades, LotOrder lotOrder, double longTermRate, double shortTermRate) {
        double total = p.total();
        LocalDate yearAgo = LocalDate.now().minusYears(1);
        List<Line> lines = new ArrayList<>();
        List<String> via = new ArrayList<>();
        for (Trade t : trades) {
            if (!(t.change() < 0) || "CASH".equals(t.asset())) continue;
            double toSell = -t.change() * total;
            Holding h = null;
            for (Holding x : p.holdings) {
                if (x.ticker.equals(t.asset())) {
                    h = x;
                    break;
                }
            }
            if (h == null) {
                via.add(t.asset());
                continue;
            }
            toSell = Math.min(toSell, h.value);
            List<TaxLot> lots = h.lots.isEmpty() ? List.of(new TaxLot("—", 1, h.value)) : h.lots;
            List<TaxLot> ordered = new ArrayList<>(lots);
            if (lotOrder == LotOrder.FIFO) {
                ordered.sort(Comparator.comparing(l -> l.acquired));
            } else {
                ordered.sort((x, y) -> Double.compare(y.costBasis / Math.max(y.share, 1e-9), x.costBasis / Math.max(x.share, 1e-9)));
            }
            for (TaxLot lot : ordered) {
                if (!(toSell > 0.5)) continue;
                double lotValue = h.value * lot.share;
                double sold = Math.min(lotValue, toSell);
                double gain = sold - lot.costBasis * sold / Math.max(lotValue, 1e-9);
                boolean shortTerm = isAfter(lot.acquired, yearAgo);
                lines.add(new Line(h.ticker, lot.acquired, sold, gain, shortTerm));
                toSell -= sold;
            }
        }
        double tax = 0;
        if (p.account.isTaxable()) {
            double st = 0, lt = 0;
            for (Line l : lines) {
                if (l.shortTerm()) st += l.gain(); else lt += l.gain();
            }
            // Losses offset gains of the same kind first, then the other kind.
            double stNet = st, ltNet = lt;
            if (stNet < 0) {
                ltNet += stNet;
                stNet = 0;
            }
            if (ltNet < 0) {
                stNet = Math.max(0, stNet + ltNet);
                ltNet = 0;
            }
            tax = stNet * shortTermRate + ltNet * longTermRate;
        }
        return new TaxEstimate(lines, tax, via);
    }

    /** Selling every holding (to move into one index fund). */
    public static TaxEstimate sellAll(Portfolio p, LotOrder lotOrder, double longTermRate, double shortTermRate) {
        List<Trade> trades = new ArrayList<>();
        for (Holding h : p.holdings) {
            if (h.security() != null && !"CASH".equals(h.ticker)) trades.add(new Trade(h.ticker, p.weight(h), 0));
        }
        return of(p, trades, lotOrder, longTermRate, shortTermRate);
    }

    /** Bought after the date (a lot held a year or less is short-term); false when the date can't be read. */
    private static boolean isAfter(String acquired, LocalDate yearAgo) {
        try {
            return acquired != null && LocalDate.parse(acquired).isAfter(yearAgo);
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
