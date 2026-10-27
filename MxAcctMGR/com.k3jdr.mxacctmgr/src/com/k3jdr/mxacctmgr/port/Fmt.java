package com.k3jdr.mxacctmgr.port;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/** Formatting helpers from Portfolio.swift: pct, signedPct, money (and String(format: "%.nf") as fixed). */
public final class Fmt {
    private Fmt() {}

    /** "12.3%" for 0.123 (String(format: "%.1f%%", 100 * x)). */
    public static String pct(double x, int digits) { return fixed(100 * x, digits, false) + "%"; }

    public static String pct(double x) { return pct(x, 1); }

    /** "+1.23%" / "-1.23%" (String(format: "%+.2f%%", 100 * x)). */
    public static String signedPct(double x, int digits) { return fixed(100 * x, digits, true) + "%"; }

    public static String signedPct(double x) { return signedPct(x, 2); }

    /** US dollars: no cents from $1,000 up; never "-$0.00". */
    public static String money(double x) {
        double v = Math.abs(x) < 0.005 ? 0 : x;
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
        f.setCurrency(Currency.getInstance("USD"));
        f.setMaximumFractionDigits(Math.abs(v) >= 1000 ? 0 : 2);
        return f.format(v);
    }

    /** printf's "%.nf" (or "%+.nf"): the exact binary value rounded half-even, as the C library does. */
    public static String fixed(double x, int digits, boolean plus) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? (plus ? "+inf" : "inf") : "-inf";
        boolean negative = x < 0 || (x == 0 && 1 / x < 0);
        String body = new BigDecimal(Math.abs(x)).setScale(Math.max(digits, 0), RoundingMode.HALF_EVEN).toPlainString();
        return (negative ? "-" : plus ? "+" : "") + body;
    }

    public static String fixed(double x, int digits) { return fixed(x, digits, false); }
}
