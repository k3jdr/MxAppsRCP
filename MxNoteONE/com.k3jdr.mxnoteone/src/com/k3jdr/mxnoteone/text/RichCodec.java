package com.k3jdr.mxnoteone.text;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rich text in and out of the notebook's files. The Mac stores RTFD (NSAttributedString.rtfd — a flattened package
 * of TXT.rtf and the attachments' files); this reads that package, and plain RTF. It writes plain RTF, which the
 * Mac reads where it expects RTFD.
 *
 * <p>The flattened RTFD package is undocumented: the RTF is found by its "{\rtf" start and its length, and
 * attachments' files by their own headers (PNG, JPEG, GIF, TIFF, PDF, HEIC) and the length before each, matched to
 * the \NeXTGraphic names in order. Everything else in the package is left alone.
 */
public final class RichCodec {
    private RichCodec() {}

    public static RText decode(byte[] data) {
        if (data == null || data.length == 0) return RText.empty();
        try {
            if (startsWith(data, 0, "{\\rtf")) return RtfReader.read(data, null);
            int at = indexOf(data, "{\\rtf".getBytes(StandardCharsets.US_ASCII), 0);
            if (at < 0) return RText.plain(new String(data, StandardCharsets.UTF_8), CharStyle.DEFAULT);
            int end = rtfEnd(data, at);
            byte[] rtf = java.util.Arrays.copyOfRange(data, at, end);
            Map<String, byte[]> files = attachments(data, at, end, new String(rtf, StandardCharsets.ISO_8859_1));
            return RtfReader.read(rtf, files);
        } catch (RuntimeException e) {
            return RText.empty();
        }
    }

    /** RTF bytes for the text (ASCII: everything else is escaped). */
    public static byte[] encode(RText text) {
        return RtfWriter.write(text).getBytes(StandardCharsets.US_ASCII);
    }

    /** The plain text of stored rich text (search, page list), without decoding more than needed. */
    public static String plainText(byte[] data) {
        return decode(data).text;
    }

    private static boolean startsWith(byte[] d, int at, String s) {
        if (at + s.length() > d.length) return false;
        for (int i = 0; i < s.length(); i++) if (d[at + i] != s.charAt(i)) return false;
        return true;
    }

    static int indexOf(byte[] d, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= d.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (d[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static long u32(byte[] d, int at) {
        if (at < 0 || at + 4 > d.length) return -1;
        return (d[at] & 0xFFL) | (d[at + 1] & 0xFFL) << 8 | (d[at + 2] & 0xFFL) << 16 | (d[at + 3] & 0xFFL) << 24;
    }

    /** Where the RTF starting at {@code at} ends: by the length before it when that fits, else by its braces. */
    private static int rtfEnd(byte[] d, int at) {
        long len = u32(d, at - 4);
        if (len > 5 && at + len <= d.length) {
            int last = (int) (at + len - 1);
            while (last > at && (d[last] == '\n' || d[last] == '\r' || d[last] == 0)) last--;
            if (d[last] == '}') return (int) (at + len);
        }
        int depth = 0;
        for (int i = at; i < d.length; i++) {
            byte b = d[i];
            if (b == '\\') {
                i++;
                continue;
            }
            if (b == '{') depth++;
            else if (b == '}' && --depth == 0) return i + 1;
        }
        return d.length;
    }

    private record Blob(int start, int length, String ext) {}

    private static final Pattern GRAPHIC = Pattern.compile("\\\\NeXTGraphic\\s+([^\\\\}]*?)\\s*\\\\");

    /** The attachments' files, by the names the RTF gives them. */
    private static Map<String, byte[]> attachments(byte[] d, int rtfStart, int rtfEnd, String rtf) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        Matcher m = GRAPHIC.matcher(rtf);
        while (m.find()) names.add(m.group(1).trim());
        if (names.isEmpty()) return files;
        List<Blob> blobs = new ArrayList<>();
        for (int i = 0; i < d.length - 8; i++) {
            if (i >= rtfStart && i < rtfEnd) {
                i = rtfEnd - 1;
                continue;
            }
            String ext = magic(d, i);
            if (ext == null) continue;
            long len = u32(d, i - 4);
            if (len <= 0 || i + len > d.length) len = guessLength(d, i, ext);
            if (len <= 0) continue;
            blobs.add(new Blob(i, (int) len, ext));
            i += (int) len - 1;
        }
        // Names in order, matched to files in order — by kind first, else by position.
        List<Blob> unused = new ArrayList<>(blobs);
        for (String name : names) {
            String ext = extension(name);
            Blob pick = null;
            for (Blob b : unused) {
                if (b.ext.equals(ext) || ext.equals("jpg") && b.ext.equals("jpeg") || ext.equals("tif") && b.ext.equals("tiff")) {
                    pick = b;
                    break;
                }
            }
            if (pick == null && !unused.isEmpty() && isPicture(ext)) pick = unused.get(0);
            if (pick == null) continue;
            unused.remove(pick);
            files.put(name, java.util.Arrays.copyOfRange(d, pick.start, pick.start + pick.length));
        }
        return files;
    }

    private static boolean isPicture(String ext) {
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "tif", "tiff", "heic", "bmp" -> true;
            default -> false;
        };
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String magic(byte[] d, int i) {
        int b0 = d[i] & 0xFF, b1 = d[i + 1] & 0xFF, b2 = d[i + 2] & 0xFF, b3 = d[i + 3] & 0xFF;
        if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') return "png";
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "jpeg";
        if (b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8') return "gif";
        if (b0 == 'I' && b1 == 'I' && b2 == 42 && b3 == 0 || b0 == 'M' && b1 == 'M' && b2 == 0 && b3 == 42) return "tiff";
        if (b0 == '%' && b1 == 'P' && b2 == 'D' && b3 == 'F') return "pdf";
        if (i + 12 <= d.length && startsWith(d, i + 4, "ftyphei")) return "heic";
        return null;
    }

    /** The length of a file whose length isn't given: up to its end marker, where the format has one. */
    private static long guessLength(byte[] d, int start, String ext) {
        if ("png".equals(ext)) {
            int iend = indexOf(d, "IEND".getBytes(StandardCharsets.US_ASCII), start);
            return iend < 0 ? -1 : iend + 8 - start;
        }
        if ("jpeg".equals(ext)) {
            for (int i = start + 2; i < d.length - 1; i++) if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD9) return i + 2 - start;
        }
        return -1;
    }
}
