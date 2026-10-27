package com.k3jdr.mx.dbml;

import org.eclipse.swt.graphics.RGB;

/** Colours written as "#rrggbb" (or "#rgb"), as DBML's headercolor and MxDataSQL's layout keep them. */
public final class DbmlColor {
    private DbmlColor() {}

    /** The colour of a hex text, or null if it isn't one. */
    public static RGB hex(String s) {
        if (s == null) return null;
        String t = trim(s, "#'\" ");
        if (t.length() == 3) {
            StringBuilder b = new StringBuilder();
            for (char c : t.toCharArray()) b.append(c).append(c);
            t = b.toString();
        }
        if (t.length() != 6) return null;
        for (char c : t.toCharArray()) if (!DbmlParser.isHexDigit(c)) return null;
        int v = Integer.parseInt(t, 16);
        return new RGB((v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff);
    }

    public static String hexString(RGB c) {
        return String.format("#%02X%02X%02X", c.red, c.green, c.blue);
    }

    private static String trim(String s, String chars) {
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }
}
