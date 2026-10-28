package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SQL text: quoting, the Run SQL pre-flight checks on the statement's text (WorkspaceSession.swift), psql-style
 * error text with a caret, and turning {@code $1} parameters into JDBC {@code ?} placeholders.
 */
public final class Sql {
    private Sql() {}

    /** "name" with embedded quotes doubled. */
    public static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    public static final String ROW_ID_COLUMN = "mx_row_ctid";

    private static final Set<String> RUNNABLE = Set.of("select", "with", "values", "table", "insert", "update", "delete",
            "merge", "call", "create", "drop", "alter");

    /** The statement without trailing whitespace and semicolons. */
    public static String statementBody(String text) {
        int end = text.length();
        while (end > 0 && (Character.isWhitespace(text.charAt(end - 1)) || text.charAt(end - 1) == ';')) end--;
        return text.substring(0, end);
    }

    /** Whitespace runs as one space, to compare statements. */
    public static String normalized(String sql) {
        return String.join(" ", sql.trim().split("\\s+"));
    }

    /** Checks made before asking the server (which then parses the statement with PREPARE). */
    public static String localPreflight(String sql) {
        if (sql.isBlank()) return "Enter a SQL statement to run.";
        String keyword = leadingKeyword(sql);
        if (keyword == null) return "The statement must start with a SQL keyword such as SELECT.";
        if (!RUNNABLE.contains(keyword)) {
            return keyword.toUpperCase() + " statements cannot be run here. Use SELECT, WITH, VALUES, TABLE, INSERT, UPDATE, DELETE, MERGE, CALL, CREATE, ALTER or DROP.";
        }
        return null;
    }

    public static boolean isDDL(String sql) {
        String k = leadingKeyword(sql);
        return "create".equals(k) || "alter".equals(k) || "drop".equals(k);
    }

    public static boolean isAlter(String sql) { return "alter".equals(leadingKeyword(sql)); }
    public static boolean isDrop(String sql) { return "drop".equals(leadingKeyword(sql)); }
    public static boolean isCall(String sql) { return "call".equals(leadingKeyword(sql)); }

    /** First word of the statement, skipping whitespace, comments and opening parentheses; lower case. */
    public static String leadingKeyword(String sql) {
        int i = skipLeading(sql, true);
        if (i < 0) return null;
        int k = i;
        while (k < sql.length() && Character.isLetter(sql.charAt(k))) k++;
        return k == i ? null : sql.substring(i, k).toLowerCase();
    }

    /** Index after leading whitespace, comments (and '(' when asked); -1 inside an unclosed comment. */
    private static int skipLeading(String sql, boolean parens) {
        int i = 0, n = sql.length();
        while (true) {
            while (i < n && (Character.isWhitespace(sql.charAt(i)) || (parens && sql.charAt(i) == '('))) i++;
            if (sql.startsWith("--", i)) {
                while (i < n && sql.charAt(i) != '\n') i++;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return -1;
                i = end + 2;
            } else {
                return i;
            }
        }
    }

    /**
     * "SELECT * FROM t WHERE ..." → "SELECT ctid::text AS mx_row_ctid, * FROM t WHERE ..."; null for any statement
     * that does not start with SELECT * (after comments).
     */
    public static String addingRowID(String sql) {
        int i = skipLeading(sql, false);
        if (i < 0 || !sql.regionMatches(true, i, "select", 0, 6)) return null;
        int k = i + 6;
        if (k >= sql.length() || !Character.isWhitespace(sql.charAt(k))) return null;
        while (k < sql.length() && Character.isWhitespace(sql.charAt(k))) k++;
        if (k >= sql.length() || sql.charAt(k) != '*') return null;
        if (k + 1 < sql.length() && !Character.isWhitespace(sql.charAt(k + 1))) return null;
        return sql.substring(0, i) + "SELECT ctid::text AS " + ROW_ID_COLUMN + ", *" + sql.substring(k + 1);
    }

    /** psql-style error text: the message, then the offending line with a caret under the error position. */
    public static String describeError(DatabaseError error, String sql) {
        String[] lines = error.getMessage().split("\n", -1);
        List<String> out = new ArrayList<>();
        out.add("ERROR:  " + lines[0]);
        int[] cps = sql.codePoints().toArray();
        Integer position = error.position();
        if (position != null && position >= 1 && position <= cps.length + 1) {
            int offset = position - 1;
            int lineNumber = 1, lineStart = 0;
            for (int k = 0; k < offset && k < cps.length; k++) {
                if (cps[k] == '\n') {
                    lineNumber++;
                    lineStart = k + 1;
                }
            }
            int lineEnd = lineStart;
            while (lineEnd < cps.length && cps[lineEnd] != '\n') lineEnd++;
            String lineText = new String(cps, lineStart, lineEnd - lineStart).replace('\t', ' ');
            String label = "LINE " + lineNumber + ": ";
            out.add(label + lineText);
            out.add(" ".repeat(label.length() + offset - lineStart) + "^");
        }
        for (int k = 1; k < lines.length; k++) out.add(lines[k]);
        return String.join("\n", out);
    }

