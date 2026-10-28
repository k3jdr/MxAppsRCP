package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.List;

/** CSV lines (RFC 4180), tab-separated text for the clipboard, and PostgreSQL record text. */
public final class TextFormats {
    private TextFormats() {}

    /** One CSV line with CRLF: NULL is an empty field, an empty string is "". */
    public static String csvLine(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            String f = fields.get(i);
            if (f == null) continue;
            boolean quote = f.isEmpty() || f.indexOf(',') >= 0 || f.indexOf('"') >= 0 || f.indexOf('\n') >= 0
                    || f.indexOf('\r') >= 0 || f.charAt(0) == ' ' || f.charAt(f.length() - 1) == ' ';
            sb.append(quote ? "\"" + f.replace("\"", "\"\"") + "\"" : f);
        }
        return sb.append("\r\n").toString();
    }

    /** One line of tab-separated values; fields with a tab, line break or quote are quoted. */
    public static String tsvLine(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append('\t');
            String f = fields.get(i);
            boolean quote = f.indexOf('\t') >= 0 || f.indexOf('\n') >= 0 || f.indexOf('\r') >= 0 || f.indexOf('"') >= 0;
            sb.append(quote ? "\"" + f.replace("\"", "\"\"") + "\"" : f);
        }
        return sb.toString();
    }

    /**
     * Tab-separated text (from this grid or a spreadsheet) as rows of fields. Quoted fields may hold tabs, line
     * breaks and doubled quotes. A trailing line break is ignored.
     */
    public static List<List<String>> parseTSV(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean atFieldStart = true;
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i++);
            if (atFieldStart && c == '"') {
                atFieldStart = false;
                while (i < n) {
                    char q = text.charAt(i++);
                    if (q == '"') {
                        if (i < n && text.charAt(i) == '"') { field.append('"'); i++; } else break;
                    } else {
                        field.append(q);
                    }
                }
                continue;
            }
            atFieldStart = false;
            if (c == '\t') {
                row.add(field.toString());
                field.setLength(0);
                atFieldStart = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i < n && text.charAt(i) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                atFieldStart = true;
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Splits PostgreSQL composite (record) text output, e.g. {@code (1,"a, b",,"")}, into fields. An empty unquoted
     * field is NULL; {@code ""} is the empty string.
     */
    public static String[] parseRecord(String text, int fieldCount) {
        String[] out = new String[fieldCount];
        if (fieldCount == 0) return out;
        String s = text.length() >= 2 ? text.substring(1, text.length() - 1) : "";
        int i = 0, n = s.length(), f = 0;
        while (true) {
            StringBuilder value = new StringBuilder();
            boolean quoted = false;
            while (i < n && s.charAt(i) != ',') {
                char c = s.charAt(i++);
                if (c == '"') {
                    quoted = true;
                    while (i < n) {
                        char q = s.charAt(i++);
                        if (q == '\\' && i < n) {
                            value.append(s.charAt(i++));
                        } else if (q == '"') {
                            if (i < n && s.charAt(i) == '"') { value.append('"'); i++; } else break;
                        } else {
                            value.append(q);
                        }
                    }
                } else {
                    value.append(c);
                }
            }
            if (f < fieldCount) out[f++] = value.length() == 0 && !quoted ? null : value.toString();
            if (i >= n) break;
            i++;   // the comma
        }
        return out;
    }
}
