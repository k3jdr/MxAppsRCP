package com.k3jdr.mxjobsmgr.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The hover text of links is RTF (what macOS's NSAttributedString writes), kept base64 in the jobspace. This reads
 * its words for the hover card and writes plain text back as minimal RTF the Mac app reads.
 */
public final class Rtf {
    private Rtf() {}

    /** The words of base64 RTF, or null when there are none. */
    public static String plain(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String rtf = new String(data, StandardCharsets.ISO_8859_1);
        if (!rtf.startsWith("{\\rtf")) {
            String text = new String(data, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        }
        String text = toText(rtf).strip();
        return text.isEmpty() ? null : text;
    }

    static String toText(String rtf) {
        StringBuilder out = new StringBuilder();
        Charset cp = Charset.forName("windows-1252");
        int depth = 0;
        int skipDepth = -1;          // inside a destination we don't show (font table, colours, info)
        int ucSkip = 0;
        int i = 0, n = rtf.length();
        while (i < n) {
            char c = rtf.charAt(i);
            if (c == '{') {
                depth++;
                if (skipDepth < 0 && i + 2 < n && rtf.charAt(i + 1) == '\\' && rtf.charAt(i + 2) == '*') skipDepth = depth;
                i++;
            } else if (c == '}') {
                if (depth == skipDepth) skipDepth = -1;
                depth--;
                i++;
            } else if (c == '\\') {
                if (i + 1 >= n) break;
                char d = rtf.charAt(i + 1);
                if (d == '\\' || d == '{' || d == '}') {
                    if (skipDepth < 0) out.append(d);
                    i += 2;
                } else if (d == '\n' || d == '\r') {
                    if (skipDepth < 0) out.append('\n');
                    i += 2;
                } else if (d == '\'') {
                    if (i + 3 < n && skipDepth < 0) {
                        try {
                            int b = Integer.parseInt(rtf.substring(i + 2, i + 4), 16);
                            if (ucSkip > 0) ucSkip--;
                            else out.append(new String(new byte[] { (byte) b }, cp));
                        } catch (NumberFormatException e) {
                            // not a hex escape
                        }
                    }
                    i += 4;
                } else if (Character.isLetter(d)) {
                    int j = i + 1;
                    while (j < n && Character.isLetter(rtf.charAt(j))) j++;
                    String word = rtf.substring(i + 1, j);
                    int k = j;
                    if (k < n && (rtf.charAt(k) == '-' || Character.isDigit(rtf.charAt(k)))) {
                        k++;
                        while (k < n && Character.isDigit(rtf.charAt(k))) k++;
                    }
                    String param = rtf.substring(j, k);
                    if (k < n && rtf.charAt(k) == ' ') k++;
                    if (skipDepth < 0) {
                        switch (word) {
                            case "fonttbl", "colortbl", "stylesheet", "info", "pict", "expandedcolortbl" -> skipDepth = depth;
                            case "par", "line" -> out.append('\n');
                            case "tab" -> out.append('\t');
                            case "u" -> {
                                try {
                                    int code = Integer.parseInt(param);
                                    out.append((char) (code < 0 ? code + 65536 : code));
                                    ucSkip = 1;
                                } catch (NumberFormatException e) {
                                    // no code
                                }
                            }
                            default -> { }
                        }
                    }
                    i = k;
                } else {
                    i += 2;
                }
            } else {
                if (skipDepth < 0 && c != '\r' && c != '\n') {
                    if (ucSkip > 0) ucSkip--;
                    else out.append(c);
                }
                i++;
            }
        }
        return out.toString();
    }

    /** Plain text as base64 RTF (Helvetica 12, as macOS writes a plain hover text), or null for none. */
    public static String fromPlain(String text) {
        if (text == null || text.isBlank()) return null;
        StringBuilder sb = new StringBuilder("{\\rtf1\\ansi\\ansicpg1252\\cocoartf2907\n{\\fonttbl\\f0\\fnil\\fcharset0 HelveticaNeue;}\n"
                + "{\\colortbl;\\red255\\green255\\blue255;}\n\\pard\\pardirnatural\\partightenfactor0\n\n\\f0\\fs24 \\cf0 ");
        for (char c : text.toCharArray()) {
            if (c == '\\' || c == '{' || c == '}') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\\n");
            else if (c > 127) sb.append("\\uc0\\u").append((int) c).append(' ');
            else sb.append(c);
        }
        sb.append('}');
        return Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }
}
