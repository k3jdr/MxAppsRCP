package com.k3jdr.mxfilemgr.tools;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How Multi-Rename builds a new name (MultiRenameView.swift's RenameRule): name and extension masks with
 * placeholders — [N] name, [N2-5] characters 2 to 5 of the name, [N3] the 3rd character, [E] extension,
 * [C] counter, [P] parent folder name, [YMD] / [hms] modification date and time — then search and replace (plain
 * or regex) and a case change.
 */
public final class RenameRule {
    public enum CaseMode {
        UNCHANGED("Unchanged"), LOWER("lowercase"), UPPER("UPPERCASE"), TITLE("Title Case");

        public final String title;

        CaseMode(String title) { this.title = title; }
    }

    /** What a rename needs to know about an item, read once. */
    public record Source(String name, boolean isDirectory, String parentName, long modified) {}

    public String nameMask = "[N]";
    public String extMask = "[E]";
    public String search = "";
    public String replace = "";
    public boolean useRegex;
    public CaseMode caseMode = CaseMode.UNCHANGED;
    public int counterStart = 1;
    public int counterStep = 1;
    public int counterDigits = 1;

    public String newName(Source s, int index) {
        String fileName = s.name();
        int dot = s.isDirectory() ? -1 : fileName.lastIndexOf('.');
        if (dot == fileName.length() - 1) dot = -1;
        String ext = dot > 0 ? fileName.substring(dot + 1) : "";
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        long counter = counterStart + (long) index * counterStep;
        String counterText = String.valueOf(counter);
        if (counterText.length() < counterDigits) counterText = "0".repeat(counterDigits - counterText.length()) + counterText;
        LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(Math.max(0, s.modified())), ZoneId.systemDefault());

        String name = expand(nameMask, stem, ext, counterText, s.parentName(), date);
        String newExt = expand(extMask, stem, ext, counterText, s.parentName(), date);
        if (!newExt.isEmpty()) name += "." + newExt;
        if (!search.isEmpty()) {
            boolean done = false;
            if (useRegex) {
                try {
                    name = Pattern.compile(search).matcher(name).replaceAll(replace);
                    done = true;
                } catch (RuntimeException e) {
                    // not a valid expression (or replacement): replace it as plain text
                }
            }
            if (!done) name = name.replace(search, replace);
        }
        return switch (caseMode) {
            case UNCHANGED -> name;
            case LOWER -> name.toLowerCase(Locale.ROOT);
            case UPPER -> name.toUpperCase(Locale.ROOT);
            case TITLE -> titleCase(name);
        };
    }

    private static String expand(String mask, String stem, String ext, String counter, String parent, LocalDateTime date) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < mask.length()) {
            char c = mask.charAt(i);
            int close = c == '[' ? mask.indexOf(']', i) : -1;
            if (close > i) {
                String token = mask.substring(i + 1, close);
                String v = value(token, stem, ext, counter, parent, date);
                out.append(v != null ? v : "[" + token + "]");
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String value(String token, String stem, String ext, String counter, String parent, LocalDateTime date) {
        switch (token) {
            case "N": return stem;
            case "E": return ext;
            case "C": return counter;
            case "P": return parent;
            case "YMD": return String.format("%04d%02d%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
            case "hms": return String.format("%02d%02d%02d", date.getHour(), date.getMinute(), date.getSecond());
            default: break;
        }
        // [Nx-y] or [Nx]: characters x…y (1-based) of the name; [Nx-] to the end.
        if (!token.startsWith("N")) return null;
        String[] parts = token.substring(1).split("-", -1);
        Integer from = parse(parts[0]);
        if (from == null || from < 1) return null;
        int[] cps = stem.codePoints().toArray();
        int count = cps.length;
        int to;
        if (parts.length > 1) {
            Integer t = parse(parts[1]);
            to = t == null ? count : t;
        } else {
            to = from;
        }
        int lo = Math.min(from - 1, count), hi = Math.min(Math.max(to, lo), count);
        return new String(cps, lo, hi - lo);
    }

    private static Integer parse(String s) {
        try {
            return s.isEmpty() ? null : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The first letter of each word upper case, the rest lower case (words are separated by white space). */
    static String titleCase(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean start = true;
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                start = true;
                out.appendCodePoint(cp);
            } else {
                out.appendCodePoint(start ? Character.toTitleCase(cp) : Character.toLowerCase(cp));
                start = false;
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
