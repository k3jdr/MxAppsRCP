package com.k3jdr.mxfilemgr.model;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/** Sizes, dates and counts as the lists and dialogs show them (FileModels.swift's Fmt), the Windows way. */
public final class Fmt {
    private Fmt() {}

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter LONG_DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault());

    /** "12 bytes", "1.4 KB", "230 MB", "1.2 GB" (1 KB = 1024 bytes, as Explorer counts). */
    public static String size(long bytes) {
        if (bytes < 1000) return bytes + (bytes == 1 ? " byte" : " bytes");
        String[] units = { "KB", "MB", "GB", "TB", "PB" };
        double v = bytes;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1000 && u < units.length - 1);
        String num = v >= 100 ? String.format("%.0f", v) : String.format("%.1f", v);
        return num + " " + units[u];
    }

    public static String bytes(long bytes) {
        return NumberFormat.getIntegerInstance().format(bytes);
    }

    public static String date(long millis) {
        return millis <= 0 ? "" : DATE_TIME.format(Instant.ofEpochMilli(millis));
    }

    public static String longDate(long millis) {
        return millis <= 0 ? "" : LONG_DATE_TIME.format(Instant.ofEpochMilli(millis));
    }

    public static String count(long n, String singular) {
        return count(n, singular, singular + "s");
    }

    public static String count(long n, String singular, String plural) {
        return NumberFormat.getIntegerInstance().format(n) + " " + (n == 1 ? singular : plural);
    }
}