    // MARK: Scanning

    /**
     * Which characters are plain SQL (true), not inside a string, comment, "quoted identifier" or dollar-quoted
     * body (false). Words are skipped whole, so a '$' inside an identifier is not a quote.
     */
    public static boolean[] codeMask(String s) {
        int n = s.length();
        boolean[] code = new boolean[n];
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            int end;
            if (c == '-' && at(s, i + 1) == '-') {
                end = i + 2;
                while (end < n && s.charAt(end) != '\n') end++;
            } else if (c == '/' && at(s, i + 1) == '*') {
                int depth = 1;
                end = i + 2;
                while (end < n && depth > 0) {
                    if (s.charAt(end) == '/' && at(s, end + 1) == '*') { depth++; end += 2; }
                    else if (s.charAt(end) == '*' && at(s, end + 1) == '/') { depth--; end += 2; }
                    else end++;
                }
            } else if (c == '\'') {
                end = endOfQuoted(s, i, '\'', false);
            } else if ((c == 'E' || c == 'e') && at(s, i + 1) == '\'' && (i == 0 || !isIdentChar(s.charAt(i - 1)))) {
                end = endOfQuoted(s, i + 1, '\'', true);
            } else if (c == '"') {
                end = endOfQuoted(s, i, '"', false);
            } else if (c == '$' && !Character.isDigit(at(s, i + 1)) && dollarTagEnd(s, i) > 0) {
                int tagEnd = dollarTagEnd(s, i);
                String tag = s.substring(i, tagEnd);
                int close = s.indexOf(tag, tagEnd);
                end = close < 0 ? n : close + tag.length();
            } else if (isIdentStart(c)) {
                end = i;
                while (end < n && isIdentChar(s.charAt(end))) end++;
                for (int k = i; k < end; k++) code[k] = true;
                i = end;
                continue;
            } else {
                code[i] = true;
                i++;
                continue;
            }
            i = Math.max(end, i + 1);
        }
        return code;
    }

    /** Index after "$tag$" starting at {@code i}, or -1 when it isn't one. */
    static int dollarTagEnd(String s, int i) {
        int k = i + 1;
        while (k < s.length() && isIdentChar(s.charAt(k)) && s.charAt(k) != '$') k++;
        if (at(s, k) == '$' && (k == i + 1 || isIdentStart(s.charAt(i + 1)))) return k + 1;
        return -1;
    }

    static int endOfQuoted(String s, int start, char quote, boolean backslash) {
        int k = start + 1, n = s.length();
        while (k < n) {
            if (backslash && s.charAt(k) == '\\') { k += 2; continue; }
            if (s.charAt(k) == quote) {
                if (at(s, k + 1) == quote) { k += 2; continue; }
                return k + 1;
            }
            k++;
        }
        return n;
    }

    static char at(String s, int k) { return k < s.length() ? s.charAt(k) : 0; }

    static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c >= 128;
    }

    static boolean isIdentChar(char c) { return isIdentStart(c) || (c >= '0' && c <= '9') || c == '$'; }

    /** Whether there is a ';' between statements (outside strings and comments): only one statement runs at a time. */
    public static boolean hasSeveralStatements(String sql) {
        boolean[] code = codeMask(sql);
        for (int i = 0; i < sql.length(); i++) if (code[i] && sql.charAt(i) == ';') return true;
        return false;
    }

    /** SQL with JDBC placeholders, and the parameter numbers in the order the placeholders appear. */
    public record Jdbc(String sql, int[] params) {}

    /**
     * {@code $n} parameters (outside strings and comments) become {@code ?}; a literal {@code ?} (a jsonb operator)
     * is escaped as {@code ??} so pgjdbc sends it as written.
     */
    public static Jdbc toJdbc(String sql) {
        boolean[] code = codeMask(sql);
        StringBuilder out = new StringBuilder(sql.length() + 8);
        List<Integer> params = new ArrayList<>();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (code[i] && c == '?') {
                out.append("??");
                i++;
            } else if (code[i] && c == '$' && Character.isDigit(at(sql, i + 1)) && (i == 0 || !isIdentChar(sql.charAt(i - 1)))) {
                int k = i + 1;
                while (k < n && Character.isDigit(sql.charAt(k))) k++;
                params.add(Integer.parseInt(sql.substring(i + 1, k)));
                out.append('?');
                i = k;
            } else {
                out.append(c);
                i++;
            }
        }
        return new Jdbc(out.toString(), params.stream().mapToInt(Integer::intValue).toArray());
    }
}
