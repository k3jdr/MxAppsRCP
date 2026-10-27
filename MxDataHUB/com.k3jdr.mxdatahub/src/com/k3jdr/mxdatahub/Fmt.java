package com.k3jdr.mxdatahub;

import java.text.NumberFormat;

/** Number formatting shared by the screens (Formatting.swift). */
final class Fmt {
    private Fmt() {}

    /** "12.3456%" for a fraction; "" for none. */
    static String percent(Double value, int digits) {
        if (value == null) return "";
        return number(value * 100, digits) + "%";
    }

    static String percent(Double value) { return percent(value, 4); }

    /** The number with grouping and {@code digits} decimals; "" for none. */
    static String number(Double value, int digits) {
        if (value == null) return "";
        NumberFormat f = NumberFormat.getNumberInstance();
        f.setMinimumFractionDigits(digits);
        f.setMaximumFractionDigits(digits);
        return f.format(value);
    }

    static String number(Double value) { return number(value, 2); }

    /** An integer with grouping: 13,263. */
    static String count(long n) { return NumberFormat.getIntegerInstance().format(n); }

    /** A number as Swift's formatted() shows it: grouping, up to a few decimals. */
    static String plain(double value) {
        NumberFormat f = NumberFormat.getNumberInstance();
        f.setMaximumFractionDigits(3);
        return f.format(value);
    }

    static String capitalized(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (char c : s.toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = c == ' ' || c == '-' || c == '_';
        }
        return sb.toString();
    }
}
