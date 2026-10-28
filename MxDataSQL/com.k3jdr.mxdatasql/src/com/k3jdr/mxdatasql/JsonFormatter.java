package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;

/**
 * View JSON… (JSONFormatter.swift): formats like VS Code's Format Document — each member on its own line,
 * {@code "key": value}, empty {} and [] kept together, key order and number spellings unchanged — and colours keys,
 * strings, numbers and true / false / null in VS Code's Light+ JSON colours.
 */
public final class JsonFormatter {
    private JsonFormatter() {}

    public static final class FormatError extends Exception {
        private static final long serialVersionUID = 1L;

        FormatError(String message, int line, int column) {
            super(message + " (line " + line + ", column " + column + ")");
        }
    }

    /** A json / jsonb column, or text that is a JSON object or array. */
    public static boolean looksLikeJSON(String value, String columnType) {
        if (value == null) return false;
        if (columnType.toLowerCase().contains("json")) return true;
        String t = value.strip();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) return false;
        try {
            format(t, "    ");
            return true;
        } catch (FormatError e) {
            return false;
        }
    }

    public static String format(String text, String indent) throws FormatError {
        return new Formatter(text, indent).run();
    }

    private static final class Formatter {
        final int[] s;
        final String indent;
        int i;
        int depth;
        final StringBuilder out = new StringBuilder();

        Formatter(String text, String indent) {
            this.s = text.codePoints().toArray();
            this.indent = indent;
        }

        String run() throws FormatError {
            value();
            skipSpace();
            if (i != s.length) throw fail("End of file expected", i);
            return out.toString();
        }

        FormatError fail(String message, int k) {
            int line = 1, column = 1;
            for (int j = 0; j < Math.min(k, s.length); j++) {
                if (s[j] == '\n') { line++; column = 1; } else column++;
            }
            return new FormatError(message, line, column);
        }

        void skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++;
        }

        void newline() {
            out.append('\n');
            for (int d = 0; d < depth; d++) out.append(indent);
        }

        void string() throws FormatError {
            int start = i;
            out.appendCodePoint(s[i++]);
            while (i < s.length) {
                int c = s[i];
                if (c == '\\') {
                    if (i + 1 >= s.length) break;
                    out.appendCodePoint(c).appendCodePoint(s[i + 1]);
                    i += 2;
                    continue;
                }
                if (c == '\n') throw fail("Line break inside a string", i);
                out.appendCodePoint(c);
                i++;
                if (c == '"') return;
            }
            throw fail("Unterminated string", start);
        }

        void scalar() throws FormatError {
            int start = i;
            while (i < s.length && ",]} \t\n\r:".indexOf(s[i]) < 0) i++;
            String word = new String(s, start, i - start);
            boolean number = word.matches("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$");
            if (!number && !word.equals("true") && !word.equals("false") && !word.equals("null")) {
                throw fail(word.isEmpty() ? "Unexpected character" : "Unexpected '" + word + "'", start);
            }
            out.append(word);
        }

        void value() throws FormatError {
            skipSpace();
            if (i >= s.length) throw fail("Value expected", i);
            switch (s[i]) {
                case '{' -> container('}', true);
                case '[' -> container(']', false);
                case '"' -> string();
                default -> scalar();
            }
        }

        void container(char close, boolean object) throws FormatError {
            out.appendCodePoint(s[i++]);
            skipSpace();
            if (i < s.length && s[i] == close) {
                out.append(close);
                i++;
                return;
            }
            depth++;
            while (true) {
                newline();
                if (object) {
                    skipSpace();
                    if (i >= s.length || s[i] != '"') throw fail("Property name in double quotes expected", i);
                    string();
                    skipSpace();
                    if (i >= s.length || s[i] != ':') throw fail("Colon expected", i);
                    i++;
                    out.append(": ");
                }
                value();
                skipSpace();
                if (i >= s.length) throw fail("'" + close + "' expected", i);
                if (s[i] == ',') {
                    out.append(',');
                    i++;
                    continue;
                }
                if (s[i] == close) {
                    depth--;
                    newline();
                    out.append(close);
                    i++;
                    return;
                }
                throw fail("Comma or '" + close + "' expected", i);
            }
        }
    }

    // MARK: Colours

    private static final Color KEY = new Color(new RGB(0x04, 0x51, 0xA5));
    private static final Color STRING = new Color(new RGB(0xA3, 0x15, 0x15));
    private static final Color NUMBER = new Color(new RGB(0x09, 0x86, 0x58));
    private static final Color LITERAL = new Color(new RGB(0x00, 0x00, 0xFF));

    private static boolean isWordChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '-' || c == '+' || c == '.';
    }

    /** The coloured ranges of {@code s} (UTF-16 indexes). */
    public static List<StyleRange> styles(String s) {
        List<StyleRange> out = new ArrayList<>();
        int n = s.length(), i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                int k = i + 1;
                while (k < n && s.charAt(k) != '"' && s.charAt(k) != '\n') k += s.charAt(k) == '\\' ? 2 : 1;
                k = Math.min(k + 1, n);
                int j = k;
                while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                out.add(new StyleRange(i, k - i, j < n && s.charAt(j) == ':' ? KEY : STRING, null));
                i = k;
                continue;
            }
            if (isWordChar(c)) {
                int k = i;
                while (k < n && isWordChar(s.charAt(k))) k++;
                String word = s.substring(i, k);
                if (word.equals("true") || word.equals("false") || word.equals("null")) {
                    out.add(new StyleRange(i, k - i, LITERAL, null));
                } else if (Character.isDigit(word.charAt(0)) || word.charAt(0) == '-') {
                    out.add(new StyleRange(i, k - i, NUMBER, null));
                }
                i = k;
                continue;
            }
            i++;
        }
        return out;
    }
}
